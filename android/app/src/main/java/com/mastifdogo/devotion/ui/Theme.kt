package com.mastifdogo.devotion.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F4A6D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF7A5900),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDEA3),
    onSecondaryContainer = Color(0xFF261900),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF05315F),
    primaryContainer = Color(0xFF244877),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFF2BF48),
    onSecondary = Color(0xFF402D00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFDEA3),
)

/** The user's theme choice; [key] is what is stored in SharedPreferences. */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Whether [mode] currently means a dark UI (System follows the device setting). */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Material3 theme: dynamic colour on Android 12+ (in whichever mode is active),
 * a fixed palette below.
 */
@Composable
fun DevotionTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** True when the active Material colour scheme is a dark one (whatever made it so). */
@Composable
fun isAppInDarkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * Background for a highlight named yellow/green/blue/pink, or null for "none"/unknown.
 * Light theme: pastel behind dark text. Dark theme: deep, muted tones behind light text.
 */
fun highlightColor(name: String?, dark: Boolean): Color? = when (name) {
    "yellow" -> if (dark) Color(0xFF6B5A00) else Color(0xFFFFF176)
    "green" -> if (dark) Color(0xFF2B5E2F) else Color(0xFFC5E1A5)
    "blue" -> if (dark) Color(0xFF1D4E80) else Color(0xFFA9D4FA)
    "pink" -> if (dark) Color(0xFF7A2F58) else Color(0xFFF8BBD0)
    else -> null
}
