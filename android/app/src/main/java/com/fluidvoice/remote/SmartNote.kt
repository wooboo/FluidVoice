package com.fluidvoice.remote

import org.json.JSONObject
import java.time.Instant

data class SmartNote(
    val id: String,
    val createdAt: Instant,
    val title: String,
    val category: String?,
    val tags: List<String>,
    val body: String,
    val isAIEnhanced: Boolean,
    val promptId: String?,
) {
    companion object {
        fun parseList(json: String): List<SmartNote> {
            val notes = JSONObject(json).getJSONArray("notes")
            return List(notes.length()) { index -> parse(notes.getJSONObject(index)) }
        }

        fun parse(json: JSONObject): SmartNote = SmartNote(
            id = json.getString("id"),
            createdAt = Instant.parse(json.getString("createdAt")),
            title = json.getString("title"),
            category = if (json.isNull("category")) null else json.getString("category").takeIf { it.isNotBlank() },
            tags = json.getJSONArray("tags").let { tags ->
                List(tags.length()) { index -> tags.getString(index) }
            },
            body = json.getString("body"),
            isAIEnhanced = json.getBoolean("isAIEnhanced"),
            promptId = if (json.isNull("promptID")) null else json.getString("promptID").takeIf { it.isNotBlank() },
        )
    }
}

fun SmartNote.promptId(): String = promptId
    ?: if (tags.contains("shopping-list")) "shopping-list" else RemotePrompt.DEFAULT_SMART_NOTE_ID

data class SmartNoteCapture(
    val note: SmartNote,
    val rawText: String,
    val enhancementError: String?,
) {
    companion object {
        fun parse(json: String): SmartNoteCapture = JSONObject(json).let { root ->
            SmartNoteCapture(
                note = SmartNote.parse(root.getJSONObject("note")),
                rawText = root.getString("rawText"),
                enhancementError = if (root.isNull("enhancementError")) {
                    null
                } else {
                    root.getString("enhancementError").takeIf { it.isNotBlank() }
                },
            )
        }
    }
}
