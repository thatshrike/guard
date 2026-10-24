package com.acousticguard.ml

import android.content.Context
import android.media.AudioRecord
import java.io.Closeable
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions

/**
 * Production inference adapter around the bundled pretrained YAMNet TFLite model.
 *
 * The model is an AudioSet classifier, not a medical or emergency-response model. Its labels are
 * deliberately narrowed by YamnetLabelMapper and then corroborated by DistressFusionEngine.
 */
class YamnetDistressClassifier(context: Context) : Closeable {
    private val classifier: AudioClassifier
    private val tensorAudio: TensorAudio

    init {
        ModelAssetVerifier.verify(
            context = context.applicationContext,
            modelAssetName = MODEL_ASSET,
            manifestAssetName = MODEL_MANIFEST_ASSET,
            expectedModelVersion = MODEL_VERSION,
        )
        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setScoreThreshold(0f)
            .setMaxResults(MAX_RESULTS)
            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
            .build()
        classifier = AudioClassifier.createFromFileAndOptions(
            context.applicationContext,
            MODEL_ASSET,
            options,
        )
        tensorAudio = classifier.createInputTensorAudio()
    }

    val requiredInputBufferSize: Int
        get() = classifier.requiredInputBufferSize.toInt()

    fun createAudioRecord(): AudioRecord = classifier.createAudioRecord()

    @Synchronized
    fun infer(
        samples: ShortArray,
        motion: MotionSnapshot,
        acousticFeatures: AcousticFeatureVector? = null,
    ): DistressInference {
        check(!classifier.isClosed) { "The local audio classifier is closed." }
        require(samples.size == requiredInputBufferSize) {
            "YAMNet requires $requiredInputBufferSize samples, received ${samples.size}."
        }
        tensorAudio.load(samples)
        val categories = classifier.classify(tensorAudio)
            .flatMap { it.categories }
            .map { YamnetCategoryScore(label = it.label, score = it.score) }
        return YamnetLabelMapper.toInference(categories, motion, acousticFeatures)
    }

    override fun close() {
        classifier.close()
    }

    private companion object {
        const val MODEL_ASSET = "yamnet.tflite"
        const val MODEL_MANIFEST_ASSET = "yamnet_model_manifest.json"
        const val MODEL_VERSION = "yamnet-audioset-1"
        const val MAX_RESULTS = 521
    }
}
