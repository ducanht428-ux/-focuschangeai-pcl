package com.focuschange.ai

import kotlin.math.abs

/**
 * Warns that "the person sitting here does not match the selected profile" WITHOUT recognizing
 * any face.
 *
 * The app only ships `face_landmarker.task` (478 geometric landmarks) and has no recognition
 * model, so it cannot answer "who is this". It can answer the weaker but real question: "is the
 * face currently sitting here WIDER or NARROWER than the face that was calibrated, by how many
 * percent".
 *
 * The comparison quantity is the **raw distance before the scale factor** (`rawUnscaled` of
 * `estimateDistanceCm`), taken as a median over 30 frames and checked against the value stored at
 * calibration time.
 * `rawUnscaled = assumedW * focal / faceWidthInPx`, so for one and the same camera only the real
 * face width of the person sitting there still moves that number - however many percent it
 * shifts, that is how much wider or narrower the face is. This quantity is used instead of
 * storing raw pixel widths ourselves because `estimateDistanceCm` already compensates it for
 * perspective using the head yaw; a student who always sits with a tilted head would be flagged
 * as "different person" if raw pixels were compared.
 *
 * **Two limits that must be stated out loud, including in front of the judges:**
 * 1. The distance estimate and this signature come from the SAME information source, so the guard
 *    can only compare when the person sits at the reference position. The window opens when the
 *    CONVERTED distance falls inside [distanceLoCm, distanceHiCm]; outside that band it counts as
 *    not measured. Consequence: a person whose face is very different but who sits 20-25 cm
 *    further away or closer can slip through.
 * 2. The focal length belongs to the phone, not to the profile. Switch devices and both the scale
 *    factor and the signature have to be calibrated again - exactly like the old behaviour of the
 *    `distance_scale_v2` preference.
 *
 * This flag never raises an audible alert and never enters the alert chain: picking the wrong
 * profile is an operator error, and feeding it into the alert counter would dirty the research
 * numbers.
 */
internal class PersonGuard(
    private val tolerance: Float = WIDTH_TOLERANCE,
    private val holdMs: Long = HOLD_MS,
    private val distanceLoCm: Float = DIST_LO_CM,
    private val distanceHiCm: Float = DIST_HI_CM,
    private val minSamples: Int = MIN_SAMPLES,
    private val windowSize: Int = WINDOW_SIZE
) {

    companion object {
        /**
         * 12% was picked from both ends: landmark noise of the eye-corner pair at 640x480/50 cm is
         * only ~1.6% (that figure is recorded in the comment of `estimateDistanceCm`), while the
         * eye-corner distance of two adults differs by roughly 5-8%. 12% therefore sits above the
         * noise but BELOW the gap of many real person pairs - so this guard misses people whose
         * faces only slightly differ; it is not a device that separates every person. Neither the
         * 1.6% nor the 5-8% has been re-measured on this project's own data.
         */
        const val WIDTH_TOLERANCE = 0.12f
        const val HOLD_MS = 10_000L

        /**
         * The "sitting in the right place" band: 40-62 cm around the 50 cm reference distance.
         * Deliberately wider than +/-10%, because a position shift and a face-width shift enter the
         * same number in the same direction.
         */
        const val DIST_LO_CM = 40f
        const val DIST_HI_CM = 62f
        const val MIN_SAMPLES = 10
        const val WINDOW_SIZE = 30

        /**
         * A calibration factor deviating by more than 20% is almost certainly a different person:
         * calibration happens at a KNOWN reference distance, so `scale = REF / median raw distance`
         * is left with a single variable - the real face width of whoever is sitting there. This is
         * a more trustworthy trigger than the continuous test.
         */
        const val CALIBRATION_REJECT_RATIO = 0.20f

        /** Relative deviation of the new factor against the stored one, NaN if either is invalid. */
        fun calibrationDeviation(newScale: Float, storedScale: Float): Float {
            if (!newScale.isFinite() || !storedScale.isFinite() || storedScale <= 0f) return Float.NaN
            return (newScale - storedScale) / storedScale
        }
    }

    private val raws = ArrayDeque<Float>()
    private var suspectSinceMs = 0L

    /** Relative deviation of the most recent evaluation; NaN when this frame is not fit to compare. */
    var deviation: Float = Float.NaN
        private set

    /** Samples currently held in the window - printed in the HUD so the operator knows data is thin. */
    fun sampleCount(): Int = raws.size

    fun reset() {
        raws.clear()
        suspectSinceMs = 0L
        deviation = Float.NaN
    }

    /**
     * Returns true when a person change is suspected AND that suspicion has lasted [holdMs].
     *
     * `referenceRawCm` comes from the selected profile; null means the profile has not been
     * calibrated, so there is nothing to compare against and "different person" must not be
     * inferred. Every frame outside the distance band clears the flag timer: one student standing
     * up and sitting back down is not new evidence.
     */
    fun observe(
        nowMs: Long,
        rawDistanceCm: Float?,
        scaledDistanceCm: Float?,
        referenceRawCm: Float?
    ): Boolean {
        val raw = rawDistanceCm?.takeIf { it.isFinite() && it > 0f }
        val scaled = scaledDistanceCm?.takeIf { it.isFinite() }
        val ref = referenceRawCm?.takeIf { it.isFinite() && it > 0f }

        if (raw == null || scaled == null || ref == null ||
            scaled < distanceLoCm || scaled > distanceHiCm
        ) {
            suspectSinceMs = 0L
            deviation = Float.NaN
            return false
        }

        raws.addLast(raw)
        while (raws.size > windowSize) raws.removeFirst()
        if (raws.size < minSamples) return false

        val dev = (medianOf(raws) - ref) / ref
        deviation = dev

        if (abs(dev) <= tolerance) {
            suspectSinceMs = 0L
            return false
        }
        if (suspectSinceMs == 0L) suspectSinceMs = nowMs
        return nowMs - suspectSinceMs >= holdMs
    }

    /** Median of the current window, null until [minSamples] frames are in. */
    fun medianRaw(): Float? = if (raws.size < minSamples) null else medianOf(raws)

    /** How many milliseconds the suspicion has lasted; 0 when clean. */
    fun suspectMs(nowMs: Long): Long =
        if (suspectSinceMs == 0L) 0L else nowMs - suspectSinceMs

    private fun medianOf(window: ArrayDeque<Float>): Float {
        val sorted = window.sorted()
        return sorted[sorted.size / 2]
    }
}
