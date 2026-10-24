package com.acousticguard.ml

enum class DistressMarker(val label: String) {
    SCREAM("Vocal distress"),
    RESPIRATORY("Respiratory distress"),
    IMPACT("Impact transient"),
}

data class DistressInference(
    val screamProbability: Float,
    val respiratoryProbability: Float,
    val impactProbability: Float,
    val modelVersion: String,
    val normalProbability: Float = 0f,
    /** A normalized uncertainty estimate; 0 means confident and 1 means unusable. */
    val uncertainty: Float = 0f,
    /** A scalar quality estimate for the current audio window. */
    val audioQuality: Float = 1f,
    val modelSource: ModelSource = ModelSource.UNAVAILABLE,
) {
    fun probability(marker: DistressMarker): Float = when (marker) {
        DistressMarker.SCREAM -> screamProbability
        DistressMarker.RESPIRATORY -> respiratoryProbability
        DistressMarker.IMPACT -> impactProbability
    }
}
