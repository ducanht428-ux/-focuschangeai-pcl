package com.focuschange.ai.domain.engine

import com.focuschange.ai.domain.model.FeatureStatus
import com.focuschange.ai.domain.model.LandmarkEvaluation
import com.focuschange.ai.domain.model.ToleranceBound
import kotlin.math.sqrt

/**
 * Tier 2: MediaPipe landmark spatial deviation.
 *
 * A "frame" is a flat FloatArray [x0,y0,z0, x1,y1,z1, ...] holding only the K tracked landmarks
 * (see [extractTracked]). The engine
 *  1. computes the baseline mean position of every coordinate (IQR-filtered),
 *  2. computes Delta_total(t) = (1/K) * sum_j ||l_j(t) - mu_j|| for every calibration frame,
 *  3. IQR-filters those scores and derives the personal bound [0, mu_dev + k*sigma_dev].
 *
 * Thread-safe: baseline and bound live in one immutable holder swapped atomically.
 */
class MediaPipeLandmarkEngine(
    val trackedIndices: IntArray = DEFAULT_TRACKED_INDICES,
    private val k: Double = RobustStats.DEFAULT_K,
    private val minStableSamples: Int = 30
) {
    init {
        require(trackedIndices.isNotEmpty()) { "At least one landmark must be tracked" }
        require(trackedIndices.all { it >= 0 }) { "Landmark indices must be non-negative" }
    }

    val landmarkCount: Int = trackedIndices.size
    val frameSize: Int = trackedIndices.size * 3
    private val requiredSourceSize: Int = ((trackedIndices.maxOrNull() ?: 0) + 1) * 3

    private class Calibration(val baseline: FloatArray, val bound: ToleranceBound)

    @Volatile
    private var calibration: Calibration? = null

    /** Picks the tracked landmarks out of a flat full-mesh array (478 * 3 floats). Null if invalid. */
    fun extractTracked(allLandmarks: FloatArray): FloatArray? {
        if (allLandmarks.size < requiredSourceSize) return null
        val out = FloatArray(frameSize)
        for (i in trackedIndices.indices) {
            val s = trackedIndices[i] * 3
            out[i * 3] = allLandmarks[s]
            out[i * 3 + 1] = allLandmarks[s + 1]
            out[i * 3 + 2] = allLandmarks[s + 2]
        }
        return if (isValidFrame(out)) out else null
    }

    fun isValidFrame(frame: FloatArray?): Boolean = frame != null && frame.size == frameSize && frame.all { it.isFinite() }

    /** Per-coordinate IQR-filtered mean of the supplied frames; null if no valid frame exists. */
    fun computeMeanFrame(frames: List<FloatArray>): FloatArray? {
        val valid = frames.filter { isValidFrame(it) }
        if (valid.isEmpty()) return null
        val out = FloatArray(frameSize)
        val column = DoubleArray(valid.size)
        for (c in 0 until frameSize) {
            for (i in valid.indices) column[i] = valid[i][c].toDouble()
            val stable = RobustStats.iqrFilter(column)
            if (stable.isEmpty()) return null
            out[c] = RobustStats.mean(stable).toFloat()
        }
        return out
    }

    /** Delta_total for one frame against a baseline. Returns NaN for invalid input. */
    fun deviation(frame: FloatArray, baseline: FloatArray): Double {
        if (!isValidFrame(frame) || !isValidFrame(baseline)) return Double.NaN
        var sum = 0.0
        for (j in 0 until landmarkCount) {
            val dx = (frame[j * 3] - baseline[j * 3]).toDouble()
            val dy = (frame[j * 3 + 1] - baseline[j * 3 + 1]).toDouble()
            val dz = (frame[j * 3 + 2] - baseline[j * 3 + 2]).toDouble()
            sum += sqrt(dx * dx + dy * dy + dz * dz)
        }
        return sum / landmarkCount
    }

    fun deviationSeries(frames: List<FloatArray>, baseline: FloatArray): DoubleArray =
        frames.map { deviation(it, baseline) }.filter { it.isFinite() }.toDoubleArray()

    /** Learns the baseline and the personal spatial bound from calibration frames. */
    fun calibrate(frames: List<FloatArray>): ToleranceBound? {
        val baseline = computeMeanFrame(frames)
        if (baseline == null) {
            calibration = null
            return null
        }
        val series = deviationSeries(frames, baseline)
        val bound = RobustStats.personalBound(
            values = series,
            k = k,
            minHalfWidth = MIN_HALF_WIDTH,
            minStableSamples = minStableSamples,
            lowerBoundAtZero = true
        )
        calibration = if (bound == null) null else Calibration(baseline, bound)
        return bound
    }

    fun reset() {
        calibration = null
    }

    fun baselineFrame(): FloatArray? = calibration?.baseline?.copyOf()

    fun currentBound(): ToleranceBound? = calibration?.bound

    /** Real-time status of the current (window-averaged) frame. Never BELOW_BOUND: deviation is >= 0. */
    fun evaluate(frame: FloatArray?): LandmarkEvaluation {
        val cal = calibration
        if (cal == null || frame == null || !isValidFrame(frame)) {
            return LandmarkEvaluation(null, cal?.bound, FeatureStatus.INSUFFICIENT_DATA, landmarkCount)
        }
        val delta = deviation(frame, cal.baseline)
        return LandmarkEvaluation(delta, cal.bound, cal.bound.classify(delta), landmarkCount)
    }

    companion object {
        /** Normalised image units; keeps the band non-degenerate for a perfectly still baseline. */
        const val MIN_HALF_WIDTH = 0.002

        /** Face-mesh indices: nose tip, forehead, chin, eye outer corners, cheeks, mouth corners. */
        val DEFAULT_TRACKED_INDICES: IntArray = intArrayOf(1, 10, 152, 33, 263, 234, 454, 61, 291)
    }
}
