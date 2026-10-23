package com.focuschange.ai

import kotlin.math.abs

/**
 * Cảnh báo "người đang ngồi không khớp hồ sơ đã chọn" mà KHÔNG nhận dạng khuôn mặt.
 *
 * Ứng dụng chỉ có `face_landmarker.task` (478 điểm hình học), không có model nhận dạng, nên nó
 * không thể trả lời "đây là ai". Nó trả lời được câu yếu hơn nhưng thật: "khuôn mặt đang ngồi
 * RỘNG hơn hay hẹp hơn khuôn mặt đã hiệu chuẩn bao nhiêu phần trăm".
 *
 * Đại lượng so sánh là **khoảng cách thô chưa nhân hệ số** (`rawUnscaled` của
 * `estimateDistanceCm`) lấy trung vị trong 30 khung, đối chiếu với giá trị đã lưu lúc hiệu chuẩn.
 * `rawUnscaled = W_gia_dinh * tieu_cua / be_rong_khoe_mat_px`, nên với cùng một máy quay thì số đó
 * chỉ còn phụ thuộc bề rộng mặt thật của người ngồi — lệch bao nhiêu phần trăm là mặt rộng/hẹp ra
 * bấy nhiêu. Dùng số này thay vì tự lưu bề rộng pixel vì nó đã được bù phối cảnh theo góc quay đầu
 * ở trong `estimateDistanceCm`; một em ngồi nghiêng đầu vĩnh viễn sẽ bị nghi "đổi người" nếu so
 * pixel thô.
 *
 * **Hai giới hạn phải nói thẳng, kể cả trước giám khảo:**
 * 1. Ước lượng khoảng cách và chữ ký này là CÙNG một nguồn thông tin, nên chốt chặn chỉ so sánh
 *    được khi người ngồi ở đúng vị trí tham chiếu. Cửa sổ bật khi khoảng cách ĐÃ quy đổi rơi vào
 *    [distanceLoCm, distanceHiCm]; ngoài băng đó coi như chưa đo. Hệ quả: một người có khuôn mặt
 *    rất khác nhưng ngồi xa/gần hơn 20-25 cm có thể lọt qua.
 * 2. Tiêu cự thuộc về chiếc điện thoại, không thuộc về hồ sơ. Đổi máy thì cả hệ số lẫn chữ ký đều
 *    phải hiệu chuẩn lại — giống hệt hành vi cũ của pref `distance_scale_v2`.
 *
 * Cờ này không phát ra cảnh báo âm và không đi vào chuỗi cảnh báo: chọn sai hồ sơ là lỗi vận hành,
 * đưa vào máy đếm cảnh báo sẽ làm bẩn số liệu nghiên cứu.
 */
internal class PersonGuard(
    private val tolerance: Float = WIDTH_TOLERANCE,
    private val holdMs: Long = HOLD_MS,
    private val distanceLoCm: Float = DIST_LO_CM,
    private val distanceHiCm: Float = DIST_HI_CM,
    private val minSamples: Int = MIN_SAMPLES,
    private val windowSize: Int = WINDOW_SIZE
) {

    companion object {
        /**
         * 12% chọn theo hai đầu: nhiễu điểm mốc của cặp khóe mắt ở 640x480/50 cm chỉ ~1,6% (con số
         * đó ghi trong comment của `estimateDistanceCm`), còn khoảng hai khóe mắt giữa hai người
         * trưởng thành chênh cỡ 5-8%. 12% vì vậy nằm trên nhiễu nhưng DƯỚI mức chênh của nhiều cặp
         * người thật — chốt chặn này bỏ sót người chỉ hơi khác khuôn mặt, nó không phải máy phân biệt
         * mọi người. Cả 1,6% lẫn 5-8% đều chưa được đo lại trên dữ liệu của chính dự án.
         */
        const val WIDTH_TOLERANCE = 0.12f
        const val HOLD_MS = 10_000L

        /**
         * Băng "đang ngồi đúng chỗ": 40-62 cm quanh khoảng cách tham chiếu 50 cm.
         * Rộng hơn ±10% cố ý, vì lệch vị trí và lệch bề rộng mặt cùng chiều đi vào một con số.
         */
        const val DIST_LO_CM = 40f
        const val DIST_HI_CM = 62f
        const val MIN_SAMPLES = 10
        const val WINDOW_SIZE = 30

        /**
         * Lệch hệ số hiệu chuẩn trên 20% thì gần như chắc chắn là người khác: hiệu chuẩn diễn ra ở
         * khoảng cách tham chiếu ĐÃ BIẾT, nên `scale = REF / trung vị khoảng cách thô` chỉ còn một
         * biến số là bề rộng mặt thật của người ngồi. Đây là trigger đáng tin hơn phép thử liên tục.
         */
        const val CALIBRATION_REJECT_RATIO = 0.20f

        /** Tỷ lệ lệch của hệ số mới so với hệ số đã lưu, NaN nếu một trong hai vô hiệu. */
        fun calibrationDeviation(newScale: Float, storedScale: Float): Float {
            if (!newScale.isFinite() || !storedScale.isFinite() || storedScale <= 0f) return Float.NaN
            return (newScale - storedScale) / storedScale
        }
    }

    private val raws = ArrayDeque<Float>()
    private var suspectSinceMs = 0L

    /** Độ lệch tương đối của lần đánh giá gần nhất, NaN khi khung này không đủ điều kiện so. */
    var deviation: Float = Float.NaN
        private set

    /** Số mẫu đang giữ trong cửa sổ — HUD in ra để người vận hành biết chưa đủ dữ liệu. */
    fun sampleCount(): Int = raws.size

    fun reset() {
        raws.clear()
        suspectSinceMs = 0L
        deviation = Float.NaN
    }

    /**
     * Trả về true nếu nghi đã đổi người và nghi ngờ đó kéo đủ [holdMs].
     *
     * `referenceRawCm` lấy từ hồ sơ đang chọn; null nghĩa là hồ sơ chưa hiệu chuẩn thì chưa có gì
     * để so, không được phép suy ra "đổi người". Mỗi khung ngoài băng khoảng cách xóa đồng hồ giữ
     * cờ: một em đứng dậy rồi ngồi lại không phải bằng chứng mới.
     */
    fun observe(
        nowMs: Long,
        rawDistanceCm: Float?,
        scaledDistanceCm: Float?,
        referenceRawCm: Float?
    ): Boolean {
        val raw = rawDistanceCm?.takeIf { it.isFinite() && it > 0f }
        val scaled = scaledDistanceCm?.takeIf { it.isFinite() }
        val ref = referenceRawCm?.takeIf { it.isFinite() && it > 0f }

        if (raw == null || scaled == null || ref == null ||
            scaled < distanceLoCm || scaled > distanceHiCm
        ) {
            suspectSinceMs = 0L
            deviation = Float.NaN
            return false
        }

        raws.addLast(raw)
        while (raws.size > windowSize) raws.removeFirst()
        if (raws.size < minSamples) return false

        val dev = (medianOf(raws) - ref) / ref
        deviation = dev

        if (abs(dev) <= tolerance) {
            suspectSinceMs = 0L
            return false
        }
        if (suspectSinceMs == 0L) suspectSinceMs = nowMs
        return nowMs - suspectSinceMs >= holdMs
    }

    /** Trung vị cửa sổ hiện tại, null nếu chưa đủ [minSamples] khung. */
    fun medianRaw(): Float? = if (raws.size < minSamples) null else medianOf(raws)

    /** Số mili-giây nghi ngờ đã kéo dài, 0 khi sạch. */
    fun suspectMs(nowMs: Long): Long =
        if (suspectSinceMs == 0L) 0L else nowMs - suspectSinceMs

    private fun medianOf(window: ArrayDeque<Float>): Float {
        val sorted = window.sorted()
        return sorted[sorted.size / 2]
    }
}
