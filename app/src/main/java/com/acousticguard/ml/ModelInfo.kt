package com.acousticguard.ml

/** Describes the model that is currently serving inference. */
enum class ModelSource(val label: String) {
    TINY_STUDENT("TinyML student"),
    YAMNET_BASELINE("YAMNet baseline"),
    YAMNET_FALLBACK("YAMNet fallback"),
    UNAVAILABLE("Unavailable"),
}

data class ModelInfo(
    val modelVersion: String = "unavailable",
    val runtime: String = "unavailable",
    val quantization: String = "unknown",
    val inferenceMode: String = "unavailable",
    val source: ModelSource = ModelSource.UNAVAILABLE,
) {
    companion object {
        val UNAVAILABLE = ModelInfo()
    }
}
