package com.acousticguard

import com.acousticguard.ml.DistressClassifier
import com.acousticguard.ml.DistressInference
import com.acousticguard.ml.ModelInfo
import com.acousticguard.ml.ModelSelector
import com.acousticguard.ml.ModelSource
import com.acousticguard.ml.MotionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionFallbackTest {
    @Test
    fun availableStudentModelIsPreferredOverYamnet() {
        val selector = ModelSelector()
        val selection = selector.select(
            tinyLoader = { FakeClassifier(ModelSource.TINY_STUDENT) },
            yamnetLoader = { error("YAMNet should not be constructed") },
        )

        assertTrue(!selection.fallbackActive)
        assertEquals(ModelSource.TINY_STUDENT, selection.classifier.modelInfo.source)
    }

    @Test
    fun studentLoadFailureUsesTheVisibleYamnetFallback() {
        val selector = ModelSelector()
        val selection = selector.select(
            tinyLoader = { error("student asset unavailable") },
            yamnetLoader = { FakeClassifier(ModelSource.YAMNET_FALLBACK) },
        )

        assertTrue(selection.fallbackActive)
        assertEquals(ModelSource.YAMNET_FALLBACK, selection.classifier.modelInfo.source)
        assertTrue(selection.fallbackReason!!.contains("YAMNet"))
    }

    @Test
    fun bothModelFailuresAreReturnedAsAVisibleSelectionError() {
        val selector = ModelSelector()

        assertTrue(
            runCatching {
                selector.select(
                    tinyLoader = { error("student failed") },
                    yamnetLoader = { error("yamnet failed") },
                )
            }.isFailure,
        )
    }

    private class FakeClassifier(source: ModelSource) : DistressClassifier {
        override val modelInfo = ModelInfo(
            modelVersion = "test",
            runtime = "test",
            quantization = "float32",
            inferenceMode = "fallback",
            source = source,
        )
        override val requiredInputBufferSize: Int = 16_000
        override val sampleRateHz: Int = 16_000

        override fun infer(
            samples: FloatArray,
            motion: MotionSnapshot,
            acousticFeatures: com.acousticguard.ml.AcousticFeatureVector?,
        ) = DistressInference(0f, 0f, 0f, modelInfo.modelVersion)

        override fun close() = Unit
    }
}
