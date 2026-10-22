package com.focuschange.ai

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

/**
 * Điểm mốc khuôn mặt dạng mảng phẳng [x0, y0, z0, x1, y1, z1, ...].
 * Tránh tạo ~1.400 đối tượng Pair/Float mỗi khung hình (giảm GC, mượt hơn).
 */
internal class Landmarks(val data: FloatArray, val count: Int) {
    fun x(i: Int): Float = data[i * 3]
    fun y(i: Int): Float = data[i * 3 + 1]
    fun z(i: Int): Float = data[i * 3 + 2]
}

internal val EMPTY_LANDMARKS = Landmarks(FloatArray(0), 0)

/**
 * Ảnh chụp toàn bộ trạng thái HUD sau mỗi khung hình.
 * Được tạo trên cameraExecutor và chỉ đọc trên UI thread (immutable).
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
    val imageDetail: String
)

/** Một mẫu tư thế đầu trong cửa sổ trượt thời gian (góc thô, chưa lọc). */
internal data class PoseSample(
    val timeMs: Long,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val ear: Float
)

/** Một lần vi phạm chú ý (có trọng số) trong cửa sổ tích lũy. */
internal data class ViolationEvent(
    val timeMs: Long,
    val weight: Float
)

/**
 * Bảng "điểm từ đâu mà ra", dựng từ các giá trị đã tính sẵn nên không đo lại gì.
 * Tách ra file thuần để test trên máy tính được: đây là bảng giám khảo sẽ đọc,
 * một con số in sai ở đây tệ hơn là điểm số thay đổi.
 *
 * @param weightedSum tổng có trọng số TRƯỚC khi trừ phạt (để cộng được ngược lại).
 * @param finalRaw giá trị thật đưa vào làm mượt, tức là sau khi trừ phạt và chặn ảnh tĩnh.
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
 * Kiểm tra nhanh một dòng CSV có đúng số cột của tiêu đề không, bằng cách đếm dấu phẩy.
 * Đếm thủ công thay bằng split: split tạo ra ~37 chuỗi cho mỗi khung hình, tức ~1.100 chuỗi
 * mỗi giây chỉ để kiểm tra một thứ lẽ ra miễn phí.
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
 * Giá trị của một cột CSV ở khung hình KHÔNG có mặt người.
 *
 * Tra theo TÊN cột thay vì đếm vị trí: trước đây dòng này được viết bằng vòng lặp
 * `for (i in 3..28) append(',')`, nghĩa là thêm một cột vào tiêu đề ở giữa là mọi giá trị
 * phía sau bị dồn lệch một ô và cả file đối chiếu thành rác mà không ai phát hiện.
 * Với cách tra tên, một cột lạ chỉ nhận ô trống - đúng ý "không đo được".
 *
 * Ô trống khác với số 0. Viết 0 vào cột tai/mắt sẽ bị đọc thành "mắt nhắm", còn để trống
 * thì người phân tích biết ngay khung hình này không có gì để đo.
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

/** Bốn chữ số thập phân, giống hệt cột tương ứng trong dòng có mặt người. */
private fun fixed4(v: Float): String =
    if (v.isNaN() || v.isInfinite()) "" else String.format(Locale.US, "%.4f", v)

private fun fixed2(v: Float): String =
    if (v.isNaN() || v.isInfinite()) "" else String.format(Locale.US, "%.2f", v)

/**
 * Ghép trọn một dòng "mất mặt" vào [out] theo đúng thứ tự [columns].
 * Tách ra ngoài MainActivity để hàm này chạy được trên máy tính trong unit test:
 * thủng một ô trong file đối chiếu là mất luôn cả phiên đo, mà mắt người thì không
 * đếm nổi 37 dấu phẩy.
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
 * Đọc [wanted] dòng cuối của một file văn bản mà không nạp cả file.
 * Cửa sổ [windowBytes] được cắt sao cho dòng đầu tiên (rất có thể bị cắt giữa chừng) bị loại,
 * trừ khi phần đọc bắt đầu đúng từ đầu file.
 *
 * Trả về `null` khi không có gì để đọc - file không tồn tại, bị xoá giữa chừng, hoặc quyền truy
 * cập bị thu hồi. Danh sách rỗng nghĩa là đọc được nhưng file chưa có dòng dữ liệu nào. Hai
 * chuyện này phải khác nhau: "app chưa ghi được dòng nào" và "tìm không thấy file" là hai kết
 * luận khác hẳn nhau khi đối chiếu số.
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
        // length() cũng trả về 0 khi file không có thật, nên phải hỏi lại exists() để không
        // báo "file rỗng" cho một file thực ra là tìm không thấy.
        return if (file.exists()) emptyList() else null
    }
    val window = minOf(len, windowBytes).toInt()
    val start = len - window
    return try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            // Dòng đầu của cửa sổ chỉ bị cắt khi cửa sổ bắt đầu GIỮA một dòng. Nếu byte liền
            // trước cửa sổ là xuống dòng thì dòng đó nguyên vẹn và phải được giữ lại: bỏ nó
            // theo phản xạ khiến bảng "vài dòng cuối" ít hơn số dòng thật đang có trên đĩa.
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
 * Bộ lọc One Euro: làm mượt tín hiệu nhiễu nhưng vẫn bám nhanh khi chuyển động nhanh.
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
 * Giới hạn trên của một khoảng cách khung hình được coi là còn thuộc nhịp máy.
 * Khung nào thưa hơn mức này là ứng dụng bị đứng, không suy ra được mắt mở hay đóng.
 */
internal const val WINDOW_STALL_MS = 700L

/**
 * Bộ đếm tỉ lệ thời gian một cờ đúng trong cửa sổ trượt (60 bucket, mỗi bucket 1 giây).
 * Dùng cho PERCLOS và tỉ lệ mất tập trung.
 *
 * @param capFrameMs trần của dt khi nhịp máy nhanh hơn [refFrameMs]; khi nhịp máy chậm
 * thì dt bị chặn ở refFrameMs để khoảng cách thật không nhân đôi mẫu số.
 * @param refFrameMs nhịp khung hình tham chiếu; mặc định cho ra xấp xỉ mức 200 ms mà
 * phiên bản cũ dùng, nên không làm đổi thang đo đã chỉnh ngưỡng.
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

    /** Gọi khi mất khuôn mặt để không cộng dồn khoảng trống thời gian. */
    fun pause() {
        lastFrameMs = 0L
    }

    /**
     * Cờ của một khung được gán cho KHOẢNG VỪA KẾT THÚC tại khung đó (dt là khoảng tính từ
     * khung trước), nên khung đầu tiên của một cụm không đóng góp gì. Cửa sổ dài 60 giây còn
     * nhịp máy cỡ vài chục ms nên độ lệch một khung không đáng kể; điều cần đúng là khoảng
     * dừng bị loại hẳn và mọi khoảng dài hơn [refFrameMs] quy về cùng một trọng số.
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
     * Trả về null khi chưa đủ [minSampleMs] mẫu trong cửa sổ. "Chưa đo đủ" và "đo ra 0%" là hai
     * kết luận khác nhau: 0% là bằng chứng tốt, còn 0% giả vì thiếu mẫu thì lại là chưa biết gì.
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

    /** Như [percentOrNull] nhưng coi "chưa đủ mẫu" là 0% - dùng cho phép tính điểm, không dùng để hiển thị. */
    fun percent(nowMs: Long): Float = percentOrNull(nowMs) ?: 0f
}
