package com.acousticguard.ml

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Creates the shared PCM input used by all local model adapters. */
object AudioRecordFactory {
    @SuppressLint("MissingPermission")
    fun create(sampleRateHz: Int, requiredInputBufferSize: Int): AudioRecord {
        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minimumBufferBytes > 0) {
            "The microphone does not support $sampleRateHz Hz mono PCM audio."
        }

        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(
                maxOf(minimumBufferBytes, requiredInputBufferSize * BYTES_PER_PCM_SAMPLE),
            )
            .build()
    }

    private const val BYTES_PER_PCM_SAMPLE = 2
}
