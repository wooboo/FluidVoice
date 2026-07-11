package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayReducerTest {
    @Test
    fun `tap starts recording and confirmation starts processing`() {
        val recording = OverlayReducer.reduce(OverlayState.Idle, OverlayAction.Start)
        val processing = OverlayReducer.reduce(recording, OverlayAction.Confirm)

        assertEquals(OverlayState.Recording, recording)
        assertEquals(OverlayState.Processing, processing)
    }

    @Test
    fun `rejecting a recording returns to idle`() {
        val state = OverlayReducer.reduce(OverlayState.Recording, OverlayAction.Reject)

        assertEquals(OverlayState.Idle, state)
    }

    @Test
    fun `successful insertion returns to idle and failure remains visible`() {
        assertEquals(OverlayState.Idle, OverlayReducer.reduce(OverlayState.Processing, OverlayAction.Complete))
        assertEquals(
            OverlayState.Error("Mac is unavailable"),
            OverlayReducer.reduce(OverlayState.Processing, OverlayAction.Fail("Mac is unavailable")),
        )
    }

    @Test
    fun `tapping after an error retries recording`() {
        assertEquals(
            OverlayState.Recording,
            OverlayReducer.reduce(OverlayState.Error("Network failed"), OverlayAction.Start),
        )
    }
}
