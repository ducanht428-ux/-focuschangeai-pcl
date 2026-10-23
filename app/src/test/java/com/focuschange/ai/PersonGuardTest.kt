package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test chốt chặn "có vẻ đã đổi người".
 *
 * Lý do tồn tại: ứng dụng KHÔNG nhận dạng khuôn mặt (chỉ có face_landmarker.task, không có model
 * nhận dạng), nên con đường dễ nói quá nhất là gọi phép so sánh bề rộng mặt bằng "ID khuôn mặt".
 * Các test này giữ nó đúng là cái nó thật: một gợi ý cho người vận hành, có vùng mù được ghi rõ.
 */
class PersonGuardTest {

    private fun feed(
        g: PersonGuard,
        startMs: Long,
        frames: Int,
        raw: Float,
        scaled: Float,
        ref: Float,
        stepMs: Long = 1_000L
    ): Boolean {
        var last = false
        for (i in 0 until frames) {
            last = g.observe(startMs + i * stepMs, raw, scaled, ref)
        }
        return last
    }

    /** Cùng người, đúng ghế: dao động điểm mốc ~1,6% phải sạch hoàn toàn. */
    @Test
    fun samePersonAtTheReferenceSeatIsNotFlagged() {
        val g = PersonGuard()
        var t = 0L
        var flagged = false
        repeat(40) {
            val raw = if (it % 2 == 0) 60.5f else 59.5f
            flagged = flagged || g.observe(t, raw, 50f, 60f)
            t += 1_000L
        }
        assertFalse("ngẫu nhiên +-1% mà báo đổi người thì HUD sẽ đỏ liên tục", flagged)
    }

    /**
     * Mặt hẹp hơn 25% (ước lượng thô dài ra) thì phải bị nghi, nhưng CHỈ sau khi đủ mẫu và
     * cờ đã kéo đủ HOLD_MS. Cờ bật ngay khung đầu là sai: một khung landmark sập không phải
     * bằng chứng đổi người.
     */
    @Test
    fun differentFaceWidthNeedsBothEnoughSamplesAndTheHoldWindow() {
        val g = PersonGuard()
        assertFalse(g.observe(0L, 75f, 50f, 60f))
        assertFalse(g.observe(1_000L, 75f, 50f, 60f))
        // Mẫu thứ 10 mới bắt đầu tính trung vị; đồng hồ nghi ngờ khởi động tại đó.
        var t = 2_000L
        for (i in 2 until PersonGuard.MIN_SAMPLES) {
            assertFalse("bật cờ trước khi đủ ${PersonGuard.MIN_SAMPLES} mẫu", g.observe(t, 75f, 50f, 60f))
            t += 1_000L
        }
        // t = 10_000: suspectSince = 9_000, mới trôi 1 giây trong số 10 giây HOLD_MS.
        assertFalse(g.observe(t, 75f, 50f, 60f))
        t += 9_000L
        assertEquals(19_000L, t)
        assertTrue("kéo dài 10 giây mà vẫn không báo thì chốt chặn vô dụng", g.observe(t, 75f, 50f, 60f))
    }

    /** Ngoài băng tham chiếu thì phải trả NaN: không có quyền suy ra gì từ một khung ngồi lệch. */
    @Test
    fun framesOutsideTheReferenceBandMakeNoClaim() {
        val g = PersonGuard()
        assertFalse(feed(g, 0L, 30, 75f, 20f, 60f))
        assertTrue(g.deviation.isNaN())
        assertNull("chưa đủ mẫu trong băng thì chưa được có trung vị", g.medianRaw())
        assertEquals(0, g.sampleCount())
    }

    /** Hồ sơ chưa hiệu chuẩn (chữ ký NaN/null) thì không bao giờ được đổ tội đổi người. */
    @Test
    fun uncalibratedProfileNeverAccusesAnyone() {
        val g = PersonGuard()
        assertFalse(g.observe(0L, 75f, 50f, null))
        assertFalse(g.observe(1_000L, 75f, 50f, Float.NaN))
        assertFalse(g.observe(2_000L, 75f, 50f, 0f))
        assertFalse(g.observe(3_000L, null, 50f, 60f))
        assertFalse(g.observe(4_000L, Float.NaN, 50f, 60f))
    }

    /**
     * Em đó đứng dậy rồi ngồi lại: đồng hồ nghi ngờ phải về 0, nếu không một cú lỡ 11 giây cộng
     * với lần trước sẽ thành "đổi người" mà không cần bằng chứng mới.
     */
    @Test
    fun standingUpResetsTheHoldTimer() {
        val g = PersonGuard()
        var t = 0L
        while (t < 20_000L) {
            g.observe(t, 75f, 50f, 60f)
            t += 1_000L
        }
        assertTrue(g.observe(t, 75f, 50f, 60f))

        // Ra khỏi băng 40-62 cm: mất tín hiệu, không phải bằng chứng.
        assertFalse(g.observe(t + 1_000L, 75f, 15f, 60f))
        assertEquals(0L, g.suspectMs(t + 1_000L))

        // Ngồi lại đúng chỗ: cửa sổ trung vị còn mẫu cũ, nhưng phải giữ đủ lâu lần nữa mới báo.
        assertFalse("quay lại là phải báo ngay -> cờ học từ lần đứng dậy trước", g.observe(t + 2_000L, 75f, 50f, 60f))
    }

    /** Hiệu chuẩn ra hệ số lệch 20% là trigger đáng tin nhất: khoảng cách lúc đó đã biết trước. */
    @Test
    fun calibrationDeviationIsTheTrustworthyTrigger() {
        assertEquals(0f, PersonGuard.calibrationDeviation(60f, 60f), 1e-6f)
        assertEquals(0.25f, PersonGuard.calibrationDeviation(75f, 60f), 1e-6f)
        assertEquals(-0.2f, PersonGuard.calibrationDeviation(48f, 60f), 1e-6f)
        // Hồ sơ chưa hiệu chuẩn thì không được so, và NaN phải nổi lên thay vì 0 (0 nghĩa là "khớp").
        assertTrue(PersonGuard.calibrationDeviation(60f, Float.NaN).isNaN())
        assertTrue(PersonGuard.calibrationDeviation(60f, 0f).isNaN())
        assertTrue(PersonGuard.calibrationDeviation(Float.NaN, 60f).isNaN())
    }
}
