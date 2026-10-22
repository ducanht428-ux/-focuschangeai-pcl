package com.focuschange.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
// =====================================================================
// HỒ SƠ THÓI QUEN TỪNG HỌC SINH (gộp chung vào một file)
// =====================================================================

/**
 * Cấu hình học thói quen. Muốn thử nhanh khi phát triển: hạ MIN_SESSION_MS xuống 20_000L
 * và MIN_TOTAL_MS xuống 60_000L.
 */
internal object HabitConfig {
    /** Một phiên chỉ được lưu khi có ít nhất ngần này thời gian "hợp lệ". */
    const val MIN_SESSION_MS = 3L * 60L * 1000L

    /** Tỉ lệ thời gian thấy khuôn mặt tối thiểu trong phiên. */
    const val MIN_FACE_RATIO = 0.70f

    /** Tỉ lệ khung hợp lệ / khung thấy mặt tối thiểu. */
    const val MIN_VALID_RATIO = 0.50f

    /** Chất lượng tín hiệu trung bình tối thiểu (0-100). */
    const val MIN_QUALITY = 60f

    /** SEI trung bình tối thiểu: thấp hơn nghĩa là phiên không phản ánh việc học bình thường. */
    const val MIN_SESSION_SEI = 40f

    /** Số phiên đạt chuẩn tối thiểu và tổng thời gian hợp lệ tối thiểu để coi là "đã có hồ sơ". */
    const val MIN_SESSIONS = 3
    const val MIN_TOTAL_MS = 15L * 60L * 1000L

    const val MAX_SESSIONS = 40
    const val MIN_FRAMES_PER_FEATURE = 30
    const val RECENCY_DECAY = 0.9
    const val MAX_WEIGHT_MINUTES = 30.0

    /** Khoảng cách giữa hai khung lớn hơn mức này: coi như đứng máy, không tính là thời gian học. */
    const val MAX_FRAME_GAP_MS = 700L
}

/** Các đặc trưng được học riêng cho từng học sinh. */
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

    /** Giới hạn cắt (winsorize) từng quan sát để một khung nhiễu không làm lệch hồ sơ. */
    val LO = floatArrayOf(0f, 40f, 10f, -60f, -60f, -0.5f, -0.6f, 0f, 0.1f, 0f, 0f, 0f)
    val HI = floatArrayOf(60f, 1000f, 150f, 60f, 60f, 0.5f, 0.6f, 1.2f, 1.5f, 0.6f, 100f, 100f)

    /** Độ rộng tối thiểu để ngưỡng không bị "co" về 0 khi dữ liệu còn ít. */
    val MIN_SPREAD = floatArrayOf(2f, 20f, 3f, 2f, 2f, 0.02f, 0.03f, 0.02f, 0.01f, 0.01f, 1.5f, 3f)
}

/** Thống kê chạy (Welford): trung bình và độ lệch chuẩn mà không lưu từng mẫu. */
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

/** Tóm tắt một phiên học: trung bình, độ lệch chuẩn và số mẫu của từng đặc trưng. */
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
 * Bộ tích lũy của phiên đang chạy (chỉ dùng trên cameraExecutor).
 * Mỗi khung hợp lệ gọi observe(); tick() cộng thời gian.
 */
internal class HabitSession(val id: Long) {
    private val stats = Array(HabitFeature.COUNT) { RunningStat() }
    private val quality = RunningStat()
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

        // activeMs là đồng hồ của phiên và cũng là mẫu số của faceRatio. Khoảng dừng dài không
        // được tính là lúc học sinh đang học, nhưng vẫn làm mẫu số lớn lên - đó là lựa chọn có
        // chủ đích, đã được đóng đinh bằng test stallKeepsFaceRatioHonest: một phiên mà phần lớn
        // thời gian máy không quay thì không đủ tư cách vào hồ sơ, dù mọi khung còn lại đều thấy mặt.
        activeMs += dt
        if (dt > HabitConfig.MAX_FRAME_GAP_MS) return

        // Cộng đủ, không kẹp ở 200 ms: khi máy chạy dưới 5 FPS thì mỗi khung cách nhau quá
        // 200 ms thật, kẹp lại sẽ khiến "phút hợp lệ" không bao giờ chạm MIN_SESSION_MS.
        if (faceVisible) faceMs += dt
        if (valid) validMs += dt
    }

    fun observe(feature: Int, value: Float) {
        if (value.isNaN() || value.isInfinite()) return
        val v = value.coerceIn(HabitFeature.LO[feature], HabitFeature.HI[feature])
        stats[feature].add(v.toDouble())
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

/** Mức cơ sở đã học: giá trị điển hình + độ dao động tự nhiên của từng đặc trưng. */
internal class HabitBaseline(
    private val means: FloatArray,
    private val spreads: FloatArray,
    private val ok: BooleanArray
) {
    fun mean(f: Int): Float? = if (ok[f]) means[f] else null
    fun spread(f: Int): Float? = if (ok[f]) spreads[f] else null

    /** |mean| + k * spread, kẹp trong [lo, hi]. Trả về null nếu chưa có dữ liệu. */
    fun threshold(f: Int, k: Float, lo: Float, hi: Float): Float? {
        if (!ok[f]) return null
        return (abs(means[f]) + k * spreads[f]).coerceIn(lo, hi)
    }
}

/** Hồ sơ thói quen của một học sinh: danh sách các phiên đạt chuẩn. */
internal class HabitProfile(val id: String, var name: String) {
    val sessions = ArrayList<SessionSummary>()

    fun sessionCount(): Int = sessions.size

    fun totalValidMs(): Long {
        var t = 0L
        for (s in sessions) t += s.validMs
        return t
    }

    fun isReady(): Boolean =
        sessions.size >= HabitConfig.MIN_SESSIONS &&
                totalValidMs() >= HabitConfig.MIN_TOTAL_MS

    /** Độ tin cậy 0..1: càng nhiều phiên, ngưỡng cá nhân càng được ưu tiên hơn ngưỡng mặc định. */
    fun confidence(): Float {
        if (!isReady()) return 0f
        return ((sessions.size - 2) / 5f).coerceIn(0.2f, 1f)
    }

    /** Lý do phiên chưa đủ điều kiện để học (null nếu đạt). */
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
     * Thêm hoặc cập nhật phiên (cùng id thì ghi đè, vì phiên được lưu định kỳ).
     * Phiên không đạt chuẩn sẽ bị gỡ nếu trước đó đã lưu.
     * Trả về true nếu hồ sơ có thay đổi.
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
     * SEI trung bình trên [fromIdx, toIdx) kèm SỐ PHIÊN thực sự đóng góp.
     * Con số thứ hai là phần quan trọng: một "xu hướng" rút ra từ một phiên trước và ba phiên
     * sau thì không nói được gì về học sinh, chỉ nói về may rủi của buổi hôm đó.
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
     * Mức cơ sở: trung bình có trọng số (phiên dài hơn và gần đây hơn nặng hơn),
     * loại phiên ngoại lai (MAD) khi đã có từ 5 phiên.
     * spread = căn(phương sai giữa các phiên + phương sai trong phiên).
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
