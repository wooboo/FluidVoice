package com.fluidvoice.remote

import kotlin.math.sqrt

object AudioLevelMapper {
    fun normalize(amplitude: Int): Float {
        val adjusted = (amplitude.coerceIn(0, MAX_AMPLITUDE) - NOISE_FLOOR).coerceAtLeast(0)
        val ratio = (adjusted / SPEECH_PEAK.toFloat()).coerceIn(0f, 1f)
        return sqrt(ratio).coerceIn(QUIET_LEVEL, 1f)
    }

    private const val MAX_AMPLITUDE = 32_767
    private const val NOISE_FLOOR = 20
    private const val SPEECH_PEAK = 500
    private const val QUIET_LEVEL = 0.08f
}
