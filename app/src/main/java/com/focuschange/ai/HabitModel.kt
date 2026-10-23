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
    const val MIN_SESSION_MS = 2L * 60L * 1000L

    /** Tỉ lệ thời gian thấy khuôn mặt tối thiểu trong phiên. */
    const val MIN_FACE_RATIO = 0.70f

    /** Tỉ lệ khung hợp lệ / khung thấy mặt tối thiểu. */
    const val MIN_VALID_RATIO = 0.50f

    /** Chất lượng tín hiệu trung bình tối thiểu (0-100). */
    const val MIN_QUALITY = 60f

    /** SEI trung bình tối thiểu: thấp hơn nghĩa là phiên không phản ánh việc học bình thường. */
    const val MIN_SESSION_SEI = 40f

    /**
     * Số phiên đạt chuẩn tối thiểu và tổng thời gian hợp lệ tối thiểu để coi là "đã có hồ sơ".
     * Hai con số này đi cặp: 4 phiên x MIN_SESSION_MS (2 phút) mới ra 8 phút, nên trần tổng 10
     * phút buộc các phiên phải dài trung bình 2,5 phút chứ không được bốn phiên 2 phút liền.
     */
    const val MIN_SESSIONS = 4
    const val MIN_TOTAL_MS = 10L * 60L * 1000L

    const val MAX_SESSIONS = 40
    const val MIN_FRAMES_PER_FEATURE = 30
    const val RECENCY_DECAY = 0.9
    const val MAX_WEIGHT_MINUTES = 30.0

    /** Khoảng cách giữa hai khung lớn hơn mức này: coi như đứng máy, không tính là thời gian học. */
    const val MAX_FRAME_GAP_MS = 700L

    /**
     * Bội số của [ToleranceBand]: dải = mức điển hình ± BAND_K * độ dao động.
     * 2.5 là con số đang dùng cho các ngưỡng cảnh báo cá nhân (quay đầu, liếc mắt) nên dải và
     * cảnh báo không tự mâu thuẫn với nhau. Đưa ra ngoài để HUD, CSV và bản ghi Firebase cùng
     * nhân một hệ số - nếu mỗi nơi tự chọn một con số thì cùng một hành vi sẽ hai kết luận.
     */
    const val BAND_K = 2.5f
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

    /**
     * Năm đặc trưng KHÔNG có ngưỡng cá nhân, ghi lại để không ai (kể cả tôi) gọi 7 cái còn lại
     * là "mọi chỉ số đều học theo từng em":
     *
     * - DIST: 30 cm là chuẩn vệ sinh học đường áp dụng cho mọi người, không phải thói quen. Cá
     *   nhân hóa thành "em này quen ngồi 25 cm" sẽ biến một thói quen có hại thành mức bình thường
     *   của riêng em đó - đúng điều dự án đang muốn sửa.
     * - BLINK_MS / BFR: chưa có bằng chứng rằng độ dài một lần chớp hay tỉ lệ nhắm một mắt mang ý
     *   nghĩa hành vi ở độ phân giải này; BFR chủ yếu phản ánh landmark trượt khi nghiêng đầu.
     * - EAR: đã được cá nhân hóa bằng đường riêng (`blendEarWithHabit`, tối đa 35%) và ngưỡng đóng
     *   mắt của phiên lấy từ chính hiệu chuẩn của em đó, nên đưa thêm vào mix() là nhân đôi hiệu
     *   chuẩn trên cùng một con số.
     * - SEI: dùng làm MỨC SO SÁNH ("thấp hơn thường ngày của bạn", LOW_VS_PERSONAL_K), không đặt
     *   thành ngưỡng cảnh báo trực tiếp vì SEI đã là tổ hợp có trọng số của sáu thứ khác.
     */

    /** Giới hạn cắt (winsorize) từng quan sát để một khung nhiễu không làm lệch hồ sơ. */
    val LO = floatArrayOf(0f, 40f, 10f, -60f, -60f, -0.5f, -0.6f, 0f, 0.1f, 0f, 0f, 0f)
    val HI = floatArrayOf(60f, 1000f, 150f, 60f, 60f, 0.5f, 0.6f, 1.2f, 1.5f, 0.6f, 100f, 100f)

    /** Độ rộng tối thiểu để ngưỡng không bị "co" về 0 khi dữ liệu còn ít. */
    val MIN_SPREAD = floatArrayOf(2f, 20f, 3f, 2f, 2f, 0.02f, 0.03f, 0.02f, 0.01f, 0.01f, 1.5f, 3f)

    /**
     * Tên miền của 12 đặc trưng, theo đúng thứ tự chỉ mục. Dùng chung cho tiêu đề CSV và khóa
     * trong bản ghi Firebase: hai chỗ tự liệt kê thì sớm muộn cũng lệch nhau một chữ và không
     * ai đọc lại được dữ liệu cũ.
     */
    val KEYS = arrayOf(
        "blink_bpm", "avg_blink_ms", "distance_cm", "yaw_deg", "pitch_deg", "gaze_h",
        "gaze_v", "mar", "bfr", "ear", "perclos_p60", "sei"
    )

    /** Chữ ngắn để in lên bảng, cùng đơn vị và số chữ thập phân đáng tin của từng đặc trưng. */
    val NAMES = arrayOf(
        "Chớp mắt", "Độ dài một cái chớp", "Khoảng cách", "Đầu quay ngang", "Đầu cúi/ngửa",
        "Liếc ngang", "Liếc dọc", "Độ mở miệng (MAR)", "Một mắt nhắm (BFR)", "Độ mở mắt (EAR)",
        "PERCLOS P60", "SEI"
    )
    val UNITS = arrayOf("/phút", "ms", "cm", "°", "°", "", "", "", "", "", "%", "/100")
    val DECIMALS = intArrayOf(0, 0, 0, 1, 1, 2, 2, 2, 2, 3, 0, 0)

    /**
     * Tám đặc trưng được đối chiếu hai phía mỗi khung hình.
     *
     * Có mặt ở đây để HUD, CSV, bản ghi Firebase và bảng "Chỉ số" dùng đúng một danh sách.
     * Năm đặc trưng còn lại bị loại vì lý do đã ghi ở khối chú thích trên [LO]: DIST (ngồi 25 cm
     * là thói quen có hại, không phải "bình thường của em"), BLINK_MS/BFR (chưa có bằng chứng ý
     * nghĩa hành vi), EAR (đã có đường trộn riêng), và chỉ riêng SEI được đưa vào làm SỐ SO SÁNH
     * chứ không làm ngưỡng cảnh báo.
     */
    val BAND_FEATURES = intArrayOf(BPM, YAW, PITCH, GAZE_H, GAZE_V, MAR, PERCLOS, SEI)
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

/** Một giá trị nằm ở đâu so với dải quen thuộc của chính học sinh đó. */
internal enum class BandPosition { IN, BELOW, ABOVE, UNKNOWN }

/**
 * Đổi vị trí dải ra mã số của cột CSV: 0 = dưới dải, 1 = trong dải, 2 = trên dải,
 * -1 = không chấm được (chưa học được dải, hoặc giá trị không hữu hạn).
 */
internal fun BandPosition.toCsvCode(): Int = when (this) {
    BandPosition.BELOW -> 0
    BandPosition.IN -> 1
    BandPosition.ABOVE -> 2
    BandPosition.UNKNOWN -> -1
}

/**
 * Dải cho phép HAI PHÍA học từ thói quen: [min, max] quanh mức điển hình của riêng em đó.
 *
 * Đây là MÔ TẢ, không phải ngưỡng cảnh báo. Cảnh báo vẫn do các ngưỡng riêng quyết (nhóm ngưỡng
 * đó kẹp trong khoảng an toàn chung); dải chỉ trả lời câu hỏi "khung hình hôm nay có thuộc thói
 * quen của chính em này không", tức là phần thấp quá / cao quá mà một đường trung bình che mất.
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
 * Kết quả đối chiếu cả một phiên với dải quen thuộc, bất biến để hiển thị và gửi đi.
 * Giữ SỐ KHUNG chứ không giữ tỷ lệ: muốn tổng hợp cả phiên thì phải cộng các số đếm, còn cộng
 * tỷ lệ đã làm tròn của tám đặc trưng cho ra một con số không đúng với cái nào.
 *
 * Đặc trưng không có khung nào được chấm bị loại hẳn khỏi danh sách thay vì báo "0%": con số 0
 * sẽ bị đọc thành "luôn nằm trong dải", tức là khẳng định ngược với sự thật (chưa đo gì).
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

    /** Phần trăm khung lọt ra NGOÀI dải - con số duy nhất đáng để đánh dấu trên bảng. */
    val outsidePct: Float get() = belowPct + abovePct
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

    /**
     * Nạp dải cho cả phiên, gọi MỘT LẦN lúc bắt đầu phiên.
     *
     * Dải tính từ các phiên đã có TRƯỚC phiên này (phiên đang chạy chưa được commit vào hồ sơ).
     * Nếu nạp lại giữa chừng thì một buổi học khác thường sẽ tự nới dải của chính nó và che đi
     * cái mà dải ấy đáng ra phải báo.
     */
    fun setBands(base: HabitBaseline, features: IntArray) {
        for (f in features) bands[f] = base.band(f)
    }

    /**
     * Chấm xem một giá trị nằm trong/dưới/trên dải quen thuộc.
     *
     * Đi riêng với [observe] có chủ đích: [observe] kẹp mẫu vào khoảng vật lý trước khi tính
     * trung bình, còn ở đây giá trị lọt ra ngoài phải giữ nguyên - kẹp thì "thấp hơn thường ngày"
     * bị kéo về đúng mép dải và không bao giờ thành BELOW. UNKNOWN (chưa có dải, giá trị không
     * hữu hạn) không được đếm vào đâu cả, kể cả "trong dải".
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

    /** Bao nhiêu đặc trưng trong [features] đã học được dải. */
    fun bandCoverage(features: IntArray): Int = features.count { bands[it] != null }

    /**
     * Toàn bộ kết quả đối chiếu của phiên, theo thứ tự [HabitFeature.BAND_FEATURES].
     * Đặc trưng chưa có dải hoặc chưa có khung nào được chấm thì bị bỏ hẳn.
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

    /**
     * Dải cho phép hai phía của một đặc trưng: mean ± k*spread, kẹp trong khoảng vật lý LO/HI
     * của chính nó. Trả null khi đặc trưng này chưa học được gì - "chưa biết" phải khác với
     * "dải rộng bằng 0".
     *
     * Không cần kiểm tra min <= max: [means] là trung bình có trọng số của các giá trị đã bị kẹp
     * vào [LO, HI] ngay lúc đo, nên mean luôn nằm giữa hai mép và mỗi mép chỉ bị kéo về phía mean.
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

/** Hồ sơ thói quen của một học sinh: danh sách các phiên đạt chuẩn. */
internal class HabitProfile(val id: String, var name: String) {
    val sessions = ArrayList<SessionSummary>()

    /**
     * Kết quả hiệu chuẩn khoảng cách, lưu THEO HỒ SƠ chứ không theo chiếc điện thoại.
     *
     * Trước đây hệ số chỉ là một pref của máy (`distance_scale_v2`), nên khi em thứ hai bấm hiệu
     * chuẩn thì mọi con số cm của em thứ nhất đổi theo mà không ai thấy.
     *
     * Hai giá trị này cũng là chữ ký hình học duy nhất mà ứng dụng có, vì hiệu chuẩn diễn ra ở
     * khoảng cách tham chiếu đã biết trước: `distanceScale = REF / calRawDistanceCm` chỉ còn biến
     * số là bề rộng mặt thật của người ngồi, và [PersonGuard] dùng đúng cái đó để nghi đã đổi người.
     */
    var distanceScale: Float = Float.NaN

    /** Trung vị khoảng cách THÔ (chưa nhân hệ số) của các khung được dùng để hiệu chuẩn. */
    var calRawDistanceCm: Float = Float.NaN

    /** Giây mà máy tính thiết bị (`System.currentTimeMillis`) của lần hiệu chuẩn gần nhất. */
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
     * Độ tin cậy 0..1: càng nhiều phiên, ngưỡng cá nhân càng được ưu tiên hơn ngưỡng mặc định.
     * Mốc "+1" neo vào MIN_SESSIONS để đúng PHIÊN ĐẦU TIÊN đạt chuẩn của một hồ sơ vừa đủ điều
     * kiện luôn bắt đầu ở 0.2. Nếu giữ `- 2` như bản cũ thì từ 3 phiên lên 4 phiên, hồ sơ vừa
     * "ready" đã nhảy thẳng xuống 0.4 - trộn mạnh gấp đôi với cùng lượng dữ liệu, chỉ vì một hằng
     * số khác bị đổi ở chỗ khác.
     */
    fun confidence(): Float {
        if (!isReady()) return 0f
        return ((sessions.size - HabitConfig.MIN_SESSIONS + 1) / 5f).coerceIn(0.2f, 1f)
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
