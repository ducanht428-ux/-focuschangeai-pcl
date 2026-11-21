package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the per-day rollup that backs the "Diễn biến" panel, run on a computer (no phone needed).
 *
 * Why this exists: the table is what a teacher reads to judge whether a student actually studies
 * regularly, so two mistakes are expensive - a day that quietly disappears makes a nine-day gap look
 * like a clean record, and "0% outside the band" printed for a session that was never scored at all
 * reads as "perfect" rather than as "unknown".
 */
class PeriodRollupTest {

    private companion object {
        const val DAY = HabitConfig.DAY_MS
        const val HOUR = 3_600_000L

        /** A "now" that sits mid-day, so the window's last bucket is unambiguous. */
        const val NOW = 100L * DAY + 12L * HOUR
    }

    private fun session(
        startMs: Long,
        validMs: Long = 5L * 60_000L,
        sei: Float? = 70f,
        bandIn: Int = 0,
        bandBelow: Int = 0,
        bandAbove: Int = 0
    ) = SessionSummary(
        id = startMs,
        validMs = validMs,
        faceRatio = 0.95f,
        validRatio = 0.90f,
        quality = 80f,
        mean = FloatArray(HabitFeature.COUNT).also { if (sei != null) it[HabitFeature.SEI] = sei },
        std = FloatArray(HabitFeature.COUNT),
        count = IntArray(HabitFeature.COUNT).also { if (sei != null) it[HabitFeature.SEI] = 100 },
        bandIn = bandIn,
        bandBelow = bandBelow,
        bandAbove = bandAbove
    )

    private fun profileOf(vararg sessions: SessionSummary): HabitProfile {
        val p = HabitProfile("hs1", "Học sinh 1")
        p.sessions.addAll(sessions)
        return p
    }

    /** Empty days must still get a row: a gap in the record has to be visible as a gap. */
    @Test
    fun everyDayInWindowIsPresentEvenWithoutSessions() {
        val rows = profileOf().rollupByDays(7, NOW, 0)
        assertEquals(7, rows.size)
        for (i in 0 until 7) {
            val r = rows[i]
            assertEquals(0, r.sessions)
            assertEquals(0L, r.validMs)
            assertTrue(r.seiMean.isNaN())
            assertTrue(r.outsidePct.isNaN())
            assertEquals((94L + i) * DAY, r.dayStartMs)
        }
    }

    /** Two sessions of the same local day add up into one row instead of becoming two rows. */
    @Test
    fun sessionsOfTheSameDayPoolTogether() {
        val d = 99L * DAY
        val p = profileOf(
            session(d + 2L * HOUR, validMs = 3L * 60_000L, sei = 60f),
            session(d + 6L * HOUR, validMs = 7L * 60_000L, sei = 80f)
        )
        val rows = p.rollupByDays(7, NOW, 0)
        val r = rows.first { it.sessions == 2 }
        assertEquals(d, r.dayStartMs)
        assertEquals(10L * 60_000L, r.validMs)
        assertEquals(70f, r.seiMean, 0.01f)
        assertEquals(2, r.seiSessions)
    }

    /**
     * A session that runs across midnight belongs to the day it STARTED. Splitting its minutes over
     * two days would credit both days with time measured only once.
     */
    @Test
    fun sessionStraddlingMidnightIsFiledUnderItsStartDay() {
        val start = 100L * DAY - 60_000L   // 23:59 of day 99
        val p = profileOf(session(start, validMs = 20L * 60_000L))
        val rows = p.rollupByDays(3, NOW, 0)
        assertEquals(0, rows.last().sessions)
        assertEquals(1, rows[rows.size - 2].sessions)
        assertEquals(99L * DAY, rows[rows.size - 2].dayStartMs)
    }

    /**
     * The caller supplies the timezone offset, and it must actually move the boundary: the same UTC
     * instant is "yesterday" at UTC+2 and "today" at UTC.
     */
    @Test
    fun offsetMovesTheDayBoundary() {
        val start = 100L * DAY - 60_000L
        val p = profileOf(session(start))

        // At UTC the instant is still day 99, so it lands in the second-to-last row.
        val utc = p.rollupByDays(2, NOW, 0)
        assertEquals(99L * DAY, utc[0].dayStartMs)
        assertEquals(1, utc[0].sessions)
        assertEquals(0, utc[1].sessions)

        // At UTC+2 the same instant is already past local midnight, so it moves into day 100 and the
        // printed day start shifts back by the offset to stay a real UTC timestamp.
        val shifted = p.rollupByDays(2, NOW, 2 * 3_600_000)
        assertEquals(99L * DAY - 2L * HOUR, shifted[0].dayStartMs)
        assertEquals(100L * DAY - 2L * HOUR, shifted[1].dayStartMs)
        assertEquals(0, shifted[0].sessions)
        assertEquals(1, shifted[1].sessions)
    }

    /** Nothing was ever scored against a band: report "no data", never "0% outside the band". */
    @Test
    fun bandWithoutSamplesMakesNoClaim() {
        val p = profileOf(session(99L * DAY + HOUR, bandIn = 0, bandBelow = 0, bandAbove = 0))
        val r = p.rollupByDays(2, NOW, 0).first { it.sessions == 1 }
        assertEquals(0, r.bandSamples)
        assertTrue(r.outsidePct.isNaN())
    }

    /** Band counts pool across sessions as raw frames, so a long day is not an average of percentages. */
    @Test
    fun bandCountsPoolByFrameNotByPercentage() {
        val d = 99L * DAY
        val p = profileOf(
            // 100 frames, 50 outside -> 50%
            session(d + HOUR, bandIn = 50, bandBelow = 25, bandAbove = 25),
            // 900 frames, 90 outside -> 10%
            session(d + 2L * HOUR, bandIn = 810, bandBelow = 45, bandAbove = 45)
        )
        val r = p.rollupByDays(2, NOW, 0).first { it.sessions == 2 }
        assertEquals(1000, r.bandSamples)
        assertEquals(70, r.bandBelow)
        assertEquals(70, r.bandAbove)
        // frame-pooled: 140 out of 1000 = 14%. Averaging the two percentages would have claimed 30%.
        assertEquals(14f, r.outsidePct, 0.01f)
    }

    /** A day outside the window must not leak into the table, and must not throw either. */
    @Test
    fun sessionsOlderThanTheWindowAreDropped() {
        val p = profileOf(session(90L * DAY + HOUR), session(99L * DAY + HOUR))
        val rows = p.rollupByDays(7, NOW, 0)
        assertEquals(1, rows.sumOf { it.sessions })
        assertTrue(rows.all { it.dayStartMs >= 94L * DAY })
    }

    /** A day whose sessions have no SEI sample is a real day with an unknown score, not a zero. */
    @Test
    fun dayWithoutSeiKeepsItsSessionsButHasNoScore() {
        val p = profileOf(session(99L * DAY + HOUR, sei = null))
        val r = p.rollupByDays(2, NOW, 0).first { it.sessions == 1 }
        assertEquals(0, r.seiSessions)
        assertTrue(r.seiMean.isNaN())
        assertTrue(r.validMs > 0L)
    }

    /** The window is capped so a profile covering a whole term cannot make the panel build 300 rows. */
    @Test
    fun windowIsCappedToTheMaximum() {
        val rows = profileOf().rollupByDays(365, NOW, 0)
        assertEquals(HabitConfig.ROLLUP_MAX_DAYS, rows.size)
        assertEquals(
            (100L - HabitConfig.ROLLUP_MAX_DAYS + 1) * DAY,
            rows.first().dayStartMs
        )
        assertEquals(100L * DAY, rows.last().dayStartMs)
    }
}
