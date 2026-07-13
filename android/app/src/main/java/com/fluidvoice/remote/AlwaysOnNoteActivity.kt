package com.fluidvoice.remote

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BurnInOffset(val x: Int, val y: Int)

fun alwaysOnBurnInOffset(cycle: Long): BurnInOffset = BURN_IN_OFFSETS[(cycle % BURN_IN_OFFSETS.size).toInt()]

private val BURN_IN_OFFSETS = listOf(
    BurnInOffset(-6, -8),
    BurnInOffset(2, -12),
    BurnInOffset(8, -5),
    BurnInOffset(5, 5),
    BurnInOffset(-2, 11),
    BurnInOffset(-8, 6),
    BurnInOffset(-3, -3),
    BurnInOffset(6, -10),
    BurnInOffset(8, 3),
    BurnInOffset(1, 12),
    BurnInOffset(-7, 8),
    BurnInOffset(-8, -1),
)

class AlwaysOnNoteActivity : ComponentActivity() {
    private lateinit var pinnedNoteStore: PinnedNoteStore
    private var note by mutableStateOf<SmartNote?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinnedNoteStore = PinnedNoteStore(this)
        note = pinnedNoteStore.load()
        if (note == null) {
            finish()
            return
        }
        configureAlwaysOnWindow()
        setContent {
            FluidVoiceTheme {
                note?.let { pinnedNote ->
                    AlwaysOnNoteScreen(
                        note = pinnedNote,
                        close = ::finish,
                        remove = {
                            pinnedNoteStore.clear()
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::pinnedNoteStore.isInitialized) note = pinnedNoteStore.load()
    }

    private fun configureAlwaysOnWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.08f }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
}

@Composable
private fun AlwaysOnNoteScreen(note: SmartNote, close: () -> Unit, remove: () -> Unit) {
    var epochMinute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    var controlsVisible by remember { mutableStateOf(true) }
    val interactionSource = remember { MutableInteractionSource() }
    val offset = alwaysOnBurnInOffset(epochMinute)

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            epochMinute = System.currentTimeMillis() / 60_000L
        }
    }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(7_000L)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { controlsVisible = !controlsVisible },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = offset.x.dp, y = offset.y.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 30.dp, vertical = 36.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = ALWAYS_ON_TIME.format(Instant.ofEpochMilli(epochMinute * 60_000L)),
                        color = AodPrimaryText,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = ALWAYS_ON_DATE.format(Instant.ofEpochMilli(epochMinute * 60_000L)),
                        color = AodMutedText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = note.category ?: "Smart Note",
                    color = AodAccent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(42.dp))
            Text(
                text = note.title,
                color = AodPrimaryText,
                fontFamily = FontFamily.Serif,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(24.dp))
            MarkdownText(
                markdown = note.body,
                modifier = Modifier.fillMaxWidth(),
                textColor = AodBodyText,
                linkColor = AodAccent,
                textSizeSp = 18f,
            )
            Spacer(Modifier.height(120.dp))
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AodAction(Icons.Rounded.Close, "Close", close)
                Text("Screen stays awake", color = AodMutedText, style = MaterialTheme.typography.bodyMedium)
                AodAction(Icons.Rounded.RemoveCircleOutline, "Remove", remove)
            }
        }
    }
}

@Composable
private fun AodAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = action, shape = CircleShape, color = Color(0xFF171A19), modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = AodPrimaryText, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = AodMutedText, style = MaterialTheme.typography.labelSmall)
    }
}

private val AodPrimaryText = Color(0xFFD8DDD9)
private val AodBodyText = Color(0xFFBCC3BE)
private val AodMutedText = Color(0xFF7C847F)
private val AodAccent = Color(0xFF69A98D)
private val ALWAYS_ON_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())
private val ALWAYS_ON_DATE = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()).withZone(ZoneId.systemDefault())
