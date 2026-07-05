package com.hdrezka.pult.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Фирменный оранжевый HDRezka + тёплые нейтрали, чтобы поверхности не выглядели
// «синими», как у дефолтной Material-палитры.
private val Orange = Color(0xFFFF7A1A)
private val OrangeDark = Color(0xFFE05E00)
private val OrangeContainer = Color(0xFF3A2410)

private val DarkColors = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF1A0E00),
    primaryContainer = OrangeContainer,
    onPrimaryContainer = Color(0xFFFFD9BE),
    secondary = Color(0xFFFFB784),
    onSecondary = Color(0xFF2A1600),
    tertiary = Color(0xFF7EC8FF),
    background = Color(0xFF0F0F11),
    onBackground = Color(0xFFECECEF),
    surface = Color(0xFF16161A),
    onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF26262C),
    onSurfaceVariant = Color(0xFFC6C6CE),
    outline = Color(0xFF4A4A52),
    outlineVariant = Color(0xFF303038),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0000),
)

private val LightColors = lightColorScheme(
    primary = OrangeDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC4),
    onPrimaryContainer = Color(0xFF331200),
    secondary = Color(0xFF8A5122),
    tertiary = Color(0xFF1C6E9E),
    background = Color(0xFFFBF8F6),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFF0E7E0),
    onSurfaceVariant = Color(0xFF52443B),
    outline = Color(0xFF867567),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

@Composable
fun HdRezkaPultTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
