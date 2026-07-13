package com.fluidvoice.remote

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
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
    private lateinit var collapsedBackground: EdgeAttachmentDrawable
    private lateinit var expandedBackground: EdgeAttachmentDrawable
    private lateinit var recorder: CompressedAudioRecorder
    private val captureModeButtons = mutableListOf<View>()
    private val client = RemoteClient()
    private val handler = Handler(Looper.getMainLooper())
    private var state: OverlayState = OverlayState.Idle
    private var idleX = 0
    private var idleY = 0
    private var preconnectThread: Thread? = null
    private var amplitudeWindowStartedAt = 0L
    private var amplitudeMinimum = Int.MAX_VALUE
    private var amplitudeMaximum = 0
    private var inputFieldContext: InputFieldContext? = null
    private var attachmentEdge = OverlayEdge.None
    private var attachmentProgress = 0f
    private var attachmentAnimator: ValueAnimator? = null
    private var refreshOverlayWhenIdle = false

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
        when (intent?.action) {
            ACTION_STOP -> {
                RemotePreferences.setOverlayEnabled(this, false)
                stopSelf()
            }
            ACTION_REFRESH -> refreshOverlayButtons()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        attachmentAnimator?.cancel()
        if (::root.isInitialized) windowManager.removeView(root)
        if (state is OverlayState.Recording) runCatching { recorder.stop() }
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::root.isInitialized) return
        root.post {
            if (root.isAttachedToWindow) repositionAfterViewportChange()
        }
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        captureModeButtons.clear()
        root = FrameLayout(this)
        collapsedButton = buildCollapsedButton()
        expandedPanel = buildExpandedPanel()
        root.addView(collapsedButton)
        root.addView(expandedPanel)
        val viewport = currentViewportSize()
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = viewport.width - dp(COLLAPSED_PANEL_WIDTH_DP + OVERLAY_EDGE_MARGIN_DP)
            y = dp(OVERLAY_EDGE_MARGIN_DP)
        }
        idleX = layoutParams.x
        idleY = layoutParams.y
        windowManager.addView(root, layoutParams)
        renderState()
        root.post {
            if (root.isAttachedToWindow) anchorPositionToTopRight()
        }
    }

    private fun buildCollapsedButton(): LinearLayout = LinearLayout(this).apply {
        val modes = overlayModes()
        val height = collapsedPanelHeight(modes.size)
        layoutParams = FrameLayout.LayoutParams(dp(COLLAPSED_PANEL_WIDTH_DP), dp(height))
        visibility = if (modes.isEmpty()) View.GONE else View.VISIBLE
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipToPadding = false
        clipChildren = false
        setPadding(
            dp(COLLAPSED_BUTTON_GAP_DP),
            dp(COLLAPSED_BUTTON_GAP_DP + ATTACHMENT_SHOULDER_DP),
            dp(COLLAPSED_BUTTON_GAP_DP),
            dp(COLLAPSED_BUTTON_GAP_DP + ATTACHMENT_SHOULDER_DP),
        )
        collapsedBackground = attachmentBackground(Color.BLACK, COLLAPSED_BUTTON_GAP_DP)
        background = collapsedBackground
        contentDescription = getString(R.string.overlay_idle)
        modes.forEachIndexed { index, mode ->
            addView(
                captureModeButton(mode),
                LinearLayout.LayoutParams(dp(CAPTURE_BUTTON_SIZE_DP), dp(CAPTURE_BUTTON_SIZE_DP)).apply {
                    if (index < modes.lastIndex) bottomMargin = dp(COLLAPSED_BUTTON_SPACING_DP)
                },
            )
        }
    }

    private fun captureModeButton(mode: OverlayPromptMode): FrameLayout = FrameLayout(this).apply {
        background = roundedBackground(colorFor(mode), CAPTURE_BUTTON_SIZE_DP / 2f)
        contentDescription = mode.title
        isClickable = true
        isFocusable = true
        addView(ImageView(context).apply {
            setImageResource(iconFor(mode))
            scaleType = ImageView.ScaleType.CENTER
        }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        setOnClickListener { beginRecording(mode) }
        installDragGesture(this)
        captureModeButtons += this
    }

    private fun buildExpandedPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6 + ATTACHMENT_SHOULDER_DP), dp(8), dp(6 + ATTACHMENT_SHOULDER_DP))
        expandedBackground = attachmentBackground(Color.BLACK)
        background = expandedBackground
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

    private fun beginRecording(mode: OverlayPromptMode) {
        if (state != OverlayState.Idle) return
        val next = OverlayReducer.reduce(state, OverlayAction.Start(mode))
        inputFieldContext = if (mode.kind == RemotePromptKind.Dictation) {
            FluidAccessibilityService.captureTarget(includeContext = !mode.isWithoutAI)
        } else {
            null
        }
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
                if (mode.kind == RemotePromptKind.Dictation) FluidAccessibilityService.clearCapturedTarget()
                inputFieldContext = null
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
        inputFieldContext = null
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
        updateNotification(if (recording.mode.isSmartNote) "Saving note" else "Transcribing")
        val connection = CredentialStore(this).load()
        if (connection == null) {
            showError("Pair with your Mac first")
            return
        }
        val mode = recording.mode
        val enhance = !mode.isWithoutAI
        val context = inputFieldContext.takeIf { mode.kind == RemotePromptKind.Dictation && enhance }
        val preparation = preconnectThread.also { preconnectThread = null }
        thread(name = "fluidvoice-overlay-capture") {
            preparation?.join(PRECONNECT_WAIT_MS)
            runCatching {
                when (mode.kind) {
                    RemotePromptKind.Dictation -> OverlayResult.Dictation(client.dictate(
                        connection,
                        audio,
                        enhance,
                        context,
                        if (mode.isDictationWithoutAI) null else mode.id,
                    ))
                    RemotePromptKind.SmartNote -> OverlayResult.Note(client.captureNote(
                        connection,
                        audio,
                        enhance,
                        mode.id,
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
                inputFieldContext = null
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
                openNote(result.capture.note.id)
                state = OverlayReducer.reduce(state, OverlayAction.Complete)
                renderState()
                updateNotification("Ready to capture")
            }
        }
    }

    private fun deliverText(text: String) {
        val inserted = FluidAccessibilityService.insertText(text)
        inputFieldContext = null
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
        inputFieldContext = null
        state = if (state is OverlayState.Processing) {
            OverlayReducer.reduce(state, OverlayAction.Fail(message))
        } else {
            OverlayState.Error(message)
        }
        renderState()
        updateNotification("Action needed")
    }

    private fun refreshOverlayButtons() {
        if (state != OverlayState.Idle) {
            refreshOverlayWhenIdle = true
            return
        }
        if (!::root.isInitialized) return
        refreshOverlayWhenIdle = false
        runCatching { windowManager.removeView(root) }
        showOverlay()
    }

    private fun openNote(noteId: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_NOTE)
                .putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun overlayModes(): List<OverlayPromptMode> {
        val prompts = RemotePreferences.cachedPrompts(this).ifEmpty { RemotePrompt.fallbackPrompts }
        return visibleOverlayPromptModes(prompts, RemotePreferences.hiddenOverlayPromptIds(this))
    }

    private fun collapsedPanelHeight(): Int = collapsedPanelHeight(overlayModes().size)

    private fun collapsedPanelHeight(modeCount: Int): Int =
        COLLAPSED_VERTICAL_PADDING_DP +
            (modeCount * CAPTURE_BUTTON_SIZE_DP) +
            (maxOf(0, modeCount - 1) * COLLAPSED_BUTTON_SPACING_DP)

    private fun iconFor(mode: OverlayPromptMode): Int = when (mode.icon) {
        "waveform" -> R.drawable.ic_dictation
        "waveform-sparkles" -> R.drawable.ic_dictation_sparkles
        "document" -> R.drawable.ic_note
        "document-sparkles" -> R.drawable.ic_note_sparkles
        "list" -> R.drawable.ic_list
        else -> if (mode.kind == RemotePromptKind.Dictation) R.drawable.ic_dictation_sparkles else R.drawable.ic_note_sparkles
    }

    private fun colorFor(mode: OverlayPromptMode): Int = when {
        mode.icon == "list" -> 0xff2e8b68.toInt()
        mode.kind == RemotePromptKind.Dictation -> 0xff344363.toInt()
        else -> 0xffa8462c.toInt()
    }

    private fun descriptionFor(mode: OverlayPromptMode): Int = when {
        mode.icon == "list" -> R.string.overlay_start_shopping_list
        mode.kind == RemotePromptKind.Dictation -> R.string.overlay_start_dictation
        else -> R.string.overlay_start_note
    }

    private fun renderState() {
        val idle = state == OverlayState.Idle
        val recording = state as? OverlayState.Recording
        val processing = state as? OverlayState.Processing
        applyPosition(idle)
        collapsedButton.visibility = if (idle && captureModeButtons.isNotEmpty()) View.VISIBLE else View.GONE
        expandedPanel.visibility = if (idle) View.GONE else View.VISIBLE
        modeIcon.visibility = if (recording != null || processing != null) View.VISIBLE else View.GONE
        val mode = recording?.mode ?: processing?.mode
        if (mode != null) {
            modeIcon.setImageResource(iconFor(mode))
            modeIcon.background = roundedBackground(colorFor(mode), 18f)
        }
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
        confirmButton.contentDescription = if (recording?.mode?.isSmartNote == true) {
            getString(R.string.overlay_confirm_note)
        } else {
            getString(R.string.overlay_confirm)
        }
        root.animate().cancel()
        root.translationX = 0f
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
        scheduleMeasuredPosition()
        if (idle && refreshOverlayWhenIdle) {
            root.post {
                if (state == OverlayState.Idle) refreshOverlayButtons()
            }
        }
    }

    private fun installDragGesture(view: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var startedEdge = OverlayEdge.None
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    root.animate().cancel()
                    root.translationX = 0f
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    dragging = false
                    startedEdge = attachmentEdge.takeIf { attachmentProgress >= 0.99f } ?: OverlayEdge.None
                    attachmentAnimator?.cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        val viewport = currentViewportSize()
                        val position = clampOverlayPosition(
                            x = startX + dx.toInt(),
                            y = startY + dy.toInt(),
                            overlayWidth = root.width,
                            overlayHeight = root.height,
                            viewportWidth = viewport.width,
                            viewportHeight = viewport.height,
                            edgeMargin = dp(OVERLAY_EDGE_MARGIN_DP),
                        )
                        layoutParams.x = position.x
                        layoutParams.y = position.y
                        idleX = layoutParams.x
                        idleY = layoutParams.y
                        setAttachment(
                            edgeAttachment(
                                x = layoutParams.x,
                                overlayWidth = root.width,
                                viewportWidth = viewport.width,
                                transitionDistance = dp(ATTACHMENT_DISTANCE_DP),
                                canAttachLeft = viewport.canAttachLeft,
                                canAttachRight = viewport.canAttachRight,
                            ),
                        )
                        windowManager.updateViewLayout(root, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        target.performClick()
                    } else {
                        finishDrag(target, startedEdge)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) finishDrag(target, startedEdge)
                    true
                }
                else -> false
            }
        }
    }

    private fun repositionAfterViewportChange() {
        val viewport = currentViewportSize()
        val requestedX = when (attachmentEdge) {
            OverlayEdge.Left -> Int.MIN_VALUE
            OverlayEdge.Right -> Int.MAX_VALUE
            OverlayEdge.None -> idleX
        }
        val idlePosition = clampOverlayPosition(
            x = requestedX,
            y = idleY,
            overlayWidth = dp(COLLAPSED_PANEL_WIDTH_DP),
            overlayHeight = dp(collapsedPanelHeight()),
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            edgeMargin = dp(OVERLAY_EDGE_MARGIN_DP),
        )
        idleX = idlePosition.x
        idleY = idlePosition.y
        applyPosition(state == OverlayState.Idle)
        windowManager.updateViewLayout(root, layoutParams)
        scheduleMeasuredPosition()
    }

    private fun anchorPositionToTopRight() {
        val viewport = currentViewportSize()
        val idlePosition = topRightOverlayPosition(
            overlayWidth = dp(COLLAPSED_PANEL_WIDTH_DP),
            overlayHeight = dp(collapsedPanelHeight()),
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            edgeMargin = dp(OVERLAY_EDGE_MARGIN_DP),
        )
        idleX = idlePosition.x
        idleY = idlePosition.y
        applyPosition(state == OverlayState.Idle)
        windowManager.updateViewLayout(root, layoutParams)
        scheduleMeasuredPosition()
    }

    private fun applyPosition(idle: Boolean) {
        val viewport = currentViewportSize()
        val position = clampOverlayPosition(
            x = idleX,
            y = idleY,
            overlayWidth = dp(if (idle) COLLAPSED_PANEL_WIDTH_DP else EXPANDED_PANEL_WIDTH_DP),
            overlayHeight = dp(if (idle) collapsedPanelHeight() else EXPANDED_PANEL_HEIGHT_DP),
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            edgeMargin = dp(OVERLAY_EDGE_MARGIN_DP),
        )
        layoutParams.x = position.x
        layoutParams.y = position.y
    }

    private fun applyMeasuredPosition() {
        if (root.width <= 0 || root.height <= 0) return
        val viewport = currentViewportSize()
        val position = clampOverlayPosition(
            x = idleX,
            y = idleY,
            overlayWidth = root.width,
            overlayHeight = root.height,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
        )
        if (layoutParams.x != position.x || layoutParams.y != position.y) {
            layoutParams.x = position.x
            layoutParams.y = position.y
            windowManager.updateViewLayout(root, layoutParams)
        }
        val target = edgeAttachment(
            x = position.x,
            overlayWidth = root.width,
            viewportWidth = viewport.width,
            transitionDistance = dp(ATTACHMENT_DISTANCE_DP),
            canAttachLeft = viewport.canAttachLeft,
            canAttachRight = viewport.canAttachRight,
        )
        animateAttachmentTo(target)
    }

    private fun scheduleMeasuredPosition() {
        root.post {
            if (root.isAttachedToWindow) applyMeasuredPosition()
        }
    }

    private fun finishDrag(target: View, startedEdge: OverlayEdge) {
        val viewport = currentViewportSize()
        val maxX = (viewport.width - root.width).coerceAtLeast(0)
        val attachment = edgeAttachment(
            x = layoutParams.x,
            overlayWidth = root.width,
            viewportWidth = viewport.width,
            transitionDistance = dp(ATTACHMENT_DISTANCE_DP),
            canAttachLeft = viewport.canAttachLeft,
            canAttachRight = viewport.canAttachRight,
        )
        if (attachment.edge == OverlayEdge.None) {
            animateAttachmentTo(EdgeAttachment(OverlayEdge.None, 0f))
            return
        }

        val previousX = layoutParams.x
        val attachedX = if (attachment.edge == OverlayEdge.Left) 0 else maxX
        layoutParams.x = attachedX
        idleX = attachedX
        windowManager.updateViewLayout(root, layoutParams)
        animateAttachmentTo(EdgeAttachment(attachment.edge, 1f))
        if (startedEdge != attachment.edge) target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (animationsEnabled() && previousX != attachedX) {
            root.translationX = (previousX - attachedX).toFloat()
            root.animate()
                .translationX(0f)
                .setDuration(ATTACHMENT_ANIMATION_MS)
                .setInterpolator(ATTACHMENT_EASING)
                .start()
        } else {
            root.translationX = 0f
        }
    }

    private fun animateAttachmentTo(target: EdgeAttachment) {
        attachmentAnimator?.cancel()
        if (target.edge != OverlayEdge.None && target.edge != attachmentEdge) {
            attachmentEdge = target.edge
        }
        if (!animationsEnabled() || attachmentProgress == target.progress) {
            setAttachment(target)
            return
        }
        attachmentAnimator = ValueAnimator.ofFloat(attachmentProgress, target.progress).apply {
            duration = ATTACHMENT_ANIMATION_MS
            interpolator = ATTACHMENT_EASING
            addUpdateListener {
                val progress = it.animatedValue as Float
                val edge = if (progress == 0f && target.edge == OverlayEdge.None) {
                    OverlayEdge.None
                } else {
                    attachmentEdge
                }
                setAttachment(EdgeAttachment(edge, progress))
            }
            start()
        }
    }

    private fun setAttachment(attachment: EdgeAttachment) {
        if (attachment.edge != OverlayEdge.None) attachmentEdge = attachment.edge
        attachmentProgress = attachment.progress.coerceIn(0f, 1f)
        if (attachmentProgress == 0f && attachment.edge == OverlayEdge.None) attachmentEdge = OverlayEdge.None
        if (::collapsedBackground.isInitialized) updateAttachmentDrawable(collapsedBackground)
        if (::expandedBackground.isInitialized) updateAttachmentDrawable(expandedBackground)
        val buttonLayout = edgeButtonLayout(
            edge = attachmentEdge,
            progress = attachmentProgress,
            buttonWidth = dp(CAPTURE_BUTTON_SIZE_DP),
            edgeGap = dp(COLLAPSED_BUTTON_GAP_DP),
        )
        captureModeButtons.forEach { button ->
            if (button.layoutParams.width != buttonLayout.width) {
                button.layoutParams = button.layoutParams.apply { width = buttonLayout.width }
            }
            button.translationX = buttonLayout.translationX
        }
    }

    private fun updateAttachmentDrawable(drawable: EdgeAttachmentDrawable?) {
        drawable ?: return
        drawable.attachmentSide = attachmentEdge
        drawable.attachmentProgress = attachmentProgress
    }

    @Suppress("DEPRECATION")
    private fun currentViewportSize(): ViewportSize =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            ViewportSize(
                width = (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(0),
                height = (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(0),
                canAttachLeft = insets.left == 0,
                canAttachRight = insets.right == 0,
            )
        } else {
            val bounds = Rect()
            windowManager.defaultDisplay.getRectSize(bounds)
            val cutout = windowManager.defaultDisplay.cutout
            ViewportSize(
                width = bounds.width(),
                height = bounds.height(),
                canAttachLeft = cutout?.safeInsetLeft.orZero() == 0,
                canAttachRight = cutout?.safeInsetRight.orZero() == 0,
            )
        }

    private fun Int?.orZero(): Int = this ?: 0

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

    private fun attachmentBackground(color: Int, innerInsetDp: Int = 0) = EdgeAttachmentDrawable(
        color = color,
        cornerRadius = dp(DETACHED_RADIUS_DP).toFloat(),
        shoulderDepth = dp(ATTACHMENT_SHOULDER_DP).toFloat(),
        innerInset = dp(innerInsetDp).toFloat(),
        shadowRadius = dp(OVERLAY_SHADOW_RADIUS_DP).toFloat(),
        shadowOffsetY = dp(OVERLAY_SHADOW_OFFSET_Y_DP).toFloat(),
        shadowColor = OVERLAY_SHADOW_COLOR,
    ).apply {
        attachmentSide = this@OverlayService.attachmentEdge
        attachmentProgress = this@OverlayService.attachmentProgress
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

    private data class ViewportSize(
        val width: Int,
        val height: Int,
        val canAttachLeft: Boolean,
        val canAttachRight: Boolean,
    )

    companion object {
        const val ACTION_STOP = "com.fluidvoice.remote.STOP_OVERLAY"
        const val ACTION_REFRESH = "com.fluidvoice.remote.REFRESH_OVERLAY"
        private const val CHANNEL_ID = "fluidvoice-overlay"
        private const val NOTIFICATION_ID = 7301
        private const val PRECONNECT_WAIT_MS = 250L
        private const val TAG = "FluidVoiceRemote"
        private const val COLLAPSED_PANEL_WIDTH_DP = 60
        private const val COLLAPSED_VERTICAL_PADDING_DP = 32
        private const val COLLAPSED_BUTTON_SPACING_DP = 6
        private const val CAPTURE_BUTTON_SIZE_DP = 48
        private const val EXPANDED_PANEL_WIDTH_DP = 294
        private const val EXPANDED_PANEL_HEIGHT_DP = 84
        private const val OVERLAY_EDGE_MARGIN_DP = 0
        private const val ATTACHMENT_DISTANCE_DP = 24
        private const val ATTACHMENT_SHOULDER_DP = 10
        private const val COLLAPSED_BUTTON_GAP_DP = 6
        private const val DETACHED_RADIUS_DP = 30
        private const val OVERLAY_SHADOW_RADIUS_DP = 4
        private const val OVERLAY_SHADOW_OFFSET_Y_DP = 2
        private const val OVERLAY_SHADOW_COLOR = 0x52000000
        private const val ATTACHMENT_ANIMATION_MS = 200L
        private val ATTACHMENT_EASING = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        @Volatile
        var isRunning = false
            private set
    }
}
