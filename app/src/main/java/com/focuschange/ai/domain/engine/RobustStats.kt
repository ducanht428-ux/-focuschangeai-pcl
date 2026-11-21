package com.focuschange.ai.domain.engine

import com.focuschange.ai.domain.model.ToleranceBound
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Pure, stateless statistics used by every pipeline (IQR filtering and personal tolerance bounds). */
object RobustStats {
    const val IQR_FENCE = 1.5
    const val DEFAULT_K = 1.2
    private const val MIN_FOR_QUARTILES = 4

    /** Linear-interpolation quantile of an ascending-sorted, non-empty array. */
    fun quantile(sorted: DoubleArray, p: Double): Double {
        require(sorted.isNotEmpty()) { "quantile of empty array" }
        if (sorted.size == 1) return sorted[0]
        val pos = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /**
     * X_stable = { x | Q1 - 1.5*IQR <= x <= Q3 + 1.5*IQR }.
     * Non-finite values are always dropped. With fewer than 4 finite values the quartiles are
     * meaningless, so all finite values are kept.
     */
    fun iqrFilter(values: DoubleArray): DoubleArray {
        val finite = values.filter { it.isFinite() }.toDoubleArray()
        if (finite.size < MIN_FOR_QUARTILES) return finite
        val sorted = finite.sortedArray()
        val q1 = quantile(sorted, 0.25)
        val q3 = quantile(sorted, 0.75)
        val iqr = q3 - q1
        val lower = q1 - IQR_FENCE * iqr
        val upper = q3 + IQR_FENCE * iqr
        return finite.filter { it >= lower && it <= upper }.toDoubleArray()
    }

    fun mean(values: DoubleArray): Double = values.sum() / values.size

    /** Population standard deviation (1/N), matching the specified formula. */
    fun populationStd(values: DoubleArray, mean: Double): Double {
        var acc = 0.0
        for (v in values) {
            val d = v - mean
            acc += d * d
        }
        return sqrt(acc / values.size)
    }

    /**
     * Personal tolerance band: mu -/+ k*sigma over the IQR-stable cluster.
     * Returns null when fewer than [minStableSamples] stable samples exist.
     *
     * @param minHalfWidth lower limit of k*sigma, guarding against degenerate (zero-width) bands.
     * @param lowerBoundAtZero produce [0, mu + k*sigma] (used for spatial deviation, which is never negative).
     */
    fun personalBound(
        values: DoubleArray,
        k: Double = DEFAULT_K,
        minHalfWidth: Double = 0.0,
        minStableSamples: Int = 1,
        hardMin: Double = Double.NEGATIVE_INFINITY,
        hardMax: Double = Double.POSITIVE_INFINITY,
        lowerBoundAtZero: Boolean = false
    ): ToleranceBound? {
        val stable = iqrFilter(values)
        if (stable.isEmpty() || stable.size < max(1, minStableSamples)) return null
        val mu = mean(stable)
        val sigma = populationStd(stable, mu)
        val half = max(k * sigma, minHalfWidth)
        val lower = if (lowerBoundAtZero) 0.0 else (mu - half).coerceAtLeast(hardMin)
        val upper = (mu + half).coerceAtMost(hardMax)
        if (!lower.isFinite() || !upper.isFinite() || lower > upper) return null
        return ToleranceBound(
            min = lower,
            max = upper,
            mean = mu,
            std = sigma,
            stableCount = stable.size,
            rawCount = values.count { it.isFinite() }
        )
    }
}
