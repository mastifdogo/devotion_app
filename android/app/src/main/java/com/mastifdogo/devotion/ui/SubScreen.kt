package com.mastifdogo.devotion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.MainViewModel

/**
 * The frame of the screens behind a Back button (entry, Settings, Wallpapers). There is no top
 * bar: a solid round Back bubble floats at the top-left, below the status bar, and the content
 * sits in the text window under it; any title belongs in the content as a heading.
 */
@Composable
fun SubScreen(
    vm: MainViewModel,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val hasWallpaper = vm.appWallpaper != null
    Scaffold(
        containerColor = screenContainerColor(hasWallpaper),
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = bottomBar,
    ) { padding ->
        // The Scaffold padding carries the status bar inset (there is no top bar to take it).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 6.dp, bottom = 8.dp),
            ) {
                BackBubble(onClick = { vm.back() })
            }
            ContentWindow(hasWallpaper, vm.windowOpacity, Modifier.weight(1f)) {
                content()
            }
        }
    }
}

/** A solid round Back button: surface colour with an outline, readable over any wallpaper. */
@Composable
fun BackBubble(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    }
}
