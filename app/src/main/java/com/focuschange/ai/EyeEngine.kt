package com.focuschange.ai

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

import kotlin.math.sqrt
// =====================================================================
// CÔNG CỤ PHÂN TÍCH MẮT NÂNG CAO (kính, góc chéo, nheo mắt, lệch hai mắt)
// =====================================================================

/** Kết quả phân tích mắt của một khung hình (đối tượng tái sử dụng, chỉ đọc trên cameraExecutor). */
internal class EyeFrame {
    var valid = false
    var openness = 1f
    var closed = false
    var closedStartMs = 0L
    var blinkDurationMs = 0L
    var blinkIncomplete = false
    var noisy = false
    var sigma = 0f
    var squintPct = 0f
    var asymPct = 0f
    var incompleteRatio = Float.NaN
    var squintAlert = false
    var asymAlert = false
    var incompleteAlert = false
    var usingBlendshapes = false
}

/**
 * Độ mở mắt hợp nhất, bền với kính và góc nhìn chéo.
 *
 * 1. Hình học: EAR 5 cặp điểm mí (trung bình cắt tỉa, bỏ điểm cao nhất/thấp nhất để loại điểm bị chói kính),
 *    chuẩn hóa theo đường cơ sở từng mắt, bù co ngắn do quay đầu (cos yaw) và mí hạ khi nhìn xuống.
 * 2. Blendshape (nếu có): mô hình học sâu của MediaPipe, ít bị ảnh hưởng bởi kính và góc nghiêng.
 * 3. Hợp nhất có kiểm tra bất đồng, lọc One Euro, ngưỡng đóng/mở tự thích nghi theo mức nhiễu.
 * 4. Máy trạng thái chớp mắt theo thời gian thực (không phụ thuộc FPS), có trễ hai ngưỡng.
 * 5. Chỉ số khó chịu: nheo mắt kéo dài, hai mắt mở không đều, chớp không hoàn toàn.
 */
internal class EyeEngine {

    // internal (không phải private) để bảng ngưỡng trên HUD in đúng hai con số nheo mắt /
    // lệch hai mắt thay vì chép lại bằng tay và có nguy cơ lệch dần.
    internal companion object {
        val R_UP = intArrayOf(161, 160, 159, 158, 157)
        val R_LOW = intArrayOf(163, 144, 145, 153, 154)
        val L_UP = intArrayOf(388, 387, 386, 385, 384)
        val L_LOW = intArrayOf(390, 373, 374, 380, 381)
        const val R_OUT = 33
        const val R_IN = 133
        const val L_IN = 362
        const val L_OUT = 263

        const val CLOSE_O = 0.60f
        const val OPEN_O = 0.78f
        const val MIN_CLOSE_DWELL_MS = 40L
        const val MIN_BLINK_MS = 25L
        const val MAX_BLINK_MS = 1_000L
        const val INCOMPLETE_MIN_O = 0.45f
        const val INCOMPLETE_MAX_DT_MS = 55f
        const val ASYM_EXTRA_THRESHOLD = 0.25f
        const val PARTIAL_LO = 0.45f
        const val PARTIAL_HI = 0.80f
        const val DEG2RAD = 0.017453292f
        const val NOISY_ON = 0.12f
        const val NOISY_OFF = 0.09f
        const val NOISY_HOLD_MS = 10_000L
        const val SQUINT_ALERT_PCT = 40f
        const val ASYM_ALERT_PCT = 50f
        const val INCOMPLETE_ALERT_RATIO = 0.6f
        const val RING = 20
    }

    val out = EyeFrame()

    /** Độ nhạy: 0 thấp (chỉ nhận chớp sâu), 1 vừa, 2 cao (nhận cả chớp nông). */
    fun setSensitivity(level: Int) {
        closeO = when (level) {
            0 -> 0.54f
            2 -> 0.66f
            else -> CLOSE_O
        }
        openO = closeO + 0.18f
    }

    private val tmp = FloatArray(5)
    private val openFilter = OneEuroFilter(4.0f, 1.0f)
    private val squintRatio = WindowRatio(60, 20_000L)
    private val asymRatio = WindowRatio(60, 30_000L)

    // Hiệu chuẩn
    private val calE1 = ArrayList<Float>()
    private val calE2 = ArrayList<Float>()
    private val calBs = ArrayList<Float>()
    private val calAsym = ArrayList<Float>()
    private var ready = false
    private var calBase1 = 0.3f
    private var calBase2 = 0.3f
    private var base1 = 0.3f
    private var base2 = 0.3f
    private var bsBase = 0.08f
    private var bsAsymBase = 0f
    private var closeO = CLOSE_O
    private var openO = OPEN_O

    // Trạng thái chớp mắt
    private var closed = false
    private var candStart = 0L
    private var candMin = 1f
    private var closedStart = 0L
    private var minDuring = 1f

    // Nhiễu và lọc khung lỗi
    private var sigma = 0.03f
    private var noisyStart = 0L
    private var prevWr = 0f
    private var prevWl = 0f
    private var glitchStreak = 0
    private var lastUpdateMs = 0L
    private var dtEma = 33f

    // Chớp không hoàn toàn
    private val ring = BooleanArray(RING)
    private var ringIdx = 0
    private var ringCount = 0

    private fun d(lm: Landmarks, a: Int, b: Int, w: Int, h: Int): Float {
        val dx = (lm.x(a) - lm.x(b)) * w
        val dy = (lm.y(a) - lm.y(b)) * h
        return sqrt(dx * dx + dy * dy)
    }

    /** EAR 5 cặp điểm, trung bình cắt tỉa. Trả về NaN nếu hình học không hợp lệ. */
    private fun ear5(
        lm: Landmarks,
        up: IntArray,
        low: IntArray,
        a: Int,
        b: Int,
        w: Int,
        h: Int
    ): Float {
        var sum = 0f
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until 5) {
            val v = d(lm, up[i], low[i], w, h)
            tmp[i] = v
            sum += v
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val hor = d(lm, a, b, w, h)
        if (hor < 1f) return Float.NaN
        return ((sum - mn - mx) / 3f) / hor
    }

    private fun percentile(list: List<Float>, p: Float): Float {
        val s = list.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    fun reset() {
        calE1.clear()
        calE2.clear()
        calBs.clear()
        calAsym.clear()
        ready = false
        sigma = 0.03f
        noisyStart = 0L
        ringIdx = 0
        ringCount = 0
        squintRatio.reset()
        asymRatio.reset()
        pause()
    }

    /** Mất khuôn mặt hoặc khởi động lại luồng: xóa trạng thái tức thời, giữ hiệu chuẩn. */
    fun pause() {
        closed = false
        candStart = 0L
        openFilter.reset()
        squintRatio.pause()
        asymRatio.pause()
        prevWr = 0f
        prevWl = 0f
        glitchStreak = 0
        lastUpdateMs = 0L
        out.valid = false
        out.closed = false
    }

    fun addCalibrationSample(lm: Landmarks, w: Int, h: Int, bs: FloatArray?) {
        val e1 = ear5(lm, R_UP, R_LOW, R_OUT, R_IN, w, h)
        val e2 = ear5(lm, L_UP, L_LOW, L_IN, L_OUT, w, h)
        if (e1.isNaN() || e2.isNaN()) return
        calE1.add(e1)
        calE2.add(e2)
        if (bs != null) {
            calBs.add((bs[0] + bs[1]) / 2f)
            calAsym.add(abs(bs[0] - bs[1]))
        }
    }

    /**
     * Chốt đường cơ sở: lấy phân vị 70 (loại các khung chớp mắt/nheo lọt vào lúc hiệu chuẩn).
     * scale là hệ số trộn với giá trị đã học trong hồ sơ thói quen.
     */
    fun finishCalibration(scale: Float) {
        if (calE1.size < 5) {
            ready = false
            return
        }
        calBase1 = percentile(calE1, 0.70f) * scale
        calBase2 = percentile(calE2, 0.70f) * scale
        base1 = calBase1
        base2 = calBase2
        bsBase = if (calBs.size >= 5) percentile(calBs, 0.5f).coerceIn(0f, 0.5f) else 0.08f
        // Mắt hai bên vốn không đối xứng hoàn toàn: chỉ coi là lệch khi vượt mức lệch tự nhiên đã đo lúc hiệu chuẩn.
        bsAsymBase = if (calAsym.size >= 5) percentile(calAsym, 0.5f).coerceIn(0f, 0.3f) else 0f
        ready = true
        sigma = 0.03f
        noisyStart = 0L
        ringIdx = 0
        ringCount = 0
        squintRatio.reset()
        asymRatio.reset()
        pause()
    }

    private fun recordBlink(incomplete: Boolean) {
        ring[ringIdx] = incomplete
        ringIdx = (ringIdx + 1) % RING
        if (ringCount < RING) ringCount++
    }

    private fun incompleteRatio(): Float {
        if (ringCount < 8) return Float.NaN
        var n = 0
        for (i in 0 until ringCount) if (ring[i]) n++
        return n.toFloat() / ringCount.toFloat()
    }

    /**
     * Khung hình này KHÔNG kết luận được về mắt. Phải xoá các chỉ số suy ra từ khung trước
     * khỏi bản phát hành: `out` là một đối tượng tái dùng, nên nếu chỉ hạ cờ valid xuống false
     * thì MainActivity vẫn đọc thấy "nheo 47%" của ba giây trước và trừ điểm SEI, thậm chí bắn
     * lại cảnh báo nheo mắt, trong khi engine đang nói "tôi không biết".
     *
     * sigma để NaN chứ không để 0: ô trống trong CSV nghĩa là "không đo được", còn 0 nghĩa là
     * "mắt mở hoàn toàn, không nhiễu" - hai kết luận ngược nhau trên cùng một con số.
     */
    private fun clearConclusions(o: EyeFrame) {
        o.squintPct = 0f
        o.asymPct = 0f
        o.incompleteRatio = Float.NaN
        o.squintAlert = false
        o.asymAlert = false
        o.incompleteAlert = false
        o.noisy = false
        o.sigma = Float.NaN
    }

    private fun markInvalid(o: EyeFrame, closedState: Boolean) {
        o.valid = false
        o.closed = closedState
        clearConclusions(o)
    }

    fun update(
        lm: Landmarks,
        w: Int,
        h: Int,
        nowMs: Long,
        yawDev: Float,
        pitchDev: Float,
        gazeVDev: Float,
        bs: FloatArray?,
        poseOk: Boolean
    ): EyeFrame {
        val o = out
        o.blinkDurationMs = 0L
        o.blinkIncomplete = false
        o.usingBlendshapes = bs != null

        if (!ready) {
            markInvalid(o, closedState = false)
            return o
        }

        val e1 = ear5(lm, R_UP, R_LOW, R_OUT, R_IN, w, h)
        val e2 = ear5(lm, L_UP, L_LOW, L_IN, L_OUT, w, h)
        val wr = d(lm, R_OUT, R_IN, w, h)
        val wl = d(lm, L_IN, L_OUT, w, h)

        if (lastUpdateMs != 0L) {
            val dtFrame = (nowMs - lastUpdateMs).toFloat()
            if (dtFrame in 1f..300f) dtEma = dtEma * 0.95f + dtFrame * 0.05f
        }
        lastUpdateMs = nowMs

        // 1) Loại khung landmark "nhảy" bất thường (thường do chói kính hoặc mất dấu một khung).
        var glitch = e1.isNaN() || e2.isNaN()
        if (!glitch && prevWr > 0f && prevWl > 0f) {
            val rr = wr / prevWr
            val rl = wl / prevWl
            if (rr > 1.3f || rr < 0.77f || rl > 1.3f || rl < 0.77f) glitch = true
        }
        if (glitch) {
            glitchStreak++
            if (glitchStreak > 8) {
                // Hình học mới ổn định lâu hơn 8 khung: chấp nhận làm mốc mới.
                prevWr = 0f
                prevWl = 0f
                glitchStreak = 0
            }
            markInvalid(o, closedState = closed)
            return o
        }
        glitchStreak = 0
        prevWr = wr
        prevWl = wl

        // 2) Góc quá nghiêng hoặc tín hiệu kém: không kết luận trạng thái mắt.
        if (!poseOk) {
            closed = false
            candStart = 0L
            openFilter.reset()
            markInvalid(o, closedState = false)
            return o
        }

        // 3) Độ mở hình học đã bù: co ngắn khi quay đầu và mí hạ khi nhìn xuống.
        val cosY = cos(min(abs(yawDev), 50f) * 0.9f * DEG2RAD)
        val down = max(pitchDev, 0f) * 0.006f + max(gazeVDev, 0f) * 0.5f
        val expected = (1f - down).coerceIn(0.7f, 1f)
        val c1 = e1 * cosY / expected
        val c2 = e2 * cosY / expected
        val og1 = (c1 / base1).coerceIn(0f, 1.5f)
        val og2 = (c2 / base2).coerceIn(0f, 1.5f)
        val og = (og1 + og2) / 2f

        // 4) Blendshape (nếu có) và hợp nhất có kiểm tra bất đồng giữa hai nguồn.
        var fused = og
        var squintBs = 0f
        var asymBs = 0f
        if (bs != null) {
            val blink = (bs[0] + bs[1]) / 2f
            val ob = (1f - (blink - bsBase) / (1f - bsBase).coerceAtLeast(0.5f)).coerceIn(0f, 1.3f)
            fused = if (abs(og - ob) > 0.45f) {
                // Hai nguồn lệch mạnh: hình học thường bị chói kính làm sai, tin blendshape hơn.
                0.8f * ob + 0.2f * og
            } else {
                0.65f * ob + 0.35f * og
            }
            squintBs = (bs[2] + bs[3]) / 2f
            asymBs = abs(bs[0] - bs[1]) - bsAsymBase
        }

        // 5) Lọc thời gian và ước lượng nhiễu khi mắt đang mở ổn định.
        val f = openFilter.filter(fused, nowMs)
        if (!closed && f > 0.85f) {
            sigma = sigma * 0.98f + abs(fused - f) * 1.25f * 0.02f
        }
        sigma = sigma.coerceIn(0f, 0.3f)

        if (sigma > NOISY_ON) {
            if (noisyStart == 0L) noisyStart = nowMs
        } else if (sigma < NOISY_OFF) {
            noisyStart = 0L
        }
        val noisy = noisyStart != 0L && nowMs - noisyStart >= NOISY_HOLD_MS

        // 6) Ngưỡng đóng/mở tự thích nghi: càng nhiễu (kính, chói) càng khắt khe để tránh chớp giả.
        val closeThr = (closeO - 1.5f * sigma).coerceIn(0.40f, closeO)
        val openThr = max(closeThr + 0.12f, openO - sigma).coerceAtMost(0.88f)

        // 7) Máy trạng thái chớp mắt theo thời gian thực.
        if (!closed) {
            if (f < closeThr) {
                if (candStart == 0L) {
                    candStart = nowMs
                    candMin = f
                }
                candMin = min(candMin, f)
                val dwell = nowMs - candStart
                // Đóng chắc chắn nếu đủ lâu, hoặc đóng rất sâu (bắt được cú chớp 1 khung khi FPS thấp).
                if (dwell >= MIN_CLOSE_DWELL_MS || f < closeThr - 0.15f) {
                    closed = true
                    closedStart = candStart
                    minDuring = candMin
                }
            } else {
                candStart = 0L
            }
        } else {
            minDuring = min(minDuring, f)
            if (f > openThr) {
                val dur = nowMs - closedStart
                if (dur in MIN_BLINK_MS..MAX_BLINK_MS) {
                    o.blinkDurationMs = dur
                    // FPS thấp (>55 ms/khung) có thể bỏ lỡ đáy của cú chớp: không kết luận "không hoàn toàn".
                    if (dtEma <= INCOMPLETE_MAX_DT_MS) {
                        o.blinkIncomplete = minDuring > INCOMPLETE_MIN_O
                        recordBlink(o.blinkIncomplete)
                    }
                }
                closed = false
                candStart = 0L
            }
        }

        // 8) Chỉ số khó chịu: nheo mắt kéo dài, hai mắt mở không đều.
        var partial = !closed && f >= PARTIAL_LO && f < PARTIAL_HI
        if (!partial && !closed && squintBs > 0.45f && f < 0.9f) partial = true
        squintRatio.record(nowMs, partial)

        val frontal = abs(yawDev) < 15f
        val asymFlag = if (bs != null) asymBs > ASYM_EXTRA_THRESHOLD else abs(og1 - og2) > 0.35f
        asymRatio.record(nowMs, frontal && !closed && f > 0.35f && asymFlag)

        val sq = squintRatio.percent(nowMs)
        val asym = asymRatio.percent(nowMs)
        val inc = incompleteRatio()

        // 9) Đường cơ sở theo dõi chậm: bám theo ánh sáng/kính thay đổi nhưng không "trôi" theo mệt mỏi.
        if (f > 0.8f && !closed) {
            if (c1 > base1) {
                if (c1 < base1 * 1.35f) base1 += 0.02f * (c1 - base1)
            } else if (c1 > base1 * 0.85f) {
                base1 -= 0.0004f * (base1 - c1)
            }
            if (c2 > base2) {
                if (c2 < base2 * 1.35f) base2 += 0.02f * (c2 - base2)
            } else if (c2 > base2 * 0.85f) {
                base2 -= 0.0004f * (base2 - c2)
            }
            base1 = base1.coerceIn(calBase1 * 0.85f, calBase1 * 1.25f)
            base2 = base2.coerceIn(calBase2 * 0.85f, calBase2 * 1.25f)
        }

        o.valid = true
        o.openness = f
        o.closed = closed
        o.closedStartMs = closedStart
        o.sigma = sigma
        o.noisy = noisy
        o.squintPct = sq
        o.asymPct = asym
        o.incompleteRatio = inc
        o.squintAlert = sq >= SQUINT_ALERT_PCT
        o.asymAlert = asym >= ASYM_ALERT_PCT
        o.incompleteAlert = !inc.isNaN() && inc >= INCOMPLETE_ALERT_RATIO
        return o
    }
}


// =====================================================================
// XỬ LÝ ẢNH MỨC THẤP: CLAHE thích ứng, lọc nhiễu nhẹ, ROI khuôn mặt
// =====================================================================

/**
 * Cải thiện ảnh trước khi đưa vào MediaPipe khi ánh sáng kém, chỉ làm việc trên kênh sáng Y
 * (không đụng tới màu), nên rẻ hơn nhiều so với xử lý ảnh RGB.
 *
 * 1. Đo ánh sáng THẬT trên vùng khuôn mặt (ROI) và trên cả khung: độ sáng, tương phản, tỉ lệ cháy sáng.
 *    Từ đó nhận ra ngược sáng (mặt tối, nền chói) và mặt bị cháy sáng.
 * 2. Bộ điều khiển có "trễ" (hysteresis) và thời gian giữ tối thiểu: không bật/tắt liên tục
 *    làm ảnh nhấp nháy và đường cơ sở hiệu chuẩn bị lệch.
 * 3. CLAHE (cân bằng histogram thích ứng có giới hạn tương phản) trên ROI khuôn mặt, cùng thuật toán
 *    với OpenCV: histogram theo ô, cắt ngưỡng, phân phối lại phần dư, nội suy song tuyến giữa 4 ô.
 * 4. Lọc Gaussian 3x3 tách trục chỉ khi rất thiếu sáng (CLAHE khuếch đại nhiễu).
 * 5. Tự tạm tắt 30 giây nếu chi phí xử lý vượt ngân sách thời gian, để không làm tụt FPS.
 */
internal class ImageEnhancer {

    private companion object {
        const val TILES_ROI = 4
        const val TILES_FULL = 8
        const val CLIP_LIMIT = 2.5f
        const val ON_MEAN = 85f
        const val OFF_MEAN = 105f
        const val ON_STD = 28f
        const val OFF_STD = 36f
        const val DENOISE_MEAN = 65f
        const val MIN_TOGGLE_MS = 4_000L
        const val BUDGET_MS = 12f
        const val BUDGET_WARMUP_FRAMES = 30
        const val PAUSE_MS = 30_000L
        const val MIN_REGION = 64
        const val SNAP = 8
        const val GLOBAL_STEP = 8
        const val ROI_STEP = 4
    }

    var active = false
        private set
    var denoising = false
        private set
    var paused = false
        private set
    var userOff = false
        private set
    var usingRoi = false
        private set
    var faceMean = 128f
        private set
    var faceStd = 50f
        private set
    var globalMean = 128f
        private set
    var backlit = false
        private set
    var overexposed = false
        private set
    var costMs = 0f
        private set

    private var lastToggleMs = 0L
    private var pausedUntilMs = 0L
    private var enhancedFrames = 0

    // Kết quả đo tạm của measure()
    private var mMean = 128f
    private var mStd = 50f
    private var mBright = 0f

    // ROI trong hệ tọa độ cảm biến: x0, y0, x1, y1
    private val roi = IntArray(4)
    private var cornerX = 0f
    private var cornerY = 0f

    // Bộ đệm tái sử dụng (không cấp phát mỗi khung)
    private var hist = IntArray(0)
    private var lut = IntArray(0)
    private var tmp = IntArray(0)
    private var colI0 = IntArray(0)
    private var colI1 = IntArray(0)
    private var colW = IntArray(0)
    private var colT = IntArray(0)
    private var rowI0 = IntArray(0)
    private var rowI1 = IntArray(0)
    private var rowW = IntArray(0)
    private var rowT = IntArray(0)

    /** Đo độ sáng, độ lệch chuẩn và tỉ lệ điểm cháy sáng của một vùng bằng lấy mẫu thưa. */
    private fun measure(
        y: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        step: Int
    ) {
        var n = 0
        var sum = 0L
        var sumSq = 0L
        var bright = 0
        var row = y0
        while (row < y1) {
            val base = row * rowStride
            var col = x0
            while (col < x1) {
                val v = y[base + col * pixelStride].toInt() and 0xFF
                sum += v
                sumSq += (v * v).toLong()
                if (v > 245) bright++
                n++
                col += step
            }
            row += step
        }

        if (n == 0) {
            mMean = 128f
            mStd = 50f
            mBright = 0f
            return
        }
        val mean = sum.toDouble() / n
        val variance = sumSq.toDouble() / n - mean * mean
        mMean = mean.toFloat()
        mStd = sqrt(max(variance, 0.0)).toFloat()
        mBright = bright.toFloat() / n.toFloat()
    }

    /**
     * Đổi điểm chuẩn hóa (u, v) của ảnh đã xoay thuận chiều kim đồng hồ `rotation` độ
     * về tọa độ chuẩn hóa của ảnh cảm biến gốc (đã kiểm chứng cho 0/90/180/270).
     */
    private fun mapCorner(u: Float, v: Float, rotation: Int) {
        when (rotation) {
            90 -> {
                cornerX = v
                cornerY = 1f - u
            }
            180 -> {
                cornerX = 1f - u
                cornerY = 1f - v
            }
            270 -> {
                cornerX = 1f - v
                cornerY = u
            }
            else -> {
                cornerX = u
                cornerY = v
            }
        }
    }

    /** Đổi hộp khuôn mặt sang vùng điểm ảnh của kênh Y, làm tròn theo lưới 8 điểm để ổn định. */
    private fun mapRoi(box: FloatArray, rotation: Int, w: Int, h: Int): Boolean {
        mapCorner(box[0], box[1], rotation)
        val ax = cornerX
        val ay = cornerY
        mapCorner(box[2], box[3], rotation)
        val bx = cornerX
        val by = cornerY

        val fx0 = min(ax, bx)
        val fx1 = max(ax, bx)
        val fy0 = min(ay, by)
        val fy1 = max(ay, by)

        val x0 = ((fx0 * w).toInt() / SNAP * SNAP).coerceIn(0, w)
        val y0 = ((fy0 * h).toInt() / SNAP * SNAP).coerceIn(0, h)
        val x1 = (((fx1 * w).toInt() + SNAP - 1) / SNAP * SNAP).coerceIn(0, w)
        val y1 = (((fy1 * h).toInt() + SNAP - 1) / SNAP * SNAP).coerceIn(0, h)

        if (x1 - x0 < MIN_REGION || y1 - y0 < MIN_REGION) return false
        roi[0] = x0
        roi[1] = y0
        roi[2] = x1
        roi[3] = y1
        return true
    }

    /** Bật khi thiếu sáng hoặc tương phản thấp, tắt khi đã đủ tốt (ngưỡng bật/tắt khác nhau). */
    private fun decide(nowMs: Long, mean: Float, std: Float) {
        val need = if (active) {
            !(mean > OFF_MEAN && std > OFF_STD)
        } else {
            mean < ON_MEAN || std < ON_STD
        }
        if (need != active && nowMs - lastToggleMs >= MIN_TOGGLE_MS) {
            active = need
            lastToggleMs = nowMs
        }
    }

    /**
     * Xử lý một khung (sửa trực tiếp mảng Y đã sao chép) và trả về độ sáng GỐC của khuôn mặt
     * (hoặc của cả khung nếu chưa biết vị trí mặt) để đánh giá chất lượng tín hiệu.
     */
    fun process(
        y: ByteArray,
        yLast: Int,
        rowStride: Int,
        pixelStride: Int,
        w: Int,
        h: Int,
        rotation: Int,
        box: FloatArray,
        boxFresh: Boolean,
        nowMs: Long,
        auto: Boolean
    ): Float {
        userOff = !auto
        val hasRoi = boxFresh && mapRoi(box, rotation, w, h)
        usingRoi = hasRoi

        // Vùng đọc phải nằm trọn trong bộ đệm của mặt phẳng Y.
        val lastNeeded = (h - 1) * rowStride + (w - 1) * pixelStride
        if (lastNeeded > yLast || w < MIN_REGION || h < MIN_REGION) {
            active = false
            denoising = false
            return faceMean
        }

        measure(y, rowStride, pixelStride, 0, 0, w, h, GLOBAL_STEP)
        globalMean = mMean
        val globalStd = mStd
        val globalBright = mBright

        if (hasRoi) {
            measure(y, rowStride, pixelStride, roi[0], roi[1], roi[2], roi[3], ROI_STEP)
            faceMean = mMean
            faceStd = mStd
            overexposed = mBright > 0.18f
        } else {
            faceMean = globalMean
            faceStd = globalStd
            overexposed = globalBright > 0.30f
        }
        backlit = hasRoi && globalMean - faceMean > 45f && faceMean < 95f
        val result = faceMean

        paused = nowMs < pausedUntilMs
        if (!auto || paused) {
            active = false
            denoising = false
            return result
        }

        decide(nowMs, faceMean, faceStd)
        denoising = active && faceMean < DENOISE_MEAN
        if (!active) return result

        val t0 = System.nanoTime()
        val rx0 = if (hasRoi) roi[0] else 0
        val ry0 = if (hasRoi) roi[1] else 0
        val rw = if (hasRoi) roi[2] - roi[0] else w
        val rh = if (hasRoi) roi[3] - roi[1] else h

        if (denoising) blur3(y, rowStride, pixelStride, rx0, ry0, rw, rh)
        val tiles = if (hasRoi) TILES_ROI else TILES_FULL
        clahe(y, rowStride, pixelStride, rx0, ry0, rw, rh, tiles, tiles, CLIP_LIMIT)

        val ms = (System.nanoTime() - t0) / 1_000_000f
        costMs = if (enhancedFrames == 0) ms else costMs * 0.9f + ms * 0.1f
        enhancedFrames++

        // Bảo vệ FPS (bỏ qua giai đoạn khởi động vì mã còn "nguội" nên đo chậm hơn thực tế).
        if (enhancedFrames > BUDGET_WARMUP_FRAMES && costMs > BUDGET_MS) {
            pausedUntilMs = nowMs + PAUSE_MS
            active = false
            denoising = false
            enhancedFrames = 0
            costMs = 0f
        }
        return result
    }

    /** Dựng bảng nội suy cho một trục: chỉ số hai ô lân cận, trọng số (thang 256) và ô chứa điểm. */
    private fun buildAxis(
        n: Int,
        tiles: Int,
        i0: IntArray,
        i1: IntArray,
        wt: IntArray,
        tileOf: IntArray
    ) {
        for (p in 0 until n) {
            val f = (p + 0.5f) * tiles / n - 0.5f
            if (f <= 0f) {
                i0[p] = 0
                i1[p] = 0
                wt[p] = 0
            } else if (f >= tiles - 1) {
                i0[p] = tiles - 1
                i1[p] = tiles - 1
                wt[p] = 0
            } else {
                val a = f.toInt()
                i0[p] = a
                i1[p] = a + 1
                wt[p] = ((f - a) * 256f).toInt().coerceIn(0, 256)
            }
            tileOf[p] = min(tiles - 1, p * tiles / n)
        }
    }

    /**
     * CLAHE trên một vùng chữ nhật của mặt phẳng Y (có xét rowStride và pixelStride).
     * Cùng thuật toán và số học nguyên đã được đối chiếu với OpenCV (sai khác tối đa 1 mức xám).
     */
    private fun clahe(
        y: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        rx0: Int,
        ry0: Int,
        rw: Int,
        rh: Int,
        tilesX: Int,
        tilesY: Int,
        clipLimit: Float
    ) {
        val nT = tilesX * tilesY
        if (hist.size < nT * 256) {
            hist = IntArray(nT * 256)
            lut = IntArray(nT * 256)
        }
        if (colI0.size < rw) {
            colI0 = IntArray(rw)
            colI1 = IntArray(rw)
            colW = IntArray(rw)
            colT = IntArray(rw)
        }
        if (rowI0.size < rh) {
            rowI0 = IntArray(rh)
            rowI1 = IntArray(rh)
            rowW = IntArray(rh)
            rowT = IntArray(rh)
        }
        java.util.Arrays.fill(hist, 0, nT * 256, 0)

        buildAxis(rw, tilesX, colI0, colI1, colW, colT)
        buildAxis(rh, tilesY, rowI0, rowI1, rowW, rowT)

        // 1) Histogram từng ô
        for (row in 0 until rh) {
            val tRow = rowT[row] * tilesX
            var idx = (ry0 + row) * rowStride + rx0 * pixelStride
            for (col in 0 until rw) {
                hist[(tRow + colT[col]) * 256 + (y[idx].toInt() and 0xFF)]++
                idx += pixelStride
            }
        }

        // 2) Cắt ngưỡng, phân phối lại phần dư (đều + rải theo bước như OpenCV), rồi tạo bảng tra từ CDF
        for (t in 0 until nT) {
            val off = t * 256
            var total = 0
            for (i in 0 until 256) total += hist[off + i]
            if (total == 0) {
                for (i in 0 until 256) lut[off + i] = i
                continue
            }

            val limit = max(1, (clipLimit * total / 256f).toInt())
            var excess = 0
            for (i in 0 until 256) {
                val c = hist[off + i]
                if (c > limit) {
                    excess += c - limit
                    hist[off + i] = limit
                }
            }
            val add = excess / 256
            var rem = excess - add * 256
            if (add > 0) {
                for (i in 0 until 256) hist[off + i] += add
            }
            if (rem > 0) {
                val step = max(256 / rem, 1)
                var i = 0
                while (i < 256 && rem > 0) {
                    hist[off + i]++
                    rem--
                    i += step
                }
            }

            val scale = 255f / total
            var cum = 0
            for (i in 0 until 256) {
                cum += hist[off + i]
                lut[off + i] = (cum * scale + 0.5f).toInt().coerceIn(0, 255)
            }
        }

        // 3) Nội suy song tuyến giữa bảng tra của 4 ô lân cận (số nguyên, thang 256 x 256)
        for (row in 0 until rh) {
            val r0 = rowI0[row] * tilesX
            val r1 = rowI1[row] * tilesX
            val wy = rowW[row]
            var idx = (ry0 + row) * rowStride + rx0 * pixelStride
            for (col in 0 until rw) {
                val v = y[idx].toInt() and 0xFF
                val wx = colW[col]
                val a = lut[(r0 + colI0[col]) * 256 + v]
                val b = lut[(r0 + colI1[col]) * 256 + v]
                val c = lut[(r1 + colI0[col]) * 256 + v]
                val d = lut[(r1 + colI1[col]) * 256 + v]
                val top = a * (256 - wx) + b * wx
                val bot = c * (256 - wx) + d * wx
                val out = (top * (256 - wy) + bot * wy + 32768) shr 16
                y[idx] = out.coerceIn(0, 255).toByte()
                idx += pixelStride
            }
        }
    }

    /** Lọc Gaussian 3x3 (nhân [1 2 1] tách trục, biên lặp lại) trên một vùng của mặt phẳng Y. */
    private fun blur3(
        y: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        rx0: Int,
        ry0: Int,
        rw: Int,
        rh: Int
    ) {
        if (tmp.size < rw * rh) tmp = IntArray(rw * rh)

        for (row in 0 until rh) {
            val base = (ry0 + row) * rowStride + rx0 * pixelStride
            for (col in 0 until rw) {
                val l = if (col > 0) col - 1 else 0
                val r = if (col < rw - 1) col + 1 else rw - 1
                val a = y[base + l * pixelStride].toInt() and 0xFF
                val b = y[base + col * pixelStride].toInt() and 0xFF
                val c = y[base + r * pixelStride].toInt() and 0xFF
                tmp[row * rw + col] = a + 2 * b + c
            }
        }

        for (row in 0 until rh) {
            val u = if (row > 0) row - 1 else 0
            val d = if (row < rh - 1) row + 1 else rh - 1
            val base = (ry0 + row) * rowStride + rx0 * pixelStride
            for (col in 0 until rw) {
                val s = tmp[u * rw + col] + 2 * tmp[row * rw + col] + tmp[d * rw + col]
                y[base + col * pixelStride] = ((s + 8) shr 4).toByte()
            }
        }
    }

    fun describe(): String {
        val state = when {
            userOff -> "tăng cường ảnh: tắt"
            paused -> "CLAHE tạm tắt để giữ FPS"
            active -> String.format(
                Locale.US,
                "CLAHE bật%s (%.1f ms)",
                if (denoising) " + lọc nhiễu" else "",
                costMs
            )
            else -> "ánh sáng đủ, không cần tăng cường"
        }
        return String.format(
            Locale.US,
            "sáng mặt %.0f (toàn khung %.0f) | tương phản %.0f | %s%s%s",
            faceMean,
            globalMean,
            faceStd,
            state,
            if (backlit) " | ngược sáng" else "",
            if (overexposed) " | mặt cháy sáng" else ""
        )
    }
}