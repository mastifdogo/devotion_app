package com.mastifdogo.devotion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.MainViewModel
import com.mastifdogo.devotion.data.Entry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: MainViewModel, entry: Entry) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = entry.title.ifBlank { "Devotion" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = { PlayerBar(vm, entry) },
    ) { padding ->
        val scroll = rememberScrollState()
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = entry.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.headlineSmall,
                )
                val date = entryDateLabel(entry)
                if (date.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = date,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val scripture = entry.scripture
                if (!scripture.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = scripture,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = entry.body,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PlayerBar(vm: MainViewModel, entry: Entry) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val audioError = vm.audioError
            if (audioError != null) {
                Text(
                    text = audioError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val cached = entry.id in vm.cachedAudio
            val downloading = vm.downloads.containsKey(entry.id)
            val loaded = vm.playerEntryId == entry.id

            when {
                !entry.hasAudio && !cached -> {
                    Text(
                        text = "No audio for this entry yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                downloading -> {
                    val fraction = vm.downloads[entry.id]
                    Text(
                        text = if (fraction != null) {
                            "Downloading audio… ${(fraction * 100f).toInt()}%"
                        } else {
                            "Downloading audio…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                else -> {
                    PlayerControls(vm = vm, entry = entry, loaded = loaded, cached = cached)
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(vm: MainViewModel, entry: Entry, loaded: Boolean, cached: Boolean) {
    val duration = if (loaded) vm.durationMs else 0L
    val position = if (loaded) vm.positionMs else 0L
    val playing = loaded && vm.isPlaying
    val canSeek = loaded && duration > 0L

    // While the thumb is being dragged the slider follows the finger, not the player.
    var dragValue by remember(entry.id) { mutableStateOf<Float?>(null) }
    val sliderMax = if (duration > 0L) duration.toFloat() else 1f
    val sliderValue = (dragValue ?: position.toFloat()).coerceIn(0f, sliderMax)

    Slider(
        value = sliderValue,
        onValueChange = { dragValue = it },
        onValueChangeFinished = {
            val target = dragValue
            if (target != null) vm.seekTo(target.toLong())
            dragValue = null
        },
        valueRange = 0f..sliderMax,
        enabled = canSeek,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = formatDuration(dragValue?.toLong() ?: position),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = if (duration > 0L) formatDuration(duration) else "--:--",
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { vm.skip(-15_000L) }, enabled = canSeek) { Text("−15 s") }
        Button(onClick = { vm.playPause(entry) }) {
            Text(
                when {
                    playing -> "Pause"
                    cached -> "Play"
                    else -> "Download & play"
                }
            )
        }
        TextButton(onClick = { vm.skip(15_000L) }, enabled = canSeek) { Text("+15 s") }
    }
}
