package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayReducerTest {
    @Test
    fun `mode button starts its recording and confirmation starts processing`() {
        val recording = OverlayReducer.reduce(OverlayState.Idle, OverlayAction.Start(CaptureMode.SmartNote))
        val processing = OverlayReducer.reduce(recording, OverlayAction.Confirm)

        assertEquals(OverlayState.Recording(CaptureMode.SmartNote), recording)
        assertEquals(OverlayState.Processing(CaptureMode.SmartNote), processing)
    }

    @Test
    fun `rejecting a recording returns to idle`() {
        val state = OverlayReducer.reduce(OverlayState.Recording(CaptureMode.Dictation), OverlayAction.Reject)

        assertEquals(OverlayState.Idle, state)
    }

    @Test
    fun `successful insertion returns to idle and failure remains visible`() {
        val processing = OverlayState.Processing(CaptureMode.Dictation)
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
}
