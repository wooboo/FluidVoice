package com.fluidvoice.remote

import android.content.Context

object RemotePreferences {
    fun isAiEnhancementEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(AI_ENHANCEMENT_KEY, true)

    fun setAiEnhancementEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AI_ENHANCEMENT_KEY, enabled)
            .apply()
    }

    fun isSmartNotesEnhancementEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(SMART_NOTES_ENHANCEMENT_KEY, true)

    fun setSmartNotesEnhancementEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SMART_NOTES_ENHANCEMENT_KEY, enabled)
            .apply()
    }

    fun isOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(OVERLAY_ENABLED_KEY, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(OVERLAY_ENABLED_KEY, enabled)
            .apply()
    }

    fun isGenericNoteOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(OVERLAY_GENERIC_NOTE_KEY, true)

    fun setGenericNoteOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(OVERLAY_GENERIC_NOTE_KEY, enabled)
            .apply()
    }

    fun isShoppingListOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(OVERLAY_SHOPPING_LIST_KEY, true)

    fun setShoppingListOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(OVERLAY_SHOPPING_LIST_KEY, enabled)
            .apply()
    }

    fun hiddenOverlayPromptIds(context: Context): Set<String> =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getStringSet(OVERLAY_HIDDEN_PROMPTS_KEY, emptySet())
            .orEmpty()

    fun setOverlayPromptVisible(context: Context, promptId: String, visible: Boolean) {
        val hidden = hiddenOverlayPromptIds(context).toMutableSet()
        if (visible) hidden.remove(promptId) else hidden.add(promptId)
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(OVERLAY_HIDDEN_PROMPTS_KEY, hidden)
            .apply()
    }

    fun cachedPrompts(context: Context): List<RemotePrompt> {
        val json = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getString(PROMPT_CATALOG_KEY, null)
            ?: return emptyList()
        return runCatching { RemotePrompt.parseList(json) }.getOrDefault(emptyList())
    }

    fun setCachedPrompts(context: Context, prompts: List<RemotePrompt>) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PROMPT_CATALOG_KEY, RemotePrompt.encodeList(prompts))
            .apply()
    }

    private const val FILE_NAME = "remote_preferences"
    private const val AI_ENHANCEMENT_KEY = "ai_enhancement"
    private const val SMART_NOTES_ENHANCEMENT_KEY = "smart_notes_enhancement"
    private const val OVERLAY_ENABLED_KEY = "overlay_enabled"
    private const val OVERLAY_GENERIC_NOTE_KEY = "overlay_generic_note"
    private const val OVERLAY_SHOPPING_LIST_KEY = "overlay_shopping_list"
    private const val OVERLAY_HIDDEN_PROMPTS_KEY = "overlay_hidden_prompts"
    private const val PROMPT_CATALOG_KEY = "prompt_catalog"
}
