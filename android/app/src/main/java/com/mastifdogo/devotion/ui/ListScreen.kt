package com.mastifdogo.devotion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.MainViewModel
import com.mastifdogo.devotion.Screen
import com.mastifdogo.devotion.data.Entry
import com.mastifdogo.devotion.logic.EntryKinds
import com.mastifdogo.devotion.logic.EntryTab
import java.time.ZoneId
import java.util.Locale

/**
 * The main screen. No top app bar: the tab row (Daily readings / Lessons / Bible) is the top of
 * the screen, with the Settings button at its right end. Syncing happens on start, in the
 * background, or from Settings.
 */
@Composable
fun ListScreen(vm: MainViewModel) {
    val hasWallpaper = vm.appWallpaper != null
    // Without a top bar the Scaffold's content padding includes the status bar.
    Scaffold(
        containerColor = screenContainerColor(hasWallpaper),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { padding ->
        ContentWindow(hasWallpaper, vm.windowOpacity, Modifier.padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabsBar(vm, hasWallpaper)
                val selected = vm.selectedTab
                if (selected == EntryTab.BIBLE) {
                    BibleTab(vm, Modifier.fillMaxSize())
                } else {
                    EntriesTab(vm, selected, cards = hasWallpaper)
                }
            }
        }
    }
}

/** The tabs, taking the remaining width, and the Settings button on the same row. */
@Composable
private fun TabsBar(vm: MainViewModel, hasWallpaper: Boolean) {
    val barColor = if (hasWallpaper) Color.Transparent else MaterialTheme.colorScheme.surface
    val tabs = EntryTab.entries
    val selected = vm.selectedTab
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabRow(
            selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
            containerColor = barColor,
            modifier = Modifier.weight(1f),
        ) {
            for (tab in tabs) {
                Tab(
                    selected = tab == selected,
                    onClick = { vm.selectTab(tab) },
                    text = {
                        Text(
                            text = tab.label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    },
                )
            }
        }
        IconButton(onClick = { vm.open(Screen.Settings) }) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun EntriesTab(vm: MainViewModel, tab: EntryTab, cards: Boolean) {
    val all = vm.entries
    val entries = remember(all, tab) { EntryKinds.entriesFor(tab, all) }
    val locale = Locale.getDefault()
    val zone = ZoneId.systemDefault()
    Column(modifier = Modifier.fillMaxSize()) {
        StatusHeader(vm)
        HorizontalDivider()
        if (entries.isEmpty()) {
            EmptyState(vm, tab)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    EntryRow(
                        card = cards,
                        entry = entry,
                        heading = EntryKinds.heading(entry, locale, zone),
                        audioCached = vm.isAudioCached(entry),
                        audioAvailable = vm.entryVoices(entry).isNotEmpty(),
                        downloading = vm.isAudioDownloading(entry),
                        onClick = { vm.open(Screen.Detail(entry.id)) },
                    )
                    if (!cards) HorizontalDivider()
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
private fun EmptyState(vm: MainViewModel, tab: EntryTab) {
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
            Text(
                text = if (tab == EntryTab.LESSONS) "No lessons yet." else "No daily readings yet.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "They arrive with the next sync (Settings → Sync).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One list item. With a [heading] (daily reading: the date; lesson: "Lesson 11 · date") the
 * heading comes first and the title under it; otherwise (devotions) the title, date and scripture.
 */
@Composable
private fun EntryRow(
    card: Boolean,
    entry: Entry,
    heading: String?,
    audioCached: Boolean,
    audioAvailable: Boolean,
    downloading: Boolean,
    onClick: () -> Unit,
) {
    val isDaily = EntryKinds.kindOf(entry) == EntryKinds.DAILY_READING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // In the text window each item is an opaque card so it stays distinct at any opacity.
            .then(
                if (card) {
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (heading != null) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.title.isNotBlank()) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
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
            if (entry.summary.isNotBlank()) {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (isDaily) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val labels = listOfNotNull(
                EntryKinds.dailyLessonLabel(entry),
                if (entry.archived) "Archived" else null,
            )
            if (labels.isNotEmpty()) {
                Text(
                    text = labels.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
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
                audioAvailable -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Audio available, not downloaded yet",
                    tint = MaterialTheme.colorScheme.outlineVariant,
                )
                else -> Unit
            }
        }
    }
}
