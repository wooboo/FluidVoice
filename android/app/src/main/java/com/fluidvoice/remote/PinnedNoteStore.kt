package com.fluidvoice.remote

import android.content.Context

class PinnedNoteStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): SmartNote? = preferences.getString(NOTE_KEY, null)
        ?.let { encoded -> runCatching { SmartNote.parse(org.json.JSONObject(encoded)) }.getOrNull() }

    fun save(note: SmartNote) {
        preferences.edit().putString(NOTE_KEY, SmartNote.encode(note).toString()).apply()
    }

    fun updateIfPinned(note: SmartNote) {
        if (load()?.id == note.id) save(note)
    }

    fun reconcile(notes: List<SmartNote>): String? {
        val pinnedNote = load() ?: return null
        notes.firstOrNull { it.id == pinnedNote.id }?.let(::save)
        return pinnedNote.id
    }

    fun clearIfPinned(noteId: String): Boolean {
        if (load()?.id != noteId) return false
        clear()
        return true
    }

    fun pinnedNoteId(): String? = load()?.id

    fun clear() {
        preferences.edit().remove(NOTE_KEY).apply()
    }

    companion object {
        private const val FILE_NAME = "always_on_note"
        private const val NOTE_KEY = "note_snapshot"
    }
}
