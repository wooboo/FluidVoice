package com.fluidvoice.remote

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class WavRecorder {
    private val recording = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    fun start() {
        val sampleRate = 16_000
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimum * 2,
        ).also { it.startRecording() }
        pcm.reset()
        recording.set(true)
        thread = Thread {
            val buffer = ByteArray(minimum)
            while (recording.get()) {
                val count = recorder?.read(buffer, 0, buffer.size) ?: 0
                if (count > 0) pcm.write(buffer, 0, count)
            }
        }.apply { start() }
    }

    fun stop(): ByteArray {
        recording.set(false)
        recorder?.stop()
        thread?.join(2_000)
        recorder?.release()
        recorder = null
        return wav(pcm.toByteArray())
    }

    private fun wav(samples: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + samples.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(samples.size)
        return header.array() + samples
    }
}
