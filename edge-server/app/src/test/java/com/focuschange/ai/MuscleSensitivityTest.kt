package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests what the on-screen sensitivity control does to the mouth, brow and blink comparisons.
 *
 * Why this exists: the slider used to scale only confirmation times and head angles, while the yawn
 * threshold (MAR, jawOpen), the brow-furrow threshold and the blink-duration charge stayed
 * hard-coded. A judge asking "what changes when you move that slider" would have gotten an answer
 * that did not include the facial numbers on the screen. Level 1 must be a no-op so the documented
 * defaults in the threshold table stay the numbers actually used.
 */
class MuscleSensitivityTest {

    private fun assertClose(expected: Float, actual: Float) {
        assertEquals(expected, actual, 1e-5f)
    }

    @Test
    fun levelOneIsExactlyTheDocumentedDefault() {
        assertClose(1f, MuscleSensitivity.factor(1))
        assertClose(BASE_MAR, MuscleSensitivity.mouthThreshold(1, BASE_MAR))
        assertClose(BASE_FURROW, MuscleSensitivity.furrowRatio(1, BASE_FURROW))
        assertClose(BASE_YAW, MuscleSensitivity.furrowYawWindow(1, BASE_YAW))
    }

    @Test
    fun higherLevelTriggersOnSmallerMovement() {
        assertTrue(MuscleSensitivity.mouthThreshold(2, BASE_MAR) < BASE_MAR)
        assertTrue(MuscleSensitivity.mouthThreshold(0, BASE_MAR) > BASE_MAR)

        // A furrow fires when the brow ratio drops BELOW the trigger, so "more sensitive" means the
        // trigger sits higher: a shallower furrow already counts.
        assertTrue(MuscleSensitivity.furrowRatio(2, BASE_FURROW) > BASE_FURROW)
        assertTrue(MuscleSensitivity.furrowRatio(0, BASE_FURROW) < BASE_FURROW)
    }

    @Test
    fun orderingIsMonotonicAcrossTheFourLevels() {
        val mar = intArrayOf(0, 1, 2, 3).map { MuscleSensitivity.mouthThreshold(it, BASE_MAR) }
        val furrow = intArrayOf(0, 1, 2, 3).map { MuscleSensitivity.furrowRatio(it, BASE_FURROW) }
        val window = intArrayOf(0, 1, 2, 3).map { MuscleSensitivity.furrowYawWindow(it, BASE_YAW) }

        for (i in 1 until mar.size) {
            assertTrue(mar[i] < mar[i - 1])
            assertTrue(furrow[i] > furrow[i - 1])
            assertTrue(window[i] > window[i - 1])
        }
    }

    @Test
    fun theFourthLevelIsARealStepAndNotTheDefault() {
        // Level 3 exists because level 2 still felt slow on the phone, so it has to move the numbers
        // rather than only change the button label.
        assertTrue(MuscleSensitivity.factor(3) < MuscleSensitivity.factor(2))
        assertTrue(
            MuscleSensitivity.mouthThreshold(3, BASE_MAR) <
                MuscleSensitivity.mouthThreshold(2, BASE_MAR)
        )
        val trigger = MuscleSensitivity.furrowRatio(3, BASE_FURROW)
        assertTrue(trigger < 1f)
        assertTrue(1f - trigger > 0.05f)
    }

    @Test
    fun levelOneKeepsTheDocumentedBlinkCharge() {
        // These are the numbers printed in the threshold table, so the default level must reproduce
        // the old hard-coded formula exactly: nothing below 150 ms, half at 275 ms, full at 400 ms.
        assertClose(0f, MuscleSensitivity.blinkLoad(1, 149f))
        assertClose(0f, MuscleSensitivity.blinkLoad(1, 150f))
        assertClose(0.5f, MuscleSensitivity.blinkLoad(1, 275f))
        assertClose(1f, MuscleSensitivity.blinkLoad(1, 400f))
    }

    @Test
    fun theSameBlinkChargesMoreTheHigherTheLevel() {
        val avg = 300f
        val charges = intArrayOf(0, 1, 2, 3).map { MuscleSensitivity.blinkLoad(it, avg) }
        for (i in 1 until charges.size) {
            assertTrue(charges[i] > charges[i - 1])
        }
        // A blink nobody would call long must stay free at every level, otherwise the most sensitive
        // setting would hold the fatigue index down on ordinary blinking.
        for (level in 0..3) {
            assertClose(0f, MuscleSensitivity.blinkLoad(level, 100f))
        }
    }

    @Test
    fun everyStorableLevelHasItsOwnNameAndOthersSayUnknown() {
        // The stored level is printed back to a teacher next to the numbers it produced, so four
        // stored values must not collapse into three words, and a value the app never writes
        // (a hand-edited file, a future fifth level) must not read as "vừa".
        val names = (0..3).map { sensitivityName(it) }
        assertEquals(4, names.toSet().size)
        assertEquals("không rõ", sensitivityName(-1))
        assertEquals("không rõ", sensitivityName(9))
    }

    @Test
    fun anUnknownLevelFallsBackToTheDefault() {
        // The value comes from SharedPreferences, so a hand-edited or future level must not silently
        // become "very sensitive" - it has to be the behaviour the slides describe.
        assertClose(1f, MuscleSensitivity.factor(7))
        assertClose(BASE_MAR, MuscleSensitivity.mouthThreshold(-1, BASE_MAR))
    }

    @Test
    fun aFurrowTriggerNeverReachesTheBaseline() {
        // At trigger >= baseline every frontal frame would read as "brows furrowed", so the most
        // sensitive level must still leave a real deficit to detect.
        val trigger = MuscleSensitivity.furrowRatio(2, BASE_FURROW)
        assertTrue(trigger < 1f)
        assertTrue(1f - trigger > 0.05f)
    }

    private companion object {
        const val BASE_MAR = 0.55f
        const val BASE_FURROW = 0.88f
        const val BASE_YAW = 25f
    }
}
