package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SmartNoteTest {
    @Test
    fun `parses notes list`() {
        val notes = SmartNote.parseList(
            """{"notes":[{"id":"note-1","createdAt":"2026-07-11T10:30:00Z","title":"Release plan","category":"Work","tags":["release","android"],"body":"Ship it.","isAIEnhanced":true}]}""",
        )

        assertEquals(1, notes.size)
        assertEquals("Release plan", notes.single().title)
        assertEquals(Instant.parse("2026-07-11T10:30:00Z"), notes.single().createdAt)
        assertEquals(listOf("release", "android"), notes.single().tags)
        assertTrue(notes.single().isAIEnhanced)
    }

    @Test
    fun `parses raw note capture without optional values`() {
        val capture = SmartNoteCapture.parse(
            """{"note":{"id":"note-1","createdAt":"2026-07-11T10:30:00Z","title":"Quick thought","category":null,"tags":[],"body":"Quick thought","isAIEnhanced":false},"rawText":"Quick thought","enhancementError":null}""",
        )

        assertEquals("Quick thought", capture.rawText)
        assertNull(capture.note.category)
        assertNull(capture.enhancementError)
    }
}
