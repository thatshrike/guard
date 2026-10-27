package com.acousticguard.ml

import android.content.Context
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions

/**
 * Production inference adapter around the bundled pretrained YAMNet TFLite model.
 *
 * The model is an AudioSet classifier, not a medical or emergency-response model. Its labels are
 * deliberately narrowed by YamnetLabelMapper and then corroborated by DistressFusionEngine.
 */
class YamnetDistressClassifier(
    context: Context,
    private val modelSource: ModelSource = ModelSource.YAMNET_BASELINE,
) : DistressClassifier {
    private val classifier: AudioClassifier
    private val tensorAudio: TensorAudio
    private val clearBuffer: FloatArray

    override val modelInfo = ModelInfo(
        modelVersion = MODEL_VERSION,
        runtime = "tflite-task-audio",
        quantization = "float32",
        inferenceMode = if (modelSource == ModelSource.YAMNET_FALLBACK) "fallback" else "baseline",
        source = modelSource,
    )

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
        clearBuffer = FloatArray(classifier.requiredInputBufferSize.toInt())
    }

    override val requiredInputBufferSize: Int
        get() = classifier.requiredInputBufferSize.toInt()

    override val sampleRateHz: Int = MODEL_SAMPLE_RATE

    @Synchronized
    override fun infer(
        samples: FloatArray,
        motion: MotionSnapshot,
        acousticFeatures: AcousticFeatureVector?,
    ): DistressInference {
        check(!classifier.isClosed) { "The local audio classifier is closed." }
        require(samples.size == requiredInputBufferSize) {
            "YAMNet requires $requiredInputBufferSize samples, received ${samples.size}."
        }
        return try {
            tensorAudio.load(samples)
            val categories = classifier.classify(tensorAudio)
                .flatMap { it.categories }
                .map { YamnetCategoryScore(label = it.label, score = it.score) }
            YamnetLabelMapper.toInference(
                categories = categories,
                motion = motion,
                acousticFeatures = acousticFeatures,
                modelVersion = modelInfo.modelVersion,
                modelSource = modelInfo.source,
            )
        } finally {
            // TensorAudio keeps a ring buffer internally. Overwrite its complete capacity so the
            // just-classified PCM window is not retained after this inference returns.
            runCatching { tensorAudio.load(clearBuffer) }
        }
    }

    override fun close() {
        classifier.close()
    }

    private companion object {
        const val MODEL_ASSET = "yamnet.tflite"
        const val MODEL_MANIFEST_ASSET = "yamnet_model_manifest.json"
        const val MODEL_VERSION = "yamnet-audioset-1"
        const val MAX_RESULTS = 521
        const val MODEL_SAMPLE_RATE = 16_000
    }
}
