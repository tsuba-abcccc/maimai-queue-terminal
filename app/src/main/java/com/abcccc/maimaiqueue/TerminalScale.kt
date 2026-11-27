package com.abcccc.maimaiqueue

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** The screen the terminal is designed for: a 10-inch 720p tablet at density 160 (docs/ui-guide.md). */
internal const val TERMINAL_DESIGN_WIDTH_DP = 1280
internal const val TERMINAL_DESIGN_HEIGHT_DP = 720

private const val MIN_TERMINAL_SCALE = 0.5f

/**
 * Fitting the height, the layout may be narrower than the design, down to this width: the
 * waiting positions scroll sideways anyway, while the height is what the pages need.
 */
private const val TERMINAL_MIN_REDUCED_WIDTH_DP = 1100
private const val MAX_TERMINAL_SCALE = 3f

/**
 * The sizes the terminal can be set to on top of fitting the screen's height, chosen in 应用详情
 * on each terminal (maintainer, 2026-11-27: “以81为100%，最大到90%那么大就好……四个档位左右吧”):
 * on that terminal 100 % is the height fit (81 %), and 111 % is about 90 %.
 */
internal val TERMINAL_SIZE_LEVELS = listOf(1.00f, 1.04f, 1.07f, 1.11f)

/** The level a terminal starts with: the layout exactly fills the screen's height. */
internal const val DEFAULT_TERMINAL_SIZE_LEVEL = 0

/**
 * How much the whole terminal is scaled on this screen. By default the layout fills the height
 * of the design screen, 720, on every screen, larger or smaller (maintainer, 2026-11-27: “以后
 * 默认都按720 高度来”), and may then be narrower than 1280, down to
 * [TERMINAL_MIN_REDUCED_WIDTH_DP]; a screen narrower than that is scaled by its width instead.
 * Filling 1280 × 720 on a 1920 × 1168 terminal at density 320 (75 %) was too small, and leaving
 * smaller screens unscaled crowded them (“缩放比例不正常，内容过大，缩成一坨”). [sizeLevel] is one of
 * [TERMINAL_SIZE_LEVELS]. Bounded to 0.5–3 against odd values.
 */
internal fun terminalScaleFactor(
    screenWidthDp: Int,
    screenHeightDp: Int,
    sizeLevel: Int = DEFAULT_TERMINAL_SIZE_LEVEL
): Float {
    if (screenWidthDp <= 0 || screenHeightDp <= 0) return 1f
    val fit = minOf(
        screenHeightDp / TERMINAL_DESIGN_HEIGHT_DP.toFloat(),
        screenWidthDp / TERMINAL_MIN_REDUCED_WIDTH_DP.toFloat()
    )
    val level = TERMINAL_SIZE_LEVELS.getOrElse(sizeLevel) { 1f }
    return (fit * level).coerceIn(MIN_TERMINAL_SCALE, MAX_TERMINAL_SCALE)
}

/** The size level chosen on this terminal, kept on the device only (not synced, not managed). */
internal object TerminalSizePreference {
    private const val PREFERENCES = "terminal_display"
    private const val KEY_LEVEL = "size_level"

    val level = mutableIntStateOf(DEFAULT_TERMINAL_SIZE_LEVEL)

    fun load(context: Context) {
        level.intValue = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getInt(KEY_LEVEL, DEFAULT_TERMINAL_SIZE_LEVEL)
            .coerceIn(0, TERMINAL_SIZE_LEVELS.lastIndex)
    }

    fun set(context: Context, value: Int) {
        val chosen = value.coerceIn(0, TERMINAL_SIZE_LEVELS.lastIndex)
        level.intValue = chosen
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LEVEL, chosen).apply()
    }
}

/**
 * Scales everything inside in proportion, as setting Android's 最小宽度 to 720 would, but for this
 * app only and without touching the device settings. Text, controls and spacing keep their ratios, so
 * the layout checked at 1280 × 720 is what every screen shows. The configuration seen inside is
 * the enlarged one, so code that reads the screen size stays consistent with the drawing.
 */
@Composable
internal fun TerminalProportionalScale(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val factor = terminalScaleFactor(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        TerminalSizePreference.level.intValue
    )
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
