package com.fluidvoice.remote

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var store: CredentialStore
    private val client = RemoteClient()
    private val recorder by lazy { CompressedAudioRecorder(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var uiState by mutableStateOf(AppUiState())
    private var preconnectThread: Thread? = null
    private var microphonePermissionPurpose: MicrophonePermissionPurpose? = null

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) return@registerForActivityResult
        when (microphonePermissionPurpose.also { microphonePermissionPurpose = null }) {
            MicrophonePermissionPurpose.DirectCapture -> startCapture()
            MicrophonePermissionPurpose.Overlay -> startOverlayIfReady()
            null -> Unit
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val levelSampler = object : Runnable {
        override fun run() {
            if (!uiState.isRecording) return
            uiState = uiState.copy(audioLevel = AudioLevelMapper.normalize(recorder.maxAmplitude()))
            handler.postDelayed(this, 70)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CredentialStore(this)
        refreshSystemState()
        setContent {
            FluidVoiceTheme {
                FluidVoiceApp(
                    state = uiState,
                    actions = AppActions(
                        selectSection = { uiState = uiState.copy(section = it, selectedNote = null) },
                        refreshNotes = ::refreshNotes,
                        openNote = { uiState = uiState.copy(selectedNote = it) },
                        requestDeleteNote = { uiState = uiState.copy(notePendingDeletion = it, noteDeleteError = null) },
                        dismissDeleteNote = {
                            if (!uiState.isDeletingNote) {
                                uiState = uiState.copy(notePendingDeletion = null, noteDeleteError = null)
                            }
                        },
                        confirmDeleteNote = ::deletePendingNote,
                        setCaptureMode = { uiState = uiState.copy(captureMode = it, captureMessage = null) },
                        startCapture = ::startCapture,
                        confirmCapture = ::confirmCapture,
                        rejectCapture = ::rejectCapture,
                        scanPairingCode = ::scanPairingCode,
                        setDictationEnhancement = ::setDictationEnhancement,
                        setNotesEnhancement = ::setNotesEnhancement,
                        toggleOverlay = ::toggleOverlay,
                        openAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    ),
                )
            }
        }
        refreshNotes()
        restoreOverlayIfReady()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) {
            refreshSystemState()
            refreshNotes()
            restoreOverlayIfReady()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (uiState.isRecording) runCatching { recorder.stop() }
        super.onDestroy()
    }

    private fun scanPairingCode() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> pair(barcode.rawValue.orEmpty()) }
            .addOnFailureListener { showError(it) }
    }

    private fun pair(rawPayload: String) {
        uiState = uiState.copy(connectionMessage = "Pairing with your Mac...")
        thread(name = "fluidvoice-pair") {
            runCatching {
                val payload = PairingPayload.parse(rawPayload)
                client.pair(
                    payload,
                    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
                    Build.MODEL,
                ).also(store::save)
            }.onSuccess {
                runOnUiThread {
                    refreshSystemState()
                    refreshNotes()
                    restoreOverlayIfReady()
                }
            }.onFailure { runOnUiThread { showError(it) } }
        }
    }

    private fun refreshNotes() {
        val connection = store.load()
        if (connection == null) {
            uiState = uiState.copy(notes = emptyList(), notesLoading = false, notesError = null)
            return
        }
        uiState = uiState.copy(notesLoading = true, notesError = null)
        thread(name = "fluidvoice-notes-refresh") {
            runCatching { client.listNotes(connection) }
                .onSuccess { notes ->
                    runOnUiThread {
                        uiState = uiState.copy(notes = notes, notesLoading = false, notesError = null)
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        uiState = uiState.copy(notesLoading = false, notesError = error.message ?: "Connection failed")
                    }
                }
        }
    }

    private fun deletePendingNote() {
        val note = uiState.notePendingDeletion ?: return
        val connection = store.load() ?: run {
            uiState = uiState.copy(noteDeleteError = "Pair with your Mac first")
            return
        }
        uiState = uiState.copy(isDeletingNote = true, noteDeleteError = null)
        thread(name = "fluidvoice-note-delete") {
            runCatching { client.deleteNote(connection, note.id) }
                .onSuccess {
                    runOnUiThread {
                        uiState = uiState.copy(
                            notes = uiState.notes.filterNot { it.id == note.id },
                            selectedNote = null,
                            notePendingDeletion = null,
                            isDeletingNote = false,
                            noteDeleteError = null,
                        )
                        refreshNotes()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        uiState = uiState.copy(
                            isDeletingNote = false,
                            noteDeleteError = error.message ?: "Unable to delete note",
                        )
                    }
                }
        }
    }

    private fun startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionPurpose = MicrophonePermissionPurpose.DirectCapture
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        runCatching { recorder.start() }
            .onSuccess {
                uiState = uiState.copy(isRecording = true, audioLevel = 0f, captureMessage = null)
                preconnectThread = store.load()?.let { startPreconnect(it, "capture") }
                handler.post(levelSampler)
            }
            .onFailure(::showError)
    }

    private fun rejectCapture() {
        if (!uiState.isRecording) return
        handler.removeCallbacks(levelSampler)
        runCatching { recorder.stop() }
        preconnectThread = null
        uiState = uiState.copy(isRecording = false, audioLevel = 0f, captureMessage = "Recording discarded")
    }

    private fun confirmCapture() {
        if (!uiState.isRecording) return
        handler.removeCallbacks(levelSampler)
        val audio = runCatching { recorder.stop() }.getOrElse {
            showError(it)
            return
        }
        val connection = store.load() ?: run {
            showError(IllegalStateException("Pair with your Mac first"))
            return
        }
        val mode = uiState.captureMode
        val preparation = preconnectThread.also { preconnectThread = null }
        uiState = uiState.copy(isRecording = false, isProcessing = true, audioLevel = 0f, captureMessage = null)
        Log.i(TAG, "REMOTE_BENCH phase=capture_stopped bytes=${audio.size} format=m4a mode=$mode")
        thread(name = "fluidvoice-capture") {
            preparation?.join(PRECONNECT_WAIT_MS)
            runCatching {
                when (mode) {
                    CaptureMode.Dictation -> CaptureResult.Dictation(
                        client.dictate(connection, audio, RemotePreferences.isAiEnhancementEnabled(this)),
                    )
                    CaptureMode.SmartNote -> CaptureResult.Note(
                        client.captureNote(connection, audio, RemotePreferences.isSmartNotesEnhancementEnabled(this)),
                    )
                }
            }.onSuccess { result -> runOnUiThread { finishCapture(result) } }
                .onFailure { error -> runOnUiThread { showError(error) } }
        }
    }

    private fun finishCapture(result: CaptureResult) {
        when (result) {
            is CaptureResult.Dictation -> {
                uiState = uiState.copy(isProcessing = false, captureMessage = result.text)
            }
            is CaptureResult.Note -> {
                result.capture.enhancementError?.let { error ->
                    Toast.makeText(
                        this,
                        "Note saved without AI: ${error.take(160)}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                uiState = uiState.copy(
                    isProcessing = false,
                    section = AppSection.Notes,
                    captureMessage = null,
                    selectedNote = result.capture.note,
                )
                refreshNotes()
            }
        }
    }

    private fun setDictationEnhancement(enabled: Boolean) {
        RemotePreferences.setAiEnhancementEnabled(this, enabled)
        uiState = uiState.copy(dictationEnhancement = enabled)
    }

    private fun setNotesEnhancement(enabled: Boolean) {
        RemotePreferences.setSmartNotesEnhancementEnabled(this, enabled)
        uiState = uiState.copy(notesEnhancement = enabled)
    }

    private fun toggleOverlay() {
        if (OverlayService.isRunning) {
            RemotePreferences.setOverlayEnabled(this, false)
            stopService(Intent(this, OverlayService::class.java))
            handler.postDelayed(::refreshSystemState, 200)
            return
        }
        startOverlayIfReady()
    }

    private fun startOverlayIfReady() {
        if (store.load() == null) {
            showError(IllegalStateException("Pair with your Mac before enabling the overlay"))
            return
        }
        RemotePreferences.setOverlayEnabled(this, true)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionPurpose = MicrophonePermissionPurpose.Overlay
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        handler.postDelayed(::refreshSystemState, 300)
    }

    private fun refreshSystemState() {
        val paired = store.load() != null
        uiState = uiState.copy(
            isPaired = paired,
            connectionMessage = if (paired) "Ready on your local network" else "Pair with FluidVoice on your Mac",
            dictationEnhancement = RemotePreferences.isAiEnhancementEnabled(this),
            notesEnhancement = RemotePreferences.isSmartNotesEnhancementEnabled(this),
            overlayRunning = OverlayService.isRunning,
            canDrawOverlays = Settings.canDrawOverlays(this),
            accessibilityEnabled = isAccessibilityEnabled(),
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == FluidAccessibilityService::class.java.name
            }
    }

    private fun restoreOverlayIfReady() {
        if (!RemotePreferences.isOverlayEnabled(this) || OverlayService.isRunning || store.load() == null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!Settings.canDrawOverlays(this)) return
        runCatching { startForegroundService(Intent(this, OverlayService::class.java)) }
            .onSuccess { handler.postDelayed(::refreshSystemState, 300) }
            .onFailure { Log.w(TAG, "Unable to restore overlay", it) }
    }

    private fun startPreconnect(connection: CredentialStore.Connection, source: String): Thread =
        thread(name = "fluidvoice-$source-preconnect") {
            runCatching { client.preconnect(connection) }
                .onFailure { Log.i(TAG, "REMOTE_BENCH phase=preconnect_failed source=$source error=${it.message}") }
        }

    private fun showError(error: Throwable) {
        uiState = uiState.copy(
            isRecording = false,
            isProcessing = false,
            audioLevel = 0f,
            captureMessage = "Error: ${error.message ?: "Operation failed"}",
            connectionMessage = error.message ?: uiState.connectionMessage,
        )
    }

    private sealed interface CaptureResult {
        data class Dictation(val text: String) : CaptureResult
        data class Note(val capture: SmartNoteCapture) : CaptureResult
    }

    private enum class MicrophonePermissionPurpose { DirectCapture, Overlay }

    companion object {
        private const val PRECONNECT_WAIT_MS = 250L
        private const val TAG = "FluidVoiceRemote"
    }
}
