package com.mastifdogo.devotion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.BibleView
import com.mastifdogo.devotion.MainViewModel
import com.mastifdogo.devotion.data.Bible
import com.mastifdogo.devotion.data.BibleInfo

/** Content of the main screen's Bible tab: version list, book list, chapter list or reader. */
@Composable
fun BibleTab(vm: MainViewModel, modifier: Modifier = Modifier) {
    val bible = vm.openBible
    Column(modifier = modifier) {
        when {
            vm.bibleLoading -> BibleLoading(vm)
            bible == null || vm.effectiveBibleView == BibleView.VERSIONS -> BibleVersions(vm)
            vm.bibleView == BibleView.BOOKS -> BibleBooks(vm, bible)
            vm.bibleView == BibleView.CHAPTERS -> BibleChapters(vm, bible)
            else -> BibleReader(vm, bible)
        }
    }
}

/** A slim header inside the tab: Back (when there is somewhere to go), title, Versions. */
@Composable
private fun BibleHeader(
    vm: MainViewModel,
    title: String,
    showVersions: Boolean,
    onTitleClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.canGoBack) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier),
        )
        if (showVersions) {
            TextButton(onClick = { vm.showBibleVersions() }) { Text("Versions") }
        }
    }
    HorizontalDivider()
}

@Composable
private fun BibleLoading(vm: MainViewModel) {
    BibleHeader(vm, "Bible", showVersions = false)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text("Opening the Bible…", style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- versions -----------------------------------------------------------------------------------

@Composable
private fun BibleVersions(vm: MainViewModel) {
    BibleHeader(vm, "Bible versions", showVersions = false)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val notice = vm.bibleNotice
        if (notice != null) {
            Text(text = notice, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
        }
        val error = vm.bibleError
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        val downloaded = vm.downloadedBibles
        Text("On this device", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        if (downloaded.isEmpty()) {
            Text(
                text = "No Bible version has been downloaded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (info in downloaded) {
            VersionRow(info = info) {
                Button(onClick = { vm.openBibleVersion(info.id) }) { Text("Read") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.deleteBible(info) }) { Text("Delete") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "On the server",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.refreshServerBibles() }, enabled = !vm.serverBiblesLoading) {
                Text(if (vm.serverBiblesLoading) "Loading…" else "Refresh")
            }
        }
        val downloadedIds = downloaded.map { it.id }.toSet()
        val available = vm.serverBibles
        if (!vm.isServerConfigured) {
            Text(
                text = "Set the server address in Settings to download Bible versions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (vm.serverBiblesLoaded && available.isEmpty()) {
            Text(
                text = "The server has no Bible versions yet. Versions are added on the server's admin page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (info in available) {
            val running = vm.bibleDownloads.containsKey(info.id)
            val fraction = vm.bibleDownloads[info.id]
            VersionRow(info = info) {
                when {
                    running -> Text(
                        text = if (fraction != null) "Downloading… ${(fraction * 100f).toInt()}%" else "Downloading…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    info.id in downloadedIds -> {
                        Text(
                            text = "Downloaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { vm.downloadBible(info) }) { Text("Download again") }
                    }
                    else -> Button(onClick = { vm.downloadBible(info) }) { Text("Download") }
                }
            }
            if (running) {
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun VersionRow(info: BibleInfo, actions: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(text = info.name.ifBlank { info.id }, style = MaterialTheme.typography.bodyLarge)
        val details = listOfNotNull(
            info.language?.takeIf { it.isNotBlank() },
            info.bytes?.takeIf { it > 0L }?.let { formatBytes(it) },
        ).joinToString(" · ")
        if (details.isNotEmpty()) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

// ---- books and chapters -------------------------------------------------------------------------

@Composable
private fun BibleBooks(vm: MainViewModel, bible: Bible) {
    BibleHeader(vm, bible.name.ifBlank { "Bible" }, showVersions = true)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(bible.books) { index, book ->
            Text(
                text = book.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.pickBibleBook(index) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun BibleChapters(vm: MainViewModel, bible: Bible) {
    val bookIndex = vm.biblePickBook.coerceIn(0, (bible.books.size - 1).coerceAtLeast(0))
    val book = bible.books.getOrNull(bookIndex)
    BibleHeader(vm, book?.name ?: "Chapters", showVersions = true)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(64.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(book?.chapters?.size ?: 0) { chapter ->
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .fillMaxWidth()
                    .clickable { vm.showChapter(bookIndex, chapter, null) }
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (chapter + 1).toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---- reader -------------------------------------------------------------------------------------

@Composable
private fun BibleReader(vm: MainViewModel, bible: Bible) {
    val book = bible.books.getOrNull(vm.bibleBook)
    val verses = book?.chapters?.getOrNull(vm.bibleChapter) ?: emptyList()
    val title = if (book == null) "Bible" else "${book.name} ${vm.bibleChapter + 1}"
    val listState = rememberLazyListState()
    val markFrom = vm.bibleVerse
    val markTo = vm.bibleVerseEnd ?: markFrom

    // Jump to the referenced verse (or the top) whenever another chapter is shown.
    LaunchedEffect(bible.id, vm.bibleBook, vm.bibleChapter, markFrom) {
        val target = if (markFrom != null && verses.isNotEmpty()) (markFrom - 1).coerceIn(0, verses.size - 1) else 0
        listState.scrollToItem(target)
    }

    BibleHeader(vm, title, showVersions = true, onTitleClick = { vm.showBibleBooks() })

    val numberStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = MaterialTheme.typography.labelMedium.fontSize,
    )
    val markColor = MaterialTheme.colorScheme.secondaryContainer
    val markTextColor = MaterialTheme.colorScheme.onSecondaryContainer
    Column(modifier = Modifier.fillMaxSize()) {
        SelectionContainer(
            modifier = Modifier
                .weight(1f)
                .background(readingCardColor(vm.appWallpaper != null, vm.wallpaperStrength)),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                if (verses.isEmpty()) {
                    item {
                        Text(
                            text = "This chapter has no text in this version.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
                itemsIndexed(verses) { index, verse ->
                    val number = index + 1
                    val marked = markFrom != null && markTo != null && number >= markFrom && number <= markTo
                    val builder = AnnotatedString.Builder()
                    builder.append("$number ")
                    builder.addStyle(numberStyle, 0, builder.length)
                    builder.append(verse)
                    Text(
                        text = builder.toAnnotatedString(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (marked) markTextColor else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (marked) Modifier.background(markColor) else Modifier)
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.previousChapter() }, enabled = vm.hasPreviousChapter) { Text("‹ Previous") }
                TextButton(onClick = { vm.showBibleBooks() }) { Text("Books") }
                TextButton(onClick = { vm.nextChapter() }, enabled = vm.hasNextChapter) { Text("Next ›") }
            }
        }
    }
}
