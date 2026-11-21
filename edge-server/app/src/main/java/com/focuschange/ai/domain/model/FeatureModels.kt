package com.focuschange.ai.domain.model

/** Calibration constants shared by the engine and the ViewModel. */
object CalibrationConfig {
    const val DAYS = 14
    const val DAY_MS = 24L * 60L * 60L * 1000L
    const val WINDOW_MS = DAYS * DAY_MS
}

/** The three globally tracked, mutually independent features (Tier 1). */
enum class Feature(
    val label: String,
    val unit: String,
    val decimals: Int,
    /** Smallest half-width of the tolerance band, so a perfectly constant baseline does not yield a zero-width band. */
    val minHalfWidth: Double,
    /** Physically plausible range; samples outside it are discarded before IQR filtering. */
    val validMin: Double,
    val validMax: Double
) {
    DISTANCE("Distance (d)", "cm", 1, 2.0, 10.0, 200.0),
    BLINK_RATE("Blink rate (b)", "blinks/min", 1, 1.0, 0.0, 80.0),
    ENGAGEMENT("SEI engagement (e)", "/ 100", 1, 2.0, 0.0, 100.0)
}

enum class FeatureStatus { STABLE, BELOW_BOUND, ABOVE_BOUND, INSUFFICIENT_DATA }

enum class CalibrationPhase { CALIBRATING, ACTIVE }

/** Personal tolerance band [min, max] derived from the IQR-stable cluster. */
data class ToleranceBound(
    val min: Double,
    val max: Double,
    val mean: Double,
    val std: Double,
    val stableCount: Int,
    val rawCount: Int
) {
    fun classify(value: Double): FeatureStatus = when {
        !value.isFinite() -> FeatureStatus.INSUFFICIENT_DATA
        value < min -> FeatureStatus.BELOW_BOUND
        value > max -> FeatureStatus.ABOVE_BOUND
        else -> FeatureStatus.STABLE
    }
}

data class FeatureEvaluation(
    val feature: Feature,
    val value: Double?,
    val bound: ToleranceBound?,
    val status: FeatureStatus
)

data class LandmarkEvaluation(
    val deviation: Double?,
    val bound: ToleranceBound?,
    val status: FeatureStatus,
    val trackedLandmarks: Int
)
