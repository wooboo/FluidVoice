package com.fluidvoice.remote

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CompressedAudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start() {
        check(recorder == null) { "Recording is already active" }
        check(recordingGate.compareAndSet(false, true)) { "Another FluidVoice recording is active" }
        var file: File? = null
        var mediaRecorder: MediaRecorder? = null
        try {
            file = File.createTempFile("fluidvoice-", ".m4a", context.cacheDir)
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioChannels(1)
            mediaRecorder.setAudioSamplingRate(16_000)
            mediaRecorder.setAudioEncodingBitRate(32_000)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            outputFile = file
        } catch (error: Throwable) {
            mediaRecorder?.release()
            file?.delete()
            recordingGate.set(false)
            throw error
        }
    }

    fun stop(): ByteArray {
        val mediaRecorder = checkNotNull(recorder) { "Recording is not active" }
        val file = checkNotNull(outputFile) { "Recording file is unavailable" }
        recorder = null
        outputFile = null
        return try {
            mediaRecorder.stop()
            file.readBytes()
        } finally {
            mediaRecorder.release()
            file.delete()
            recordingGate.set(false)
        }
    }

    fun maxAmplitude(): Int = recorder?.maxAmplitude ?: 0

    companion object {
        private val recordingGate = AtomicBoolean(false)
    }
}
