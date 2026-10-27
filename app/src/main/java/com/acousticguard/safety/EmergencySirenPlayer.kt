package com.acousticguard.safety

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Autonomous synthesized emergency alarm siren that plays through the alarm audio stream.
 *
 * It generates an alternating high-intensity two-tone siren (850 Hz / 1250 Hz) at full volume,
 * requiring no bundled media assets or network connectivity.
 */
object EmergencySirenPlayer {
    private var sirenJob: Job? = null
    private var activeTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @Synchronized
    fun start() {
        if (sirenJob?.isActive == true) return

        sirenJob = scope.launch {
            val sampleRate = 24_000
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(sampleRate / 4)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            activeTrack = track
            runCatching { track.play() }

            val chunkSize = 2400 // 100 ms chunks
            val pcm = ShortArray(chunkSize)
            var phase = 0.0

            try {
                var chunkIndex = 0
                while (isActive) {
                    val freq = if ((chunkIndex / 5) % 2 == 0) 850.0 else 1250.0 // Alternates tone every 500ms
                    val delta = 2.0 * PI * freq / sampleRate

                    for (i in 0 until chunkSize) {
                        pcm[i] = (sin(phase) * 32000.0).toInt().toShort()
                        phase += delta
                        if (phase >= 2.0 * PI) phase -= 2.0 * PI
                    }

                    track.write(pcm, 0, chunkSize)
                    chunkIndex++
                }
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
                activeTrack = null
            }
        }
    }

    @Synchronized
    fun stop() {
        sirenJob?.cancel()
        sirenJob = null
        activeTrack?.let { track ->
            runCatching {
                track.stop()
                track.release()
            }
        }
        activeTrack = null
    }

    val isPlaying: Boolean
        get() = sirenJob?.isActive == true
}
