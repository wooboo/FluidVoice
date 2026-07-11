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

    fun isOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(OVERLAY_ENABLED_KEY, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(OVERLAY_ENABLED_KEY, enabled)
            .apply()
    }

    private const val FILE_NAME = "remote_preferences"
    private const val AI_ENHANCEMENT_KEY = "ai_enhancement"
    private const val OVERLAY_ENABLED_KEY = "overlay_enabled"
}
