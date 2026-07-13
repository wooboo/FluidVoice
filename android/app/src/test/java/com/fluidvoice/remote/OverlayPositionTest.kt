package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionTest {
    @Test
    fun `position is anchored at top right after rotating to portrait`() {
        val position = topRightOverlayPosition(
            overlayWidth = 60,
            overlayHeight = 134,
            viewportWidth = 1080,
            viewportHeight = 2400,
            edgeMargin = 0,
        )

        assertEquals(1020, position.x)
        assertEquals(0, position.y)
    }

    @Test
    fun `position is clamped on both axes when viewport shrinks`() {
        val position = clampOverlayPosition(
            x = 2100,
            y = 900,
            overlayWidth = 300,
            overlayHeight = 120,
            viewportWidth = 1080,
            viewportHeight = 800,
        )

        assertEquals(780, position.x)
        assertEquals(680, position.y)
    }

    @Test
    fun `rotation keeps vertical position within shorter landscape viewport`() {
        val position = rightAnchoredOverlayPosition(
            y = 1900,
            overlayWidth = 60,
            overlayHeight = 134,
            viewportWidth = 2160,
            viewportHeight = 954,
            edgeMargin = 0,
        )

        assertEquals(2100, position.x)
        assertEquals(820, position.y)
    }

    @Test
    fun `attachment progressively increases near either edge`() {
        assertEquals(
            EdgeAttachment(OverlayEdge.None, 0f),
            edgeAttachment(x = 900, overlayWidth = 60, viewportWidth = 1080, transitionDistance = 24),
        )
        assertEquals(
            EdgeAttachment(OverlayEdge.Right, 0.5f),
            edgeAttachment(x = 1008, overlayWidth = 60, viewportWidth = 1080, transitionDistance = 24),
        )
        assertEquals(
            EdgeAttachment(OverlayEdge.Right, 1f),
            edgeAttachment(x = 1020, overlayWidth = 60, viewportWidth = 1080, transitionDistance = 24),
        )
        assertEquals(
            EdgeAttachment(OverlayEdge.Left, 1f),
            edgeAttachment(x = 0, overlayWidth = 60, viewportWidth = 1080, transitionDistance = 24),
        )
    }

    @Test
    fun `overlay does not attach to a safe boundary created by a display cutout`() {
        assertEquals(
            EdgeAttachment(OverlayEdge.None, 0f),
            edgeAttachment(
                x = 1020,
                overlayWidth = 60,
                viewportWidth = 1080,
                transitionDistance = 24,
                canAttachRight = false,
            ),
        )
        assertEquals(
            EdgeAttachment(OverlayEdge.Left, 1f),
            edgeAttachment(
                x = 0,
                overlayWidth = 60,
                viewportWidth = 1080,
                transitionDistance = 24,
                canAttachRight = false,
            ),
        )
    }

    @Test
    fun `attached button consumes only the gap facing the screen edge`() {
        assertEquals(
            EdgeButtonLayout(width = 48, translationX = 6f),
            edgeButtonLayout(
                edge = OverlayEdge.Right,
                progress = 1f,
                buttonWidth = 48,
                edgeGap = 6,
            ),
        )
        assertEquals(
            EdgeButtonLayout(width = 48, translationX = -6f),
            edgeButtonLayout(
                edge = OverlayEdge.Left,
                progress = 1f,
                buttonWidth = 48,
                edgeGap = 6,
            ),
        )
        assertEquals(
            EdgeButtonLayout(width = 48, translationX = 0f),
            edgeButtonLayout(
                edge = OverlayEdge.None,
                progress = 0f,
                buttonWidth = 48,
                edgeGap = 6,
            ),
        )
    }

    @Test
    fun `attached corner arc stays centered on the capture button`() {
        assertEquals(
            30f,
            overlayCornerRadius(
                requestedRadius = 30f,
                bodyWidth = 54f,
                bodyHeight = 114f,
                attached = true,
            ),
        )
    }
}
