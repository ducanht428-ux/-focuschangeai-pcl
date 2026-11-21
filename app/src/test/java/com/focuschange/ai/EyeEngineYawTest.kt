package com.focuschange.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A head turn foreshortens the two eyes by different amounts, while the cos(yaw) compensation inside
 * EyeEngine is one factor applied to both. So on a wide turn the far eye's landmark aperture can
 * collapse to almost nothing while the student's eyes are wide open, and averaging the two eyes used
 * to drag the whole face under the close line: the HUD said "mắt đang nhắm" to someone not blinking.
 *
 * `poseOk` is passed in as true for every frame here, so these tests exercise the fusion rule alone
 * and not the head-angle gate MainActivity puts in front of it.
 */
class EyeEngineYawTest {

    private companion object {
        const val W = 480
        const val H = 480

        // The corners of each eye are 0.15*W apart, so an aperture of 0.0375*H gives
        // EAR = 18/72 = 0.25: a normally open eye.
        const val OPEN_AP = 0.0375f

        // An aperture of 0.003*H gives EAR = 0.02: lids almost touching, whether that is a real
        // blink or the far eye seen edge-on. The engine has to tell those two apart by the OTHER eye.
        const val SHUT_AP = 0.003f
    }

    private val rightUp = intArrayOf(161, 160, 159, 158, 157)
    private val rightLow = intArrayOf(163, 144, 145, 153, 154)
    private val leftUp = intArrayOf(388, 387, 386, 385, 384)
    private val leftLow = intArrayOf(390, 373, 374, 380, 381)

    private fun set(data: FloatArray, index: Int, x: Float, y: Float) {
        data[index * 3] = x
        data[index * 3 + 1] = y
    }

    /** A flat face whose two eyelid openings are exactly the apertures given, in normalized units. */
    private fun face(rightAperture: Float, leftAperture: Float): Landmarks {
        val data = FloatArray(478 * 3)
        set(data, 33, 0.15f, 0.5f)
        set(data, 133, 0.30f, 0.5f)
        set(data, 362, 0.70f, 0.5f)
        set(data, 263, 0.85f, 0.5f)
        for (i in 0 until 5) {
            val rx = 0.18f + i * 0.02f
            val lx = 0.73f + i * 0.02f
            set(data, rightUp[i], rx, 0.5f - rightAperture / 2f)
            set(data, rightLow[i], rx, 0.5f + rightAperture / 2f)
            set(data, leftUp[i], lx, 0.5f - leftAperture / 2f)
            set(data, leftLow[i], lx, 0.5f + leftAperture / 2f)
        }
        return Landmarks(data, 478)
    }

    private fun feed(e: EyeEngine, lm: Landmarks, yawDeg: Float, startMs: Long, frames: Int) {
        for (i in 0 until frames) {
            e.update(lm, W, H, startMs + i * 33L, yawDeg, 0f, 0f, null, true)
        }
    }

    /** Calibrated frontally with both eyes open, the same way the "Đang hiệu chuẩn" phase does. */
    private fun calibrated(): EyeEngine {
        val e = EyeEngine()
        val open = face(OPEN_AP, OPEN_AP)
        repeat(10) { e.addCalibrationSample(open, W, H, null) }
        e.finishCalibration(1f)
        return e
    }

    @Test
    fun aTurnedFarEyeCollapseIsNotReadAsAClosedEye() {
        val e = calibrated()
        feed(e, turnedFace(), 40f, 1_000L, 12)
        val frame = e.out
        assertTrue("the frame must still be measurable", frame.valid)
        assertFalse("a far eye collapsed by yaw must not put the whole face into the closed state", frame.closed)
        assertTrue(
            "openness dropped to ${frame.openness}, below the close line the averaging rule used to cross",
            frame.openness > 0.70f
        )
    }

    @Test
    fun oneEyeShutWhileFrontalIsNotCountedAsAClosure() {
        val e = calibrated()
        feed(e, face(OPEN_AP, OPEN_AP), 0f, 1_000L, 5)
        feed(e, turnedFace(), 0f, 1_200L, 12)
        // Deliberate: a wink is not drowsiness. The uneven-two-eyes indicator is the channel that
        // reports a one-sided lid, and it compares the two eyes directly instead of their mean.
        assertFalse(e.out.closed)
    }

    @Test
    fun bothLidsFallingTogetherIsStillSeenAsClosed() {
        val e = calibrated()
        feed(e, face(OPEN_AP, OPEN_AP), 0f, 1_000L, 5)
        feed(e, face(SHUT_AP, SHUT_AP), 0f, 1_200L, 3)
        assertTrue("taking the better-opened eye must not blind the engine to a real blink", e.out.closed)
    }

    private fun turnedFace(): Landmarks = face(OPEN_AP, SHUT_AP)
}
