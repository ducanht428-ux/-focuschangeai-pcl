package com.focuschange.ai

import kotlin.math.abs

/**
 * What the student is doing, inferred from the face stream that is already running.
 *
 * This is the middle leg of the model the team drew: face features + situation -> observable
 * behaviour. The features come from the same 478 FaceLandmarker points the eyelid maths already
 * uses, so no second model and no second camera is needed.
 *
 * The scene leg (is there a notebook, a phone, a book on the desk) is deliberately NOT here yet.
 * The student phone's front camera points at the face and sees the wall behind it, so an object
 * detector would have nothing to detect. It becomes available when a camera that looks down at the
 * desk is connected. Until then this file must not carry an unused "objects" field: a field that is
 * never filled is read by the next person as a feature that exists.
 */
internal enum class Situation(val csvCode: Int) {
    /** Face square to the screen, lids at their usual openness. */
    FORWARD(0),

    /** Head tipped down, or eyes shifted down, while the head keeps moving: writing or reading. */
    DOWN(1),

    /** Head rolled onto one shoulder long enough to be a posture, not a passing frame. */
    TILT(2),

    /** Head turned aside. The eyelids themselves are not scored in this posture. */
    TURNED(3),

    /** Head down AND unmoving AND the lids dropping: the shape of nodding off, not of writing. */
    DOZING(4),

    /** Too little signal to name a situation. */
    UNKNOWN(5)
}

/**
 * One frame's worth of numbers the situation layer reads.
 *
 * Mutable and reused per frame, like [EyeFrame]: the camera thread must not allocate on every frame
 * on this phone.
 *
 * `poseStdDeg` is the standard deviation of the head angles over the recent window, NaN when that
 * window has too few samples. The difference between "still" and "not measured yet" is exactly the
 * difference between 0.1 and NaN, so it must not be defaulted to zero.
 */
internal class SituationFrame(
    var yawDeg: Float = 0f,
    var pitchDeg: Float = 0f,
    var rollDeg: Float = 0f,
    var gazeVDev: Float = 0f,
    var openness: Float = Float.NaN,
    var closedMs: Long = 0L,
    var poseStdDeg: Float = Float.NaN
)

/**
 * Splits the measurement stream into situations and learns, for THIS student, how open the eyes
 * usually are in each of them.
 *
 * Why it exists: one shared openness line cannot tell a student bent over a notebook from a student
 * dropping off. The two look identical to the eyelid and different to everything else - writing keeps
 * the head moving, dozing does not.
 *
 * Three rules bound what this layer is allowed to do:
 *
 * 1. It changes nothing until the student's own numbers exist. Every situation needs
 *    [MIN_FRAMES_PER_SITUATION] frames of its own; until then [allow] answers "fire" and the app
 *    behaves exactly as it did before. A booth demo therefore never shows a delay nobody asked for.
 * 2. It cannot hold back a long closure. [ALWAYS_ALERT_CLOSED_MS] of continuous eye closure overrides
 *    every situation.
 * 3. It cannot hold back indefinitely. A single delay expires after [HOLD_MAX_MS].
 *
 * Statistics live for one session and are not written into the habit profile, so the cloud schema and
 * the 40-session profile file stay untouched; the cross-session view is produced offline from the
 * per-session CSVs.
 *
 * Feeding closed lids into a situation's baseline is on purpose. If a student dozes head-down often,
 * that situation's floor falls, which makes the gate suppress LESS over time, not more. The loop runs
 * toward more alerts, which is the only direction it is allowed to run in.
 */
internal class SituationEngine(private val bandK: Float = BAND_K) {

    companion object {
        /** Continuous eye closure that no situation may keep quiet. Printed on the threshold screen. */
        const val ALWAYS_ALERT_CLOSED_MS = 2_500L

        /** Longest a delayed alert can be held back before it fires anyway. Also printed there. */
        const val HOLD_MAX_MS = 700L

        /** Frames of one situation before its own openness floor is trusted. */
        private const val MIN_FRAMES_PER_SITUATION = 60

        /** A new label must be seen this long before switching, so one odd frame cannot flip it. */
        private const val SWITCH_HOLD_MS = 400L

        // Head geometry gates, in degrees of deviation from the calibrated facing.
        private const val YAW_TURN_DEG = 20f
        private const val ROLL_TILT_DEG = 12f
        private const val PITCH_DOWN_DEG = 12f
        private const val PITCH_DOZE_DEG = 20f

        /** Looking down without moving the head: the eyes drop by this fraction of the gaze range. */
        private const val GAZE_DOWN = 0.25f

        /** Below this the head is not resting on the desk, it is motionless on it. Kept above the 0.15
         * degrees that marks a still image, so a printed photo cannot be mistaken for a doze. */
        private const val STILL_STD_DEG = 0.35f

        /** Lids this far down, on a tipped and motionless head, is the nod-off shape. */
        private const val DOZE_OPENNESS = 0.55f

        /** Long enough to exclude a single blink on the way down. */
        private const val DOZE_CLOSED_MS = 300L

        /**
         * How far below the situation's own mean the floor sits, in that situation's deviations.
         *
         * Public, and a constructor parameter rather than a number used inline, so the offline replay harness can
         * score one recorded session at other values on identical frames. That is the experiment: it
         * needs a knob a computer can turn, not one only a rebuild can change.
         */
        const val BAND_K = 2.5f

        private val ALL: Array<Situation> = Situation.values()
    }

    /** Label named from the geometry alone, before the switch timer. */
    var rawSituation = Situation.UNKNOWN
        private set

    var situation = Situation.UNKNOWN
        private set

    private var candidate = Situation.UNKNOWN
    private var candidateStartMs = 0L

    private val stats = Array(ALL.size) { RunningStat() }

    private var holdStartMs = 0L

    /**
     * How many alert episodes this session's layer has actually intervened in, and how many of those
     * it then let go of entirely. Public because the HUD shows them: a claim about context has to be
     * pointable at on screen, not only recoverable from a CSV afterwards.
     *
     * Counted per episode, never per frame - thirty held frames of one closure are one intervention,
     * and a number that grows with frame rate measures the frame rate, not the rule.
     */
    var intervenedEpisodes = 0
        private set
    var suppressedEpisodes = 0
        private set

    /** Frames counted into this situation's own openness baseline. */
    fun framesIn(s: Situation): Int = stats[s.ordinal].n

    /**
     * Geometry-only label. Order matters: a head turned aside is also pitched, and the turn is the
     * stronger claim, because eyelid readings in that posture are already refused upstream.
     *
     * Never returns UNKNOWN: posture numbers stay usable in the frames where the eye numbers are not,
     * and that overlap is the whole point of having this layer.
     */
    fun classify(f: SituationFrame): Situation {
        if (abs(f.yawDeg) > YAW_TURN_DEG) return Situation.TURNED
        val pitched = f.pitchDeg > PITCH_DOWN_DEG
        val gazesDown = f.gazeVDev > GAZE_DOWN
        val still = f.poseStdDeg.isFinite() && f.poseStdDeg < STILL_STD_DEG
        if (pitched && f.pitchDeg > PITCH_DOZE_DEG && still &&
            f.openness < DOZE_OPENNESS && f.closedMs >= DOZE_CLOSED_MS
        ) {
            return Situation.DOZING
        }
        if (abs(f.rollDeg) > ROLL_TILT_DEG) return Situation.TILT
        if (pitched || gazesDown) return Situation.DOWN
        return Situation.FORWARD
    }

    /**
     * Consumes one frame: switches the label with the hold timer, then adds the openness reading to
     * that label's statistics.
     */
    fun update(f: SituationFrame, nowMs: Long) {
        val raw = classify(f)
        rawSituation = raw
        situation = when {
            raw == situation -> {
                candidate = raw
                candidateStartMs = 0L
                raw
            }
            // The hold timer exists to stop one odd frame from disturbing a label that already stands.
            // At the start of a session nothing stands, so naming the first frame at once costs nothing
            // and lets every situation baseline begin filling 400 ms earlier.
            situation == Situation.UNKNOWN -> {
                candidate = raw
                candidateStartMs = 0L
                raw
            }
            raw != candidate -> {
                candidate = raw
                candidateStartMs = nowMs
                situation
            }
            nowMs - candidateStartMs >= SWITCH_HOLD_MS -> {
                candidateStartMs = 0L
                candidate
            }
            else -> situation
        }
        if (f.openness.isFinite()) {
            stats[situation.ordinal].add(f.openness.toDouble())
        }
    }

    /**
     * The openness at or above which a narrowed eye is still ordinary for this student in this
     * situation, or NaN when nothing is learned yet.
     *
     * There is deliberately no fallback to another situation's numbers: borrowing the squared-to-screen
     * band to judge a head bent over a notebook would let a rare posture suppress alerts using data
     * from a posture it is not.
     */
    fun opennessFloor(s: Situation): Float {
        val st = stats[s.ordinal]
        if (st.n < MIN_FRAMES_PER_SITUATION) return Float.NaN
        return (st.mean - bandK * st.std()).toFloat()
    }

    /**
     * May an eyelid-based alert fire on this frame? `eyelidEvidence` is false for every alert whose
     * evidence is not "the eye looks closed right now", and those are never touched here.
     *
     * PERCLOS in particular is deliberately left alone: it is a ratio over a whole minute, so holding
     * it back frame by frame would bend the ratio itself rather than the reading of it. The situation
     * layer describes PERCLOS, it does not gate it.
     */
    fun allow(eyelidEvidence: Boolean, f: SituationFrame, nowMs: Long): Boolean {
        if (!eyelidEvidence) {
            // The evidence stopped while an episode was being held, so no alert ever went out for it.
            // This is the only way an episode ends as a real suppression: every other early return below
            // lets the alert through, which makes it late rather than gone - and those two claims are not
            // the same claim, so the replay and the HUD must be able to tell them apart.
            if (holdStartMs != 0L) suppressedEpisodes++
            return clearHold()
        }
        if (f.closedMs >= ALWAYS_ALERT_CLOSED_MS) return clearHold()
        if (situation == Situation.DOZING || situation == Situation.UNKNOWN) return clearHold()
        val floor = opennessFloor(situation)
        if (!floor.isFinite()) return clearHold()
        if (f.openness >= floor) {
            // Inside this student's own range for this posture: hold the alert briefly, do not drop it.
            if (holdStartMs == 0L) {
                holdStartMs = nowMs
                intervenedEpisodes++
            }
            if (nowMs - holdStartMs < HOLD_MAX_MS) return false
        } else {
            holdStartMs = 0L
        }
        return clearHold()
    }

    private fun clearHold(): Boolean {
        holdStartMs = 0L
        return true
    }

    /** Wipes everything: called when the student, the calibration or the session changes. */
    fun reset() {
        rawSituation = Situation.UNKNOWN
        situation = Situation.UNKNOWN
        candidate = Situation.UNKNOWN
        candidateStartMs = 0L
        holdStartMs = 0L
        // The tallies are read as "what this layer did during this learning", so they go with the
        // learning they describe. The CSV keeps the full history either way.
        intervenedEpisodes = 0
        suppressedEpisodes = 0
        for (i in stats.indices) stats[i] = RunningStat()
    }
}
