package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TWO-SIDED allowed band learned from each student's own habit.
 *
 * Why it needs its own test: this band is printed on the HUD, written into the CSV and uploaded to
 * Firebase. If it reports wrongly in some direction, the consequence is not one skewed number but a
 * student called "abnormal" merely because the profile has too few sessions - or the reverse, a
 * "0% outside the band" on exactly the sessions where nothing was measured.
 */
class PersonalBandTest {

    /** Synthetic baseline: every feature marked as learned, mean/spread hand-set for each index. */
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

        // BAND_K = 2.5, so the yaw band is 0 +/- 5 degrees, clamped inside [-60, 60].
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

        // +/-100 around 98 would demand a band up to 198 - SEI has no such unit. The upper edge must be clamped.
        assertEquals(100f, band.max, 1e-4f)
        assertEquals(0f, band.min, 1e-4f)
        assertTrue(band.min <= band.mean && band.mean <= band.max)
    }

    @Test
    fun featureWithoutDataHasNoBandInsteadOfAZeroWidthBand() {
        val ok = BooleanArray(HabitFeature.COUNT) // all false
        val base = fakeBaseline(zeros(), zeros(), ok)

        assertNull(base.band(HabitFeature.YAW))

        // Reporting "unknown" as a band [0,0] turns every positive value into "above the band": that is two
        // errors at once - a wrong verdict for today and tomorrow's evidence wiped out at the same time.
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
        // NaN frames and frames of features outside BAND_FEATURES must not fall into "inside the band".
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

        // -70 degrees is outside the physical range [-60, 60]. observe() clamps it to -60 (correct, clamping is
        // the averaging job), but band scoring still has to see BELOW - clamping before scoring would record
        // an over-turned head as "just inside the edge of the band".
        session.observe(HabitFeature.YAW, -70f)
        assertEquals(BandPosition.BELOW, session.observeBand(HabitFeature.YAW, -70f))
        assertEquals(-60f, session.toSummary().mean[HabitFeature.YAW], 0.001f)
    }

    @Test
    fun csvCodesSeparateNotScoredFromNoBand() {
        // -1 (no band yet) comes from toCsvCode(); -2 (this frame was not scored) is a constant in MainActivity.
        // The two codes must differ: merged, the CSV cannot say "the machine has not learned" or "student absent".
        assertEquals(0, BandPosition.BELOW.toCsvCode())
        assertEquals(1, BandPosition.IN.toCsvCode())
        assertEquals(2, BandPosition.ABOVE.toCsvCode())
        assertEquals(-1, BandPosition.UNKNOWN.toCsvCode())
    }

    @Test
    fun bandColumnsFollowTheMeasuredBlockAndNewestColumnsGoLast() {
        val cols = DataLogger().columnNames
        val expected = HabitFeature.BAND_FEATURES.map { "band_" + HabitFeature.KEYS[it] }

        // Appended at the end rather than inserted in the middle: the 37 old columns keep their order so
        // that files from earlier sessions still open with the same analysis script. The situation group
        // went in AFTER the band block for exactly that reason, which moves the band block away
        // from the end of the list without moving a single older column - so this test now pins the
        // group order (measured, then band, then situation) instead of "band is last".
        val situationCols = listOf("situation", "alert_held", "closed_ms", "pose_std_deg")
        // The cheek / mouth-corner block went in after the situation group, so those four are the only
        // names whose position is allowed to move. Pinned because a value written into the wrong slot
        // leaves no trace on screen: the file opens, every number looks plausible, and the average of
        // each shifted column is quietly wrong.
        val blendCols = listOf("cheek_squint_l", "cheek_squint_r", "mouth_smile_l", "mouth_smile_r")
        val tail = cols.takeLast(expected.size + situationCols.size + blendCols.size)
        assertEquals(expected, tail.take(expected.size))
        assertEquals(situationCols, tail.subList(expected.size, expected.size + situationCols.size))
        assertEquals(blendCols, tail.takeLast(blendCols.size))
        assertEquals(cols.size, cols.toSet().size)
        assertFalse(cols.contains("band_distance_cm"))
        assertFalse(cols.contains("band_ear"))
    }

    /**
     * The band must be buildable from a REAL profile, not only from a made-up baseline: the path
     * commit() -> baseline() -> band() is where the constants (MIN_SESSIONS, MIN_TOTAL_MS,
     * MIN_FRAMES_PER_FEATURE) meet, and changing any one of them can make the band disappear.
     * The session count is read from HabitConfig instead of hard-coded so this test keeps meaning
     * "one demo run" after MIN_SESSIONS moved from 4 to 5.
     */
    @Test
    fun realProfileProducesAUsableBandAfterOneDemoRun() {
        val prof = HabitProfile("test_band", "Em Test")
        repeat(HabitConfig.MIN_SESSIONS) { i ->
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
                    validMs = 180_000L,
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
