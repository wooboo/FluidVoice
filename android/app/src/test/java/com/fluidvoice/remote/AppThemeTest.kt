package com.fluidvoice.remote

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test
    fun `dark theme text colors meet WCAG contrast`() {
        assertTrue(contrastRatio(FluidInk.luminance(), FluidBackground.luminance()) >= 4.5f)
        assertTrue(contrastRatio(FluidMuted.luminance(), FluidBackground.luminance()) >= 4.5f)
        assertTrue(contrastRatio(FluidInk.luminance(), FluidSurface.luminance()) >= 4.5f)
    }

    private fun contrastRatio(first: Float, second: Float): Float {
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
