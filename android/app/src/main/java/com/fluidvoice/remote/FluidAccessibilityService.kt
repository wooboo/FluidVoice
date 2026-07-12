package com.fluidvoice.remote

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

class FluidAccessibilityService : AccessibilityService() {
    private var lastEditableNode: AccessibilityNodeInfo? = null
    private var capturedEditableNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        activeService = WeakReference(this)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == packageName) return
        val source = event?.source ?: return
        if (source.isEditable) {
            lastEditableNode = source
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService?.get() === this) activeService = null
        lastEditableNode = null
        capturedEditableNode = null
        super.onDestroy()
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val focused = windows
            .asSequence()
            .sortedByDescending { it.layer }
            .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .firstOrNull { it.isEditable }
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused?.takeIf { it.isEditable }
            ?: lastEditableNode?.takeIf { it.refresh() && it.isEditable }
    }

    private fun captureFocusedField(): Boolean {
        capturedEditableNode = findFocusedEditableNode()
        Log.i(TAG, "Insertion target captured=${capturedEditableNode != null}")
        return capturedEditableNode != null
    }

    private fun clearCapturedField() {
        capturedEditableNode = null
    }

    private fun insertIntoCapturedField(text: String): Boolean {
        val target = capturedEditableNode
            ?.takeIf { it.refresh() && it.isEditable }
            ?: run {
                capturedEditableNode = null
                Log.i(TAG, "Text insertion skipped: captured node is unavailable")
                return false
        }
        capturedEditableNode = null
        val update = buildTextInsertionUpdate(
            nodeText = target.text,
            isShowingHintText = target.isShowingHintText,
            selectionStart = target.textSelectionStart,
            selectionEnd = target.textSelectionEnd,
            insertedText = text,
        )
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, update.text)
        }
        val inserted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (inserted) {
            target.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, update.cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, update.cursor)
                },
            )
        }
        Log.i(TAG, "Text insertion result=$inserted package=${target.packageName}")
        return inserted
    }

    companion object {
        @Volatile
        private var activeService: WeakReference<FluidAccessibilityService>? = null

        fun captureTarget(): Boolean = activeService?.get()?.captureFocusedField() == true

        fun clearCapturedTarget() {
            activeService?.get()?.clearCapturedField()
        }

        fun insertText(text: String): Boolean = activeService?.get()?.insertIntoCapturedField(text) == true

        fun isConnected(): Boolean = activeService?.get() != null

        private const val TAG = "FluidVoiceAccessibility"
    }
}
