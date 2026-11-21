package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the guard for "the person sitting here seems to have changed".
 *
 * Why this exists: the app does NOT recognise faces (only face_landmarker.task, no recognition model), so
 * the claim that is easiest to overstate is calling the face-width comparison "face ID". These tests keep
 * it as what it really is: a hint for the operator, with its blind spots written out.
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

    /** Same person, right seat: landmark jitter of ~1.6% must come out completely clean. */
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
     * A face 25% narrower (the raw estimate lengthens) must be suspected, but ONLY once there are enough
     * samples and the flag has already held for HOLD_MS. Raising it on the first frame is wrong: one frame
     * with collapsed landmarks is not evidence that the person changed.
     */
    @Test
    fun differentFaceWidthNeedsBothEnoughSamplesAndTheHoldWindow() {
        val g = PersonGuard()
        assertFalse(g.observe(0L, 75f, 50f, 60f))
        assertFalse(g.observe(1_000L, 75f, 50f, 60f))
        // The median only starts at sample 10; the suspicion clock begins counting right there.
        var t = 2_000L
        for (i in 2 until PersonGuard.MIN_SAMPLES) {
            assertFalse("bật cờ trước khi đủ ${PersonGuard.MIN_SAMPLES} mẫu", g.observe(t, 75f, 50f, 60f))
            t += 1_000L
        }
        // t = 10_000: suspectSince = 9_000, only 1 of the 10 HOLD_MS seconds has elapsed.
        assertFalse(g.observe(t, 75f, 50f, 60f))
        t += 9_000L
        assertEquals(19_000L, t)
        assertTrue("kéo dài 10 giây mà vẫn không báo thì chốt chặn vô dụng", g.observe(t, 75f, 50f, 60f))
    }

    /** Outside the reference band it must return NaN: no conclusion is allowed from a frame off the seat. */
    @Test
    fun framesOutsideTheReferenceBandMakeNoClaim() {
        val g = PersonGuard()
        assertFalse(feed(g, 0L, 30, 75f, 20f, 60f))
        assertTrue(g.deviation.isNaN())
        assertNull("chưa đủ mẫu trong băng thì chưa được có trung vị", g.medianRaw())
        assertEquals(0, g.sampleCount())
    }

    /** A profile that was never calibrated (NaN/null signature) must never be blamed on a person change. */
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
     * That student stands up and sits back down: the suspicion clock must go to 0, otherwise one 11 second
     * slip added to the earlier one becomes "person changed" without any new evidence at all.
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

        // Leaving the 40-62 cm band: signal lost, not evidence of anything.
        assertFalse(g.observe(t + 1_000L, 75f, 15f, 60f))
        assertEquals(0L, g.suspectMs(t + 1_000L))

        // Sitting back in place: the median window still holds old samples but must hold long enough again.
        assertFalse("quay lại là phải báo ngay -> cờ học từ lần đứng dậy trước", g.observe(t + 2_000L, 75f, 50f, 60f))
    }

    /** A calibration whose factor deviates 20% is the most trustworthy trigger: the distance was known. */
    @Test
    fun calibrationDeviationIsTheTrustworthyTrigger() {
        assertEquals(0f, PersonGuard.calibrationDeviation(60f, 60f), 1e-6f)
        assertEquals(0.25f, PersonGuard.calibrationDeviation(75f, 60f), 1e-6f)
        assertEquals(-0.2f, PersonGuard.calibrationDeviation(48f, 60f), 1e-6f)
        // An uncalibrated profile must not be compared, and NaN has to surface instead of 0 (0 means "match").
        assertTrue(PersonGuard.calibrationDeviation(60f, Float.NaN).isNaN())
        assertTrue(PersonGuard.calibrationDeviation(60f, 0f).isNaN())
        assertTrue(PersonGuard.calibrationDeviation(Float.NaN, 60f).isNaN())
    }
}
