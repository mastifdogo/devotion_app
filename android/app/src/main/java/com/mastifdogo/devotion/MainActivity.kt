package com.mastifdogo.devotion

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.core.util.Consumer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mastifdogo.devotion.ui.DetailScreen
import com.mastifdogo.devotion.ui.DevotionTheme
import com.mastifdogo.devotion.logic.WallpaperLogic
import com.mastifdogo.devotion.playback.PlaybackService
import com.mastifdogo.devotion.reminder.ReadingReminder
import com.mastifdogo.devotion.ui.ListScreen
import com.mastifdogo.devotion.ui.MiniPlayer
import com.mastifdogo.devotion.ui.ProfileDialog
import com.mastifdogo.devotion.ui.SettingsScreen
import com.mastifdogo.devotion.ui.WallpapersScreen
import com.mastifdogo.devotion.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    /** Set when the media notification was tapped: open the entry that is playing. */
    private val openPlayingRequest = mutableStateOf(false)

    /** Set when the reading reminder was tapped: the id of the entry to open (-1 = none). */
    private val openEntryRequest = mutableStateOf(-1L)

    private fun noteOpenPlaying(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYING, false)) {
            // Only once: a recreated activity (rotation) must not jump to the entry again.
            intent.removeExtra(PlaybackService.EXTRA_OPEN_PLAYING)
            openPlayingRequest.value = true
        }
        val entryId = intent.getLongExtra(ReadingReminder.EXTRA_OPEN_ENTRY, -1L)
        if (entryId >= 0L) {
            intent.removeExtra(ReadingReminder.EXTRA_OPEN_ENTRY)
            openEntryRequest.value = entryId
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) noteOpenPlaying(intent)
        // The activity is singleTop: a notification tap while it is open arrives here.
        addOnNewIntentListener(Consumer<Intent> { newIntent -> noteOpenPlaying(newIntent) })
        setContent {
            val vm: MainViewModel = viewModel()
            val dark = isDarkTheme(vm.themeMode)

            LaunchedEffect(openPlayingRequest.value) {
                if (openPlayingRequest.value) {
                    openPlayingRequest.value = false
                    vm.openPlayingEntry()
                }
            }

            LaunchedEffect(openEntryRequest.value) {
                val entryId = openEntryRequest.value
                if (entryId >= 0L) {
                    openEntryRequest.value = -1L
                    vm.openEntryById(entryId)
                }
            }

            // Android 13+: ask for the notification permission the first time the user presses
            // play (for the media notification). Whatever the answer, playback goes on.
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(vm.askNotificationPermission) {
                if (vm.askNotificationPermission) {
                    vm.notificationPermissionHandled()
                    try {
                        notificationPermission.launch(MainViewModel.POST_NOTIFICATIONS)
                    } catch (e: Exception) {
                        // No permission dialog available: playback works without it.
                    }
                }
            }

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
                AppBackground(vm) {
                    // Only recompose the screens when the mini player appears or disappears,
                    // not on every position tick.
                    val miniPlayer by remember { derivedStateOf { vm.miniPlayerVisible } }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                // The mini player below takes the navigation bar inset instead.
                                .then(
                                    if (miniPlayer) {
                                        Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            DevotionApp(vm)
                        }
                        if (miniPlayer) MiniPlayer(vm)
                    }
                }
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
                    // A wallpaper wider than the screen keeps its full height and shows the
                    // horizontal crop chosen with it (centre for one picked by hand).
                    alignment = BiasAlignment(WallpaperLogic.alignmentBias(vm.wallpaperBias), 0f),
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
