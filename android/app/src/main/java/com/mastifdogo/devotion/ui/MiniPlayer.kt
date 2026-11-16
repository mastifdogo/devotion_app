package com.mastifdogo.devotion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.MainViewModel
import com.mastifdogo.devotion.logic.MiniPlayerLogic
import com.mastifdogo.devotion.logic.WindowLogic

/**
 * The bar at the bottom of every screen except the playing entry's own, while audio is playing or
 * paused part-way: what is playing (tap to open it), play/pause, progress, the speed control and
 * stop. It sits below the screens (so nothing is hidden behind it) and takes the navigation bar
 * inset. Over a wallpaper it is a rounded window like the rest; otherwise a plain bottom bar.
 */
@Composable
fun MiniPlayer(vm: MainViewModel) {
    val hasWallpaper = vm.appWallpaper != null
    if (!hasWallpaper) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.navigationBarsPadding()) { MiniPlayerContent(vm) }
        }
        return
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        // The same inset and width cap as the text window above it.
        val layout = WindowLogic.layoutFor(maxWidth.value.toInt())
        val inset = layout.insetDp.dp
        val cap = layout.maxWidthDp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = inset, end = inset, bottom = inset),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                color = windowColor().copy(alpha = WindowLogic.clampOpacity(vm.windowOpacity)),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = (if (cap != null) Modifier.widthIn(max = cap.dp) else Modifier).fillMaxWidth(),
            ) {
                MiniPlayerContent(vm)
            }
        }
    }
}

@Composable
private fun MiniPlayerContent(vm: MainViewModel) {
    val now = vm.nowPlaying
    val position = vm.positionMs
    val duration = vm.durationMs
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { MiniPlayerLogic.progressFraction(position, duration) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // What is playing; tapping it opens that entry.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { vm.openPlayingEntry() }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = now.primary,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (now.secondary.isNotEmpty()) {
                    Text(
                        text = now.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { vm.stopFromMiniPlayer() }) {
                Icon(Icons.Filled.Close, contentDescription = "Stop")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.togglePlayback() }) {
                Text(if (vm.isPlaying) "Pause" else "Play")
            }
            Text(
                text = MiniPlayerLogic.progressText(position, duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            SpeedControl(vm)
        }
    }
}
