package com.acousticguard.ml

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Allocation-free encoder for the student model's `logmel64x32+motion-v1` contract.
 *
 * The encoder deliberately keeps only bounded working buffers. It never exposes or stores audio
 * after [encode] returns. The model asset and its training pipeline must use this same contract.
 */
class LogMelFeatureEncoder(
    private val sampleRateHz: Int = SAMPLE_RATE_HZ,
    private val melBins: Int = MEL_BINS,
    private val frameCount: Int = FRAME_COUNT,
) {
    private val window = FloatArray(FRAME_LENGTH) { index ->
        (0.5 - 0.5 * cos((2.0 * Math.PI * index) / (FRAME_LENGTH - 1))).toFloat()
    }
    private val real = FloatArray(FRAME_LENGTH)
    private val imaginary = FloatArray(FRAME_LENGTH)
    private val power = FloatArray(FRAME_LENGTH / 2 + 1)
    private val filterStarts = IntArray(melBins)
    private val filterCenters = IntArray(melBins)
    private val filterEnds = IntArray(melBins)

    init {
        require(sampleRateHz > 0) { "The sample rate must be positive." }
        require(melBins > 0) { "The mel-bin count must be positive." }
        require(frameCount > 0) { "The frame count must be positive." }
        initializeMelFilters()
    }

    fun encode(samples: FloatArray, motion: MotionSnapshot, destination: FloatArray) {
        require(destination.size >= melBins * frameCount + MOTION_FEATURE_COUNT) {
            "The destination is too small for the log-mel feature contract."
        }

        for (frame in 0 until frameCount) {
            val offset = frame * FRAME_HOP
            for (index in 0 until FRAME_LENGTH) {
                val sampleIndex = offset + index
                real[index] = if (sampleIndex < samples.size) samples[sampleIndex] * window[index] else 0f
                imaginary[index] = 0f
            }
            fftInPlace(real, imaginary)
            for (bin in power.indices) {
                power[bin] = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            }

            val featureOffset = frame * melBins
            for (mel in 0 until melBins) {
                val start = filterStarts[mel]
                val center = filterCenters[mel]
                val end = filterEnds[mel]
                var energy = 0f

                for (bin in start until center) {
                    val denominator = (center - start).coerceAtLeast(1)
                    energy += power[bin] * (bin - start).toFloat() / denominator
                }
                for (bin in center until end) {
                    val denominator = (end - center).coerceAtLeast(1)
                    energy += power[bin] * (end - bin).toFloat() / denominator
                }

                val logEnergy = ln(energy + LOG_EPSILON)
                destination[featureOffset + mel] =
                    ((logEnergy - LOG_MIN) / (LOG_MAX - LOG_MIN)).coerceIn(0f, 1f)
            }
        }

        val motionOffset = melBins * frameCount
        destination[motionOffset] = motion.energy.coerceIn(0f, 1f)
        destination[motionOffset + 1] = motion.jerk.coerceIn(0f, 1f)
    }

    private fun initializeMelFilters() {
        val minimumMel = hzToMel(MIN_FILTER_HZ)
        val maximumMel = hzToMel(max(MIN_FILTER_HZ, sampleRateHz / 2f - 1f))
        val points = IntArray(melBins + 2)
        for (index in points.indices) {
            val mel = minimumMel + (maximumMel - minimumMel) * index / (melBins + 1)
            val frequency = melToHz(mel)
            points[index] = floor((FRAME_LENGTH + 1) * frequency / sampleRateHz)
                .toInt()
                .coerceIn(0, power.lastIndex)
        }

        for (mel in 0 until melBins) {
            filterStarts[mel] = points[mel]
            filterCenters[mel] = max(points[mel + 1], points[mel])
            filterEnds[mel] = max(points[mel + 2], points[mel + 1] + 1).coerceAtMost(power.size)
        }
    }

    private fun fftInPlace(real: FloatArray, imaginary: FloatArray) {
        var reverseIndex = 0
        for (index in 1 until real.size) {
            var bit = real.size shr 1
            while ((reverseIndex and bit) != 0) {
                reverseIndex = reverseIndex xor bit
                bit = bit shr 1
            }
            reverseIndex = reverseIndex xor bit
            if (index < reverseIndex) {
                val realValue = real[index]
                real[index] = real[reverseIndex]
                real[reverseIndex] = realValue

                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reverseIndex]
                imaginary[reverseIndex] = imaginaryValue
            }
        }

        var length = 2
        while (length <= real.size) {
            val angle = -2.0 * Math.PI / length
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()
            var blockStart = 0
            while (blockStart < real.size) {
                var weightReal = 1f
                var weightImaginary = 0f
                for (offset in 0 until length / 2) {
                    val evenIndex = blockStart + offset
                    val oddIndex = evenIndex + length / 2
                    val oddReal = real[oddIndex] * weightReal - imaginary[oddIndex] * weightImaginary
                    val oddImaginary = real[oddIndex] * weightImaginary + imaginary[oddIndex] * weightReal
                    val evenReal = real[evenIndex]
                    val evenImaginary = imaginary[evenIndex]
                    real[evenIndex] = evenReal + oddReal
                    imaginary[evenIndex] = evenImaginary + oddImaginary
                    real[oddIndex] = evenReal - oddReal
                    imaginary[oddIndex] = evenImaginary - oddImaginary

                    val nextWeightReal = weightReal * stepReal - weightImaginary * stepImaginary
                    weightImaginary = weightReal * stepImaginary + weightImaginary * stepReal
                    weightReal = nextWeightReal
                }
                blockStart += length
            }
            length = length shl 1
        }
    }

    private fun hzToMel(hz: Float): Float = 1_127f * ln(1f + hz / 700f)

    private fun melToHz(mel: Float): Float = 700f * (kotlin.math.exp(mel / 1_127f) - 1f)

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MEL_BINS = 64
        const val FRAME_COUNT = 32
        const val FRAME_LENGTH = 512
        const val FRAME_HOP = 496
        const val MIN_FILTER_HZ = 50f
        const val LOG_EPSILON = 1e-6f
        const val LOG_MIN = -12f
        const val LOG_MAX = 4f
        const val MOTION_FEATURE_COUNT = 2
    }
}
