package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPreviewTest {
    @Test
    fun `removes markdown syntax from note preview`() {
        val markdown = """
            ## Launch **checklist**
            - Confirm the [build](https://example.com)
            - Notify `support`
        """.trimIndent()

        assertEquals(
            "Launch checklist Confirm the build Notify support",
            MarkdownPreview.plainText(markdown),
        )
    }
}
