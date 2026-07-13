package com.fluidvoice.remote

internal data class OverlayPosition(val x: Int, val y: Int)

internal enum class OverlayEdge { None, Left, Right }

internal data class EdgeAttachment(val edge: OverlayEdge, val progress: Float)

internal data class EdgeButtonLayout(val width: Int, val translationX: Float)

internal fun overlayCornerRadius(
    requestedRadius: Float,
    bodyWidth: Float,
    bodyHeight: Float,
    attached: Boolean,
): Float = minOf(
    requestedRadius,
    if (attached) bodyHeight / 2f else minOf(bodyWidth, bodyHeight) / 2f,
)

internal fun edgeButtonLayout(
    edge: OverlayEdge,
    progress: Float,
    buttonWidth: Int,
    edgeGap: Int,
): EdgeButtonLayout {
    val offset = edgeGap * progress.coerceIn(0f, 1f)
    val translationX = when (edge) {
        OverlayEdge.Left -> -offset
        OverlayEdge.Right -> offset
        OverlayEdge.None -> 0f
    }
    return EdgeButtonLayout(
        width = buttonWidth,
        translationX = translationX,
    )
}

internal fun clampOverlayPosition(
    x: Int,
    y: Int,
    overlayWidth: Int,
    overlayHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    insetLeft: Int = 0,
    insetTop: Int = 0,
    insetRight: Int = 0,
    insetBottom: Int = 0,
    edgeMargin: Int = 0,
): OverlayPosition = OverlayPosition(
    x = x.coerceIn(
        insetLeft + edgeMargin,
        (viewportWidth - insetRight - edgeMargin - overlayWidth).coerceAtLeast(insetLeft + edgeMargin),
    ),
    y = y.coerceIn(
        insetTop + edgeMargin,
        (viewportHeight - insetBottom - edgeMargin - overlayHeight).coerceAtLeast(insetTop + edgeMargin),
    ),
)

internal fun topRightOverlayPosition(
    overlayWidth: Int,
    overlayHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    edgeMargin: Int,
): OverlayPosition = rightAnchoredOverlayPosition(
    y = Int.MIN_VALUE,
    overlayWidth = overlayWidth,
    overlayHeight = overlayHeight,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
    edgeMargin = edgeMargin,
)

internal fun rightAnchoredOverlayPosition(
    y: Int,
    overlayWidth: Int,
    overlayHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    edgeMargin: Int,
): OverlayPosition = clampOverlayPosition(
    x = Int.MAX_VALUE,
    y = y,
    overlayWidth = overlayWidth,
    overlayHeight = overlayHeight,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
    edgeMargin = edgeMargin,
)

internal fun edgeAttachment(
    x: Int,
    overlayWidth: Int,
    viewportWidth: Int,
    transitionDistance: Int,
    canAttachLeft: Boolean = true,
    canAttachRight: Boolean = true,
): EdgeAttachment {
    if (!canAttachLeft && !canAttachRight) return EdgeAttachment(OverlayEdge.None, 0f)
    val maxX = (viewportWidth - overlayWidth).coerceAtLeast(0)
    val leftDistance = x.coerceAtLeast(0)
    val rightDistance = (maxX - x).coerceAtLeast(0)
    val edge = when {
        !canAttachLeft -> OverlayEdge.Right
        !canAttachRight -> OverlayEdge.Left
        leftDistance <= rightDistance -> OverlayEdge.Left
        else -> OverlayEdge.Right
    }
    val distance = if (edge == OverlayEdge.Left) leftDistance else rightDistance
    if (transitionDistance <= 0) {
        return EdgeAttachment(edge, if (distance == 0) 1f else 0f)
    }
    val progress = 1f - (distance.toFloat() / transitionDistance).coerceIn(0f, 1f)
    return EdgeAttachment(if (progress > 0f) edge else OverlayEdge.None, progress)
}
