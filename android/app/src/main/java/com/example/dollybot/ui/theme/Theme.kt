package com.example.dollybot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6DD9E3),
    onPrimary = Color(0xFF00363E),
    secondaryContainer = Color(0xFF233E46),
    onSecondaryContainer = Color(0xFFC1EDF2),
    background = Color(0xFF101519),
    onBackground = Color(0xFFE9F1F3),
    surface = Color(0xFF192228),
    onSurface = Color(0xFFE9F1F3),
    surfaceContainer = Color(0xFF202B33),
    surfaceVariant = Color(0xFF303E47),
    onSurfaceVariant = Color(0xFFB9CAD0),
    outline = Color(0xFF8C9FA8),
    outlineVariant = Color(0xFF42535B),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF602824),
    onErrorContainer = Color(0xFFFFDAD5)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006575),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFD1EEF1),
    onSecondaryContainer = Color(0xFF123C44),
    background = Color(0xFFF2F6F7),
    onBackground = Color(0xFF14232A),
    surface = Color.White,
    onSurface = Color(0xFF14232A),
    surfaceContainer = Color(0xFFE5EFF1),
    surfaceVariant = Color(0xFFDDE8EB),
    onSurfaceVariant = Color(0xFF42555E),
    outline = Color(0xFF687D86),
    outlineVariant = Color(0xFFBBCBD0),
    error = Color(0xFFAE2B2B),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF7D1717)
)

@Composable
fun DollybotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
