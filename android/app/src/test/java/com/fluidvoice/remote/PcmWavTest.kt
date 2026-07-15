package com.fluidvoice.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmWavTest {
    @Test
    fun `wraps pcm16 in a mono 16khz wav header`() {
        val pcm = byteArrayOf(1, 2, 3, 4)

        val wav = PcmWav.wrap(pcm)

        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals(16_000, littleEndianInt(wav, 24))
        assertEquals(1, littleEndianShort(wav, 22))
        assertEquals(16, littleEndianShort(wav, 34))
        assertEquals(pcm.size, littleEndianInt(wav, 40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}
