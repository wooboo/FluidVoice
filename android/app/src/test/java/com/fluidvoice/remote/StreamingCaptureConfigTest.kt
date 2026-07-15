package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingCaptureConfigTest {
    @Test
    fun `no AI note keeps its prompt identity`() {
        val config = RemotePrompt.fallbackPrompts
            .first { it.id == RemotePrompt.SMART_NOTE_NO_AI_ID }
            .streamingCaptureConfig()

        assertEquals(StreamingCaptureKind.SmartNote, config.kind)
        assertEquals(RemotePrompt.SMART_NOTE_NO_AI_ID, config.promptId)
        assertFalse(config.enhance)
    }

    @Test
    fun `no AI dictation omits the prompt override`() {
        val config = RemotePrompt.fallbackPrompts
            .first { it.id == RemotePrompt.DICTATION_NO_AI_ID }
            .streamingCaptureConfig()

        assertEquals(StreamingCaptureKind.Dictation, config.kind)
        assertNull(config.promptId)
        assertFalse(config.enhance)
    }
}
