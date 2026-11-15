package com.mastifdogo.devotion

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mastifdogo.devotion.ui.DetailScreen
import com.mastifdogo.devotion.ui.DevotionTheme
import com.mastifdogo.devotion.ui.ListScreen
import com.mastifdogo.devotion.ui.SettingsScreen
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
                DevotionApp(vm)
            }
        }
    }
}

@Composable
private fun DevotionApp(vm: MainViewModel) {
    val screen = vm.screen
    BackHandler(enabled = screen != Screen.EntryList) { vm.back() }
    when (screen) {
        is Screen.EntryList -> ListScreen(vm)
        is Screen.Settings -> SettingsScreen(vm)
        is Screen.Detail -> {
            val entry = vm.entryById(screen.id)
            if (entry == null) {
                // The entry was deleted on the server while it was open.
                ListScreen(vm)
            } else {
                DetailScreen(vm, entry)
            }
        }
    }
}
