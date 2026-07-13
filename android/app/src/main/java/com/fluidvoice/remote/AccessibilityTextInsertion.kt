package com.fluidvoice.remote

internal data class TextInsertionUpdate(
    val text: String,
    val cursor: Int,
)

internal fun buildTextInsertionUpdate(
    nodeText: CharSequence?,
    isShowingHintText: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
    insertedText: String,
): TextInsertionUpdate {
    val current = if (isShowingHintText) "" else nodeText?.toString().orEmpty()
    val start = selectionStart.takeIf { it in 0..current.length } ?: current.length
    val end = selectionEnd.takeIf { it in start..current.length } ?: start
    return TextInsertionUpdate(
        text = current.substring(0, start) + insertedText + current.substring(end),
        cursor = start + insertedText.length,
    )
}
