package com.fluidvoice.remote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Records mono PCM16 and emits one-second chunks while retaining a fallback copy. */
class StreamingAudioRecorder(private val context: Context) {
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var output: ByteArrayOutputStream? = null
    private val active = AtomicBoolean(false)
    private val amplitude = AtomicInteger(0)

    @SuppressLint("MissingPermission")
    fun start(onChunk: ((ByteArray) -> Unit)? = null) {
        check(recorder == null) { "Recording is already active" }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required"
        }
        check(recordingGate.compareAndSet(false, true)) { "Another FluidVoice recording is active" }

        var audioRecord: AudioRecord? = null
        try {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBuffer > 0) { "Unable to configure microphone buffer" }
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer * 2, CHUNK_BYTES),
            )
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }

            val recordingOutput = ByteArrayOutputStream()
            output = recordingOutput
            recorder = audioRecord
            amplitude.set(0)
            active.set(true)
            audioRecord.startRecording()
            recordingThread = Thread(
                { recordLoop(audioRecord, recordingOutput, onChunk) },
                "fluidvoice-pcm-recorder",
            ).also { it.start() }
        } catch (error: Throwable) {
            active.set(false)
            audioRecord?.release()
            recorder = null
            output = null
            recordingGate.set(false)
            throw error
        }
    }

    fun stop(): ByteArray {
        val audioRecord = checkNotNull(recorder) { "Recording is not active" }
        val recordingOutput = checkNotNull(output) { "Recording buffer is unavailable" }
        recorder = null
        output = null
        active.set(false)
        return try {
            recordingThread?.join(500)
            if (recordingThread?.isAlive == true) runCatching { audioRecord.stop() }
            recordingThread?.join(1_500)
            runCatching { audioRecord.stop() }
            recordingOutput.toByteArray()
        } finally {
            recordingThread = null
            audioRecord.release()
            amplitude.set(0)
            recordingGate.set(false)
        }
    }

    fun maxAmplitude(): Int = amplitude.getAndSet(0)

    private fun recordLoop(
        audioRecord: AudioRecord,
        recordingOutput: ByteArrayOutputStream,
        onChunk: ((ByteArray) -> Unit)?,
    ) {
        val samples = ShortArray(2_048)
        val chunk = ByteArrayOutputStream(CHUNK_BYTES)
        while (active.get()) {
            val count = audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
            if (count <= 0) continue
            var peak = 0
            repeat(count) { index ->
                val value = samples[index].toInt()
                peak = maxOf(peak, abs(value))
                recordingOutput.write(value and 0xff)
                recordingOutput.write((value ushr 8) and 0xff)
                chunk.write(value and 0xff)
                chunk.write((value ushr 8) and 0xff)
            }
            amplitude.accumulateAndGet(peak, ::maxOf)
            if (chunk.size() >= CHUNK_BYTES) {
                onChunk?.let { callback -> runCatching { callback(chunk.toByteArray()) } }
                chunk.reset()
            }
        }
        if (chunk.size() > 0) {
            onChunk?.let { callback -> runCatching { callback(chunk.toByteArray()) } }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = SAMPLE_RATE * 2
        private val recordingGate = AtomicBoolean(false)
    }
}
