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
 * Tests the Tier 1 layer (per-feature tolerance band). This layer is NOT wired into the app measurement
 * pipeline yet, but it is already in the repo and pushed, so any of its behaviour can be called at any
 * moment. The three tests below lock the three places where an accidental edit would change the numbers
 * without anyone seeing it: the physical-range gate while measuring, refusing to emit a band when the
 * data is all NaN, and a band that must not collapse when a student sits too still.
 */
class DomainToleranceTest {

    private fun spread(center: Double, n: Int) = DoubleArray(n) { center + (it % 5) * 0.1 }

    /**
     * While learning, samples outside the physical range are dropped. Measuring must do the same: a junk
     * distance frame (3 cm or 250 cm from jumping landmarks) is a MEASUREMENT ERROR, not "sitting too close
     * or too far". Before the fix evaluate only checked isFinite, so it returned BELOW/ABOVE_BOUND and turned
     * a measurement error into an alert - exactly the kind of error falseAlertCount is counting.
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

        // A rejected value must still be returned: the table reader needs to see what the sensor said.
        assertEquals(250.0, engine.evaluate(Feature.DISTANCE, 250.0).value!!, 0.001)
    }

    /** All NaN means no band, and with NO band there is no licence to conclude STABLE or ABOVE. */
    @Test
    fun allNanCalibrationYieldsNoBandAndNoVerdict() {
        val engine = IndependentFeatureEngine()
        assertTrue(engine.calibrate(mapOf(Feature.BLINK_RATE to DoubleArray(60) { Double.NaN })).isEmpty())
        assertEquals(FeatureStatus.INSUFFICIENT_DATA, engine.evaluate(Feature.BLINK_RATE, 14.0).status)
        assertNull(RobustStats.personalBound(DoubleArray(60) { Double.NaN }))
    }

    /**
     * A student sitting almost motionless has sigma near 0. Without the minHalfWidth floor the band
     * collapses to [mu, mu] and every later frame is reported as a deviation. This is exactly the trap
     * that would make the best-behaved student the one called out most often.
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
