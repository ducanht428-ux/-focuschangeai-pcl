package com.focuschange.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
// =====================================================================
// PER-STUDENT HABIT PROFILE (several classes folded into one file)
// =====================================================================

/**
 * Habit-learning configuration. To test quickly during development: lower MIN_SESSION_MS to
 * 20_000L and MIN_TOTAL_MS to 60_000L.
 */
internal object HabitConfig {
    /** A session is only saved when it has at least this much "valid" time. */
    const val MIN_SESSION_MS = 2L * 60L * 1000L

    /** Minimum share of session time with a face visible. */
    const val MIN_FACE_RATIO = 0.70f

    /** Minimum ratio of valid frames / frames with a face. */
    const val MIN_VALID_RATIO = 0.50f

    /** Minimum average signal quality (0-100). */
    const val MIN_QUALITY = 60f

    /** Minimum average SEI: below this the session does not reflect normal studying. */
    const val MIN_SESSION_SEI = 40f

    /**
     * Minimum number of qualifying sessions and minimum total valid time to count as "profile
     * established". Sized for the demo booth: one visit is 5 sessions x 3 minutes of recording =
     * 900 s filmed against 600 s required, so 300 s (a third of the filming) may be lost to frames
     * without a usable face signal before the profile stops forming. validMs counts only those
     * frames, which is why filmed time and required time are not the same quantity. The earlier
     * 5 x 2.5 min script left 150 s of slack and a 4 x 2.5 min one had none at all - one long
     * glance away from the camera could lose the whole profile.
     */
    const val MIN_SESSIONS = 5
    const val MIN_TOTAL_MS = 10L * 60L * 1000L

    const val MAX_SESSIONS = 40
    const val MIN_FRAMES_PER_FEATURE = 30
    const val RECENCY_DECAY = 0.9
    const val MAX_WEIGHT_MINUTES = 30.0

    /** A gap between two frames larger than this: treat as a stall, not counted as study time. */
    const val MAX_FRAME_GAP_MS = 700L

    /**
     * Multiplier for [ToleranceBand]: band = typical level +/- BAND_K * natural spread.
     * 2.5 is the figure currently used by the personal alert thresholds (head turn, eye glance),
     * so the band and the alerts cannot contradict each other. Kept public so the HUD, the CSV and
     * the Firebase record all multiply by one factor - if each picked its own number, the same
     * behaviour would get two different conclusions.
     */
    const val BAND_K = 2.5f
}

/** Features that are learned individually per student. */
internal object HabitFeature {
    const val BPM = 0
    const val BLINK_MS = 1
    const val DIST = 2
    const val YAW = 3
    const val PITCH = 4
    const val GAZE_H = 5
    const val GAZE_V = 6
    const val MAR = 7
    const val BFR = 8
    const val EAR = 9
    const val PERCLOS = 10
    const val SEI = 11
    const val COUNT = 12

    /**
     * Five features that have NO personal threshold, recorded here so that nobody (including me)
     * calls the other seven "every metric is learned per student":
     *
     * - DIST: 30 cm is a classroom-hygiene standard applied to everyone, not a habit.
     *   Personalising it into "this student is used to sitting at 25 cm" would turn a harmful
     *   habit into that student's own normal - exactly the thing the project wants to fix.
     * - BLINK_MS / BFR: there is no evidence yet that one blink's duration or the one-eye closure
     *   ratio carries behavioural meaning at this resolution; BFR mostly reflects landmark slip when the head tilts.
     * - EAR: already personalised through its own path (`blendEarWithHabit`, at most 35%) and the
     *   session's eye-closure threshold comes from that student's own calibration, so adding it to
     *   mix() would double-count the calibration on the same number.
     * - SEI: used as a COMPARISON LEVEL ("lower than your usual", LOW_VS_PERSONAL_K), not set as a
     *   direct alert threshold because SEI is already a weighted combination of six other things.
     */

    /** Per-observation clipping (winsorize) so one noisy frame cannot drag the profile off. */
    val LO = floatArrayOf(0f, 40f, 10f, -60f, -60f, -0.5f, -0.6f, 0f, 0.1f, 0f, 0f, 0f)
    val HI = floatArrayOf(60f, 1000f, 150f, 60f, 60f, 0.5f, 0.6f, 1.2f, 1.5f, 0.6f, 100f, 100f)

    /** Minimum width so the threshold cannot "shrink" to 0 while data is still scarce. */
    val MIN_SPREAD = floatArrayOf(2f, 20f, 3f, 2f, 2f, 0.02f, 0.03f, 0.02f, 0.01f, 0.01f, 1.5f, 3f)

    /**
     * Key names of the 12 features, in exact index order. Shared by the CSV header and the keys in
     * the Firebase record: two hand-maintained lists will sooner or later drift by one word and no
     * one will be able to read the old data any more.
     */
    val KEYS = arrayOf(
        "blink_bpm", "avg_blink_ms", "distance_cm", "yaw_deg", "pitch_deg", "gaze_h",
        "gaze_v", "mar", "bfr", "ear", "perclos_p60", "sei"
    )

    /** Short labels printed on the table, plus each feature's unit and trustworthy decimals. */
    val NAMES = arrayOf(
        "Chớp mắt", "Độ dài một cái chớp", "Khoảng cách", "Đầu quay ngang", "Đầu cúi/ngửa",
        "Liếc ngang", "Liếc dọc", "Độ mở miệng (MAR)", "Một mắt nhắm (BFR)", "Độ mở mắt (EAR)",
        "PERCLOS P60", "SEI"
    )
    val UNITS = arrayOf("/phút", "ms", "cm", "°", "°", "", "", "", "", "", "%", "/100")
    val DECIMALS = intArrayOf(0, 0, 0, 1, 1, 2, 2, 2, 2, 3, 0, 0)

    /**
     * The eight features that are checked two-sided on every frame.
     *
     * Listed here so the HUD, CSV, Firebase record and the metrics table all use one same list.
     * The five remaining features are excluded for the reasons written in the block above [LO]:
     * DIST (sitting at 25 cm is a harmful habit, not "this student's normal"), BLINK_MS/BFR (no
     * evidence of behavioural meaning yet), EAR (already has its own blending path), and only SEI
     * is included as a COMPARISON NUMBER rather than as an alert threshold.
     */
    val BAND_FEATURES = intArrayOf(BPM, YAW, PITCH, GAZE_H, GAZE_V, MAR, PERCLOS, SEI)
}

/** Running statistics (Welford): mean and standard deviation without storing every sample. */
internal class RunningStat {
    var n = 0
        private set
    var mean = 0.0
        private set
    private var m2 = 0.0

    fun add(x: Double) {
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)
    }

    fun std(): Double = if (n > 1) sqrt(m2 / n) else 0.0
}

/** Where one value sits relative to that student's own familiar band. */
internal enum class BandPosition { IN, BELOW, ABOVE, UNKNOWN }

/**
 * Convert the band position into the CSV column code: 0 = below the band, 1 = inside the band,
 * 2 = above the band, -1 = cannot be scored (no band learned yet, or the value is not finite).
 */
internal fun BandPosition.toCsvCode(): Int = when (this) {
    BandPosition.BELOW -> 0
    BandPosition.IN -> 1
    BandPosition.ABOVE -> 2
    BandPosition.UNKNOWN -> -1
}

/**
 * The TWO-SIDED band learned from habit: [min, max] around that student's own typical level.
 *
 * This is a DESCRIPTION, not an alert threshold. Alerts are still decided by their own thresholds
 * (that threshold group is clamped inside the overall safe range); the band only answers "is
 * today's frame part of this very student's habit", i.e. the too-low / too-high part that a single
 * average line would hide.
 */
internal data class ToleranceBand(val mean: Float, val min: Float, val max: Float) {
    fun position(value: Float): BandPosition = when {
        !value.isFinite() -> BandPosition.UNKNOWN
        value < min -> BandPosition.BELOW
        value > max -> BandPosition.ABOVE
        else -> BandPosition.IN
    }
}

/**
 * Result of comparing a whole session against the familiar band; immutable for display and sending.
 * Keeps FRAME COUNTS rather than ratios: aggregating a session must add counters, while adding the
 * rounded ratios of eight features yields one number that is correct for none of them.
 *
 * A feature with no scored frame at all is dropped from the list instead of reported as "0%": the
 * number 0 would be read as "always inside the band", i.e. the opposite of the truth (nothing was measured).
 */
internal data class BandOutcome(
    val feature: Int,
    val mean: Float,
    val min: Float,
    val max: Float,
    val samples: Int,
    val below: Int,
    val above: Int
) {
    val inSamples: Int get() = samples - below - above
    val inPct: Float get() = inSamples * 100f / samples
    val belowPct: Float get() = below * 100f / samples
    val abovePct: Float get() = above * 100f / samples

    /** Percent of frames falling OUTSIDE the band - the only number worth flagging on the table. */
    val outsidePct: Float get() = belowPct + abovePct
}

/** Summary of one study session: mean, standard deviation and sample count of each feature. */
internal class SessionSummary(
    val id: Long,
    val validMs: Long,
    val faceRatio: Float,
    val validRatio: Float,
    val quality: Float,
    val mean: FloatArray,
    val std: FloatArray,
    val count: IntArray
) {
    companion object
}

/**
 * Accumulator for the running session (only used on cameraExecutor).
 * Every valid frame calls observe(); tick() adds up the time.
 */
internal class HabitSession(val id: Long) {
    private val stats = Array(HabitFeature.COUNT) { RunningStat() }
    private val quality = RunningStat()
    private val bands = arrayOfNulls<ToleranceBand>(HabitFeature.COUNT)
    private val bandIn = IntArray(HabitFeature.COUNT)
    private val bandBelow = IntArray(HabitFeature.COUNT)
    private val bandAbove = IntArray(HabitFeature.COUNT)
    private var lastTickMs = 0L

    var activeMs = 0L
        private set
    var faceMs = 0L
        private set
    var validMs = 0L
        private set

    fun tick(nowMs: Long, faceVisible: Boolean, valid: Boolean) {
        val dt = if (lastTickMs == 0L) 0L else nowMs - lastTickMs
        lastTickMs = nowMs
        if (dt <= 0L) return

        // activeMs is the session's clock and also the denominator of faceRatio. A long stall must not
        // count as the student studying, yet it still grows the denominator - that is a deliberate
        // choice, pinned down by the test stallKeepsFaceRatioHonest: a session where the device was not
        // recording most of the time is not qualified for the profile, even if every frame left shows a face.
        activeMs += dt
        if (dt > HabitConfig.MAX_FRAME_GAP_MS) return

        // Add the full amount, do not clamp at 200 ms: when the device runs under 5 FPS the frames
        // really are more than 200 ms apart, and clamping would stop "valid minutes" from ever reaching MIN_SESSION_MS.
        if (faceVisible) faceMs += dt
        if (valid) validMs += dt
    }

    fun observe(feature: Int, value: Float) {
        if (value.isNaN() || value.isInfinite()) return
        val v = value.coerceIn(HabitFeature.LO[feature], HabitFeature.HI[feature])
        stats[feature].add(v.toDouble())
    }

    /**
     * Load the bands for the whole session, called ONCE at session start.
     *
     * The bands are computed from the sessions that existed BEFORE this one (the running session has
     * not been committed into the profile yet). Reloading them mid-session would let one unusual study
     * session widen its own band and hide exactly what the band was supposed to report.
     */
    fun setBands(base: HabitBaseline, features: IntArray) {
        for (f in features) bands[f] = base.band(f)
    }

    /**
     * Score whether one value is inside / below / above the familiar band.
     *
     * Deliberately separate from [observe]: [observe] clamps samples into the physical range before
     * computing the mean, while here an out-of-range value must be kept as-is - clamping would drag
     * "lower than usual" back onto the band edge so it never becomes BELOW. UNKNOWN (no band yet,
     * value not finite) is not counted anywhere, not even as "inside the band".
     */
    fun observeBand(feature: Int, value: Float?): BandPosition {
        val band = bands[feature] ?: return BandPosition.UNKNOWN
        val v = value ?: return BandPosition.UNKNOWN
        return when (band.position(v)) {
            BandPosition.IN -> { bandIn[feature]++; BandPosition.IN }
            BandPosition.BELOW -> { bandBelow[feature]++; BandPosition.BELOW }
            BandPosition.ABOVE -> { bandAbove[feature]++; BandPosition.ABOVE }
            BandPosition.UNKNOWN -> BandPosition.UNKNOWN
        }
    }

    /** How many features in [features] have a learned band. */
    fun bandCoverage(features: IntArray): Int = features.count { bands[it] != null }

    /**
     * All band outcomes of the session, in [HabitFeature.BAND_FEATURES] order.
     * A feature with no band yet, or with no scored frame, is skipped entirely.
     */
    fun bandOutcomes(): List<BandOutcome> {
        val out = ArrayList<BandOutcome>(HabitFeature.BAND_FEATURES.size)
        for (f in HabitFeature.BAND_FEATURES) {
            val band = bands[f] ?: continue
            val n = bandIn[f] + bandBelow[f] + bandAbove[f]
            if (n == 0) continue
            out.add(
                BandOutcome(
                    feature = f,
                    mean = band.mean,
                    min = band.min,
                    max = band.max,
                    samples = n,
                    below = bandBelow[f],
                    above = bandAbove[f]
                )
            )
        }
        return out
    }

    fun addQuality(q: Float) {
        quality.add(q.toDouble())
    }

    fun toSummary(): SessionSummary {
        val c = HabitFeature.COUNT
        val mean = FloatArray(c)
        val std = FloatArray(c)
        val cnt = IntArray(c)
        for (f in 0 until c) {
            cnt[f] = stats[f].n
            mean[f] = stats[f].mean.toFloat()
            std[f] = stats[f].std().toFloat()
        }
        val faceRatio = if (activeMs > 0L) faceMs.toFloat() / activeMs.toFloat() else 0f
        val validRatio = if (faceMs > 0L) validMs.toFloat() / faceMs.toFloat() else 0f
        return SessionSummary(
            id = id,
            validMs = validMs,
            faceRatio = faceRatio,
            validRatio = validRatio,
            quality = quality.mean.toFloat(),
            mean = mean,
            std = std,
            count = cnt
        )
    }
}

/** Learned baseline: typical value + natural spread of each feature. */
internal class HabitBaseline(
    private val means: FloatArray,
    private val spreads: FloatArray,
    private val ok: BooleanArray
) {
    fun mean(f: Int): Float? = if (ok[f]) means[f] else null
    fun spread(f: Int): Float? = if (ok[f]) spreads[f] else null

    /** |mean| + k * spread, clamped into [lo, hi]. Returns null when there is no data yet. */
    fun threshold(f: Int, k: Float, lo: Float, hi: Float): Float? {
        if (!ok[f]) return null
        return (abs(means[f]) + k * spreads[f]).coerceIn(lo, hi)
    }

    /**
     * Two-sided allowed band of one feature: mean +/- k*spread, clamped into its own physical LO/HI
     * range. Returns null when nothing was learned for this feature - "not known yet" must differ
     * from "band of width zero".
     *
     * No min <= max check is needed: [means] is a weighted average of values already clamped into
     * [LO, HI] at measurement time, so the mean always sits between the two edges and each edge is
     * only ever pulled toward the mean.
     */
    fun band(f: Int, k: Float = HabitConfig.BAND_K): ToleranceBand? {
        if (!ok[f]) return null
        val half = k * spreads[f]
        return ToleranceBand(
            mean = means[f],
            min = max(HabitFeature.LO[f], means[f] - half),
            max = min(HabitFeature.HI[f], means[f] + half)
        )
    }
}

/** One student's habit profile: the list of qualifying sessions. */
internal class HabitProfile(val id: String, var name: String) {
    val sessions = ArrayList<SessionSummary>()

    /**
     * Distance calibration result, stored PER PROFILE rather than per phone.
     *
     * The factor used to be a device-level pref (`distance_scale_v2`), so when a second student
     * pressed calibrate, every cm number of the first one changed with nobody seeing why.
     *
     * These two values are also the only geometric signature the app has, because calibration runs at
     * a reference distance that is already known: `distanceScale = REF / calRawDistanceCm` leaves one
     * variable, the real face width of whoever is sitting there, and [PersonGuard] uses exactly that.
     */
    var distanceScale: Float = Float.NaN

    /** Median RAW distance (before the factor) of the frames used for calibration. */
    var calRawDistanceCm: Float = Float.NaN

    /** Device clock second (`System.currentTimeMillis`) of the most recent calibration. */
    var calibratedAtMs: Long = 0L

    fun hasCalibration(): Boolean = distanceScale.isFinite() && distanceScale > 0f

    fun sessionCount(): Int = sessions.size

    fun totalValidMs(): Long {
        var t = 0L
        for (s in sessions) t += s.validMs
        return t
    }

    fun isReady(): Boolean =
        sessions.size >= HabitConfig.MIN_SESSIONS &&
                totalValidMs() >= HabitConfig.MIN_TOTAL_MS

    /**
     * Confidence 0..1: the more sessions, the more the personal threshold outweighs the default.
     * The "+1" milestone is anchored to MIN_SESSIONS so the FIRST qualifying session of a profile
     * that has just become eligible always starts at 0.2. Keeping the old `- 2` would have made a
     * profile jump straight to 0.4 the moment it turned "ready" after MIN_SESSIONS went from 4 to
     * 5 - twice the blending strength for the same amount of data, just because a constant was
     * changed elsewhere.
     */
    fun confidence(): Float {
        if (!isReady()) return 0f
        return ((sessions.size - HabitConfig.MIN_SESSIONS + 1) / 5f).coerceIn(0.2f, 1f)
    }

    /** Why the session does not qualify for learning (null when it does). */
    fun ineligibleReason(s: SessionSummary): String? = when {
        s.validMs < HabitConfig.MIN_SESSION_MS ->
            "chưa đủ ${HabitConfig.MIN_SESSION_MS / 60000L} phút hợp lệ"
        s.faceRatio < HabitConfig.MIN_FACE_RATIO ->
            "ít khi thấy khuôn mặt"
        s.validRatio < HabitConfig.MIN_VALID_RATIO ->
            "tín hiệu hoặc tư thế không ổn định, hoặc mất tập trung kéo dài"
        s.quality < HabitConfig.MIN_QUALITY ->
            "chất lượng tín hiệu thấp (ánh sáng, khoảng cách)"
        s.count[HabitFeature.SEI] > 0 && s.mean[HabitFeature.SEI] < HabitConfig.MIN_SESSION_SEI ->
            "mức tập trung trung bình thấp, không đại diện cho thói quen học"
        else -> null
    }

    /**
     * Add or update a session (same id overwrites, because sessions are saved periodically).
     * A session that fails the bar is removed if it had been stored earlier.
     * Returns true if the profile changed.
     */
    fun commit(s: SessionSummary): Boolean {
        val pos = sessions.indexOfFirst { it.id == s.id }
        if (ineligibleReason(s) != null) {
            if (pos >= 0) {
                sessions.removeAt(pos)
                return true
            }
            return false
        }
        if (pos >= 0) sessions[pos] = s else sessions.add(s)
        while (sessions.size > HabitConfig.MAX_SESSIONS) sessions.removeAt(0)
        return true
    }

    /**
     * Mean SEI over [fromIdx, toIdx) plus the NUMBER OF SESSIONS that actually contributed.
     * The second number is the important part: a "trend" drawn from one earlier session and three
     * later ones says nothing about the student, only about the luck of that particular afternoon.
     */
    internal fun avgSei(fromIdx: Int, toIdx: Int): Pair<Float, Int>? {
        var sum = 0f
        var n = 0
        val lo = max(0, fromIdx)
        val hi = min(sessions.size, toIdx)
        for (i in lo until hi) {
            val s = sessions[i]
            if (s.count[HabitFeature.SEI] > 0) {
                sum += s.mean[HabitFeature.SEI]
                n++
            }
        }
        return if (n > 0) (sum / n) to n else null
    }

    /**
     * Baseline: weighted mean (longer and more recent sessions count more), outlier sessions
     * removed by MAD once there are at least 5 - this gate is a hard-coded 5, not MIN_SESSIONS.
     * spread = sqrt(between-session variance + within-session variance).
     */
    fun baseline(): HabitBaseline {
        val c = HabitFeature.COUNT
        val means = FloatArray(c)
        val spreads = FloatArray(c)
        val ok = BooleanArray(c)
        val n = sessions.size

        for (f in 0 until c) {
            val minCount = if (f == HabitFeature.EAR) 1 else HabitConfig.MIN_FRAMES_PER_FEATURE
            val idx = ArrayList<Int>()
            for (i in 0 until n) {
                if (sessions[i].count[f] >= minCount) idx.add(i)
            }
            if (idx.isEmpty()) continue

            var keep: List<Int> = idx
            if (idx.size >= 5) {
                val vals = idx.map { sessions[it].mean[f] }.sorted()
                val med = vals[vals.size / 2]
                val dev = idx.map { abs(sessions[it].mean[f] - med) }.sorted()
                val mad = max(dev[dev.size / 2], HabitFeature.MIN_SPREAD[f] * 0.5f)
                val limit = 3.5f * 1.4826f * mad
                val filtered = idx.filter { abs(sessions[it].mean[f] - med) <= limit }
                if (filtered.size >= 3) keep = filtered
            }

            val w = DoubleArray(keep.size)
            var sumW = 0.0
            var sumM = 0.0
            for ((j, i) in keep.withIndex()) {
                val s = sessions[i]
                val age = n - 1 - i
                val minutes = min(s.validMs / 60000.0, HabitConfig.MAX_WEIGHT_MINUTES)
                w[j] = max(minutes, 0.5) * HabitConfig.RECENCY_DECAY.pow(age)
                sumW += w[j]
                sumM += w[j] * s.mean[f]
            }
            val mean = sumM / sumW

            var between = 0.0
            var within = 0.0
            for ((j, i) in keep.withIndex()) {
                val s = sessions[i]
                val d = s.mean[f] - mean
                between += w[j] * d * d
                within += w[j] * s.std[f] * s.std[f]
            }
            val spread = sqrt(between / sumW + within / sumW).toFloat()

            means[f] = mean.toFloat()
            spreads[f] = max(spread, HabitFeature.MIN_SPREAD[f])
            ok[f] = true
        }
        return HabitBaseline(means, spreads, ok)
    }
}

internal data class HabitStudent(val id: String, val name: String)
