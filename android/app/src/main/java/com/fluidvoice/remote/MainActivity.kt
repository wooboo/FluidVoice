package com.fluidvoice.remote

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.util.Log
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var record: Button
    private lateinit var enhancement: Switch
    private lateinit var overlayButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var store: CredentialStore
    private val client = RemoteClient()
    private val recorder by lazy { CompressedAudioRecorder(this) }
    private var isRecording = false
    private var preconnectThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CredentialStore(this)
        setContentView(buildView())
        updateConnectionStatus()
        restoreOverlayIfReady()
    }

    override fun onResume() {
        super.onResume()
        if (::overlayButton.isInitialized) updateOverlayControls()
        restoreOverlayIfReady()
    }

    private fun buildView(): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f }
        result = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.rgb(40, 35, 30))
        }
        val pair = Button(this).apply {
            text = "Scan pairing QR"
            setOnClickListener { scanPairingCode() }
        }
        record = Button(this).apply {
            text = "Start dictation"
            isEnabled = store.load() != null
            setOnClickListener { toggleRecording() }
        }
        enhancement = Switch(this).apply {
            text = "AI enhancement"
            isChecked = RemotePreferences.isAiEnhancementEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                RemotePreferences.setAiEnhancementEnabled(this@MainActivity, enabled)
                updateConnectionStatus()
            }
        }
        accessibilityButton = Button(this).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        overlayButton = Button(this).apply {
            setOnClickListener { toggleOverlay() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding * 2, padding, padding)
            addView(TextView(context).apply { text = "FluidVoice Remote"; textSize = 32f })
            addView(status, ViewGroup.LayoutParams(-1, -2))
            addView(pair, ViewGroup.LayoutParams(-1, -2))
            addView(enhancement, ViewGroup.LayoutParams(-1, -2))
            addView(record, ViewGroup.LayoutParams(-1, -2))
            addView(TextView(context).apply {
                text = "Use FluidVoice inside other apps"
                textSize = 18f
                setPadding(0, padding, 0, 0)
            }, ViewGroup.LayoutParams(-1, -2))
            addView(accessibilityButton, ViewGroup.LayoutParams(-1, -2))
            addView(overlayButton, ViewGroup.LayoutParams(-1, -2))
            addView(result, ViewGroup.LayoutParams(-1, -2))
        }
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
        status.text = "Pairing..."
        thread {
            runCatching {
                val payload = PairingPayload.parse(rawPayload)
                val connection = client.pair(
                    payload,
                    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
                    android.os.Build.MODEL,
                )
                store.save(connection)
            }.onSuccess { runOnUiThread { updateConnectionStatus() } }
                .onFailure { runOnUiThread { showError(it) } }
        }
    }

    private fun toggleRecording() {
        if (!isRecording && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_DIRECT_MICROPHONE)
            return
        }
        if (!isRecording) {
            runCatching { recorder.start() }.onFailure {
                showError(it)
                return
            }
            isRecording = true
            preconnectThread = store.load()?.let { startPreconnect(it, "direct") }
            record.text = "Stop and transcribe"
            status.text = "Listening..."
            return
        }
        isRecording = false
        record.isEnabled = false
        record.text = "Transcribing..."
        val stopStartedAt = SystemClock.elapsedRealtime()
        val audio = runCatching { recorder.stop() }.getOrElse {
            showError(it)
            resetRecordButton()
            return
        }
        Log.i(
            "FluidVoiceRemote",
            "REMOTE_BENCH phase=recording_stopped elapsedMs=${SystemClock.elapsedRealtime() - stopStartedAt} bytes=${audio.size} format=m4a",
        )
        val connection = store.load() ?: return
        val enhance = enhancement.isChecked
        val preparation = preconnectThread.also { preconnectThread = null }
        thread {
            preparation?.join(PRECONNECT_WAIT_MS)
            runCatching { client.dictate(connection, audio, enhance) }
                .onSuccess { text -> runOnUiThread { result.text = text; resetRecordButton() } }
                .onFailure { runOnUiThread { showError(it); resetRecordButton() } }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_DIRECT_MICROPHONE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toggleRecording()
        }
        if (requestCode == REQUEST_OVERLAY_MICROPHONE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startOverlayIfReady()
        }
    }

    private fun updateConnectionStatus() {
        val connected = store.load() != null
        status.text = when {
            !connected -> "Pair with FluidVoice on your Mac"
            enhancement.isChecked -> "Paired - AI enhancement on"
            else -> "Paired - Fast transcription"
        }
        record.isEnabled = connected
        updateOverlayControls()
    }

    private fun resetRecordButton() {
        updateConnectionStatus()
        record.text = "Start dictation"
        record.isEnabled = true
    }

    private fun showError(error: Throwable) {
        status.text = error.message ?: "Operation failed"
    }

    private fun toggleOverlay() {
        if (OverlayService.isRunning) {
            RemotePreferences.setOverlayEnabled(this, false)
            stopService(Intent(this, OverlayService::class.java))
            updateOverlayControls()
            return
        }
        startOverlayIfReady()
    }

    private fun startOverlayIfReady() {
        if (store.load() == null) {
            status.text = "Pair with your Mac before enabling the overlay"
            return
        }
        RemotePreferences.setOverlayEnabled(this, true)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_OVERLAY_MICROPHONE)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        overlayButton.postDelayed({ updateOverlayControls() }, 300)
    }

    private fun updateOverlayControls() {
        if (!::overlayButton.isInitialized) return
        accessibilityButton.text = if (isAccessibilityEnabled()) {
            "Text insertion enabled"
        } else {
            "Enable text insertion"
        }
        overlayButton.text = when {
            OverlayService.isRunning -> "Hide FluidVoice overlay"
            !Settings.canDrawOverlays(this) -> "Allow display over other apps"
            else -> "Show FluidVoice overlay"
        }
        overlayButton.isEnabled = store.load() != null
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name == FluidAccessibilityService::class.java.name }
    }

    private fun restoreOverlayIfReady() {
        if (!RemotePreferences.isOverlayEnabled(this) || OverlayService.isRunning || store.load() == null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!Settings.canDrawOverlays(this)) return
        runCatching { startForegroundService(Intent(this, OverlayService::class.java)) }
            .onFailure { Log.w(TAG, "Unable to restore overlay", it) }
    }

    private fun startPreconnect(connection: CredentialStore.Connection, source: String): Thread =
        thread(name = "fluidvoice-$source-preconnect") {
            runCatching { client.preconnect(connection) }
                .onFailure { Log.i(TAG, "REMOTE_BENCH phase=preconnect_failed source=$source error=${it.message}") }
        }

    companion object {
        private const val REQUEST_DIRECT_MICROPHONE = 10
        private const val REQUEST_OVERLAY_MICROPHONE = 11
        private const val REQUEST_NOTIFICATIONS = 12
        private const val PRECONNECT_WAIT_MS = 250L
        private const val TAG = "FluidVoiceRemote"
    }
}
