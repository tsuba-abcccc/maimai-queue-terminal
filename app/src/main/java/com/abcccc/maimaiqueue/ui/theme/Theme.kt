package com.abcccc.maimaiqueue.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = AppBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF142B44),
    onPrimaryContainer = Color(0xFFF5F5F7),
    secondary = Color(0xFFD1D1D6),
    onSecondary = Color(0xFF1C1C1E),
    background = Color.Black,
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF242426),
    onSurfaceVariant = Color(0xFFA1A1A6),
    outline = Color(0xFF48484A),
    error = Color(0xFFFF453A),
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = AppBlue,
    onPrimary = Color.White,
    primaryContainer = AppSoftBlue,
    onPrimaryContainer = AppText,
    secondary = AppText,
    onSecondary = Color.White,
    secondaryContainer = AppSurfaceVariant,
    onSecondaryContainer = AppText,
    tertiary = AppWarning,
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceVariant = AppSurfaceVariant,
    onSurfaceVariant = AppSecondaryText,
    outline = AppSeparator,
    error = AppDestructive,
    onError = Color.White
)

@Composable
fun MaimaiQueueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
