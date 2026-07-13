package com.fluidvoice.remote

enum class CaptureMode {
    Dictation,
    SmartNote,
    ShoppingList,
}

fun CaptureMode.isSmartNoteMode(): Boolean = this == CaptureMode.SmartNote || this == CaptureMode.ShoppingList

fun CaptureMode.notePromptId(): String = when (this) {
    CaptureMode.ShoppingList -> "shopping-list"
    CaptureMode.SmartNote -> "default"
    CaptureMode.Dictation -> "default"
}
