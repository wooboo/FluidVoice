package com.fluidvoice.remote

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var record: Button
    private lateinit var store: CredentialStore
    private val client = RemoteClient()
    private val recorder = WavRecorder()
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CredentialStore(this)
        setContentView(buildView())
        updateConnectionStatus()
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
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding * 2, padding, padding)
            addView(TextView(context).apply { text = "FluidVoice Remote"; textSize = 32f })
            addView(status, ViewGroup.LayoutParams(-1, -2))
            addView(pair, ViewGroup.LayoutParams(-1, -2))
            addView(record, ViewGroup.LayoutParams(-1, -2))
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
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        if (!isRecording) {
            recorder.start()
            isRecording = true
            record.text = "Stop and transcribe"
            status.text = "Listening..."
            return
        }
        isRecording = false
        record.isEnabled = false
        record.text = "Transcribing..."
        val wav = recorder.stop()
        val connection = store.load() ?: return
        thread {
            runCatching { client.dictate(connection, wav) }
                .onSuccess { text -> runOnUiThread { result.text = text; resetRecordButton() } }
                .onFailure { runOnUiThread { showError(it); resetRecordButton() } }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleRecording()
    }

    private fun updateConnectionStatus() {
        val connected = store.load() != null
        status.text = if (connected) "Paired with desktop" else "Pair with FluidVoice on your Mac"
        record.isEnabled = connected
    }

    private fun resetRecordButton() {
        status.text = "Paired with desktop"
        record.text = "Start dictation"
        record.isEnabled = true
    }

    private fun showError(error: Throwable) {
        status.text = error.message ?: "Operation failed"
    }
}
