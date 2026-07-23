package com.abcccc.maimaiqueue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QueueSoundWaveformsTest {
    @Test
    fun cuesAreShortFadedAndAudibleWithoutClipping() {
        QueueSoundCue.entries.forEach { cue ->
            val samples = queueSoundSamples(cue)
            val durationMillis = samples.size * 1_000L / QUEUE_SOUND_SAMPLE_RATE
            val peak = samples.maxOf { abs(it.toInt()) }

            assertTrue(durationMillis in 120L..260L)
            assertTrue(abs(samples.first().toInt()) <= 2)
            assertTrue(abs(samples.last().toInt()) <= 16)
            assertTrue(peak > 1_000)
            assertTrue(peak < Short.MAX_VALUE)
        }
    }

    @Test
    fun eachCueUsesADistinctWaveform() {
        val waveforms = QueueSoundCue.entries.map(::queueSoundSamples)

        waveforms.indices.forEach { firstIndex ->
            for (secondIndex in firstIndex + 1 until waveforms.size) {
                assertFalse(waveforms[firstIndex].contentEquals(waveforms[secondIndex]))
            }
        }
    }
}
