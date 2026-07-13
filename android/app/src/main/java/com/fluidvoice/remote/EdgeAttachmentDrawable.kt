package com.fluidvoice.remote

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

internal class EdgeAttachmentDrawable(
    color: Int,
    private val cornerRadius: Float,
    private val shoulderDepth: Float,
    private val innerInset: Float = 0f,
    shadowRadius: Float = 0f,
    shadowOffsetY: Float = 0f,
    shadowColor: Int = 0,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (shadowRadius > 0f) setShadowLayer(shadowRadius, 0f, shadowOffsetY, shadowColor)
    }
    private val path = Path()
    private val mirrorMatrix = Matrix()

    var attachmentSide: OverlayEdge = OverlayEdge.None
        set(value) {
            if (field == value) return
            field = value
            rebuildPath(bounds)
            invalidateSelf()
        }

    var attachmentProgress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            rebuildPath(bounds)
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildPath(bounds)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun rebuildPath(bounds: Rect) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= shoulderDepth * 2f) return
        buildRightAttachmentPath(width, height)
        if (attachmentSide == OverlayEdge.Left) {
            mirrorMatrix.setScale(-1f, 1f, width / 2f, height / 2f)
            path.transform(mirrorMatrix)
        }
    }

    private fun buildRightAttachmentPath(width: Float, height: Float) {
        val top = shoulderDepth
        val bottom = height - shoulderDepth
        val progress = if (attachmentSide == OverlayEdge.None) 0f else attachmentProgress
        val left = innerInset * progress
        val radius = overlayCornerRadius(
            requestedRadius = cornerRadius,
            bodyWidth = width - left,
            bodyHeight = bottom - top,
            attached = progress > 0f,
        )
        val kappa = 0.5522848f

        val upperStartX = lerp(width - radius, width - shoulderDepth, progress)
        val upperControl1X = lerp(width - radius + radius * kappa, width - shoulderDepth * 0.35f, progress)
        val upperControl2Y = lerp(top + radius - radius * kappa, shoulderDepth * 0.35f, progress)
        val upperEndY = lerp(top + radius, 0f, progress)

        val lowerStartY = lerp(bottom - radius, height, progress)
        val lowerControl1Y = lerp(bottom - radius + radius * kappa, height - shoulderDepth * 0.35f, progress)
        val lowerControl2X = lerp(width - radius + radius * kappa, width - shoulderDepth * 0.35f, progress)
        val lowerEndX = lerp(width - radius, width - shoulderDepth, progress)

        path.reset()
        path.moveTo(left + radius, top)
        path.lineTo(upperStartX, top)
        path.cubicTo(upperControl1X, top, width, upperControl2Y, width, upperEndY)
        path.lineTo(width, lowerStartY)
        path.cubicTo(width, lowerControl1Y, lowerControl2X, bottom, lowerEndX, bottom)
        path.lineTo(left + radius, bottom)
        path.cubicTo(
            left + radius - radius * kappa,
            bottom,
            left,
            bottom - radius + radius * kappa,
            left,
            bottom - radius,
        )
        path.lineTo(left, top + radius)
        path.cubicTo(
            left,
            top + radius - radius * kappa,
            left + radius - radius * kappa,
            top,
            left + radius,
            top,
        )
        path.close()
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress
}
