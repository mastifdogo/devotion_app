package com.mastifdogo.devotion.ui

import android.app.WallpaperManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mastifdogo.devotion.MainViewModel
import com.mastifdogo.devotion.logic.ServerWallpaper
import com.mastifdogo.devotion.logic.WallpaperLibrary
import com.mastifdogo.devotion.logic.WallpaperLogic

/** Browse the wallpapers on the server (opened from Settings); nothing works without the server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpapersScreen(vm: MainViewModel) {
    val hasWallpaper = vm.appWallpaper != null
    val preview = vm.wallPreview
    Scaffold(
        containerColor = screenContainerColor(hasWallpaper),
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(if (preview != null) "Wallpaper" else "Wallpapers") },
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
            val library = vm.wallLibrary
            when {
                preview != null -> WallpaperPreview(vm, preview)
                vm.wallUnreachable -> CenteredMessage(
                    text = "Connect to your server to browse wallpapers",
                    busy = vm.wallLoading,
                    onRetry = { vm.refreshWallpapers() },
                )
                vm.wallUnsupported -> CenteredMessage(
                    text = "This server has no wallpapers yet (update it).",
                    busy = vm.wallLoading,
                    onRetry = { vm.refreshWallpapers() },
                )
                library == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> WallpaperBrowser(vm, library)
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, busy: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRetry, enabled = !busy) { Text(if (busy) "Connecting…" else "Retry") }
    }
}

@Composable
private fun WallpaperBrowser(vm: MainViewModel, library: WallpaperLibrary) {
    val chips = remember(library) { WallpaperLogic.chips(library) }
    val topic = vm.wallTopic
    val shown = remember(library, topic) { WallpaperLogic.filter(library, topic) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (chip in chips) {
                FilterChip(
                    selected = chip.topicId == topic,
                    onClick = { vm.selectWallTopic(chip.topicId) },
                    label = { Text(chip.label) },
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
        if (vm.wallLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (library.wallpapers.isEmpty()) {
            Text(
                text = "The server has no wallpapers yet. They are added on the server's admin page.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.id }) { wallpaper -> Thumbnail(vm, wallpaper) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Wallpapers from wallhaven.cc",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(vm: MainViewModel, wallpaper: ServerWallpaper) {
    val placeholder = wallpaper.color?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(placeholder)
            .clickable { vm.openWallpaperPreview(wallpaper) },
    ) {
        AsyncImage(
            model = vm.wallThumbUrl(wallpaper.id),
            contentDescription = "Wallpaper",
            imageLoader = vm.imageLoader,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun WallpaperPreview(vm: MainViewModel, wallpaper: ServerWallpaper) {
    var loading by remember(wallpaper.id) { mutableStateOf(true) }
    var failed by remember(wallpaper.id) { mutableStateOf(false) }
    var choosingTarget by remember(wallpaper.id) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = vm.wallImageUrl(wallpaper.id),
                contentDescription = "Wallpaper preview",
                imageLoader = vm.imageLoader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onLoading = {
                    loading = true
                    failed = false
                },
                onSuccess = { loading = false },
                onError = {
                    loading = false
                    failed = true
                },
            )
            if (loading) CircularProgressIndicator()
            if (failed) {
                Text(
                    text = "Could not load the wallpaper from the server.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                val resolution = WallpaperLogic.resolutionLabel(wallpaper)
                if (resolution != null) {
                    Text(text = resolution, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val busy = vm.wallBusy
                Button(
                    onClick = { vm.useWallpaperInApp(wallpaper) },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (vm.appWallpaperId == wallpaper.id) "Use in app (current)" else "Use in app")
                }
                OutlinedButton(
                    onClick = { choosingTarget = true },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Set as phone wallpaper")
                }
                if (busy != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = busy, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                val message = vm.wallMessage
                if (message != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                val source = wallpaper.sourceUrl
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Wallpapers from wallhaven.cc",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (source != null) {
                        TextButton(
                            onClick = {
                                try {
                                    uriHandler.openUri(source)
                                } catch (e: Exception) {
                                    // No browser: nothing to do.
                                }
                            },
                        ) {
                            Text("View on wallhaven.cc")
                        }
                    }
                }
            }
        }
    }

    if (choosingTarget) {
        AlertDialog(
            onDismissRequest = { choosingTarget = false },
            title = { Text("Set as phone wallpaper") },
            text = {
                Column {
                    TargetButton("Home screen") {
                        choosingTarget = false
                        vm.setPhoneWallpaper(wallpaper, WallpaperManager.FLAG_SYSTEM)
                    }
                    TargetButton("Lock screen") {
                        choosingTarget = false
                        vm.setPhoneWallpaper(wallpaper, WallpaperManager.FLAG_LOCK)
                    }
                    TargetButton("Both") {
                        choosingTarget = false
                        vm.setPhoneWallpaper(wallpaper, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingTarget = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TargetButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text = label, modifier = Modifier.fillMaxWidth())
    }
}
