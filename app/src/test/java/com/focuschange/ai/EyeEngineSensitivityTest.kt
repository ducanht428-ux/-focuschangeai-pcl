package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sensitivity knob moves the eyelid close line. It must not also move the reopen line
 * upward, because a blink is only recorded when the eye climbs back over it: raising it makes
 * shallow or slowly-reopening blinks start and never end, which shows up as "I blinked eight
 * times but the counter moved four".
 */
class EyeEngineSensitivityTest {

    private fun engine(level: Int): EyeEngine = EyeEngine().apply { setSensitivity(level) }

    @Test
    fun theCloseLineRisesWithEveryLevel() {
        val deep = floatArrayOf(
            engine(0).closeO, engine(1).closeO, engine(2).closeO, engine(3).closeO
        )
        for (i in 1..3) assertTrue("level $i must close later than level ${i - 1}", deep[i] > deep[i - 1])
        assertEquals(0.54f, deep[0], 1e-4f)
        assertEquals(0.60f, deep[1], 1e-4f)
        assertEquals(0.66f, deep[2], 1e-4f)
        assertEquals(0.70f, deep[3], 1e-4f)
    }

    @Test
    fun noLevelDemandsAReopeningWiderThanTheDefault() {
        for (level in 0..3) {
            assertTrue(
                "level $level sets the reopen line to ${engine(level).openO}, above the default ${EyeEngine.OPEN_O}",
                engine(level).openO <= EyeEngine.OPEN_O + 1e-6f
            )
        }
        // The bug this pins: level 3 used to ask for 0.88, level 1 only 0.78.
        assertEquals(engine(1).openO, engine(3).openO, 1e-6f)
    }

    @Test
    fun everyLevelStillKeepsHysteresisAboveItsOwnCloseLine() {
        for (level in 0..3) {
            val e = engine(level)
            // 0.78f - 0.70f is 0.079999983f in binary floating point, so the gap gets a small
            // tolerance; the invariant being guarded is "at least 0.08 apart", not six decimals.
            assertTrue("level $level: reopen ${e.openO} is not above close ${e.closeO}", e.openO - e.closeO >= 0.08f - 1e-3f)
        }
    }

    @Test
    fun anUnknownLevelFallsBackToTheDocumentedDefaults() {
        val e = engine(7)
        assertEquals(EyeEngine.CLOSE_O, e.closeO, 1e-6f)
        assertEquals(EyeEngine.OPEN_O, e.openO, 1e-6f)
    }
}
