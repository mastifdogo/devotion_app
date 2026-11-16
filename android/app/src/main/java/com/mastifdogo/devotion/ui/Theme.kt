package com.mastifdogo.devotion.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.logic.WindowLogic

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

// ---- app wallpaper ------------------------------------------------------------------------------

/** Scaffold container colour: transparent while the app wallpaper shows behind the screens. */
@Composable
fun screenContainerColor(hasWallpaper: Boolean): Color =
    if (hasWallpaper) Color.Transparent else MaterialTheme.colorScheme.background

/** Top app bar over the wallpaper: lightly translucent so its title and icons stay legible. */
@Composable
fun barContainerColor(hasWallpaper: Boolean): Color =
    if (hasWallpaper) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surface

/**
 * Fill of the list item cards inside the text window (entry cards, Bible chapters, books and
 * versions): surfaceContainer at the list item opacity; at 0% only their outline remains.
 */
@Composable
fun itemCardFill(opacity: Float): Color =
    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = WindowLogic.clampItemOpacity(opacity))

/**
 * The text window. With an app wallpaper: a rounded surface at [opacity] (clamped to 50..100%),
 * inset from the sides and bottom (more, and at most 720dp wide, when 600dp or wider), so the
 * wallpaper frames it. Without one: just the content, as before wallpapers existed.
 * [modifier] should carry the Scaffold padding (which already includes the system bar insets).
 */
@Composable
fun ContentWindow(
    hasWallpaper: Boolean,
    opacity: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!hasWallpaper) {
        Box(modifier = modifier.fillMaxSize()) { content() }
        return
    }
    // The width available to the screen decides the inset and the width cap.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = WindowLogic.layoutFor(maxWidth.value.toInt())
        val inset = layout.insetDp.dp
        val cap = layout.maxWidthDp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = inset, end = inset, bottom = inset),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = WindowLogic.clampOpacity(opacity)),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = (if (cap != null) Modifier.widthIn(max = cap.dp) else Modifier).fillMaxSize(),
            ) {
                content()
            }
        }
    }
}
