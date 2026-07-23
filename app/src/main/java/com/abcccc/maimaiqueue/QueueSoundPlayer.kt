package com.abcccc.maimaiqueue

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class QueueSoundPlayer : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "maimai-q-sound").apply { isDaemon = true }
    }
    private val currentPlayback = AtomicReference<Future<*>?>(null)
    private val samples = QueueSoundCue.entries.associateWith(::queueSoundSamples)

    fun play(cue: QueueSoundCue) {
        if (closed.get()) return
        val waveform = samples.getValue(cue)
        val playback = executor.submit { playWaveform(waveform) }
        currentPlayback.getAndSet(playback)?.cancel(true)
    }

    private fun playWaveform(waveform: ShortArray) {
        if (closed.get() || Thread.currentThread().isInterrupted) return
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(QUEUE_SOUND_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(waveform.size * Short.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.setVolume(.20f)
            val written = track.write(waveform, 0, waveform.size, AudioTrack.WRITE_BLOCKING)
            if (written <= 0 || Thread.currentThread().isInterrupted) return
            track.play()
            Thread.sleep(waveform.size * 1_000L / QUEUE_SOUND_SAMPLE_RATE + 24L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: IllegalArgumentException) {
            // Unsupported audio configurations should never block queue operations.
        } catch (_: IllegalStateException) {
            // Audio output may be unavailable while the device is changing routes.
        } finally {
            track?.let { audioTrack ->
                runCatching {
                    if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.stop()
                }
                audioTrack.release()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        currentPlayback.getAndSet(null)?.cancel(true)
        executor.shutdownNow()
    }
}
