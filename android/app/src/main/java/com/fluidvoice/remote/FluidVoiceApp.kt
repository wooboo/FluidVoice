package com.fluidvoice.remote

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sin

enum class AppSection { Notes, Capture, Settings }

data class AppUiState(
    val section: AppSection = AppSection.Notes,
    val isPaired: Boolean = false,
    val connectionMessage: String = "Pair with FluidVoice on your Mac",
    val notes: List<SmartNote> = emptyList(),
    val notesLoading: Boolean = false,
    val notesError: String? = null,
    val selectedNote: SmartNote? = null,
    val notesTagFilter: String? = null,
    val notesTagQuery: String = "",
    val prompts: List<RemotePrompt> = RemotePrompt.fallbackPrompts,
    val selectedPromptId: String = RemotePrompt.DICTATION_NO_AI_ID,
    val hiddenOverlayPromptIds: Set<String> = emptySet(),
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val audioLevel: Float = 0f,
    val captureMessage: String? = null,
    val overlayRunning: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notePendingDeletion: SmartNote? = null,
    val isDeletingNote: Boolean = false,
    val noteDeleteError: String? = null,
)

data class AppActions(
    val selectSection: (AppSection) -> Unit,
    val refreshNotes: () -> Unit,
    val openNote: (SmartNote?) -> Unit,
    val requestDeleteNote: (SmartNote) -> Unit,
    val setNotesTagFilter: (String?) -> Unit,
    val setNotesTagQuery: (String) -> Unit,
    val dismissDeleteNote: () -> Unit,
    val confirmDeleteNote: () -> Unit,
    val selectPrompt: (String) -> Unit,
    val startCapture: () -> Unit,
    val confirmCapture: () -> Unit,
    val rejectCapture: () -> Unit,
    val startNoteConversation: () -> Unit,
    val confirmNoteConversation: () -> Unit,
    val rejectNoteConversation: () -> Unit,
    val scanPairingCode: () -> Unit,
    val setOverlayPromptVisible: (String, Boolean) -> Unit,
    val toggleOverlay: () -> Unit,
    val openAccessibilitySettings: () -> Unit,
)

@Composable
fun FluidVoiceApp(state: AppUiState, actions: AppActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                AppSection.entries.forEach { section ->
                    val icon = when (section) {
                        AppSection.Notes -> Icons.AutoMirrored.Rounded.Notes
                        AppSection.Capture -> Icons.Rounded.Mic
                        AppSection.Settings -> Icons.Rounded.Settings
                    }
                    NavigationBarItem(
                        selected = state.section == section,
                        onClick = { actions.selectSection(section) },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(section.name) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (state.section) {
                AppSection.Notes -> NotesScreen(state, actions)
                AppSection.Capture -> CaptureScreen(state, actions)
                AppSection.Settings -> SettingsScreen(state, actions)
            }
        }
    }
    state.notePendingDeletion?.let { note ->
        DeleteNoteDialog(note, state.isDeletingNote, state.noteDeleteError, actions)
    }
}

@Composable
private fun DeleteNoteDialog(note: SmartNote, deleting: Boolean, error: String?, actions: AppActions) {
    AlertDialog(
        onDismissRequest = { if (!deleting) actions.dismissDeleteNote() },
        title = { Text("Delete this note?") },
        text = {
            Column {
                Text("\"${note.title}\" will be permanently removed from your Mac.")
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = actions.dismissDeleteNote, enabled = !deleting) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(
                onClick = actions.confirmDeleteNote,
                enabled = !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (deleting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (deleting) "Deleting" else "Delete")
            }
        },
    )
}

@Composable
private fun NotesScreen(state: AppUiState, actions: AppActions) {
    AnimatedContent(targetState = state.selectedNote, label = "note-detail") { note ->
        if (note != null) {
            Box(Modifier.fillMaxSize()) {
                NoteDetail(
                    note = note,
                    onBack = { actions.openNote(null) },
                    onDelete = { actions.requestDeleteNote(note) },
                )
                NoteConversationFloatingControl(
                    state = state,
                    actions = actions,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 0.dp, bottom = 20.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ScreenHeader(
                    eyebrow = connectionLabel(state),
                    title = "Smart Notes",
                    action = {
                        IconButton(onClick = actions.refreshNotes, enabled = state.isPaired && !state.notesLoading) {
                            if (state.notesLoading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh notes")
                            }
                        }
                    },
                )
                when {
                    !state.isPaired -> PairingPrompt(actions.scanPairingCode)
                    state.notesLoading && state.notes.isEmpty() -> NotesLoading()
                    state.notesError != null && state.notes.isEmpty() -> ErrorState(state.notesError, actions.refreshNotes)
                    state.notes.isEmpty() -> EmptyNotes { actions.selectSection(AppSection.Capture) }
                    else -> {
                        val filteredNotes = state.notes.filteredByTag(state.notesTagFilter)
                        NotesTagFilters(
                            tags = state.notes.allTags(),
                            selectedTag = state.notesTagFilter,
                            query = state.notesTagQuery,
                            setTag = actions.setNotesTagFilter,
                            setQuery = actions.setNotesTagQuery,
                        )
                        if (filteredNotes.isEmpty()) {
                            ErrorState("No notes match this tag.", { actions.setNotesTagFilter(null) })
                        } else {
                            NotesList(filteredNotes, actions.openNote)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesTagFilters(
    tags: List<String>,
    selectedTag: String?,
    query: String,
    setTag: (String?) -> Unit,
    setQuery: (String) -> Unit,
) {
    if (tags.isEmpty()) return
    val normalizedQuery = query.trim()
    val visibleTags = tags
        .filter { normalizedQuery.isEmpty() || it.contains(normalizedQuery, ignoreCase = true) }
        .take(8)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (selectedTag != null || query.isNotBlank()) {
                    TextButton(onClick = {
                        setTag(null)
                        setQuery("")
                    }) { Text("Clear") }
                }
            },
            label = { Text("Search tags") },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TagChip("All", selectedTag == null) {
                setTag(null)
                setQuery("")
            }
            visibleTags.take(3).forEach { tag ->
                TagChip("#$tag", selectedTag == tag) { setTag(tag) }
            }
        }
        if (visibleTags.size > 3) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                visibleTags.drop(3).take(4).forEach { tag ->
                    TagChip("#$tag", selectedTag == tag) { setTag(tag) }
                }
            }
        }
    }
}

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ScreenHeader(eyebrow: String, title: String, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = eyebrow.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        action?.invoke()
    }
}

@Composable
private fun PairingPrompt(onPair: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painterResource(R.drawable.ic_fluid_mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(18.dp).size(36.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Your notes live on your Mac.", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Pair once to capture, organize and read Smart Notes over your local network.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPair) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Scan pairing QR")
        }
    }
}

@Composable
private fun NotesLoading() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        repeat(4) { index ->
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(if (index % 2 == 0) 0.68f else 0.82f).height(18.dp),
                ) {}
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                ) {}
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyNotes(openCapture: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Icon(Icons.Rounded.EditNote, contentDescription = null, tint = FluidOrange, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(20.dp))
        Text("A quiet place for spoken thoughts.", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Choose Note from the overlay or record here. FluidVoice saves the transcript first, then organizes it if AI is enabled.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = openCapture) { Text("Record a Smart Note") }
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Notes are unavailable", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = retry) { Text("Try again") }
    }
}

@Composable
private fun NotesList(notes: List<SmartNote>, openNote: (SmartNote) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        items(notes, key = { it.id }) { note ->
            NoteRow(note, openNote)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f))
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun NoteRow(note: SmartNote, openNote: (SmartNote) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable { openNote(note) }.padding(vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.category ?: formatDate(note),
                style = MaterialTheme.typography.labelLarge,
                color = if (note.category != null) FluidOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(formatDate(note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(7.dp))
        Text(note.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(7.dp))
        Text(
            MarkdownPreview.plainText(note.body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (note.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(note.tags.take(4).joinToString("  ") { "#$it" }, style = MaterialTheme.typography.bodyMedium, color = FluidGreen)
        }
    }
}

@Composable
private fun NoteDetail(note: SmartNote, onBack: () -> Unit, onDelete: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Back") }
                Spacer(Modifier.weight(1f))
                if (note.isAIEnhanced) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = FluidGreen, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Organized", style = MaterialTheme.typography.labelLarge, color = FluidGreen)
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete note",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(note.category?.uppercase(Locale.getDefault()) ?: "SMART NOTE", style = MaterialTheme.typography.labelLarge, color = FluidOrange)
            Spacer(Modifier.height(8.dp))
            Text(note.title, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(10.dp))
            Text(formatDate(note), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (note.tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(note.tags.joinToString("  ") { "#$it" }, color = FluidGreen)
            }
            HorizontalDivider(Modifier.padding(vertical = 24.dp))
            MarkdownText(note.body, Modifier.fillMaxWidth())
            Spacer(Modifier.height(132.dp))
        }
    }
}

@Composable
private fun NoteConversationFloatingControl(state: AppUiState, actions: AppActions, modifier: Modifier = Modifier) {
    if (!state.isRecording && !state.isProcessing) {
        Surface(
            onClick = actions.startNoteConversation,
            enabled = state.isPaired,
            color = if (state.isPaired) FluidOrange else MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            shadowElevation = if (state.isPaired) 8.dp else 0.dp,
            modifier = modifier.size(58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, contentDescription = "Continue note by voice", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    } else {
        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 8.dp,
            modifier = modifier,
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.isProcessing) {
                    Box(Modifier.width(116.dp).height(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                    }
                } else {
                    VoiceWaveform(state.audioLevel, Modifier.width(116.dp).height(48.dp).padding(horizontal = 8.dp))
                    CaptureAction(Icons.Rounded.Close, "Discard", Color(0xFF9D3740), actions.rejectNoteConversation)
                    CaptureAction(Icons.Rounded.Check, "Finish", FluidGreen, actions.confirmNoteConversation)
                }
            }
        }
    }
}

@Composable
private fun CaptureScreen(state: AppUiState, actions: AppActions) {
    val selectedPrompt = state.prompts.firstOrNull { it.id == state.selectedPromptId }
        ?: RemotePrompt.fallbackPrompts.first()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(eyebrow = connectionLabel(state), title = "Capture")
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PromptPicker(state.prompts, state.selectedPromptId, state.isRecording || state.isProcessing, actions.selectPrompt)
            Spacer(Modifier.weight(0.8f))
            Text(
                if (selectedPrompt.kind == RemotePromptKind.Dictation) "Speak to type" else "Speak to remember",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.isRecording) "Listening on this phone" else if (state.isProcessing) "Working on your Mac" else promptDescription(selectedPrompt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            CaptureControl(state, actions)
            AnimatedVisibility(state.captureMessage != null) {
                Text(
                    state.captureMessage.orEmpty(),
                    modifier = Modifier.padding(top = 24.dp),
                    color = if (state.captureMessage?.startsWith("Error") == true) MaterialTheme.colorScheme.error else FluidGreen,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!state.isPaired) {
                OutlinedButton(onClick = actions.scanPairingCode, modifier = Modifier.padding(bottom = 24.dp)) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pair to capture")
                }
            }
        }
    }
}

@Composable
private fun PromptPicker(prompts: List<RemotePrompt>, selectedPromptId: String, disabled: Boolean, selectPrompt: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            "DICTATION" to prompts.filter { it.kind == RemotePromptKind.Dictation },
            "SMART NOTES" to prompts.filter { it.kind == RemotePromptKind.SmartNote },
        ).forEach { (label, group) ->
            if (group.isEmpty()) return@forEach
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.forEach { prompt ->
                    val selected = selectedPromptId == prompt.id
            Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.clickable(enabled = !disabled) { selectPrompt(prompt.id) },
            ) {
                Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                                painterResource(promptIconResource(prompt)),
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                            Text(prompt.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureControl(state: AppUiState, actions: AppActions) {
    when {
        state.isProcessing -> Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(116.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(42.dp), strokeWidth = 3.dp)
            }
        }
        state.isRecording -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VoiceWaveform(state.audioLevel, Modifier.fillMaxWidth().height(74.dp))
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CaptureAction(Icons.Rounded.Close, "Discard", Color(0xFF9D3740), actions.rejectCapture)
                CaptureAction(Icons.Rounded.Check, "Finish", FluidGreen, actions.confirmCapture)
            }
        }
        else -> Surface(
            onClick = actions.startCapture,
            enabled = state.isPaired,
            shape = CircleShape,
            color = if (state.isPaired) FluidOrange else MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = if (state.isPaired) 10.dp else 0.dp,
            modifier = Modifier.size(116.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, contentDescription = "Start recording", tint = Color.White, modifier = Modifier.size(42.dp))
            }
        }
    }
}

@Composable
private fun CaptureAction(icon: ImageVector, label: String, color: Color, action: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = action, shape = CircleShape, color = color, modifier = Modifier.size(62.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = Color.White) }
        }
        Spacer(Modifier.height(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun VoiceWaveform(level: Float, modifier: Modifier = Modifier) {
    val animatedLevel by animateFloatAsState(level.coerceIn(0f, 1f), label = "audio-level")
    Canvas(modifier) {
        val bars = 17
        val spacing = size.width / bars
        repeat(bars) { index ->
            val envelope = 0.35f + 0.65f * sin((index + 1) * 0.55f).let { kotlin.math.abs(it) }
            val barHeight = size.height * (0.13f + animatedLevel * 0.87f * envelope)
            drawLine(
                color = FluidOrange,
                start = Offset(spacing * (index + 0.5f), (size.height - barHeight) / 2),
                end = Offset(spacing * (index + 0.5f), (size.height + barHeight) / 2),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, actions: AppActions) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader(eyebrow = "Android companion", title = "Settings") }
        item {
            SettingsSection("CONNECTION") {
                SettingsActionRow(
                    icon = Icons.Rounded.QrCodeScanner,
                    title = if (state.isPaired) "Paired with Mac" else "Pair with Mac",
                    detail = state.connectionMessage,
                    actionLabel = if (state.isPaired) "Replace" else "Scan",
                    onAction = actions.scanPairingCode,
                )
            }
        }
        item {
            SettingsSection("OVERLAY") {
                SettingsActionRow(
                    icon = Icons.Rounded.Tune,
                    title = if (state.overlayRunning) "Overlay is visible" else "FluidVoice overlay",
                    detail = when {
                        state.overlayRunning -> "Tap waveform to dictate or the note icon to save a thought."
                        !state.canDrawOverlays -> "Display-over-apps permission is required."
                        else -> "Keep capture one tap away in every app."
                    },
                    actionLabel = if (state.overlayRunning) "Hide" else "Show",
                    onAction = actions.toggleOverlay,
                )
                HorizontalDivider(Modifier.padding(start = 52.dp))
                state.prompts.forEach { prompt ->
                    SettingsSwitchRow(
                        iconResource = promptIconResource(prompt),
                        title = prompt.title,
                        detail = if (prompt.kind == RemotePromptKind.Dictation) "Dictation prompt" else "Smart Note prompt",
                        checked = !state.hiddenOverlayPromptIds.contains(prompt.id),
                        onChecked = { visible -> actions.setOverlayPromptVisible(prompt.id, visible) },
                    )
                    HorizontalDivider(Modifier.padding(start = 52.dp))
                }
                SettingsActionRow(
                    icon = Icons.Rounded.EditNote,
                    title = if (state.accessibilityEnabled) "Text insertion enabled" else "Enable text insertion",
                    detail = "Required only for Dictation into other apps.",
                    actionLabel = if (state.accessibilityEnabled) "Open" else "Enable",
                    onAction = actions.openAccessibilitySettings,
                )
            }
        }
        item { Spacer(Modifier.height(34.dp)) }
    }
}

@Composable
private fun SettingsSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    iconResource: Int,
    title: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(iconResource),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onAction, contentPadding = ButtonDefaults.ContentPadding) { Text(actionLabel) }
    }
}

private fun connectionLabel(state: AppUiState): String = if (state.isPaired) "Mac connected" else "Not paired"

private fun promptDescription(prompt: RemotePrompt): String = when {
    prompt.kind == RemotePromptKind.Dictation && prompt.isWithoutAI -> "Transcribes without an AI prompt."
    prompt.kind == RemotePromptKind.Dictation -> "Uses ${prompt.title} and returns text to this app."
    prompt.isWithoutAI -> "Saves the transcription as a note without AI."
    else -> "Creates a ${prompt.title} note on your Mac."
}

private fun promptIconResource(prompt: RemotePrompt): Int = when (prompt.icon) {
    "waveform" -> R.drawable.ic_dictation
    "waveform-sparkles" -> R.drawable.ic_dictation_sparkles
    "document" -> R.drawable.ic_note
    "document-sparkles" -> R.drawable.ic_note_sparkles
    "list" -> R.drawable.ic_list
    else -> if (prompt.kind == RemotePromptKind.Dictation) R.drawable.ic_dictation_sparkles else R.drawable.ic_note_sparkles
}

private fun List<SmartNote>.filteredByTag(tag: String?): List<SmartNote> =
    tag?.let { filter { note -> note.tags.contains(it) } } ?: this

private fun List<SmartNote>.allTags(): List<String> =
    flatMap { it.tags }.distinct().sorted()

private fun formatDate(note: SmartNote): String = DateTimeFormatter
    .ofPattern("d MMM, HH:mm", Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(note.createdAt)
