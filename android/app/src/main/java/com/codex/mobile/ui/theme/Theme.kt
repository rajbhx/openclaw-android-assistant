package com.codex.mobile.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AnyClawPrimary,
    onPrimary = AnyClawTextPrimary,
    primaryContainer = AnyClawPrimaryDark,
    onPrimaryContainer = AnyClawTextPrimary,
    secondary = AnyClawAccent,
    onSecondary = AnyClawTextPrimary,
    secondaryContainer = AnyClawSurfaceVariant,
    onSecondaryContainer = AnyClawTextPrimary,
    tertiary = AnyClawInfo,
    onTertiary = AnyClawTextPrimary,
    background = AnyClawBackground,
    onBackground = AnyClawTextPrimary,
    surface = AnyClawSurface,
    onSurface = AnyClawTextPrimary,
    surfaceVariant = AnyClawSurfaceVariant,
    onSurfaceVariant = AnyClawTextSecondary,
    error = AnyClawError,
    onError = AnyClawTextPrimary,
    outline = AnyClawTextTertiary,
)

private val LightColorScheme = lightColorScheme(
    primary = AnyClawPrimaryDark,
    onPrimary = AnyClawTextPrimary,
    primaryContainer = AnyClawPrimaryLight,
    onPrimaryContainer = AnyClawBackground,
    secondary = AnyClawAccent,
    onSecondary = AnyClawBackground,
    tertiary = AnyClawInfo,
    onTertiary = AnyClawTextPrimary,
    background = AnyClawTextPrimary,
    onBackground = AnyClawBackground,
    surface = AnyClawTextPrimary,
    onSurface = AnyClawBackground,
    surfaceVariant = AnyClawTextSecondary,
    onSurfaceVariant = AnyClawBackground,
    error = AnyClawError,
    onError = AnyClawTextPrimary,
    outline = AnyClawTextTertiary,
)

@Composable
fun AnyClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AnyClawTypography,
        content = content,
    )
}
