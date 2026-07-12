package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityTextInsertionTest {
    @Test
    fun `visible hint is not treated as editable text`() {
        val update = buildTextInsertionUpdate(
            nodeText = "Email address",
            isShowingHintText = true,
            selectionStart = -1,
            selectionEnd = -1,
            insertedText = "Hello",
        )

        assertEquals(TextInsertionUpdate(text = "Hello", cursor = 5), update)
    }

    @Test
    fun `insertion preserves existing editable text`() {
        val update = buildTextInsertionUpdate(
            nodeText = "Hello ",
            isShowingHintText = false,
            selectionStart = 6,
            selectionEnd = 6,
            insertedText = "world",
        )

        assertEquals(TextInsertionUpdate(text = "Hello world", cursor = 11), update)
    }

    @Test
    fun `insertion replaces selected editable text`() {
        val update = buildTextInsertionUpdate(
            nodeText = "Hello there",
            isShowingHintText = false,
            selectionStart = 6,
            selectionEnd = 11,
            insertedText = "world",
        )

        assertEquals(TextInsertionUpdate(text = "Hello world", cursor = 11), update)
    }
}
