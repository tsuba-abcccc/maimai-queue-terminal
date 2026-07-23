package com.abcccc.maimaiqueue

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

internal const val QUEUE_SOUND_SAMPLE_RATE = 24_000

internal enum class QueueSoundCue {
    CONFIRM,
    QUEUE_CHANGE,
    UNDO,
    CAUTION
}

private data class SoundNote(
    val startMillis: Int,
    val durationMillis: Int,
    val frequencyHz: Double,
    val amplitude: Double
)

internal fun queueSoundSamples(cue: QueueSoundCue): ShortArray {
    val notes = when (cue) {
        QueueSoundCue.CONFIRM -> listOf(
            SoundNote(0, 105, 659.25, .34),
            SoundNote(42, 128, 987.77, .26)
        )
        QueueSoundCue.QUEUE_CHANGE -> listOf(
            SoundNote(0, 105, 587.33, .28),
            SoundNote(48, 138, 880.00, .26),
            SoundNote(96, 130, 1_174.66, .20)
        )
        QueueSoundCue.UNDO -> listOf(
            SoundNote(0, 90, 880.00, .25),
            SoundNote(42, 130, 659.25, .29)
        )
        QueueSoundCue.CAUTION -> listOf(
            SoundNote(0, 100, 659.25, .24),
            SoundNote(52, 145, 523.25, .30)
        )
    }
    val durationMillis = notes.maxOf { it.startMillis + it.durationMillis }
    val sampleCount = durationMillis * QUEUE_SOUND_SAMPLE_RATE / 1_000
    return ShortArray(sampleCount) { index ->
        val absoluteSeconds = index.toDouble() / QUEUE_SOUND_SAMPLE_RATE
        val mixed = notes.sumOf { note -> note.sampleAt(absoluteSeconds) }
        (mixed.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * .72).toInt().toShort()
    }
}

private fun SoundNote.sampleAt(absoluteSeconds: Double): Double {
    val startSeconds = startMillis / 1_000.0
    val durationSeconds = durationMillis / 1_000.0
    val localSeconds = absoluteSeconds - startSeconds
    if (localSeconds !in 0.0..durationSeconds) return 0.0

    val attack = (localSeconds / .008).coerceIn(0.0, 1.0)
    val release = ((durationSeconds - localSeconds) / .050).coerceIn(0.0, 1.0)
    val attackEnvelope = sin(attack * PI / 2.0).let { it * it }
    val releaseEnvelope = sin(release * PI / 2.0).let { it * it }
    val decayEnvelope = exp(-1.15 * localSeconds / durationSeconds)
    val phase = 2.0 * PI * frequencyHz * localSeconds
    val timbre = (sin(phase) + .08 * sin(phase * 2.0 + .22)) / 1.08
    return timbre * amplitude * attackEnvelope * releaseEnvelope * decayEnvelope
}
