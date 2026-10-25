package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests what the on-screen sensitivity control does to the mouth and brow features.
 *
 * Why this exists: the slider used to scale only confirmation times and head angles, while the yawn
 * threshold (MAR, jawOpen) and the brow-furrow threshold stayed hard-coded. A judge asking "what
 * changes when you move that slider" would have gotten an answer that did not include the two
 * facial-muscle numbers on the screen. Level 1 must be a no-op so the documented defaults in the
 * threshold table stay the numbers actually used.
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
    fun orderingIsMonotonicAcrossTheThreeLevels() {
        val mar = intArrayOf(0, 1, 2).map { MuscleSensitivity.mouthThreshold(it, BASE_MAR) }
        val furrow = intArrayOf(0, 1, 2).map { MuscleSensitivity.furrowRatio(it, BASE_FURROW) }
        val window = intArrayOf(0, 1, 2).map { MuscleSensitivity.furrowYawWindow(it, BASE_YAW) }

        assertTrue(mar[0] > mar[1] && mar[1] > mar[2])
        assertTrue(furrow[0] < furrow[1] && furrow[1] < furrow[2])
        assertTrue(window[0] < window[1] && window[1] < window[2])
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
