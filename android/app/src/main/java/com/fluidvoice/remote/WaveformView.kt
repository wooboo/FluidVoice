package com.fluidvoice.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

class WaveformView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffd7e2ff.toInt() }
    private val amplitudes = FloatArray(11) { QUIET_LEVEL }
    private var recording = false
    private var processing = false

    fun pushAmplitude(amplitude: Int) {
        amplitudes.copyInto(amplitudes, destinationOffset = 0, startIndex = 1)
        amplitudes[amplitudes.lastIndex] = AudioLevelMapper.normalize(amplitude)
        invalidate()
    }

    fun setProcessing(value: Boolean) {
        processing = value
        invalidate()
    }

    fun setRecording(value: Boolean) {
        if (value && !recording) amplitudes.fill(QUIET_LEVEL)
        recording = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barWidth = width / (amplitudes.size * 2f)
        val gap = barWidth
        val centerY = height / 2f
        val phase = SystemClock.uptimeMillis() / 180.0
        amplitudes.forEachIndexed { index, amplitude ->
            val level = when {
                processing -> (0.25 + 0.55 * ((sin(phase + index * 0.65) + 1) / 2)).toFloat()
                recording -> amplitude
                else -> amplitude
            }
            val barHeight = (height * 0.78f * level).coerceAtLeast(barWidth)
            val left = index * (barWidth + gap)
            canvas.drawRoundRect(
                left,
                centerY - barHeight / 2,
                left + barWidth,
                centerY + barHeight / 2,
                barWidth / 2,
                barWidth / 2,
                paint,
            )
        }
        if (processing) postInvalidateDelayed(32)
    }

    private companion object {
        const val QUIET_LEVEL = 0.08f
    }
}
