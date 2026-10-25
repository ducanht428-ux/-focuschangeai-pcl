package com.focuschange.ai

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

/**
 * Face landmarks as one flat array [x0, y0, z0, x1, y1, z1, ...].
 * Avoids allocating ~1,400 Pair/Float objects per frame (less GC, smoother).
 */
internal class Landmarks(val data: FloatArray, val count: Int) {
    fun x(i: Int): Float = data[i * 3]
    fun y(i: Int): Float = data[i * 3 + 1]
    fun z(i: Int): Float = data[i * 3 + 2]
}

internal val EMPTY_LANDMARKS = Landmarks(FloatArray(0), 0)

/**
 * Snapshot of the whole HUD state after each frame.
 * Built on cameraExecutor, read only on the UI thread (immutable).
 */
internal data class HudSnapshot(
    val faceDetected: Boolean,
    val points: Landmarks,
    val frameWidth: Int,
    val frameHeight: Int,
    val ear1: Float?,
    val ear2: Float?,
    val eyeStatus: String,
    val distanceCm: Float?,
    val tooClose: Boolean,
    val calibrated: Boolean,
    val calibrationProgress: Int,
    val baselineEar: Float?,
    val closeThreshold: Float?,
    val bpm: Float?,
    val blinkDetected: Boolean,
    val sei: Int?,
    val processingMs: Float,
    val perclosPercent: Float?,
    val perclosP70Percent: Float?,
    val perclosP80Percent: Float?,
    val alertMessage: String?,
    val sessionSeconds: Long,
    val averageSei: Int?,
    val fps: Float,
    val distanceScale: Float,
    val yawDeg: Float?,
    val pitchDeg: Float?,
    val rollDeg: Float?,
    val gazeStatus: String,
    val expressionStatus: String,
    val bfr: Float?,
    val mar: Float?,
    val yawnCount: Int,
    val chronicLevel: Int,
    val inattentionPercent: Float?,
    val violationScore: Float,
    val anomaly: String?,
    val avgBlinkMs: Float?,
    val fatigueIndex: Int?,
    val signalQuality: Int,
    val signalNote: String,
    val minSei: Int?,
    val violationsTotal: Int,
    val chronicEpisodes: Int,
    val yawnTotal: Int,
    val habitStatus: String,
    val habitDetail: String,
    val habitHistory: String,
    val alertCode: Int,
    val eyeDetail: String,
    val imageDetail: String,
    /**
     * Reminder line for the operator when the face width does not match the selected profile.
     * Not a distraction alert and never audible; null while there is nothing to suspect.
     */
    val personHint: String? = null,
    /**
     * Reminder line shown while the face fills too few pixels for the eye ratios to be trustworthy.
     * Deliberately outside the alert chain: a bad framing is not a sign of distraction, and a warning
     * with priority would silence the PERCLOS, yawn and pose alerts on exactly those frames.
     */
    val faceSizeHint: String? = null,
    val bandSummary: String = ""
)

/** One head-pose sample in the time sliding window (raw angle, unfiltered). */
internal data class PoseSample(
    val timeMs: Long,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val ear: Float
)

/** One weighted attention violation inside the accumulating window. */
internal data class ViolationEvent(
    val timeMs: Long,
    val weight: Float
)

/**
 * The "where did the score come from" table, built from values already computed so nothing is
 * measured again. Kept in a pure file so it can be tested on a computer: this is the table judges
 * read, and one wrongly printed number there is worse than a score that changes.
 *
 * @param weightedSum weighted total BEFORE the penalty (so it can be added back up).
 * @param finalRaw value actually smoothed, i.e. after the penalty and the still-image block.
 */
internal fun buildSeiBreakdown(
    posture: Float,
    dist: Float,
    gaze: Float,
    eyes: Float,
    blinkRate: Float,
    expression: Float,
    weights: FloatArray,
    closePenalty: Float,
    chronicPenalty: Float,
    frozen: Boolean,
    anomalyCap: Float,
    weightedSum: Float,
    finalRaw: Float,
    sei: Int
): String = buildString {
    appendLine("Mỗi thành phần tính trên thang 0-100 (cao = tốt), nhân trọng số rồi cộng:")
    seiPart("Tư thế đầu", posture, weights[0])
    seiPart("Khoảng cách", dist, weights[1])
    seiPart("Hướng nhìn", gaze, weights[2])
    seiPart("Mắt (EAR + PERCLOS + nheo)", eyes, weights[3])
    seiPart("Nhịp chớp mắt", blinkRate, weights[4])
    seiPart("Vi biểu cảm", expression, weights[5])
    appendLine(String.format(Locale.US, "  Tổng có trọng số: %.1f", weightedSum))
    if (closePenalty > 0f) {
        appendLine(String.format(Locale.US, "  - ngồi quá gần: -%.0f", closePenalty))
    }
    if (chronicPenalty > 0f) {
        appendLine(String.format(Locale.US, "  - mất tập trung kéo dài: -%.0f", chronicPenalty))
    }
    if (frozen) {
        appendLine(String.format(Locale.US, "  - hình đứng yên (nghi ảnh tĩnh): chặn ở %.0f", anomalyCap))
    }
    appendLine(String.format(Locale.US, "  Điểm đưa vào làm mượt: %.1f", finalRaw))
    appendLine(String.format(Locale.US, "SEI hiển thị: %d (làm mượt theo nhịp khung hình)", sei))
    append("Trọng số là lựa chọn ban đầu, chưa hồi quy với quan sát của giáo viên.")
}

private fun StringBuilder.seiPart(name: String, score: Float, weight: Float) {
    appendLine(
        String.format(
            Locale.US,
            "  %-28s %6.1f x %.2f = %6.1f",
            name,
            score,
            weight,
            score * weight
        )
    )
}

/**
 * Quick check that a CSV row has exactly the header's column count, by counting commas.
 * Counted by hand instead of split: split would allocate ~37 strings per frame, i.e. ~1,100
 * strings per second just to check something that should be free.
 */
internal fun csvShapeOk(line: String, columnCount: Int): Boolean {
    var commas = 0
    for (c in line) {
        if (c == ',') {
            commas++
        }
    }
    return commas == columnCount - 1
}

/**
 * Value of a CSV column in a frame with NO face present.
 *
 * Looked up by column NAME instead of counting positions: this row used to be written with
 * `for (i in 3..28) append(',')`, so inserting one column into the middle of the header shifted
 * every later value by one cell and turned the whole audit file to garbage unnoticed.
 * With a name lookup an unknown column simply gets an empty cell - exactly "not measured".
 *
 * An empty cell is not a 0. Writing 0 into an eye column reads as "eyes closed", while an empty
 * cell tells the analyst immediately that this frame had nothing to measure.
 */
internal fun noFaceCsvValue(
    column: String,
    nowMs: Long,
    sei: Int?,
    chronic: Int,
    sessionSec: Long,
    fps: Float,
    procMs: Float,
    luma: Float
): String = when (column) {
    "t_ms" -> nowMs.toString()
    "face" -> "0"
    "sei" -> if (sei != null) sei.toString() else ""
    "alert_code" -> "0"
    "chronic" -> chronic.toString()
    "session_s" -> sessionSec.toString()
    "fps" -> fixed4(fps)
    "proc_ms" -> fixed4(procMs)
    "luma" -> fixed2(luma)
    else -> ""
}

/** Four decimals, matching the corresponding column of a face-present row exactly. */
private fun fixed4(v: Float): String =
    if (v.isNaN() || v.isInfinite()) "" else String.format(Locale.US, "%.4f", v)

private fun fixed2(v: Float): String =
    if (v.isNaN() || v.isInfinite()) "" else String.format(Locale.US, "%.2f", v)

/**
 * Builds a complete "no face" row into [out] in exactly the [columns] order.
 * Moved out of MainActivity so this runs on a computer in a unit test: one missing cell
 * in the audit file costs the whole measurement session, and a human
 * eye cannot count 37 commas.
 */
internal fun buildNoFaceCsvRow(
    columns: List<String>,
    out: StringBuilder,
    nowMs: Long,
    sei: Int?,
    chronic: Int,
    sessionSec: Long,
    fps: Float,
    procMs: Float,
    luma: Float
): StringBuilder {
    out.setLength(0)
    for (i in columns.indices) {
        if (i > 0) {
            out.append(',')
        }
        out.append(noFaceCsvValue(columns[i], nowMs, sei, chronic, sessionSec, fps, procMs, luma))
    }
    return out
}

/**
 * Reads the last [wanted] lines of a text file without loading the whole file.
 * The [windowBytes] window is cut so that its first line (very likely truncated mid-line) is
 * dropped, unless the read started exactly at the beginning of the file.
 *
 * Returns `null` when there is nothing to read - the file does not exist, was deleted midway, or
 * access was revoked. An empty list means the file could be read but holds no data rows yet.
 * Those two must stay different: "the app has not written any row" and "file not found" are
 * opposite conclusions when reconciling the numbers.
 */
internal fun readTailLines(
    file: java.io.File,
    wanted: Int,
    windowBytes: Long = 96L * 1024L
): List<String>? {
    val len = file.length()
    if (wanted <= 0) {
        return emptyList()
    }
    if (len == 0L) {
        // length() also returns 0 for a file that does not exist, so exists() is asked again so as not
        // to report "empty file" for a file that was actually not found.
        return if (file.exists()) emptyList() else null
    }
    val window = minOf(len, windowBytes).toInt()
    val start = len - window
    return try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            // The first line of the window is truncated only when the window starts MID-line. If the byte
            // right before the window is a newline, that line is intact and must be kept: dropping it
            // reflexively would make the "last few rows" table show fewer rows than the disk really has.
            val firstLineCut = start > 0L && try {
                raf.seek(start - 1L)
                raf.readByte() != '\n'.code.toByte()
            } catch (e: Exception) {
                true
            }
            raf.seek(start)
            val bytes = ByteArray(window)
            raf.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8)
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val usable = if (firstLineCut && lines.isNotEmpty()) lines.drop(1) else lines
            usable.takeLast(wanted)
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * One Euro filter: smooths a noisy signal yet keeps up during fast movement.
 */
internal class OneEuroFilter(
    private val minCutoff: Float,
    private val beta: Float,
    private val dCutoff: Float = 1f
) {
    private var initialized = false
    private var prevRaw = 0f
    private var prevFiltered = 0f
    private var prevDeriv = 0f
    private var prevTimeMs = 0L

    fun reset() {
        initialized = false
    }

    private fun alpha(cutoff: Float, dtSeconds: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dtSeconds)
    }

    fun filter(value: Float, nowMs: Long): Float {
        if (!initialized) {
            initialized = true
            prevRaw = value
            prevFiltered = value
            prevDeriv = 0f
            prevTimeMs = nowMs
            return value
        }

        val dt = (nowMs - prevTimeMs).coerceAtLeast(1L) / 1000f
        prevTimeMs = nowMs

        val deriv = (value - prevRaw) / dt
        val aD = alpha(dCutoff, dt)
        val derivHat = aD * deriv + (1f - aD) * prevDeriv

        val cutoff = minCutoff + beta * abs(derivHat)
        val a = alpha(cutoff, dt)
        val filtered = a * value + (1f - a) * prevFiltered

        prevRaw = value
        prevDeriv = derivHat
        prevFiltered = filtered
        return filtered
    }
}

/**
 * Upper bound of a frame gap still considered part of the machine cadence.
 * A gap wider than this means the app stalled - no inference about eyes open or closed.
 */
internal const val WINDOW_STALL_MS = 700L

/**
 * Counts the fraction of time a flag holds inside a sliding window (60 buckets, 1 second each).
 * Used for PERCLOS and the distraction ratio.
 *
 * @param capFrameMs ceiling for dt when the machine cadence is faster than [refFrameMs]; when the
 * cadence is slower, dt is clamped to refFrameMs so a real gap does not double the denominator.
 * @param refFrameMs reference frame cadence; the default lands close to the 200 ms the old
 * version used, so thresholds tuned against that scale stay valid.
 */
internal class WindowRatio(
    private val buckets: Int,
    private val minSampleMs: Long,
    private val capFrameMs: Long = 200L,
    private val refFrameMs: Long = capFrameMs / 3L
) {
    private val flaggedMs = LongArray(buckets)
    private val totalMs = LongArray(buckets)
    private val bucketSecond = LongArray(buckets) { -1L }
    private var lastFrameMs = 0L

    fun reset() {
        flaggedMs.fill(0L)
        totalMs.fill(0L)
        bucketSecond.fill(-1L)
        lastFrameMs = 0L
    }

    /** Called when the face is lost so a time gap is not accumulated. */
    fun pause() {
        lastFrameMs = 0L
    }

    /**
     * A frame's flag is assigned to the INTERVAL THAT JUST ENDED at that frame (dt is measured from
     * the previous frame), so the first frame of a burst contributes nothing. The window is 60 seconds
     * while the machine cadence is tens of ms, so a one-frame offset is negligible; what has to be
     * right is that stalls are excluded entirely and every gap over [refFrameMs] carries one weight.
     */
    fun record(nowMs: Long, flagged: Boolean) {
        val rawDt = if (lastFrameMs == 0L) 0L else nowMs - lastFrameMs
        lastFrameMs = nowMs
        if (rawDt <= 0L || rawDt > WINDOW_STALL_MS) return

        val dt = if (rawDt > refFrameMs) refFrameMs else rawDt.coerceAtMost(capFrameMs)
        if (dt == 0L) return

        val sec = nowMs / 1000L
        val idx = (sec % buckets).toInt()

        if (bucketSecond[idx] != sec) {
            bucketSecond[idx] = sec
            flaggedMs[idx] = 0L
            totalMs[idx] = 0L
        }

        totalMs[idx] += dt
        if (flagged) flaggedMs[idx] += dt
    }

    /**
     * Returns null while the window holds fewer than [minSampleMs] samples. "Not measured enough" and
     * "measured 0%" are different conclusions: 0% is evidence, a fake 0% from missing samples is not.
     */
    fun percentOrNull(nowMs: Long): Float? {
        val nowSec = nowMs / 1000L
        var flagged = 0L
        var total = 0L

        for (i in 0 until buckets) {
            val sec = bucketSecond[i]
            if (sec < 0L) continue

            val age = nowSec - sec
            if (age in 0L until buckets.toLong()) {
                flagged += flaggedMs[i]
                total += totalMs[i]
            }
        }

        return if (total < minSampleMs) {
            null
        } else {
            flagged * 100f / total.toFloat()
        }
    }

    /** Like [percentOrNull] but treats "not enough samples" as 0% - for scoring, never for display. */
    fun percent(nowMs: Long): Float = percentOrNull(nowMs) ?: 0f
}
