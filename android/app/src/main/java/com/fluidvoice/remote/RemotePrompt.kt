package com.fluidvoice.remote

import org.json.JSONObject
import org.json.JSONArray

enum class RemotePromptKind { Dictation, SmartNote }

data class RemotePrompt(
    val id: String,
    val title: String,
    val kind: RemotePromptKind,
    val icon: String,
    val isBuiltIn: Boolean,
) {
    val isWithoutAI: Boolean get() = id == DICTATION_NO_AI_ID || id == SMART_NOTE_NO_AI_ID

    companion object {
        const val DICTATION_NO_AI_ID = "__dictation_no_ai__"
        const val SMART_NOTE_NO_AI_ID = "__smart_note_no_ai__"
        const val DEFAULT_DICTATION_ID = "__dictation_default__"
        const val DEFAULT_SMART_NOTE_ID = "__smart_note_default__"

        val fallbackPrompts = listOf(
            RemotePrompt(DICTATION_NO_AI_ID, "Dictation (no AI)", RemotePromptKind.Dictation, "waveform", true),
            RemotePrompt(DEFAULT_DICTATION_ID, "Default Dictation", RemotePromptKind.Dictation, "waveform-sparkles", true),
            RemotePrompt(SMART_NOTE_NO_AI_ID, "Note (no AI)", RemotePromptKind.SmartNote, "document", true),
            RemotePrompt(DEFAULT_SMART_NOTE_ID, "General Note", RemotePromptKind.SmartNote, "document-sparkles", true),
        )

        fun parseList(json: String): List<RemotePrompt> {
            val prompts = JSONObject(json).getJSONArray("prompts")
            return List(prompts.length()) { index ->
                val prompt = prompts.getJSONObject(index)
                RemotePrompt(
                    id = prompt.getString("id"),
                    title = prompt.getString("title"),
                    kind = when (prompt.getString("kind")) {
                        "smartNote" -> RemotePromptKind.SmartNote
                        else -> RemotePromptKind.Dictation
                    },
                    icon = prompt.optString("icon", "note"),
                    isBuiltIn = prompt.optBoolean("isBuiltIn", false),
                )
            }
        }

        fun encodeList(prompts: List<RemotePrompt>): String = JSONObject()
            .put(
                "prompts",
                JSONArray().apply {
                    prompts.forEach { prompt ->
                        put(
                            JSONObject()
                                .put("id", prompt.id)
                                .put("title", prompt.title)
                                .put("kind", if (prompt.kind == RemotePromptKind.SmartNote) "smartNote" else "dictation")
                                .put("icon", prompt.icon)
                                .put("isBuiltIn", prompt.isBuiltIn),
                        )
                    }
                },
            )
            .toString()
    }
}

data class OverlayPromptMode(
    val id: String,
    val title: String,
    val kind: RemotePromptKind,
    val icon: String,
) {
    val isWithoutAI: Boolean get() = id == RemotePrompt.DICTATION_NO_AI_ID || id == RemotePrompt.SMART_NOTE_NO_AI_ID
    val isDictationWithoutAI: Boolean get() = kind == RemotePromptKind.Dictation && isWithoutAI
    val isSmartNote: Boolean get() = kind == RemotePromptKind.SmartNote
}

internal fun visibleOverlayPromptModes(
    prompts: List<RemotePrompt>,
    hiddenPromptIds: Set<String>,
): List<OverlayPromptMode> = prompts
    .filterNot { hiddenPromptIds.contains(it.id) }
    .map { OverlayPromptMode(it.id, it.title, it.kind, it.icon) }
