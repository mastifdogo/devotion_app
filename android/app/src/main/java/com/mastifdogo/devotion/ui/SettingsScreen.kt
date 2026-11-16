package com.mastifdogo.devotion.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.BuildConfig
import com.mastifdogo.devotion.MainViewModel
import com.mastifdogo.devotion.Screen
import com.mastifdogo.devotion.logic.AutoSync
import com.mastifdogo.devotion.logic.SyncScheduleLogic
import com.mastifdogo.devotion.logic.WindowLogic
import com.mastifdogo.devotion.UpdateUi
import com.mastifdogo.devotion.update.Updater
import android.app.TimePickerDialog
import android.text.format.DateFormat
import java.io.File
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val hasWallpaper = vm.appWallpaper != null
    Scaffold(
        containerColor = screenContainerColor(hasWallpaper),
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = barContainerColor(hasWallpaper)),
            )
        },
    ) { padding ->
        ContentWindow(hasWallpaper, vm.windowOpacity, Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                ServerSection(vm)
                SectionDivider()
                SyncSection(vm)
                SectionDivider()
                ProfileSection(vm)
                SectionDivider()
                PlaybackSection(vm)
                SectionDivider()
                if (vm.voicesSupported) {
                    VoiceSection(vm)
                    SectionDivider()
                }
                AppearanceSection(vm)
                SectionDivider()
                WallpaperSection(vm)
                SectionDivider()
                CacheSection(vm)
                SectionDivider()
                UpdateSection(vm)
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
}

/** Last sync, Sync now, and when to sync automatically (also in the background). */
@Composable
private fun SyncSection(vm: MainViewModel) {
    val context = LocalContext.current
    SectionTitle("Sync")
    val last = SyncScheduleLogic.lastSyncedLabel(
        System.currentTimeMillis(),
        vm.lastSyncMillis,
        ZoneId.systemDefault(),
        Locale.getDefault(),
    )
    Text(text = "Last synced: $last", style = MaterialTheme.typography.bodyMedium)
    val error = vm.lastSyncErrorText
    if (error != null) {
        Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = { vm.sync() }, enabled = !vm.syncing && vm.isServerConfigured) {
        Text(if (vm.syncing) "Syncing…" else "Sync now")
    }
    if (vm.syncing) {
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text("Sync automatically", style = MaterialTheme.typography.bodyLarge)
    for (mode in AutoSync.entries) {
        val selected = vm.autoSync == mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = { vm.updateAutoSync(mode) })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (mode == AutoSync.DAILY) {
                    "${mode.label} ${SyncScheduleLogic.timeLabel(vm.dailySyncMinutes)}"
                } else {
                    mode.label
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (mode == AutoSync.DAILY) {
                TextButton(onClick = { showDailyTimePicker(context, vm) }) { Text("Change time") }
            }
        }
    }
    Text(
        text = "Hourly, 6-hourly and daily syncs also run while the app is closed, when the phone is " +
            "online; Android may delay them a little to save battery.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The platform time picker (stable on every Android version the app supports). */
private fun showDailyTimePicker(context: Context, vm: MainViewModel) {
    val minutes = vm.dailySyncMinutes
    TimePickerDialog(
        context,
        { _, hour, minute -> vm.updateDailySyncTime(hour, minute) },
        minutes / 60,
        minutes % 60,
        DateFormat.is24HourFormat(context),
    ).show()
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ServerSection(vm: MainViewModel) {
    SectionTitle("Server")
    OutlinedTextField(
        value = vm.serverUrl,
        onValueChange = { vm.updateServerUrl(it) },
        label = { Text("Server address") },
        placeholder = { Text("192.168.1.50") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(4.dp))
    val effective = vm.effectiveServerUrl
    Text(
        text = if (effective.isEmpty()) {
            "Type the IP address or name of the computer the Devotion server runs on."
        } else {
            "The app connects to $effective"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = { vm.testConnection() }, enabled = !vm.testing && vm.isServerConfigured) {
        Text(if (vm.testing) "Testing…" else "Test connection")
    }
    val result = vm.testResult
    if (result != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = result,
            style = MaterialTheme.typography.bodyMedium,
            color = if (result.startsWith("Failed")) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun ProfileSection(vm: MainViewModel) {
    SectionTitle("Profile")
    val current = vm.profile
    Text(
        text = if (current != null) "Signed in as ${current.name}" else "No profile chosen",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "Highlights and notes belong to a profile.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = { vm.openProfilePrompt() }, enabled = vm.isServerConfigured) {
        Text(if (current != null) "Switch profile" else "Choose profile")
    }
}

/** The voice audio is played (and downloaded automatically) in; hidden for servers without voices. */
@Composable
private fun VoiceSection(vm: MainViewModel) {
    SectionTitle("Voice")
    val voices = vm.voices
    if (voices.isEmpty()) {
        Text(
            text = "The server has no voices yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val selectedVoice = vm.selectedSettingsVoice
    for (voice in voices) {
        val selected = voice.id == selectedVoice
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = { vm.updatePreferredVoice(voice.id) },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                val name = voice.name.ifBlank { voice.id }
                Text(
                    text = if (voice.id == vm.serverDefaultVoice) "$name (server default)" else name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                val description = voice.description
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Text(
        text = "Only this voice is kept on the phone: changing it deletes the other voices' audio " +
            "(archived entries keep theirs) and downloads this one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PlaybackSection(vm: MainViewModel) {
    SectionTitle("Playback speed")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (speed in MainViewModel.PLAYBACK_SPEEDS) {
            val label = MainViewModel.speedLabel(speed)
            if (speed == vm.playbackSpeed) {
                Button(onClick = { }) { Text(label) }
            } else {
                OutlinedButton(onClick = { vm.updatePlaybackSpeed(speed) }) { Text(label) }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

/** The app wallpaper: current one, text window opacity, remove; the row opens the Wallpapers screen. */
@Composable
private fun WallpaperSection(vm: MainViewModel) {
    SectionTitle("Wallpaper")
    val current = vm.appWallpaper
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.open(Screen.Wallpapers) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbModifier = Modifier
            .size(width = 45.dp, height = 80.dp)
            .clip(RoundedCornerShape(8.dp))
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = "Current app wallpaper",
                contentScale = ContentScale.Crop,
                modifier = thumbModifier,
            )
        } else {
            Box(
                modifier = thumbModifier.background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "None",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Wallpaper", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (current != null) "Shown behind the app. Tap to choose another." else "None. Tap to choose one from your server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text("On each app start", style = MaterialTheme.typography.bodyLarge)
    RadioRow("Keep my wallpaper", !vm.newWallpaperOnStart) { vm.updateNewWallpaperOnStart(false) }
    RadioRow("New wallpaper each time", vm.newWallpaperOnStart) { vm.updateNewWallpaperOnStart(true) }
    if (vm.newWallpaperOnStart) {
        Text("From", style = MaterialTheme.typography.bodyMedium)
        val topics = vm.wallTopicsCached
        val chosen = vm.wallStartTopic
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = chosen == null, onClick = { vm.updateWallStartTopic(null) }, label = { Text("All topics") })
            for (topic in topics) {
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = chosen == topic.id,
                    onClick = { vm.updateWallStartTopic(topic.id) },
                    label = { Text(topic.name) },
                )
            }
            if (chosen != null && topics.none { it.id == chosen }) {
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(selected = true, onClick = { vm.updateWallStartTopic(null) }, label = { Text(chosen) })
            }
        }
        if (topics.isEmpty()) {
            Text(
                text = "Open Wallpapers once to load your server's topics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    // The text window over the wallpaper: 50% (wallpaper shows through) to 100% (opaque).
    Text(
        text = "Text window opacity: ${WindowLogic.opacityLabel(vm.windowOpacity)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = vm.windowOpacity,
        onValueChange = { vm.updateWindowOpacity(it) },
        valueRange = WindowLogic.MIN_OPACITY..WindowLogic.MAX_OPACITY,
        enabled = current != null,
        modifier = Modifier.fillMaxWidth(),
    )
    if (current == null) {
        Text(
            text = "Choose a wallpaper first",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    // The cards inside the window (entries, Bible chapters, books, versions): 0% = outline only.
    Text(
        text = "List item opacity: ${WindowLogic.itemOpacityLabel(vm.listItemOpacity)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = vm.listItemOpacity,
        onValueChange = { vm.updateListItemOpacity(it) },
        valueRange = 0f..1f,
        enabled = current != null,
        modifier = Modifier.fillMaxWidth(),
    )
    if (current == null) {
        Text(
            text = "Choose a wallpaper first",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        OutlinedButton(onClick = { vm.removeAppWallpaper() }) { Text("Remove app wallpaper") }
    }
}

@Composable
private fun AppearanceSection(vm: MainViewModel) {
    SectionTitle("Theme")
    for (mode in ThemeMode.entries) {
        val selected = vm.themeMode == mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = { vm.updateThemeMode(mode) },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The whole row is the click target, so the button itself takes no clicks.
            RadioButton(selected = selected, onClick = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (mode == ThemeMode.SYSTEM) "System (follow device)" else mode.label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun CacheSection(vm: MainViewModel) {
    var confirmClear by remember { mutableStateOf(false) }

    SectionTitle("Offline storage")
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Download audio automatically", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "After each sync, save each entry's narration in your voice on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = vm.autoDownload, onCheckedChange = { vm.updateAutoDownload(it) })
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "${vm.entries.size} entries and ${vm.cachedAudio.size} audio files saved on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = { confirmClear = true }) { Text("Clear cache") }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cache?") },
            text = { Text("This removes all saved entries and audio from this device. They are downloaded again on the next sync. Your notes and downloaded Bibles are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearCache()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun UpdateSection(vm: MainViewModel) {
    val context = LocalContext.current

    SectionTitle("App")
    Text(
        text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "Updates come from github.com/${BuildConfig.GITHUB_REPO}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = vm.githubToken,
        onValueChange = { vm.updateGithubToken(it) },
        label = { Text("GitHub token (optional)") },
        supportingText = { Text("Only needed when the repository is private.") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))

    val state = vm.update
    val busy = state is UpdateUi.Checking || state is UpdateUi.Downloading
    Button(onClick = { vm.checkForUpdates() }, enabled = !busy) {
        Text(if (state is UpdateUi.Checking) "Checking…" else "Check for updates")
    }
    Spacer(modifier = Modifier.height(8.dp))

    when (state) {
        is UpdateUi.Idle, is UpdateUi.Checking -> Unit

        is UpdateUi.UpToDate -> Text(
            text = "You are up to date (latest release: ${state.latestVersion}).",
            style = MaterialTheme.typography.bodyMedium,
        )

        is UpdateUi.Error -> Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        is UpdateUi.Available -> {
            Text(
                text = "Version ${state.version} is available.",
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = state.notes, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (state.asset != null) {
                Button(onClick = { vm.downloadUpdate() }) { Text("Download and install") }
            } else {
                Text(
                    text = "This release has no .apk file attached, so it cannot be installed from here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is UpdateUi.Downloading -> {
            val fraction = state.progress
            Text(
                text = if (fraction != null) {
                    "Downloading version ${state.version}… ${(fraction * 100f).toInt()}%"
                } else {
                    "Downloading version ${state.version}…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is UpdateUi.ReadyToInstall -> {
            Text(
                text = "Version ${state.version} is downloaded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { launchInstaller(context, state.file, vm) }) { Text("Install") }
            Text(
                text = "Android may first ask you to allow this app to install apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun launchInstaller(context: Context, apk: File, vm: MainViewModel) {
    try {
        context.startActivity(Updater.installIntent(context, apk))
    } catch (e: Exception) {
        vm.reportUpdateError("Could not open the installer: ${e.message ?: e.javaClass.simpleName}")
    }
}
