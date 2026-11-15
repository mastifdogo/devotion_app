package com.mastifdogo.devotion

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mastifdogo.devotion.ui.DetailScreen
import com.mastifdogo.devotion.ui.DevotionTheme
import com.mastifdogo.devotion.ui.ListScreen
import com.mastifdogo.devotion.ui.ProfileDialog
import com.mastifdogo.devotion.ui.SettingsScreen
import com.mastifdogo.devotion.ui.WallpapersScreen
import com.mastifdogo.devotion.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val dark = isDarkTheme(vm.themeMode)

            // Keep status/navigation bar icons readable for the chosen mode (not just the system one).
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            DevotionTheme(dark = dark) {
                AppBackground(vm) { DevotionApp(vm) }
            }
        }
    }
}

/**
 * The theme background, and over it the app wallpaper (if one is set), fully visible. Screens
 * then put their content in a rounded text window (see ContentWindow) so the wallpaper frames it.
 */
@Composable
private fun AppBackground(vm: MainViewModel, content: @Composable () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        // Crossfades when a new wallpaper is swapped in (e.g. "New wallpaper each time").
        Crossfade(targetState = vm.appWallpaper, label = "wallpaper") { wallpaper ->
            if (wallpaper != null) {
                Image(
                    bitmap = wallpaper,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        content()
    }
}

@Composable
private fun DevotionApp(vm: MainViewModel) {
    val screen = vm.screen
    // Inside the app Back walks back (Detail/Settings -> tabs, Bible chapter -> books -> versions);
    // at the root it is not handled, so the system closes the app.
    BackHandler(enabled = vm.canGoBack) { vm.back() }
    when (screen) {
        is Screen.EntryList -> ListScreen(vm)
        is Screen.Settings -> SettingsScreen(vm)
        is Screen.Wallpapers -> WallpapersScreen(vm)
        is Screen.Detail -> {
            val entry = vm.entryById(screen.id)
            if (entry == null) {
                // The entry was deleted on the server while it was open.
                ListScreen(vm)
            } else {
                // A fresh scroll position and dialog state for each entry.
                key(entry.id) { DetailScreen(vm, entry) }
            }
        }
    }
    if (vm.profilePromptOpen) ProfileDialog(vm)
}
