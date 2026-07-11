package com.fluidvoice.remote

import android.graphics.Color
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val textColor = FluidInk.toArgb()
    val linkColor = FluidGreen.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextView(viewContext).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                setTextIsSelectable(true)
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                includeFontPadding = false
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 16f
                setLineSpacing(0f, 1.18f)
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.setLinkTextColor(linkColor)
            markwon.setMarkdown(textView, markdown)
        },
    )
}

object MarkdownPreview {
    private val image = Regex("!\\[([^]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val heading = Regex("^#{1,6}\\s+")
    private val listMarker = Regex("^(?:[-+*]|\\d+[.)])\\s+")
    private val quote = Regex("^>\\s?")
    private val decoration = Regex("(?<!\\w)[*_~`]+|[*_~`]+(?!\\w)")
    private val whitespace = Regex("\\s+")

    fun plainText(markdown: String): String = markdown
        .lineSequence()
        .map { line ->
            line.trim()
                .replace(heading, "")
                .replace(listMarker, "")
                .replace(quote, "")
        }
        .joinToString(" ")
        .replace(image, "$1")
        .replace(link, "$1")
        .replace(decoration, "")
        .replace(whitespace, " ")
        .trim()
}
