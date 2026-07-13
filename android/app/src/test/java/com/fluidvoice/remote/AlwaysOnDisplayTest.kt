package com.fluidvoice.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnDisplayTest {
    @Test
    fun `burn-in offset stays subtle and moves across cycles`() {
        val offsets = (0L until 12L).map(::alwaysOnBurnInOffset)

        assertTrue(offsets.all { it.x in -8..8 && it.y in -12..12 })
        assertNotEquals(offsets[0], offsets[1])
        assertEquals(offsets[0], alwaysOnBurnInOffset(12))
    }
}
