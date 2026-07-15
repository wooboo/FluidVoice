package com.fluidvoice.remote

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class StreamingCaptureKind(val wireValue: String) {
    Dictation("dictation"),
    SmartNote("smart-note"),
    NoteContinuation("note-continuation"),
}

internal data class StreamingCaptureConfig(
    val kind: StreamingCaptureKind,
    val enhance: Boolean,
    val promptId: String? = null,
    val noteId: String? = null,
    val inputContext: InputFieldContext? = null,
)

internal fun RemotePrompt.streamingCaptureConfig(): StreamingCaptureConfig = StreamingCaptureConfig(
    kind = if (kind == RemotePromptKind.Dictation) {
        StreamingCaptureKind.Dictation
    } else {
        StreamingCaptureKind.SmartNote
    },
    enhance = !isWithoutAI,
    promptId = id.takeUnless { kind == RemotePromptKind.Dictation && isWithoutAI },
)

internal sealed interface StreamingCaptureResult {
    data class Dictation(val text: String) : StreamingCaptureResult
    data class Note(val capture: SmartNoteCapture) : StreamingCaptureResult
}

internal class StreamingCaptureUploader(
    private val client: RemoteClient,
    private val connection: CredentialStore.Connection,
    private val config: StreamingCaptureConfig,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fluidvoice-stream-upload")
    }
    private val closed = AtomicBoolean(false)
    private var streamId: String? = null
    private var streamingFailure: Throwable? = null

    init {
        executor.execute {
            runCatching { client.startStream(connection, config) }
                .onSuccess { streamId = it }
                .onFailure { streamingFailure = it }
        }
    }

    fun append(pcm: ByteArray) {
        if (closed.get()) return
        executor.execute {
            val id = streamId ?: return@execute
            if (streamingFailure != null) return@execute
            runCatching { client.appendStream(connection, id, pcm) }
                .onFailure { streamingFailure = it }
        }
    }

    fun finish(fallbackPCM: ByteArray, completion: (Result<StreamingCaptureResult>) -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            val result = runCatching {
                val id = streamId
                if (id != null && streamingFailure == null) {
                    runCatching { client.finishStream(connection, id, config.kind) }
                        .getOrElse {
                            runCatching { client.cancelStream(connection, id) }
                            fallback(fallbackPCM)
                        }
                } else {
                    id?.let { runCatching { client.cancelStream(connection, it) } }
                    fallback(fallbackPCM)
                }
            }
            completion(result)
            executor.shutdown()
        }
    }

    fun cancel() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            streamId?.let { runCatching { client.cancelStream(connection, it) } }
            executor.shutdown()
        }
    }

    private fun fallback(pcm: ByteArray): StreamingCaptureResult = when (config.kind) {
        StreamingCaptureKind.Dictation -> StreamingCaptureResult.Dictation(
            client.dictate(
                connection = connection,
                audio = pcm,
                enhance = config.enhance,
                inputContext = config.inputContext,
                promptId = config.promptId,
            ),
        )
        StreamingCaptureKind.SmartNote -> StreamingCaptureResult.Note(
            client.captureNote(
                connection = connection,
                audio = pcm,
                enhance = config.enhance,
                promptId = config.promptId ?: "default",
            ),
        )
        StreamingCaptureKind.NoteContinuation -> StreamingCaptureResult.Note(
            client.continueNote(
                connection = connection,
                noteId = checkNotNull(config.noteId),
                audio = pcm,
                promptId = config.promptId ?: "default",
            ),
        )
    }
}
