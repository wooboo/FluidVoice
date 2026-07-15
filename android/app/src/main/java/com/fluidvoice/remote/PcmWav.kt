package com.fluidvoice.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PcmWav {
    fun wrap(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
        val output = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray())
        output.putInt(36 + pcm.size)
        output.put("WAVE".toByteArray())
        output.put("fmt ".toByteArray())
        output.putInt(16)
        output.putShort(1.toShort())
        output.putShort(1.toShort())
        output.putInt(sampleRate)
        output.putInt(sampleRate * 2)
        output.putShort(2.toShort())
        output.putShort(16.toShort())
        output.put("data".toByteArray())
        output.putInt(pcm.size)
        output.put(pcm)
        return output.array()
    }
}
