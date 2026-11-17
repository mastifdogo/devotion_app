package com.mastifdogo.devotion.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.logic.BibleAudioLogic
import com.mastifdogo.devotion.logic.BibleRef
import com.mastifdogo.devotion.logic.BibleTextLogic
import com.mastifdogo.devotion.logic.Passage
import com.mastifdogo.devotion.logic.TextLogic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
            VersionRow(vm = vm, info = info) {
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
            VersionRow(vm = vm, info = info) {
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
        // Chapter audio kept on the phone (at most about 300 MB; least recently played goes first).
        if (vm.bibleAudioBytes > 0L) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = { vm.clearBibleAudio() }) {
                Text("Clear Bible audio (${BibleAudioLogic.sizeLabel(vm.bibleAudioBytes)})")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** The shared list item card look (see itemCardModifier in Theme.kt). */
@Composable
private fun itemCardModifier(vm: MainViewModel, shape: Shape): Modifier =
    itemCardModifier(vm.appWallpaper != null, vm.windowOpacity, vm.listItemShade, shape)

@Composable
private fun VersionRow(vm: MainViewModel, info: BibleInfo, actions: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(itemCardModifier(vm, RoundedCornerShape(12.dp)))
            .padding(12.dp),
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
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(bible.books) { index, book ->
            Text(
                text = book.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .then(itemCardModifier(vm, RoundedCornerShape(12.dp)))
                    .clickable { vm.pickBibleBook(index) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
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
                    // Chapter tiles are list item cards like everything else.
                    .then(itemCardModifier(vm, RoundedCornerShape(8.dp)))
                    .clickable { vm.showChapter(bookIndex, chapter, null) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (chapter + 1).toString(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---- reader -------------------------------------------------------------------------------------

/** A Bible passage whose highlight/comment dialog is open: positions, its display range for the quote, the annotation on it. */
private class BibleEdit(val positions: Passage, val display: Passage, val existing: Annotation?)

@Composable
private fun BibleReader(vm: MainViewModel, bible: Bible) {
    val book = bible.books.getOrNull(vm.bibleBook)
    val verses = book?.chapters?.getOrNull(vm.bibleChapter) ?: emptyList()
    val title = if (book == null) "Bible" else "${book.name} ${vm.bibleChapter + 1}"

    BibleHeader(vm, title, showVersions = true, onTitleClick = { vm.showBibleBooks() })

    Column(modifier = Modifier.fillMaxSize()) {
        // A fresh scroll position (top) for every chapter.
        key(bible.id, vm.bibleBook, vm.bibleChapter) {
            ChapterText(vm, bible, verses, Modifier.weight(1f))
        }
        if (book != null && verses.isNotEmpty()) {
            ChapterAudioBar(
                vm = vm,
                ref = BibleRef(bible.id, vm.bibleBook, vm.bibleChapter + 1),
                title = BibleAudioLogic.chapterTitle(book.name, vm.bibleChapter + 1),
                versionName = bible.name.ifBlank { bible.id },
            )
        }
        val hasWallpaper = vm.appWallpaper != null
        if (hasWallpaper) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(
            color = if (hasWallpaper) Color.Transparent else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = if (hasWallpaper) 0.dp else 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
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

/**
 * Listen to the chapter: shown when the server can read chapters aloud (or this one is already on
 * the phone). Listen asks the server, which may still have to make the audio ("Preparing…", with
 * Cancel), downloads it and plays it through the same player as entries; errors offer Retry.
 * Like the entry's bar, the speed, slider and −15/+15 show only while it is playing.
 */
@Composable
private fun ChapterAudioBar(vm: MainViewModel, ref: BibleRef, title: String, versionName: String) {
    val loadedHere = vm.playerBibleRef == ref
    if (!vm.bibleAudioAvailable && !vm.isChapterAudioCached(ref) && !loadedHere) return
    val playingHere = loadedHere && vm.isPlaying
    val play = { vm.playChapter(ref, title, versionName) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val error = vm.chapterAudioError?.takeIf { it.first == ref }?.second
        when {
            vm.preparingChapter == ref -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Preparing the audio\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.cancelChapterAudio() }) { Text("Cancel") }
            }

            playingHere -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = versionName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SpeedControl(vm)
                }
                PlaybackControls(vm = vm, key = ref, loaded = true, playLabel = "Play", onPlayPause = play)
            }

            else -> {
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = play) {
                        Text(
                            when {
                                error != null -> "Retry"
                                loadedHere -> "Play"
                                else -> "Listen"
                            },
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = versionName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The chapter as one selectable text (so a selection can run across verses), with the same
 * highlights, Highlight/Comment menu, 💬 markers and dialog as entries, the referenced verses
 * marked, and a note on the whole chapter below. Annotations are per version.
 */
@Composable
private fun ChapterText(vm: MainViewModel, bible: Bible, verses: List<String>, modifier: Modifier) {
    val ref = BibleRef(bible.id, vm.bibleBook, vm.bibleChapter + 1)
    val layout = remember(verses) { BibleTextLogic.layout(verses) }
    val numbers = remember(layout) { BibleTextLogic.numberRanges(layout) }
    val allAnnotations = vm.annotations
    val annotations = remember(allAnnotations, ref) { vm.bibleAnnotations(ref) }
    // HighlightLogic works on offsets in the shown text: convert verse positions to display offsets.
    val shown = remember(annotations, layout) {
        annotations.map {
            it.copy(start = BibleTextLogic.toDisplay(layout, it.start), end = BibleTextLogic.toDisplay(layout, it.end))
        }
    }
    val markFrom = vm.bibleVerse
    val markTo = vm.bibleVerseEnd ?: markFrom
    val marked = if (markFrom != null && markTo != null) BibleTextLogic.versesRange(layout, markFrom, markTo) else null
    val scrollTarget = markFrom?.let { BibleTextLogic.verseStart(layout, it) }
    val dark = isAppInDarkTheme()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val topPadding = with(LocalDensity.current) { 12.dp.roundToPx() }
    var editing by remember { mutableStateOf<BibleEdit?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (verses.isEmpty()) {
            Text(
                text = "This chapter has no text in this version.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EntryBodyText(
                body = layout.text,
                annotations = shown,
                links = emptyList(),
                dark = dark,
                onHighlight = { start, end ->
                    BibleTextLogic.selectionToRange(layout, start, end)?.let { vm.highlightRange(0L, it.start, it.end, ref) }
                },
                onComment = { start, end ->
                    val range = BibleTextLogic.selectionToRange(layout, start, end)
                    if (range != null) {
                        if (vm.profile == null) {
                            vm.openProfilePrompt()
                        } else {
                            editing = BibleEdit(range, Passage(start, end), null)
                        }
                    }
                },
                onOpenAnnotation = { tapped ->
                    val original = annotations.firstOrNull { it.id == tapped.id }
                    if (original != null) {
                        editing = BibleEdit(Passage(original.start, original.end), Passage(tapped.start, tapped.end), original)
                    }
                },
                onOpenLink = { },
                modifier = Modifier.fillMaxWidth(),
                verseNumbers = numbers,
                marked = marked,
                markColor = MaterialTheme.colorScheme.secondaryContainer,
                scrollToOffset = scrollTarget,
                scrollKey = scrollTarget?.let { Triple(ref, markFrom, markTo) },
                onScrollTarget = { y ->
                    scope.launch {
                        // Wait (briefly) until the column knows how far it can scroll.
                        withTimeoutOrNull(1500L) { snapshotFlow { scroll.maxValue }.first { it > 0 } }
                        scroll.scrollTo(y + topPadding)
                    }
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Press and hold to select text, then choose Highlight or Comment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            ChapterNote(vm, ref)
        }
    }

    val edit = editing
    if (edit != null) {
        val start = edit.display.start.coerceIn(0, layout.text.length)
        AnnotationDialog(
            quote = layout.text.substring(start, edit.display.end.coerceIn(start, layout.text.length)),
            existing = edit.existing,
            initialColor = vm.lastHighlightColor,
            dark = dark,
            onDismiss = { editing = null },
            onSave = { color, comment ->
                vm.saveAnnotation(0L, edit.positions.start, edit.positions.end, edit.existing, color, comment, ref)
                editing = null
            },
            onDelete = edit.existing?.let { existing ->
                {
                    vm.deleteAnnotation(existing)
                    editing = null
                }
            },
        )
    }
}

/** The note on the whole chapter (like the entry note), for the current profile and version. */
@Composable
private fun ChapterNote(vm: MainViewModel, ref: BibleRef) {
    val current = vm.profile ?: return
    val saved = TextLogic.chapterNote(vm.annotations.map { it.annotation }, ref.bibleId, ref.book, ref.chapter)?.comment ?: ""
    var text by remember(ref, current.id, saved) { mutableStateOf(saved) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Your note on this chapter") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = { vm.saveChapterNote(ref, text) }, enabled = text.trim() != saved.trim()) {
        Text("Save note")
    }
}
