package com.focuschange.ai.domain.engine

import com.focuschange.ai.domain.model.Feature
import com.focuschange.ai.domain.model.FeatureEvaluation
import com.focuschange.ai.domain.model.FeatureStatus
import com.focuschange.ai.domain.model.ToleranceBound

/**
 * Tier 1: feature-wise decoupled metrics (distance d, blink rate b, SEI e).
 * Every feature runs through its own isolated pipeline: its own validity filter, IQR filter,
 * bound and status. No feature's data or result ever influences another's.
 *
 * Thread-safe: bounds are an immutable map swapped atomically through a volatile reference.
 */
class IndependentFeatureEngine(
    private val k: Double = RobustStats.DEFAULT_K,
    private val minStableSamples: Int = 30
) {
    @Volatile
    private var bounds: Map<Feature, ToleranceBound> = emptyMap()

    /** Recomputes the personal bounds of every feature from historical samples. */
    fun calibrate(samples: Map<Feature, DoubleArray>): Map<Feature, ToleranceBound> {
        val result = HashMap<Feature, ToleranceBound>()
        for (feature in Feature.values()) {
            val raw = samples[feature] ?: continue
            val valid = raw.filter { it.isFinite() && it >= feature.validMin && it <= feature.validMax }.toDoubleArray()
            val bound = RobustStats.personalBound(
                values = valid,
                k = k,
                minHalfWidth = feature.minHalfWidth,
                minStableSamples = minStableSamples,
                hardMin = feature.validMin,
                hardMax = feature.validMax
            )
            if (bound != null) result[feature] = bound
        }
        bounds = result.toMap()
        return bounds
    }

    fun reset() {
        bounds = emptyMap()
    }

    fun currentBounds(): Map<Feature, ToleranceBound> = bounds

    fun evaluate(feature: Feature, value: Double?): FeatureEvaluation {
        val bound = bounds[feature]
        val finite = value?.takeIf { it.isFinite() }
        // Cổng khoảng vật lý phải áp cả lúc đo, không chỉ lúc học. calibrate() bỏ mẫu ngoài
        // validMin/validMax ở trên, còn evaluate trước đây chỉ kiểm tra isFinite: một khung
        // distance rác (3 cm hoặc 250 cm do landmark nhảy) thành BELOW/ABOVE_BOUND chứ không phải
        // INSUFFICIENT_DATA, tức là sinh cảnh báo giả từ lỗi đo.
        val plausible = finite?.takeIf { it >= feature.validMin && it <= feature.validMax }
        if (plausible == null || bound == null) {
            return FeatureEvaluation(feature, finite, bound, FeatureStatus.INSUFFICIENT_DATA)
        }
        return FeatureEvaluation(feature, finite, bound, bound.classify(finite))
    }

    fun evaluateAll(distanceCm: Double?, blinkRateBpm: Double?, seiScore: Double?): Map<Feature, FeatureEvaluation> =
        mapOf(
            Feature.DISTANCE to evaluate(Feature.DISTANCE, distanceCm),
            Feature.BLINK_RATE to evaluate(Feature.BLINK_RATE, blinkRateBpm),
            Feature.ENGAGEMENT to evaluate(Feature.ENGAGEMENT, seiScore)
        )
}
