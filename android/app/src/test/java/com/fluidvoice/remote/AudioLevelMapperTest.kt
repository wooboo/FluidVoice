package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelMapperTest {
    @Test
    fun `nothing phone silence remains quiet while speech is clearly visible`() {
        val silence = AudioLevelMapper.normalize(26)
        val lowSpeech = AudioLevelMapper.normalize(182)
        val loudSpeech = AudioLevelMapper.normalize(513)

        assertTrue(silence <= 0.12f)
        assertTrue(lowSpeech >= 0.5f)
        assertTrue(loudSpeech >= 0.9f)
        assertTrue(silence < lowSpeech)
        assertTrue(lowSpeech < loudSpeech)
    }

    @Test
    fun `amplitude is clamped to recorder range`() {
        assertEquals(AudioLevelMapper.normalize(0), AudioLevelMapper.normalize(-1), 0.001f)
        assertEquals(1f, AudioLevelMapper.normalize(100_000), 0.001f)
    }
}
