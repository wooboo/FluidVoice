package com.fluidvoice.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var collapsedButton: LinearLayout
    private lateinit var expandedPanel: LinearLayout
    private lateinit var modeIcon: ImageView
    private lateinit var waveform: WaveformView
    private lateinit var progress: ProgressBar
    private lateinit var errorLabel: TextView
    private lateinit var confirmButton: ImageButton
    private lateinit var rejectButton: ImageButton
    private lateinit var recorder: CompressedAudioRecorder
    private val client = RemoteClient()
    private val handler = Handler(Looper.getMainLooper())
    private var state: OverlayState = OverlayState.Idle
    private var idleX = 0
    private var idleY = 0
    private var preconnectThread: Thread? = null
    private var amplitudeWindowStartedAt = 0L
    private var amplitudeMinimum = Int.MAX_VALUE
    private var amplitudeMaximum = 0

    private val amplitudeSampler = object : Runnable {
        override fun run() {
            if (state !is OverlayState.Recording) return
            val amplitude = recorder.maxAmplitude()
            waveform.pushAmplitude(amplitude)
            amplitudeMinimum = minOf(amplitudeMinimum, amplitude)
            amplitudeMaximum = maxOf(amplitudeMaximum, amplitude)
            val now = SystemClock.elapsedRealtime()
            if (now - amplitudeWindowStartedAt >= 1_000) {
                Log.i(
                    TAG,
                    "AUDIO_LEVEL min=$amplitudeMinimum max=$amplitudeMaximum normalizedMax=${AudioLevelMapper.normalize(amplitudeMaximum)}",
                )
                resetAmplitudeWindow(now)
            }
            handler.postDelayed(this, 70)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        recorder = CompressedAudioRecorder(this)
        startForeground(NOTIFICATION_ID, notification("Ready to capture"))
        if (Settings.canDrawOverlays(this)) showOverlay() else stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RemotePreferences.setOverlayEnabled(this, false)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::root.isInitialized) windowManager.removeView(root)
        if (state is OverlayState.Recording) runCatching { recorder.stop() }
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        root = FrameLayout(this)
        collapsedButton = buildCollapsedButton()
        expandedPanel = buildExpandedPanel()
        root.addView(collapsedButton)
        root.addView(expandedPanel)
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(COLLAPSED_PANEL_WIDTH_DP + OVERLAY_EDGE_MARGIN_DP)
            y = (resources.displayMetrics.heightPixels - dp(COLLAPSED_PANEL_HEIGHT_DP)) / 2
        }
        idleX = layoutParams.x
        idleY = layoutParams.y
        windowManager.addView(root, layoutParams)
        renderState()
    }

    private fun buildCollapsedButton(): LinearLayout = LinearLayout(this).apply {
        layoutParams = FrameLayout.LayoutParams(dp(COLLAPSED_PANEL_WIDTH_DP), dp(COLLAPSED_PANEL_HEIGHT_DP))
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = roundedBackground(0xff151d32.toInt(), 30f)
        contentDescription = getString(R.string.overlay_idle)
        addView(captureModeButton(CaptureMode.Dictation), LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            bottomMargin = dp(6)
        })
        addView(captureModeButton(CaptureMode.SmartNote), LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun captureModeButton(mode: CaptureMode): FrameLayout = FrameLayout(this).apply {
        val isNote = mode == CaptureMode.SmartNote
        background = roundedBackground(if (isNote) 0xffa8462c.toInt() else 0xff344363.toInt(), 25f)
        contentDescription = getString(if (isNote) R.string.overlay_start_note else R.string.overlay_start_dictation)
        isClickable = true
        isFocusable = true
        addView(ImageView(context).apply {
            setImageResource(if (isNote) R.drawable.ic_note else R.drawable.ic_dictation)
            scaleType = ImageView.ScaleType.CENTER
        }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        setOnClickListener { beginRecording(mode) }
        installDragGesture(this)
    }

    private fun buildExpandedPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(8), dp(6))
        background = roundedBackground(0xff151d32.toInt(), 30f)
        contentDescription = getString(R.string.overlay_recording)

        modeIcon = ImageView(context).apply {
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = roundedBackground(0xff344363.toInt(), 18f)
        }
        addView(modeIcon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) })

        waveform = WaveformView(context)
        addView(waveform, LinearLayout.LayoutParams(dp(116), dp(52)).apply { marginEnd = dp(8) })

        progress = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        addView(progress, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })

        errorLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            visibility = View.GONE
        }
        addView(errorLabel, LinearLayout.LayoutParams(dp(120), WindowManager.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })

        rejectButton = actionButton(R.drawable.ic_reject, R.string.overlay_reject, 0xffa93643.toInt()) {
            rejectRecording()
        }
        addView(rejectButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) })

        confirmButton = actionButton(R.drawable.ic_confirm, R.string.overlay_confirm, 0xff2e8b68.toInt()) {
            confirmRecording()
        }
        addView(confirmButton, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun actionButton(icon: Int, description: Int, color: Int, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            background = roundedBackground(color, 24f)
            contentDescription = getString(description)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }

    private fun beginRecording(mode: CaptureMode) {
        if (state != OverlayState.Idle) return
        val next = OverlayReducer.reduce(state, OverlayAction.Start(mode))
        if (mode == CaptureMode.Dictation) FluidAccessibilityService.captureTarget()
        runCatching { recorder.start() }
            .onSuccess {
                state = next
                resetAmplitudeWindow(SystemClock.elapsedRealtime())
                preconnectThread = CredentialStore(this).load()?.let { startPreconnect(it) }
                collapsedButton.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                renderState()
                handler.post(amplitudeSampler)
                updateNotification("Listening")
            }
            .onFailure {
                if (mode == CaptureMode.Dictation) FluidAccessibilityService.clearCapturedTarget()
                showError(it.message ?: "Microphone is unavailable")
            }
    }

    private fun rejectRecording() {
        if (state is OverlayState.Error) {
            state = OverlayReducer.reduce(state, OverlayAction.Reject)
            renderState()
            updateNotification("Ready to capture")
            return
        }
        if (state !is OverlayState.Recording) return
        handler.removeCallbacks(amplitudeSampler)
        runCatching { recorder.stop() }
        preconnectThread = null
        FluidAccessibilityService.clearCapturedTarget()
        state = OverlayReducer.reduce(state, OverlayAction.Reject)
        renderState()
        updateNotification("Ready to dictate")
    }

    private fun confirmRecording() {
        val recording = state as? OverlayState.Recording ?: return
        handler.removeCallbacks(amplitudeSampler)
        val audio = runCatching { recorder.stop() }.getOrElse {
            showError(it.message ?: "Recording was too short")
            return
        }
        state = OverlayReducer.reduce(state, OverlayAction.Confirm)
        renderState()
        updateNotification(if (recording.mode == CaptureMode.SmartNote) "Saving note" else "Transcribing")
        val connection = CredentialStore(this).load()
        if (connection == null) {
            showError("Pair with your Mac first")
            return
        }
        val mode = recording.mode
        val preparation = preconnectThread.also { preconnectThread = null }
        thread(name = "fluidvoice-overlay-capture") {
            preparation?.join(PRECONNECT_WAIT_MS)
            runCatching {
                when (mode) {
                    CaptureMode.Dictation -> OverlayResult.Dictation(client.dictate(
                        connection,
                        audio,
                        RemotePreferences.isAiEnhancementEnabled(this),
                    ))
                    CaptureMode.SmartNote -> OverlayResult.Note(client.captureNote(
                        connection,
                        audio,
                        RemotePreferences.isSmartNotesEnhancementEnabled(this),
                    ))
                }
            }
                .onSuccess { result -> handler.post { deliverResult(result) } }
                .onFailure { error -> handler.post { showError(error.message ?: "Transcription failed") } }
        }
    }

    private fun deliverResult(result: OverlayResult) {
        when (result) {
            is OverlayResult.Dictation -> deliverText(result.text)
            is OverlayResult.Note -> {
                FluidAccessibilityService.clearCapturedTarget()
                val enhancementError = result.capture.enhancementError
                val message = if (enhancementError == null) {
                    "Saved: ${result.capture.note.title}"
                } else {
                    "Note saved without AI: ${enhancementError.take(160)}"
                }
                Toast.makeText(
                    this,
                    message,
                    if (enhancementError == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
                state = OverlayReducer.reduce(state, OverlayAction.Complete)
                renderState()
                updateNotification("Ready to capture")
            }
        }
    }

    private fun deliverText(text: String) {
        val inserted = FluidAccessibilityService.insertText(text)
        Log.i("FluidVoiceRemote", "OVERLAY_INSERT inserted=$inserted chars=${text.length}")
        if (!inserted) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("FluidVoice transcription", text))
            Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        state = OverlayReducer.reduce(state, OverlayAction.Complete)
        renderState()
        updateNotification("Ready to dictate")
    }

    private fun showError(message: String) {
        FluidAccessibilityService.clearCapturedTarget()
        state = if (state is OverlayState.Processing) {
            OverlayReducer.reduce(state, OverlayAction.Fail(message))
        } else {
            OverlayState.Error(message)
        }
        renderState()
        updateNotification("Action needed")
    }

    private fun renderState() {
        val idle = state == OverlayState.Idle
        val recording = state as? OverlayState.Recording
        val processing = state as? OverlayState.Processing
        if (idle) {
            layoutParams.x = idleX
            layoutParams.y = idleY
        } else {
            layoutParams.x = minOf(idleX, resources.displayMetrics.widthPixels - dp(310))
            layoutParams.y = idleY
        }
        collapsedButton.visibility = if (idle) View.VISIBLE else View.GONE
        expandedPanel.visibility = if (idle) View.GONE else View.VISIBLE
        modeIcon.visibility = if (recording != null || processing != null) View.VISIBLE else View.GONE
        val mode = recording?.mode ?: processing?.mode
        modeIcon.setImageResource(if (mode == CaptureMode.SmartNote) R.drawable.ic_note else R.drawable.ic_dictation)
        modeIcon.background = roundedBackground(
            if (mode == CaptureMode.SmartNote) 0xffa8462c.toInt() else 0xff344363.toInt(),
            18f,
        )
        waveform.visibility = if (recording != null) View.VISIBLE else View.GONE
        waveform.setRecording(recording != null)
        waveform.setProcessing(processing != null)
        progress.visibility = if (processing != null) View.VISIBLE else View.GONE
        errorLabel.visibility = if (state is OverlayState.Error) View.VISIBLE else View.GONE
        errorLabel.text = (state as? OverlayState.Error)?.message.orEmpty()
        expandedPanel.contentDescription = when (state) {
            is OverlayState.Recording -> getString(R.string.overlay_recording)
            is OverlayState.Processing -> getString(R.string.overlay_processing)
            is OverlayState.Error -> getString(R.string.overlay_error, (state as OverlayState.Error).message)
            OverlayState.Idle -> getString(R.string.overlay_idle)
        }
        rejectButton.visibility = if (processing != null) View.GONE else View.VISIBLE
        confirmButton.visibility = if (recording != null) View.VISIBLE else View.GONE
        confirmButton.contentDescription = if (recording?.mode == CaptureMode.SmartNote) {
            getString(R.string.overlay_confirm_note)
        } else {
            getString(R.string.overlay_confirm)
        }
        root.animate().cancel()
        if (animationsEnabled()) {
            root.alpha = 0.82f
            root.scaleX = 0.96f
            root.scaleY = 0.96f
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        } else {
            root.alpha = 1f
            root.scaleX = 1f
            root.scaleY = 1f
        }
        windowManager.updateViewLayout(root, layoutParams)
    }

    private fun installDragGesture(view: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        val maxX = max(0, resources.displayMetrics.widthPixels - root.width)
                        val maxY = max(0, resources.displayMetrics.heightPixels - root.height)
                        layoutParams.x = (startX + dx.toInt()).coerceIn(0, maxX)
                        layoutParams.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        idleX = layoutParams.x
                        idleY = layoutParams.y
                        windowManager.updateViewLayout(root, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) target.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "FluidVoice overlay", NotificationManager.IMPORTANCE_LOW))
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fluid_mark)
            .setContentTitle("FluidVoice overlay")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop overlay", stopIntent).build())
            .build()
    }

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(message))
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun animationsEnabled(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private fun resetAmplitudeWindow(now: Long) {
        amplitudeWindowStartedAt = now
        amplitudeMinimum = Int.MAX_VALUE
        amplitudeMaximum = 0
    }

    private fun startPreconnect(connection: CredentialStore.Connection): Thread =
        thread(name = "fluidvoice-overlay-preconnect") {
            runCatching { client.preconnect(connection) }
                .onFailure { Log.i(TAG, "REMOTE_BENCH phase=preconnect_failed source=overlay error=${it.message}") }
        }

    private sealed interface OverlayResult {
        data class Dictation(val text: String) : OverlayResult
        data class Note(val capture: SmartNoteCapture) : OverlayResult
    }

    companion object {
        const val ACTION_STOP = "com.fluidvoice.remote.STOP_OVERLAY"
        private const val CHANNEL_ID = "fluidvoice-overlay"
        private const val NOTIFICATION_ID = 7301
        private const val PRECONNECT_WAIT_MS = 250L
        private const val TAG = "FluidVoiceRemote"
        private const val COLLAPSED_PANEL_WIDTH_DP = 60
        private const val COLLAPSED_PANEL_HEIGHT_DP = 114
        private const val OVERLAY_EDGE_MARGIN_DP = 16

        @Volatile
        var isRunning = false
            private set
    }
}
