package com.acousticguard.ml

import java.io.Closeable

/** Stable boundary between monitoring and a concrete on-device classifier. */
interface DistressClassifier : Closeable {
    val modelInfo: ModelInfo
    val requiredInputBufferSize: Int
    val sampleRateHz: Int

    fun infer(
        samples: FloatArray,
        motion: MotionSnapshot,
        acousticFeatures: AcousticFeatureVector? = null,
    ): DistressInference
}
