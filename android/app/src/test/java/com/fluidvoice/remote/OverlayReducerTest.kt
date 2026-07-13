package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayReducerTest {
    private val dictation = OverlayPromptMode("dictation", "Dictation", RemotePromptKind.Dictation, "dictation")
    private val smartNote = OverlayPromptMode("note", "Note", RemotePromptKind.SmartNote, "note")

    @Test
    fun `mode button starts its recording and confirmation starts processing`() {
        val recording = OverlayReducer.reduce(OverlayState.Idle, OverlayAction.Start(smartNote))
        val processing = OverlayReducer.reduce(recording, OverlayAction.Confirm)

        assertEquals(OverlayState.Recording(smartNote), recording)
        assertEquals(OverlayState.Processing(smartNote), processing)
    }

    @Test
    fun `rejecting a recording returns to idle`() {
        val state = OverlayReducer.reduce(OverlayState.Recording(dictation), OverlayAction.Reject)

        assertEquals(OverlayState.Idle, state)
    }

    @Test
    fun `successful insertion returns to idle and failure remains visible`() {
        val processing = OverlayState.Processing(dictation)
        assertEquals(OverlayState.Idle, OverlayReducer.reduce(processing, OverlayAction.Complete))
        assertEquals(
            OverlayState.Error("Mac is unavailable"),
            OverlayReducer.reduce(processing, OverlayAction.Fail("Mac is unavailable")),
        )
    }

    @Test
    fun `dismissing an error returns to idle`() {
        assertEquals(
            OverlayState.Idle,
            OverlayReducer.reduce(OverlayState.Error("Network failed"), OverlayAction.Reject),
        )
    }

    @Test
    fun `hiding every prompt leaves the overlay empty instead of restoring fallbacks`() {
        val prompts = RemotePrompt.fallbackPrompts
        val hidden = prompts.mapTo(mutableSetOf()) { it.id }

        assertEquals(emptyList<OverlayPromptMode>(), visibleOverlayPromptModes(prompts, hidden))
    }

    @Test
    fun `visible overlay modes preserve the icon configured by each prompt`() {
        val prompts = listOf(
            RemotePrompt("plain", "Plain", RemotePromptKind.Dictation, "waveform", false),
            RemotePrompt("ai-note", "AI Note", RemotePromptKind.SmartNote, "document-sparkles", false),
        )

        assertEquals(
            listOf("waveform", "document-sparkles"),
            visibleOverlayPromptModes(prompts, emptySet()).map { it.icon },
        )
    }

    @Test
    fun `prompt catalog survives local cache serialization without losing icons`() {
        val prompts = listOf(
            RemotePrompt("plain", "Plain", RemotePromptKind.Dictation, "waveform", true),
            RemotePrompt("shopping", "Shopping", RemotePromptKind.SmartNote, "list", false),
        )

        assertEquals(prompts, RemotePrompt.parseList(RemotePrompt.encodeList(prompts)))
    }
}
