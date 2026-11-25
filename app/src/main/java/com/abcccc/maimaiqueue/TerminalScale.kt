package com.abcccc.maimaiqueue

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** The screen the terminal is designed for: a 10-inch 720p tablet at density 160 (docs/ui-guide.md). */
internal const val TERMINAL_DESIGN_WIDTH_DP = 1280
internal const val TERMINAL_DESIGN_HEIGHT_DP = 720

/**
 * How much the whole terminal is enlarged on this screen. A 1080P or 2K panel whose system
 * density leaves more room than the design screen shows the same layout, larger in proportion;
 * a screen at or below the design size is not shrunk.
 */
internal fun terminalScaleFactor(screenWidthDp: Int, screenHeightDp: Int): Float {
    if (screenWidthDp <= 0 || screenHeightDp <= 0) return 1f
    val proportional = minOf(
        screenWidthDp / TERMINAL_DESIGN_WIDTH_DP.toFloat(),
        screenHeightDp / TERMINAL_DESIGN_HEIGHT_DP.toFloat()
    )
    return proportional.coerceIn(1f, 3f)
}

/**
 * Enlarges everything inside in proportion, as lowering Android's 最小宽度 would, but for this app
 * only and without touching the device settings. Text, controls and spacing keep their ratios, so
 * the layout checked at 1280 × 720 is what every screen shows. The configuration seen inside is
 * the enlarged one, so code that reads the screen size stays consistent with the drawing.
 */
@Composable
internal fun TerminalProportionalScale(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val factor = terminalScaleFactor(configuration.screenWidthDp, configuration.screenHeightDp)
    if (factor == 1f) {
        content()
        return
    }
    val scaledDensity = remember(density, factor) {
        Density(density.density * factor, density.fontScale)
    }
    val scaledConfiguration = remember(configuration, factor) {
        Configuration(configuration).apply {
            screenWidthDp = (configuration.screenWidthDp / factor).toInt()
            screenHeightDp = (configuration.screenHeightDp / factor).toInt()
            smallestScreenWidthDp = (configuration.smallestScreenWidthDp / factor).toInt()
            densityDpi = (configuration.densityDpi * factor).toInt()
        }
    }
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalConfiguration provides scaledConfiguration,
        content = content
    )
}
