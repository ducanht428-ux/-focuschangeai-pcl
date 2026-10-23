package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dải cho phép HAI PHÍA học từ thói quen của từng em.
 *
 * Lý do phải có test riêng: dải này được in ra HUD, ghi vào CSV và gửi lên Firebase. Nếu nó
 * báo sai theo một chiều nào đó thì hậu quả không phải một con số lệch mà là một em bị gọi là
 * "bất thường" chỉ vì hồ sơ chưa đủ phiên, hoặc ngược lại - "0% lọt dải" trên đúng những phiên
 * chưa đo được gì.
 */
class PersonalBandTest {

    /** Bas giả tạo: mọi đặc trưng đều "đã học", mean/spread đặt tay đôi với từng chỉ mục. */
    private fun fakeBaseline(means: FloatArray, spreads: FloatArray, ok: BooleanArray) =
        HabitBaseline(means, spreads, ok)

    private fun allOk() = BooleanArray(HabitFeature.COUNT) { true }
    private fun zeros() = FloatArray(HabitFeature.COUNT)

    @Test
    fun bandIsTwoSidedAroundThePersonalMean() {
        val means = FloatArray(HabitFeature.COUNT)
        val spreads = FloatArray(HabitFeature.COUNT)
        means[HabitFeature.YAW] = 0f
        spreads[HabitFeature.YAW] = 2f
        val band = fakeBaseline(means, spreads, allOk()).band(HabitFeature.YAW)!!

        // BAND_K = 2.5, nên dải của yaw = 0 ± 5 độ, kẹp trong [-60, 60].
        assertEquals(-5f, band.min, 1e-4f)
        assertEquals(5f, band.max, 1e-4f)
        assertEquals(BandPosition.BELOW, band.position(-7f))
        assertEquals(BandPosition.IN, band.position(3f))
        assertEquals(BandPosition.ABOVE, band.position(7f))
    }

    @Test
    fun bandClampsToThePhysicalRangeAndNeverCrosses() {
        val means = FloatArray(HabitFeature.COUNT)
        val spreads = FloatArray(HabitFeature.COUNT)
        means[HabitFeature.SEI] = 98f
        spreads[HabitFeature.SEI] = 40f
        val band = fakeBaseline(means, spreads, allOk()).band(HabitFeature.SEI)!!

        // ±100 quanh 98 sẽ đòi dải tới 198 - SEI không có đơn vị đó. Mép trên phải bị kẹp.
        assertEquals(100f, band.max, 1e-4f)
        assertEquals(0f, band.min, 1e-4f)
        assertTrue(band.min <= band.mean && band.mean <= band.max)
    }

    @Test
    fun featureWithoutDataHasNoBandInsteadOfAZeroWidthBand() {
        val ok = BooleanArray(HabitFeature.COUNT) // tất cả false
        val base = fakeBaseline(zeros(), zeros(), ok)

        assertNull(base.band(HabitFeature.YAW))

        // "Chưa biết" mà báo thành dải [0,0] thì mọi giá trị dương đều thành "trên dải":
        // có tới hai lỗi cùng lúc - báo sai cho hôm nay và làm sạch bằng chứng của ngày mai.
        val session = HabitSession(1L)
        session.setBands(base, HabitFeature.BAND_FEATURES)
        assertEquals(BandPosition.UNKNOWN, session.observeBand(HabitFeature.YAW, 12f))
        assertEquals(0, session.bandCoverage(HabitFeature.BAND_FEATURES))
        assertTrue(session.bandOutcomes().isEmpty())
    }

    @Test
    fun tallyKeepsBelowAndAboveApartAndNeverCountsUnknown() {
        val means = FloatArray(HabitFeature.COUNT)
        val spreads = FloatArray(HabitFeature.COUNT)
        means[HabitFeature.YAW] = 0f
        spreads[HabitFeature.YAW] = 2f
        val session = HabitSession(2L)
        session.setBands(fakeBaseline(means, spreads, allOk()), HabitFeature.BAND_FEATURES)

        repeat(6) { session.observeBand(HabitFeature.YAW, 0f) }
        repeat(3) { session.observeBand(HabitFeature.YAW, -30f) }
        repeat(1) { session.observeBand(HabitFeature.YAW, 30f) }
        // Khung NaN và khung của đặc trưng nằm ngoài BAND_FEATURES không được rơi vào "trong dải".
        session.observeBand(HabitFeature.YAW, Float.NaN)
        session.observeBand(HabitFeature.DIST, 999f)

        val outcomes = session.bandOutcomes()
        assertEquals(1, outcomes.size)
        val yaw = outcomes[0]
        assertEquals(HabitFeature.YAW, yaw.feature)
        assertEquals(10, yaw.samples)
        assertEquals(3, yaw.below)
        assertEquals(1, yaw.above)
        assertEquals(6, yaw.inSamples)
        assertEquals(30f, yaw.belowPct, 1e-4f)
        assertEquals(10f, yaw.abovePct, 1e-4f)
        assertEquals(40f, yaw.outsidePct, 1e-4f)
    }

    @Test
    fun outOfBandValueIsJudgedBeforeClipping() {
        val means = FloatArray(HabitFeature.COUNT)
        val spreads = FloatArray(HabitFeature.COUNT)
        means[HabitFeature.YAW] = 0f
        spreads[HabitFeature.YAW] = 2f
        val session = HabitSession(3L)
        session.setBands(fakeBaseline(means, spreads, allOk()), HabitFeature.BAND_FEATURES)

        // -70 độ vượt ngoài khoảng vật lý [-60, 60]. observe() kẹp nó về -60 (đúng, vì kẹp là
        // việc của trung bình), nhưng chấm dải thì phải thấy BELOW - kẹp trước khi chấm thì
        // một cái quay đầu quá tay sẽ bị ghi thành "vừa đúng mép trong dải".
        session.observe(HabitFeature.YAW, -70f)
        assertEquals(BandPosition.BELOW, session.observeBand(HabitFeature.YAW, -70f))
        assertEquals(-60f, session.toSummary().mean[HabitFeature.YAW], 0.001f)
    }

    @Test
    fun csvCodesSeparateNotScoredFromNoBand() {
        // -1 (chưa có dải) do toCsvCode(); -2 (khung này không chấm) là hằng ở MainActivity.
        // Hai mã phải khác nhau, nếu gộp thì CSV không nói được "máy chưa học" hay "em đó vắng".
        assertEquals(0, BandPosition.BELOW.toCsvCode())
        assertEquals(1, BandPosition.IN.toCsvCode())
        assertEquals(2, BandPosition.ABOVE.toCsvCode())
        assertEquals(-1, BandPosition.UNKNOWN.toCsvCode())
    }

    @Test
    fun bandColumnsAreAppendedAtTheEndAndNamedAfterTheKeys() {
        val cols = DataLogger().columnNames
        val expected = HabitFeature.BAND_FEATURES.map { "band_" + HabitFeature.KEYS[it] }

        // Nối cuối chứ không chèn giữa: 37 cột cũ phải giữ nguyên thứ tự để file của các phiên
        // trước vẫn mở được bằng cùng một script phân tích.
        assertEquals(expected, cols.takeLast(expected.size))
        assertEquals(cols.size, cols.toSet().size)
        assertFalse(cols.contains("band_distance_cm"))
        assertFalse(cols.contains("band_ear"))
    }

    /**
     * Dải phải dựng được từ hồ sơ thật, không chỉ từ một baseline bịa ra: đường đi qua
     * commit() -> baseline() -> band() là nơi các hằng số (MIN_SESSIONS, MIN_TOTAL_MS,
     * MIN_FRAMES_PER_FEATURE) gặp nhau, và chỉ một trong bốn cái bị đổi là dải biến mất.
     */
    @Test
    fun realProfileProducesAUsableBandAfterFourSessions() {
        val prof = HabitProfile("test_band", "Em Test")
        repeat(4) { i ->
            val means = FloatArray(HabitFeature.COUNT) { 50f }
            val stds = FloatArray(HabitFeature.COUNT)
            val counts = IntArray(HabitFeature.COUNT) { 100 }
            means[HabitFeature.YAW] = 4f
            means[HabitFeature.SEI] = 70f
            stds[HabitFeature.YAW] = 2f
            stds[HabitFeature.SEI] = 3f
            prof.commit(
                SessionSummary(
                    id = 1000L + i,
                    validMs = 160_000L,
                    faceRatio = 0.95f,
                    validRatio = 0.95f,
                    quality = 85f,
                    mean = means,
                    std = stds,
                    count = counts
                )
            )
        }
        assertTrue(prof.isReady())

        val base = prof.baseline()
        val sei = base.band(HabitFeature.SEI)!!
        assertEquals(70f, sei.mean, 0.01f)
        assertTrue(sei.min < 70f && sei.max > 70f)
        assertEquals(BandPosition.BELOW, sei.position(55f))
        assertEquals(BandPosition.IN, sei.position(69f))

        val yaw = base.band(HabitFeature.YAW)!!
        assertEquals(BandPosition.ABOVE, yaw.position(12f))
    }
}
