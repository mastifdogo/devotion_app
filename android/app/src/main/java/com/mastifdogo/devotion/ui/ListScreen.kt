package com.mastifdogo.devotion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.MainViewModel
import com.mastifdogo.devotion.Screen
import com.mastifdogo.devotion.data.Entry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devotion") },
                actions = {
                    if (vm.syncing) {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = { vm.sync() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
                        }
                    }
                    IconButton(onClick = { vm.open(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StatusHeader(vm)
            HorizontalDivider()
            if (vm.entries.isEmpty()) {
                EmptyState(vm)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(vm.entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            audioCached = entry.id in vm.cachedAudio,
                            downloading = vm.downloads.containsKey(entry.id),
                            onClick = { vm.open(Screen.Detail(entry.id)) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(vm: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val status = when {
            vm.syncing -> "Syncing…"
            vm.lastSyncMillis > 0L -> "Last sync: ${formatSyncTime(vm.lastSyncMillis)}"
            else -> "Not synced yet"
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val syncError = vm.syncError
        if (syncError != null) {
            Text(
                text = syncError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (vm.entries.isNotEmpty()) {
                Text(
                    text = "Showing what is saved on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val audioError = vm.audioError
        if (audioError != null) {
            Text(
                text = audioError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EmptyState(vm: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!vm.isServerConfigured) {
            Text("No server is set up yet.", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { vm.open(Screen.Settings) }) { Text("Open Settings") }
        } else {
            Text("No entries yet.", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { vm.sync() }, enabled = !vm.syncing) { Text("Sync now") }
        }
    }
}

@Composable
private fun EntryRow(
    entry: Entry,
    audioCached: Boolean,
    downloading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val date = entryDateLabel(entry)
            if (date.isNotEmpty()) {
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val scripture = entry.scripture
            if (!scripture.isNullOrBlank()) {
                Text(
                    text = scripture,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                audioCached -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Audio saved on this device",
                    tint = MaterialTheme.colorScheme.primary,
                )
                downloading -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                entry.hasAudio -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Audio available, not downloaded yet",
                    tint = MaterialTheme.colorScheme.outlineVariant,
                )
                else -> Unit
            }
        }
    }
}
