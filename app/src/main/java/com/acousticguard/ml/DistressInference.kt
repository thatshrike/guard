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
) {
    fun probability(marker: DistressMarker): Float = when (marker) {
        DistressMarker.SCREAM -> screamProbability
        DistressMarker.RESPIRATORY -> respiratoryProbability
        DistressMarker.IMPACT -> impactProbability
    }
}
