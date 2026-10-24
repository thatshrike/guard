package com.acousticguard.ml

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Adapter for the optional domain-specific full-integer student model.
 *
 * The model is intentionally an asset, not a downloaded artifact. If it is absent or fails its
 * manifest/integrity contract, ModelSelector falls back to YAMNet and exposes that state to the UI.
 */
class TinyDistressClassifier(context: Context) : DistressClassifier {
    private val interpreter: Interpreter
    private val encoder = LogMelFeatureEncoder()
    private val featureBuffer = FloatArray(INPUT_FEATURE_COUNT)
    private val inputType: DataType
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputType: DataType
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val outputValues = FloatArray(OUTPUT_COUNT)
    private val probabilityBuffer = FloatArray(OUTPUT_COUNT)
    private val inputTarget: Any
    private val outputTarget: Any

    override val modelInfo: ModelInfo
    override val requiredInputBufferSize: Int = MODEL_SAMPLE_RATE * MODEL_WINDOW_MS / 1_000
    override val sampleRateHz: Int = MODEL_SAMPLE_RATE

    init {
        val manifest = ModelAssetVerifier.verify(
            context = context.applicationContext,
            modelAssetName = MODEL_ASSET,
            manifestAssetName = MODEL_MANIFEST_ASSET,
            expectedModelVersion = MODEL_VERSION,
            expectedRuntime = "tflite",
            expectedQuantization = "int8",
        )
        require(manifest.sampleRateHz == MODEL_SAMPLE_RATE) {
            "The student model sample-rate contract is not 16 kHz."
        }
        require(manifest.windowMs == MODEL_WINDOW_MS) {
            "The student model window contract is not one second."
        }
        require(manifest.inputContract == INPUT_CONTRACT) {
            "The student model input contract is unsupported."
        }
        require(manifest.outputs == OUTPUT_LABELS) {
            "The student model output labels do not match the classifier contract."
        }

        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
        modelBuffer.put(modelBytes)
        modelBuffer.rewind()

        interpreter = Interpreter(
            modelBuffer,
            Interpreter.Options().setNumThreads(1),
        )
        interpreter.allocateTensors()
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        require(inputTensor.numElements() == INPUT_FEATURE_COUNT) {
            "The student model input does not contain $INPUT_FEATURE_COUNT values."
        }
        require(outputTensor.numElements() == OUTPUT_COUNT) {
            "The student model must emit exactly $OUTPUT_COUNT class scores."
        }

        inputType = inputTensor.dataType()
        inputScale = inputTensor.quantizationParams().scale
        inputZeroPoint = inputTensor.quantizationParams().zeroPoint
        outputType = outputTensor.dataType()
        outputScale = outputTensor.quantizationParams().scale
        outputZeroPoint = outputTensor.quantizationParams().zeroPoint

        require(inputType == DataType.INT8 || inputType == DataType.UINT8 || inputType == DataType.FLOAT32) {
            "The student model input type is unsupported: $inputType."
        }
        require(outputType == DataType.INT8 || outputType == DataType.UINT8 || outputType == DataType.FLOAT32) {
            "The student model output type is unsupported: $outputType."
        }
        if (inputType != DataType.FLOAT32) require(inputScale > 0f) {
            "The student model input quantization scale is invalid."
        }
        if (outputType != DataType.FLOAT32) require(outputScale > 0f) {
            "The student model output quantization scale is invalid."
        }

        inputTarget = when (inputType) {
            DataType.FLOAT32 -> FloatArray(INPUT_FEATURE_COUNT)
            else -> ByteArray(INPUT_FEATURE_COUNT)
        }
        outputTarget = when (outputType) {
            DataType.FLOAT32 -> FloatArray(OUTPUT_COUNT)
            else -> ByteArray(OUTPUT_COUNT)
        }
        modelInfo = ModelInfo(
            modelVersion = manifest.modelVersion,
            runtime = manifest.runtime ?: "tflite",
            quantization = manifest.quantization ?: "int8",
            inferenceMode = "primary",
            source = ModelSource.TINY_STUDENT,
        )
    }

    @Synchronized
    override fun infer(
        samples: FloatArray,
        motion: MotionSnapshot,
        acousticFeatures: AcousticFeatureVector?,
    ): DistressInference {
        check(samples.size == requiredInputBufferSize) {
            "The student model requires $requiredInputBufferSize samples, received ${samples.size}."
        }
        encoder.encode(samples, motion, featureBuffer)
        populateInputTarget()
        interpreter.run(inputTarget, outputTarget)
        readOutputTarget()

        normalizedProbabilities(outputValues)
        val probabilities = probabilityBuffer
        val normal = probabilities[0]
        val scream = probabilities[1]
        val respiratory = probabilities[2]
        val impact = probabilities[3]
        val uncertainty = normalizedEntropy(probabilities)
        val audioQuality = acousticFeatures?.let {
            (1f - it.silenceRatio).coerceIn(0f, 1f)
        } ?: 1f

        return DistressInference(
            screamProbability = scream,
            respiratoryProbability = respiratory,
            impactProbability = impact,
            modelVersion = modelInfo.modelVersion,
            normalProbability = normal,
            uncertainty = uncertainty,
            audioQuality = audioQuality,
            modelSource = modelInfo.source,
        )
    }

    override fun close() {
        interpreter.close()
    }

    private fun populateInputTarget() {
        when (inputTarget) {
            is FloatArray -> featureBuffer.copyInto(inputTarget)
            is ByteArray -> {
                val minimum = if (inputType == DataType.UINT8) 0 else -128
                val maximum = if (inputType == DataType.UINT8) 255 else 127
                for (index in featureBuffer.indices) {
                    inputTarget[index] = quantize(
                        featureBuffer[index],
                        inputScale,
                        inputZeroPoint,
                        minimum,
                        maximum,
                    ).toByte()
                }
            }
        }
    }

    private fun readOutputTarget() {
        when (outputTarget) {
            is FloatArray -> outputTarget.copyInto(outputValues)
            is ByteArray -> {
                for (index in outputValues.indices) {
                    val raw = if (outputType == DataType.UINT8) {
                        outputTarget[index].toInt() and 0xff
                    } else {
                        outputTarget[index].toInt()
                    }
                    outputValues[index] = (raw - outputZeroPoint) * outputScale
                }
            }
        }
    }

    private fun normalizedProbabilities(raw: FloatArray) {
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        var sum = 0f
        var peak = Float.NEGATIVE_INFINITY
        for (value in raw) {
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            sum += value
            peak = maxOf(peak, value)
        }

        val probabilities = probabilityBuffer
        val alreadyProbabilities = minimum >= 0f && maximum <= 1f && abs(sum - 1f) <= 0.15f
        if (alreadyProbabilities && sum > 0f) {
            for (index in probabilities.indices) probabilities[index] = raw[index] / sum
            return
        }

        var exponentialSum = 0f
        for (index in probabilities.indices) {
            probabilities[index] = exp(raw[index] - peak)
            exponentialSum += probabilities[index]
        }
        if (exponentialSum <= 0f || !exponentialSum.isFinite()) {
            probabilities.fill(1f / OUTPUT_COUNT)
        } else {
            for (index in probabilities.indices) probabilities[index] /= exponentialSum
        }
    }

    private fun normalizedEntropy(probabilities: FloatArray): Float {
        var entropy = 0f
        for (probability in probabilities) {
            if (probability > 0f) entropy -= probability * ln(probability)
        }
        return (entropy / ln(OUTPUT_COUNT.toFloat())).coerceIn(0f, 1f)
    }

    private fun quantize(value: Float, scale: Float, zeroPoint: Int, minimum: Int, maximum: Int): Int =
        ((value / scale) + zeroPoint).roundToInt().coerceIn(minimum, maximum)

    private companion object {
        const val MODEL_ASSET = "distress_student_int8.tflite"
        const val MODEL_MANIFEST_ASSET = "distress_student_manifest.json"
        const val MODEL_VERSION = "distress-student-int8-1"
        const val MODEL_SAMPLE_RATE = 16_000
        const val MODEL_WINDOW_MS = 1_000
        const val INPUT_CONTRACT = "logmel64x32+motion-v1"
        const val INPUT_FEATURE_COUNT = 64 * 32 + 2
        const val OUTPUT_COUNT = 4
        val OUTPUT_LABELS = listOf("normal", "scream", "respiratory", "impact")
    }
}
