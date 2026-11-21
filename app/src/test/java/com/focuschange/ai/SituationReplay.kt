package com.focuschange.ai

import kotlin.math.abs

/**
 * Offline replay of the situation layer over a recorded session file.
 *
 * Kept in a pure file with no Android imports so it runs on a computer: this is the code that produces
 * the competition's comparison numbers, and a number that cannot be recomputed from the raw file is not
 * evidence, it is a claim about the app.
 *
 * The replay re-runs [SituationEngine] from the columns the phone wrote, so "what the layer did" is
 * measured against "what the bare threshold did" on the SAME frames. [ReplayReport.situationMismatch]
 * counts the frames where replay and device disagree: anything above 0 means the file does not hold
 * every input the phone used, and then the numbers below it are suspect. That is why `closed_ms` and
 * `pose_std_deg` were added to the CSV - re-deriving them offline would be a second implementation of
 * the closure timer and the pose window, free to drift from the real one.
 *
 * Known limits, stated rather than hidden:
 * - a mid-session calibration reset wipes the engine's learning on the phone and nothing in the CSV
 *   marks where that happened, so replaying a re-calibrated session learns later than the device did;
 * - the replay can only see one session at a time, so the per-situation floor always starts from zero
 *   here while on the phone it may already carry earlier sessions' habit statistics.
 */
internal object SituationReplay {

    /** Eyelid alert kinds, spelled out so this file does not reach into MainActivity's numbering. */
    private val EYELID_CODES = setOf(1, 11)   // KIND_DROWSY, KIND_SQUINT

    private const val NO_FACE = -1

    /**
     * One parsed frame: only the columns the situation layer consumes, plus the two answers the phone
     * already wrote, which is what makes the disagreement countable.
     */
    internal class Row(
        val tMs: Long = 0L,
        /** Whole seconds since monitoring started, the column both files share. */
        val sessionS: Long = 0L,
        val alertCode: Int = 0,
        val loggedSituation: Int = NO_FACE,
        val loggedHeld: Int = -1,
        val yawDeg: Float = Float.NaN,
        val pitchDeg: Float = Float.NaN,
        val rollDeg: Float = Float.NaN,
        val gazeV: Float = Float.NaN,
        val openness: Float = Float.NaN,
        val closedMs: Long = 0L,
        val poseStdDeg: Float = Float.NaN,
    )

    /** Consecutive frames on which the bare threshold claimed the same eyelid alert. */
    internal data class Episode(
        val startMs: Long,
        val endMs: Long,
        /** Session-relative second the episode opened, which is what the label file can be matched on. */
        val startSec: Long,
        val frames: Int,
        val peakClosedMs: Long,
        /** When the gated rule still let it through, or -1 when the layer held the whole episode. */
        val firedAtMs: Long,
    ) {
        val suppressed: Boolean get() = firedAtMs < 0L

        /** Gap the layer put between "the rule said so" and "the alert went out". */
        val addedLagMs: Long get() = if (firedAtMs < 0L) 0L else firedAtMs - startMs
    }

    internal data class ReplayReport(
        val rows: Int,
        val noFaceRows: Int,
        /** Frames counted into each label, indexed by [Situation.csvCode]. */
        val framesBySituation: IntArray,
        val episodes: List<Episode>,
        val situationMismatch: Int,
        val heldFrameMismatch: Int,
        /** Eyelid-alert frames whose closure passed the absolute cap, so no situation could interfere. */
        val overrideFrames: Int,
        /** -1 when the session never produced one. */
        val firstDozeMs: Long,
        val firstDrowsyAlertMs: Long,
    ) {
        /** Alerts the bare threshold would have raised. */
        val rawAlertCount: Int get() = episodes.size

        /** Alerts the context layer actually let through. */
        val gatedAlertCount: Int get() = episodes.count { !it.suppressed }

        val suppressedCount: Int get() = episodes.count { it.suppressed }

        /** Longest gap the layer ever put in front of an alert that still went out. */
        val worstAddedLagMs: Long get() = episodes.maxOfOrNull { it.addedLagMs } ?: 0L

        fun framesIn(s: Situation): Int =
            if (s.ordinal in framesBySituation.indices) framesBySituation[s.ordinal] else 0
    }

    /**
     * Read a `frames_<session>.csv` by column NAME, never by position: the file has grown by four
     * columns on purpose and a positional reader would quietly relabel the whole table instead of
     * failing. Null means "this file cannot be replayed" - either it predates the situation layer or
     * it is not a frame file at all.
     */
    internal fun parse(text: String): List<Row>? {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val header = lines[0].split(',').map { it.trim() }
        val idx = HashMap<String, Int>()
        for (i in header.indices) idx[header[i]] = i
        val required = listOf(
            "t_ms", "session_s", "alert_code", "situation", "alert_held", "closed_ms", "pose_std_deg",
            "yaw_deg", "pitch_deg", "roll_deg", "gaze_v", "openness"
        )
        if (required.any { !idx.containsKey(it) }) return null

        fun List<String>.cell(name: String): String = idx[name]?.let { this[it] }?.trim() ?: ""
        fun List<String>.num(name: String): Float? = cell(name).toFloatOrNull()
        fun List<String>.long(name: String): Long? = cell(name).toLongOrNull()

        val out = ArrayList<Row>(lines.size - 1)
        for (l in 1 until lines.size) {
            val cells = lines[l].split(',')
            // A short row cannot be trusted positionally, and the writer already counts these.
            if (cells.size < header.size) continue
            if (cells.cell("situation").isEmpty()) {
                out.add(Row(tMs = cells.long("t_ms") ?: 0L, sessionS = cells.long("session_s") ?: 0L))
                continue
            }
            out.add(
                Row(
                    tMs = cells.long("t_ms") ?: 0L,
                    sessionS = cells.long("session_s") ?: 0L,
                    alertCode = cells.long("alert_code")?.toInt() ?: 0,
                    loggedSituation = cells.long("situation")?.toInt() ?: NO_FACE,
                    loggedHeld = cells.long("alert_held")?.toInt() ?: -1,
                    yawDeg = cells.num("yaw_deg") ?: Float.NaN,
                    pitchDeg = cells.num("pitch_deg") ?: Float.NaN,
                    rollDeg = cells.num("roll_deg") ?: Float.NaN,
                    gazeV = cells.num("gaze_v") ?: Float.NaN,
                    openness = cells.num("openness") ?: Float.NaN,
                    closedMs = cells.long("closed_ms") ?: 0L,
                    poseStdDeg = cells.num("pose_std_deg") ?: Float.NaN,
                )
            )
        }
        return out
    }

    /**
     * Replay the layer over parsed rows and compare it against what the phone wrote.
     *
     * `bandK` is the whole point of this being offline: the same recorded session can be scored with
     * k = 0 (a floor pinned to the mean, i.e. an unforgiving per-situation threshold) or with the
     * shipped 2.5, on identical frames. Everything else is the same engine, so a difference in the
     * result is a difference in the rule and nothing else.
     */
    internal fun replay(rows: List<Row>, bandK: Float = SituationEngine.BAND_K): ReplayReport {
        val engine = SituationEngine(bandK)
        val frame = SituationFrame()
        val episodes = ArrayList<Episode>()
        val counts = IntArray(Situation.values().size)

        var mismatch = 0
        var heldMismatch = 0
        var overrideFrames = 0
        var noFace = 0
        var firstDoze = -1L
        var firstDrowsy = -1L

        var epStartMs = -1L
        var epStartSec = 0L
        var epEndMs = -1L
        var epFrames = 0
        var epPeakClosedMs = 0L
        var epFiredAtMs = -1L

        for (r in rows) {
            if (r.loggedSituation == NO_FACE) {
                noFace++
                // The phone stops feeding the engine when there is no face, so the replay must not
                // either - otherwise the statistics grow from frames that never existed.
                continue
            }
            frame.yawDeg = r.yawDeg
            frame.pitchDeg = r.pitchDeg
            frame.rollDeg = r.rollDeg
            frame.gazeVDev = r.gazeV
            frame.openness = r.openness
            frame.closedMs = r.closedMs
            frame.poseStdDeg = r.poseStdDeg
            engine.update(frame, r.tMs)

            if (engine.situation.csvCode != r.loggedSituation) mismatch++
            counts[engine.situation.ordinal]++
            if (engine.situation == Situation.DOZING && firstDoze < 0L) firstDoze = r.tMs

            val eyelid = r.alertCode in EYELID_CODES
            // One call per frame, on every frame, exactly as the phone does it: allow() owns the hold
            // timer, so a second call on the same frame would advance it twice and the replay would
            // diverge from the device for a reason that has nothing to do with the rule.
            val allowed = engine.allow(eyelid, frame, r.tMs)
            if (r.loggedHeld == 0 || r.loggedHeld == 1) {
                if ((!allowed) != (r.loggedHeld == 1)) heldMismatch++
            }
            if (eyelid) {
                if (r.closedMs >= SituationEngine.ALWAYS_ALERT_CLOSED_MS) overrideFrames++
                if (epStartMs < 0L) {
                    epStartMs = r.tMs
                    epStartSec = r.sessionS
                    epFiredAtMs = -1L
                    epFrames = 0
                    epPeakClosedMs = 0L
                }
                epEndMs = r.tMs
                epFrames++
                if (r.closedMs > epPeakClosedMs) epPeakClosedMs = r.closedMs
                // Decided here, frame by frame, not when the episode closes: by then the engine has
                // already moved past the learning state that produced the answer.
                if (allowed && epFiredAtMs < 0L) epFiredAtMs = r.tMs
                if (r.alertCode == 1 && firstDrowsy < 0L) firstDrowsy = r.tMs
            } else if (epStartMs >= 0L) {
                episodes.add(Episode(epStartMs, epEndMs, epStartSec, epFrames, epPeakClosedMs, epFiredAtMs))
                epStartMs = -1L
            }
        }
        if (epStartMs >= 0L) {
            episodes.add(Episode(epStartMs, epEndMs, epStartSec, epFrames, epPeakClosedMs, epFiredAtMs))
        }

        return ReplayReport(
            rows = rows.size,
            noFaceRows = noFace,
            framesBySituation = counts,
            episodes = episodes,
            situationMismatch = mismatch,
            heldFrameMismatch = heldMismatch,
            overrideFrames = overrideFrames,
            firstDozeMs = firstDoze,
            firstDrowsyAlertMs = firstDrowsy,
        )
    }

    /**
     * Attach the "Cảnh báo sai" labels to the episodes they were pressed for.
     *
     * Matching is by alert kind and by [Episode.startSec] within [labelToleranceSeconds], because that
     * is the only time both files share: the frame file's `t_ms` runs on elapsedRealtime and the label
     * file's `wall_ms` on the wall clock, so comparing those two would be off by however long the phone
     * had been up. `session_s` is in both. The tolerance is the human reaction time between seeing an
     * alert and pressing the button, not a measurement gap.
     *
     * Labels that match nothing come back as the second half of the pair and are still reported: a label
     * that cannot be placed is a fact about the recording, not something to drop quietly.
     *
     * The episode a label attaches to must be a SUPPRESSED one for the layer to have prevented a
     * complaint - that crossing, not [ReplayReport.suppressedCount] alone, is the precision claim.
     */
    internal fun labelledEpisodes(
        episodes: List<Episode>,
        falseAlertsCsv: String,
        labelToleranceSeconds: Long = 10L,
    ): Pair<List<Episode>, Int> {
        val lines = falseAlertsCsv.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return emptyList<Episode>() to 0
        val header = lines[0].split(',').map { it.trim() }
        val sIdx = header.indexOf("session_s")
        val cIdx = header.indexOf("alert_code")
        if (sIdx < 0 || cIdx < 0) return emptyList<Episode>() to 0

        val labels = ArrayList<Long>()
        for (i in 1 until lines.size) {
            val cells = lines[i].split(',')
            if (cells.size <= maxOf(sIdx, cIdx)) continue
            val code = cells[cIdx].trim().toIntOrNull() ?: continue
            if (code !in EYELID_CODES) continue
            val sec = cells[sIdx].trim().toLongOrNull() ?: continue
            labels.add(sec)
        }

        val matched = ArrayList<Episode>()
        val taken = BooleanArray(labels.size)
        for (e in episodes) {
            for (j in labels.indices) {
                if (taken[j]) continue
                if (abs(labels[j] - e.startSec) <= labelToleranceSeconds) {
                    taken[j] = true
                    matched.add(e)
                    break
                }
            }
        }
        return matched to (labels.size - matched.size).coerceAtLeast(0)
    }

}
