package com.focuschange.ai

import com.focuschange.ai.domain.engine.IndependentFeatureEngine
import com.focuschange.ai.domain.engine.RobustStats
import com.focuschange.ai.domain.model.Feature
import com.focuschange.ai.domain.model.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test lớp Tier 1 (dải dung sai từng đặc trưng). Lớp này CHƯA nối vào pipeline đo của app,
 * nhưng nó đã nằm trong repo và đã push, nên mọi hành vi của nó đều có thể được gọi bất cứ
 * lúc nào. Ba test dưới đây chốt lại ba chỗ mà một sửa đổi vô tình sẽ làm sai số mà không ai
 * nhìn thấy bằng mắt: cổng khoảng vật lý lúc đo, từ chối xuất dải khi dữ liệu toàn NaN, và
 * dải không bị co về 0 khi học sinh ngồi quá yên.
 */
class DomainToleranceTest {

    private fun spread(center: Double, n: Int) = DoubleArray(n) { center + (it % 5) * 0.1 }

    /**
     * Lúc học, mẫu ngoài khoảng vật lý bị loại. Lúc đo cũng phải vậy: một khung khoảng cách rác
     * (3 cm hoặc 250 cm do landmark nhảy) là LỖI ĐO, không phải "ngồi quá gần/quá xa". Trước khi
     * sửa, evaluate chỉ kiểm tra isFinite nên nó trả BELOW/ABOVE_BOUND và biến lỗi đo thành cảnh
     * báo giả - đúng loại sai số mà falseAlertCount đang đếm.
     */
    @Test
    fun implausibleLiveReadingIsNotReportedAsAnAlert() {
        val engine = IndependentFeatureEngine()
        val bounds = engine.calibrate(mapOf(Feature.DISTANCE to spread(50.0, 60)))
        assertTrue("cần dải cho DISTANCE để test có ý nghĩa", bounds.containsKey(Feature.DISTANCE))

        assertEquals(FeatureStatus.INSUFFICIENT_DATA, engine.evaluate(Feature.DISTANCE, 250.0).status)
        assertEquals(FeatureStatus.INSUFFICIENT_DATA, engine.evaluate(Feature.DISTANCE, 3.0).status)
        assertEquals(FeatureStatus.INSUFFICIENT_DATA, engine.evaluate(Feature.DISTANCE, Double.NaN).status)
        assertEquals(FeatureStatus.STABLE, engine.evaluate(Feature.DISTANCE, 50.2).status)

        // Giá trị bị từ chối vẫn phải được trả về: người đọc bảng cần thấy cảm biến đã nói gì.
        assertEquals(250.0, engine.evaluate(Feature.DISTANCE, 250.0).value!!, 0.001)
    }

    /** Toàn NaN thì không có dải, và KHÔNG có dải thì không được phép kết luận STABLE hay ABOVE. */
    @Test
    fun allNanCalibrationYieldsNoBandAndNoVerdict() {
        val engine = IndependentFeatureEngine()
        assertTrue(engine.calibrate(mapOf(Feature.BLINK_RATE to DoubleArray(60) { Double.NaN })).isEmpty())
        assertEquals(FeatureStatus.INSUFFICIENT_DATA, engine.evaluate(Feature.BLINK_RATE, 14.0).status)
        assertNull(RobustStats.personalBound(DoubleArray(60) { Double.NaN }))
    }

    /**
     * Một học sinh ngồi gần như bất động có sigma ~ 0. Nếu không có sàn minHalfWidth thì dải
     * co về [mu, mu] và mọi khung sau đó đều bị báo lệch. Đây chính là bẫy khiến học sinh
     * ngồi ngoan nhất bị gọi tên nhiều nhất.
     */
    @Test
    fun constantSeriesStillGetsANonDegenerateBand() {
        val engine = IndependentFeatureEngine()
        val bound = engine.calibrate(mapOf(Feature.DISTANCE to DoubleArray(60) { 50.0 }))
            .getValue(Feature.DISTANCE)
        assertEquals(0.0, bound.std, 0.001)
        assertEquals(48.0, bound.min, 0.001)
        assertEquals(52.0, bound.max, 0.001)
        assertEquals(FeatureStatus.STABLE, engine.evaluate(Feature.DISTANCE, 51.9).status)
        assertEquals(FeatureStatus.ABOVE_BOUND, engine.evaluate(Feature.DISTANCE, 53.0).status)
    }
}
