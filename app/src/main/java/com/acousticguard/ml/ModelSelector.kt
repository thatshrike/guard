package com.acousticguard.ml

import android.content.Context

data class ModelSelection(
    val classifier: DistressClassifier,
    val fallbackActive: Boolean,
    val fallbackReason: String? = null,
)

class ModelSelectionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Chooses the domain model first and keeps the existing YAMNet path as a visible fallback. */
class ModelSelector(
    private val tinyFactory: (Context) -> DistressClassifier = { TinyDistressClassifier(it) },
    private val yamnetFactory: (Context) -> DistressClassifier = {
        YamnetDistressClassifier(it, ModelSource.YAMNET_FALLBACK)
    },
) {
    fun select(context: Context): ModelSelection = select(
        tinyLoader = { tinyFactory(context.applicationContext) },
        yamnetLoader = { yamnetFactory(context.applicationContext) },
    )

    internal fun select(
        tinyLoader: () -> DistressClassifier,
        yamnetLoader: () -> DistressClassifier,
    ): ModelSelection {
        val tinyResult = runCatching { tinyLoader() }
        tinyResult.getOrNull()?.let { classifier ->
            return ModelSelection(classifier = classifier, fallbackActive = false)
        }

        val tinyFailure = tinyResult.exceptionOrNull()
        val yamnetResult = runCatching { yamnetLoader() }
        yamnetResult.getOrNull()?.let { classifier ->
            return ModelSelection(
                classifier = classifier,
                fallbackActive = true,
                fallbackReason = "TinyML student unavailable (${safeReason(tinyFailure)}); using the verified YAMNet fallback.",
            )
        }

        val yamnetFailure = yamnetResult.exceptionOrNull()
        throw ModelSelectionException(
            message = "Neither the TinyML student model nor the YAMNet fallback could be loaded " +
                "(student: ${safeReason(tinyFailure)}; YAMNet: ${safeReason(yamnetFailure)}).",
            cause = yamnetFailure ?: tinyFailure,
        )
    }

    private fun safeReason(failure: Throwable?): String =
        failure?.message?.lineSequence()?.firstOrNull()?.take(160) ?: failure?.javaClass?.simpleName ?: "unknown error"
}
