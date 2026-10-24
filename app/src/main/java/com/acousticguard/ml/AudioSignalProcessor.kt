package com.acousticguard.ml

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight feature extraction for a 16 kHz PCM window.
 *
 * The processor deliberately returns only scalar features. Callers should clear their temporary
 * PCM buffer after this function returns; no audio is retained by this class.
 */
class AudioSignalProcessor {
    fun extract(samples: ShortArray, sampleCount: Int, motion: MotionSnapshot): AcousticFeatureVector {
        val count = sampleCount.coerceIn(0, samples.size)
        if (count == 0) return AcousticFeatureVector(0f, 0f, 0f, 0f, 0f, 1f, 0f, motion.energy, motion.jerk)

        var sumSquares = 0.0
        var peak = 0.0
        var diffEnergy = 0.0
        var silenceSamples = 0
        var previous = samples[0] / PCM_SCALE
        val envelope = FloatArray(ENVELOPE_BUCKETS)
        val bucketSize = (count / ENVELOPE_BUCKETS).coerceAtLeast(1)

        for (index in 0 until count) {
            val value = samples[index] / PCM_SCALE
            val absolute = abs(value)
            sumSquares += value * value
            peak = maxOf(peak, absolute.toDouble())
            if (absolute < SILENCE_THRESHOLD) silenceSamples++

            val difference = value - previous
            diffEnergy += difference * difference
            previous = value

            val bucket = (index / bucketSize).coerceAtMost(ENVELOPE_BUCKETS - 1)
            envelope[bucket] += absolute
        }

        val meanSquare = sumSquares / count
        val rms = sqrt(meanSquare).toFloat()
        val loudness = ((20f * log10(rms.coerceAtLeast(MIN_SIGNAL)) + LOUDNESS_OFFSET) / LOUDNESS_RANGE)
            .coerceIn(0f, 1f)
        val highFrequencyRatio = sqrt((diffEnergy / (sumSquares + EPSILON)).toFloat())
            .coerceIn(0f, 1f)
        val spectralFlux = (diffEnergy / count / FLUX_SCALE).toFloat().coerceIn(0f, 1f)
        val crestFactor = (peak.toFloat() / (rms * CREST_SCALE + EPSILON)).coerceIn(0f, 1f)
        val lowFrequencyRatio = (1f - highFrequencyRatio * LOW_FREQUENCY_DISCOUNT).coerceIn(0f, 1f)
        val silenceRatio = silenceSamples.toFloat() / count
        val envelopeVariation = normalizedVariation(envelope, count, bucketSize)

        // The local neural network consumes a compact, non-identifying feature vector only.
        return AcousticFeatureVector(
            loudness = loudness,
            highFrequencyRatio = highFrequencyRatio,
            spectralFlux = spectralFlux,
            crestFactor = crestFactor,
            lowFrequencyRatio = lowFrequencyRatio,
            silenceRatio = silenceRatio,
            envelopeVariation = envelopeVariation,
            motionEnergy = motion.energy.coerceIn(0f, 1f),
            motionJerk = motion.jerk.coerceIn(0f, 1f),
        )
    }

    private fun normalizedVariation(values: FloatArray, sampleCount: Int, bucketSize: Int): Float {
        val actualBuckets = ((sampleCount + bucketSize - 1) / bucketSize).coerceIn(1, values.size)
        if (actualBuckets < 2) return 0f
        val mean = values.take(actualBuckets).average().toFloat().coerceAtLeast(EPSILON)
        var variance = 0f
        for (index in 0 until actualBuckets) {
            val average = values[index] / bucketSize
            val delta = average - mean
            variance += delta * delta
        }
        val standardDeviation = sqrt(variance / actualBuckets)
        return (standardDeviation / (mean + EPSILON)).coerceIn(0f, 1f)
    }

    private companion object {
        const val PCM_SCALE = 32768f
        const val ENVELOPE_BUCKETS = 16
        const val SILENCE_THRESHOLD = 0.008f
        const val MIN_SIGNAL = 1e-6f
        const val EPSILON = 1e-4f
        const val LOUDNESS_OFFSET = 80f
        const val LOUDNESS_RANGE = 80f
        const val FLUX_SCALE = 0.25f
        const val CREST_SCALE = 4f
        const val LOW_FREQUENCY_DISCOUNT = 0.8f
    }
}
