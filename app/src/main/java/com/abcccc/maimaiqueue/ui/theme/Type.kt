package com.abcccc.maimaiqueue.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val DefaultTypography = Typography()

/**
 * Text sits in a box as tall as the font itself, so a label is centred in its control by its
 * real height. Material's default 24 sp line made a 12 sp label 24 tall and pushed it off centre
 * (docs/ui-guide.md, 字号). Where a text sets a line height of its own, the extra space is split
 * evenly above and below each line.
 */
private fun TextStyle.terminalStyle() = copy(
    letterSpacing = 0.sp,
    lineHeight = TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )
)

val Typography = Typography(
    displayLarge = DefaultTypography.displayLarge.terminalStyle(),
    displayMedium = DefaultTypography.displayMedium.terminalStyle(),
    displaySmall = DefaultTypography.displaySmall.terminalStyle(),
    headlineLarge = DefaultTypography.headlineLarge.terminalStyle(),
    headlineMedium = DefaultTypography.headlineMedium.terminalStyle(),
    headlineSmall = DefaultTypography.headlineSmall.terminalStyle(),
    titleLarge = DefaultTypography.titleLarge.terminalStyle(),
    titleMedium = DefaultTypography.titleMedium.terminalStyle(),
    titleSmall = DefaultTypography.titleSmall.terminalStyle(),
    bodyLarge = DefaultTypography.bodyLarge.terminalStyle(),
    bodyMedium = DefaultTypography.bodyMedium.terminalStyle(),
    bodySmall = DefaultTypography.bodySmall.terminalStyle(),
    labelLarge = DefaultTypography.labelLarge.terminalStyle(),
    labelMedium = DefaultTypography.labelMedium.terminalStyle(),
    labelSmall = DefaultTypography.labelSmall.terminalStyle()
)
