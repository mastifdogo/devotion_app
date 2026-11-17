package com.mastifdogo.devotion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.MainViewModel
import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.data.Entry
import com.mastifdogo.devotion.logic.EntryKinds
import com.mastifdogo.devotion.logic.FollowLogic
import com.mastifdogo.devotion.logic.ReminderLogic
import com.mastifdogo.devotion.logic.ScrollMode
import com.mastifdogo.devotion.logic.PlayerOffer
import com.mastifdogo.devotion.logic.Passage
import com.mastifdogo.devotion.logic.TextLogic
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.util.Locale

/** Where the scroll viewport and the body text are on screen (plain fields: read by the follow loop, not by composition). */
private class FollowGeometry {
    var viewportRootY: Float = 0f
    var viewportHeight: Int = 0
    var bodyRootY: Float = 0f
}

/** The range whose highlight/comment dialog is open, and the annotation already on it (if any). */
private class EditTarget(val passage: Passage, val existing: Annotation?)

/** The text a dialog quotes: body[[passage]] clamped to the body. */
private fun quoteOf(body: String, passage: Passage): String {
    val start = passage.start.coerceIn(0, body.length)
    return body.substring(start, passage.end.coerceIn(start, body.length))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: MainViewModel, entry: Entry) {
    val allAnnotations = vm.annotations
    val annotations = remember(allAnnotations, entry.id) { vm.annotationsFor(entry.id) }
    var editing by remember(entry.id) { mutableStateOf<EditTarget?>(null) }
    val dark = isAppInDarkTheme()
    val hasWallpaper = vm.appWallpaper != null
    val scroll = rememberScrollState()
    val locator = remember(entry.id) { BodyLocator() }
    val geometry = remember(entry.id) { FollowGeometry() }
    val density = LocalDensity.current
    val bottomSlop = with(density) { 24.dp.roundToPx() }

    // ---- read tracking: open for 3 s and scrolled to the end (or the text fits) ---------------
    LaunchedEffect(entry.id) {
        delay(3000L)
        snapshotFlow { ReminderLogic.isAtBottom(scroll.value, scroll.maxValue, bottomSlop) }.first { it }
        vm.markRead(entry.id)
    }

    // ---- follow along / auto scroll ------------------------------------------------------------
    // While this entry's audio plays the button is "Follow"; otherwise it is "Auto scroll".
    // "following" is the snapped state of Follow (the saved preference decides how it starts).
    var following by remember(entry.id) { mutableStateOf(vm.followAlong) }
    var autoScrolling by remember(entry.id) { mutableStateOf(false) }
    val playingHere = vm.playerEntryId == entry.id && vm.isPlaying
    val minFollowSpeed = with(density) { 6.dp.toPx() }

    LaunchedEffect(playingHere) {
        if (playingHere) {
            // Audio started: auto scroll becomes Follow; the preference snaps as well.
            if (autoScrolling) {
                autoScrolling = false
                following = true
            } else if (vm.followAlong) {
                following = true
            }
        }
        // Playback stopped: following just stops moving (it does not turn into auto scroll).
    }
    // A drag by hand switches both off; the button switches them on again. Our own scrolling
    // goes through dispatchRawDelta, which is not a drag and emits no interaction.
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                following = false
                autoScrolling = false
            }
        }
    }

    // Follow: every frame glide toward the line being spoken (see FollowLogic.followDelta).
    LaunchedEffect(following, playingHere, entry.id) {
        if (!following || !playingHere) return@LaunchedEffect
        var previousFrame = 0L
        var carry = 0f
        var target = -1
        var targetAt = 0L
        // The player reports its position only twice a second: run a clock in between.
        var knownPosition = -1L
        var knownAt = 0L
        while (true) {
            val now = withFrameNanos { it }
            val seconds = FollowLogic.frameSeconds(previousFrame, now)
            previousFrame = now
            val reported = vm.positionMs
            if (reported != knownPosition) {
                knownPosition = reported
                knownAt = now
            }
            // Where the spoken line should be: recomputed a few times a second.
            if (target < 0 || now - targetAt > 200_000_000L) {
                targetAt = now
                val duration = vm.durationMs
                val maxScroll = scroll.maxValue
                if (duration > 0L && maxScroll != Int.MAX_VALUE) {
                    val sinceReport = ((now - knownAt) / 1_000_000L).coerceIn(0L, 600L)
                    val position = knownPosition + (sinceReport * vm.playbackSpeed).toLong()
                    val offset = FollowLogic.spokenOffset(position, duration, entry.speechOffset, entry.body.length)
                    val lineY = locator.lineCenterY(offset)
                    if (lineY != null) {
                        // The line's y in the scrolled content: the body's place in it plus the line.
                        val bodyTop = (geometry.bodyRootY - geometry.viewportRootY).roundToInt() + scroll.value
                        target = FollowLogic.targetScroll(bodyTop + lineY, geometry.viewportHeight, maxScroll)
                    }
                }
            }
            if (target >= 0) {
                val remaining = target - (scroll.value + carry)
                val step = FollowLogic.step(carry, FollowLogic.followDelta(remaining, seconds, minFollowSpeed))
                carry = step.carry
                if (step.pixels != 0) scroll.dispatchRawDelta(step.pixels.toFloat())
            }
        }
    }

    // Auto scroll: a constant speed, every frame, from wherever the reader is to the bottom.
    LaunchedEffect(autoScrolling, playingHere, entry.id) {
        if (!autoScrolling || playingHere) return@LaunchedEffect
        var previousFrame = 0L
        var carry = 0f
        while (true) {
            val now = withFrameNanos { it }
            val seconds = FollowLogic.frameSeconds(previousFrame, now)
            previousFrame = now
            if (FollowLogic.reachedEnd(scroll.value, scroll.maxValue)) {
                // At the bottom: it switches itself off.
                autoScrolling = false
                break
            }
            val speed = with(density) { vm.autoScrollSpeed.dp.toPx() }
            val step = FollowLogic.step(carry, FollowLogic.autoDelta(speed, seconds))
            carry = step.carry
            if (step.pixels != 0) scroll.dispatchRawDelta(step.pixels.toFloat())
        }
    }

    // Keep the screen on only while the text is actually moving by itself.
    val keepScreenOn = (following && playingHere) || (autoScrolling && !playingHere)
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) view.keepScreenOn = true
        onDispose {
            if (keepScreenOn) view.keepScreenOn = false
        }
    }

    val playerBar: @Composable (Boolean) -> Unit = { inWindow ->
        PlayerBar(
            vm = vm,
            entry = entry,
            inWindow = inWindow,
            mode = FollowLogic.modeFor(playingHere),
            following = following,
            onToggleFollow = {
                following = !following
                vm.updateFollowAlong(following)
            },
            autoScrolling = autoScrolling,
            onToggleAuto = { autoScrolling = !autoScrolling },
        )
    }

    SubScreen(
        vm = vm,
        // Without a wallpaper the player is the bottom bar; with one it moves into the text window.
        bottomBar = { if (!hasWallpaper) playerBar(false) },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // The viewport (before scrolling): where it is and how high.
                    .onGloballyPositioned {
                        geometry.viewportRootY = it.positionInRoot().y
                        geometry.viewportHeight = it.size.height
                    }
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                DetailContent(
                    vm = vm,
                    entry = entry,
                    annotations = annotations,
                    dark = dark,
                    locator = locator,
                    geometry = geometry,
                    onEdit = { target -> editing = target },
                )
            }
            if (hasWallpaper) playerBar(true)
        }
    }

    val target = editing
    if (target != null) {
        val existing = target.existing
        AnnotationDialog(
            quote = quoteOf(entry.body, target.passage),
            existing = existing,
            initialColor = vm.lastHighlightColor,
            dark = dark,
            onDismiss = { editing = null },
            onSave = { color, comment ->
                vm.saveAnnotation(entry.id, target.passage.start, target.passage.end, existing, color, comment)
                editing = null
            },
            onDelete = if (existing != null) {
                {
                    vm.deleteAnnotation(existing)
                    editing = null
                }
            } else {
                null
            },
        )
    }
}

/** Header, summary, body and notes of an entry. [onEdit] opens the highlight/comment dialog. */
@Composable
private fun DetailContent(
    vm: MainViewModel,
    entry: Entry,
    annotations: List<Annotation>,
    dark: Boolean,
    locator: BodyLocator,
    geometry: FollowGeometry,
    onEdit: (EditTarget) -> Unit,
) {
    Header(vm, entry)

    if (entry.summary.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        val summary: @Composable () -> Unit = {
            Text(
                text = "Summary",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = entry.summary, style = MaterialTheme.typography.bodyMedium)
        }
        // Drawn like every list item card (see itemCardModifier), with or without a wallpaper.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(itemCardModifier(vm.appWallpaper != null, vm.windowOpacity, vm.listItemShade))
                .padding(12.dp),
        ) {
            summary()
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    if (entry.body.isBlank()) {
        if (entry.archived) {
            Text(
                text = "Archived on the server — full text is no longer available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        val references = entry.references
        val links = remember(references, entry.body) {
            TextLogic.usableLinks(references.map { Passage(it.start, it.end) }, entry.body.length)
        }
        EntryBodyText(
            body = entry.body,
            annotations = annotations,
            links = links,
            dark = dark,
            onHighlight = { start, end -> vm.highlightRange(entry.id, start, end) },
            onComment = { start, end ->
                if (vm.profile == null) {
                    vm.openProfilePrompt()
                } else {
                    onEdit(EditTarget(Passage(start, end), null))
                }
            },
            onOpenAnnotation = { annotation -> onEdit(EditTarget(Passage(annotation.start, annotation.end), annotation)) },
            onOpenLink = { index -> references.getOrNull(index)?.let { vm.openBibleReference(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { geometry.bodyRootY = it.positionInRoot().y },
            locator = locator,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Press and hold to select text, then choose Highlight or Comment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))
    NotesSection(
        vm = vm,
        entry = entry,
        annotations = annotations,
        onEdit = { annotation -> onEdit(EditTarget(Passage(annotation.start, annotation.end), annotation)) },
    )
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun Header(vm: MainViewModel, entry: Entry) {
    Text(
        text = entry.title.ifBlank { "(untitled)" },
        style = MaterialTheme.typography.headlineSmall,
    )
    // Daily reading: the full date; lesson: "Lesson 11 · date"; anything else: the date.
    val label = EntryKinds.heading(entry, Locale.getDefault(), ZoneId.systemDefault()) ?: entryDateLabel(entry)
    if (label.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (entry.archived) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Archived",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    val lessonNumber = entry.lessonNumber
    if (lessonNumber != null && EntryKinds.kindOf(entry) == EntryKinds.DAILY_READING) {
        val lesson = EntryKinds.newestLesson(vm.entries, lessonNumber)
        if (lesson != null && lesson.id != entry.id) {
            TextButton(onClick = { vm.openLesson(lessonNumber) }) {
                Text("Open ${EntryKinds.lessonLabel(lessonNumber)}")
            }
        }
    }
    val scripture = entry.scripture
    if (!scripture.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = scripture,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (vm.downloadedBibles.isNotEmpty()) {
            TextButton(onClick = { vm.openBibleAt(scripture) }) { Text("Open in Bible") }
        }
    }
}

// ---- notes --------------------------------------------------------------------------------------

@Composable
private fun NotesSection(
    vm: MainViewModel,
    entry: Entry,
    annotations: List<Annotation>,
    onEdit: (Annotation) -> Unit,
) {
    Text("Notes", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))

    val current = vm.profile
    if (current == null) {
        Text(
            text = "Choose a profile to highlight passages and write notes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.openProfilePrompt() }) { Text("Choose profile") }
        return
    }

    val savedNote = TextLogic.entryNote(annotations, entry.id)?.comment ?: ""
    var noteText by remember(entry.id, current.id, savedNote) { mutableStateOf(savedNote) }
    OutlinedTextField(
        value = noteText,
        onValueChange = { noteText = it },
        label = { Text("Your note on this entry") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = { vm.saveEntryNote(entry.id, noteText) },
        enabled = noteText.trim() != savedNote.trim(),
    ) {
        Text("Save note")
    }

    val comments = annotations
        .filter { !TextLogic.isEntryNote(it) && it.comment.isNotBlank() }
        .sortedBy { it.start }
    if (comments.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        Text("Comments on passages", style = MaterialTheme.typography.titleSmall)
        for (annotation in comments) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(annotation) }
                    .padding(vertical = 8.dp),
            ) {
                val quoted = TextLogic.quote(entry.body, annotation)
                if (quoted.isNotBlank()) {
                    Text(
                        text = "“$quoted”",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(text = annotation.comment, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }
    }
}

// ---- player -------------------------------------------------------------------------------------

/**
 * The audio player. As the Scaffold's bottom bar it pads for the navigation bar itself. Inside the
 * text window ([inWindow]) the Scaffold padding already did that, and it is transparent, so it has
 * the window's colour at the window's opacity, with a divider above it.
 */
@Composable
private fun PlayerBar(
    vm: MainViewModel,
    entry: Entry,
    inWindow: Boolean,
    mode: ScrollMode,
    following: Boolean,
    onToggleFollow: () -> Unit,
    autoScrolling: Boolean,
    onToggleAuto: () -> Unit,
) {
    Surface(
        color = if (inWindow) Color.Transparent else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (inWindow) 0.dp else 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        if (inWindow) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .then(if (inWindow) Modifier else Modifier.navigationBarsPadding())
                .fillMaxWidth()
                // The bar is taller while playing: grow and shrink smoothly instead of jumping.
                .animateContentSize()
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

            // Only the voice chosen in Settings is played (archived entries: whatever is cached).
            val offer = vm.playerOffer(entry)
            if (offer !is PlayerOffer.Play) {
                Text(
                    text = when {
                        offer is PlayerOffer.NotReady -> "Audio in ${vm.voiceLabel(offer.voice)} isn't ready yet."
                        entry.archived -> "Archived on the server — the audio is no longer available."
                        else -> "No audio for this entry yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                // No audio to play: a slim bar with just the auto-scroll control.
                AutoScrollControl(vm, selected = autoScrolling, onClick = onToggleAuto)
                return@Column
            }
            val voice = offer.voice
            // Kept audio of an archived entry in another voice is labelled as such.
            val voiceText = if (offer.otherVoice) "Voice: ${vm.voiceLabel(voice)}" else vm.voiceLabel(voice)
            val parts = FollowLogic.audioBarParts(playingHere = mode == ScrollMode.FOLLOW)

            if (parts.autoScrollRow) {
                // Not playing: "− Auto scroll +" on top, then the play button with the voice name.
                // No playback speed and no progress until it plays.
                AutoScrollControl(vm, selected = autoScrolling, onClick = onToggleAuto)
            }
            if (parts.followButton || parts.speedControl) {
                // Playing: the voice, Follow and the playback speed on one row.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (voice.isNotEmpty()) {
                        Text(
                            text = voiceText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Spacer(modifier = Modifier.size(1.dp))
                    }
                    if (parts.followButton) {
                        ScrollToggle(vm, ScrollMode.FOLLOW.label, selected = following, onClick = onToggleFollow)
                    }
                    if (parts.speedControl) SpeedControl(vm)
                }
            }

            if (vm.isAudioDownloading(entry)) {
                val fraction = vm.audioDownloadFraction(entry)
                Spacer(modifier = Modifier.height(4.dp))
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
            } else if (parts.progress) {
                val loaded = vm.playerEntryId == entry.id && vm.playerVoice == voice
                PlaybackControls(
                    vm = vm,
                    key = entry.id,
                    loaded = loaded,
                    playLabel = if (vm.isAudioCached(entry)) "Play" else "Download & play",
                    onPlayPause = { vm.playPause(entry) },
                )
            } else {
                // Not playing: just the play button and the voice name.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { vm.playPause(entry) }) {
                        Text(if (vm.isAudioCached(entry)) "Play" else "Download & play")
                    }
                    if (voice.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = voiceText,
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
    }
}

/** The Follow / Auto scroll toggle, drawn as an item box like the speed indicator; outlined in primary when on. */
@Composable
fun ScrollToggle(vm: MainViewModel, label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        // A 48dp-high touch target around the smaller box.
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (selected) "$label, on" else "$label, off" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(itemCardModifier(vm.appWallpaper != null, vm.windowOpacity, vm.listItemShade, shape))
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                // On is shown only by the primary outline and text colour (no check mark).
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** "−  [Auto scroll]  +": the toggle, with slower/faster for the scroll speed (disabled at the ends). */
@Composable
private fun AutoScrollControl(vm: MainViewModel, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { vm.stepAutoScrollSpeed(-1) },
            enabled = vm.canAutoScrollSlower,
            modifier = Modifier.semantics { contentDescription = "Slower" },
        ) {
            Text("\u2212", style = MaterialTheme.typography.titleLarge)
        }
        ScrollToggle(vm, ScrollMode.AUTO.label, selected = selected, onClick = onClick)
        IconButton(
            onClick = { vm.stepAutoScrollSpeed(1) },
            enabled = vm.canAutoScrollFaster,
            modifier = Modifier.semantics { contentDescription = "Faster" },
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** "−  1.25×  +": steps through the playback speeds (disabled at the ends). */
@Composable
fun SpeedControl(vm: MainViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { vm.stepPlaybackSpeed(-1) },
            enabled = vm.canSlowDown,
            modifier = Modifier.semantics { contentDescription = "Slower" },
        ) {
            Text("\u2212", style = MaterialTheme.typography.titleLarge)
        }
        Box(
            modifier = Modifier
                .then(itemCardModifier(vm.appWallpaper != null, vm.windowOpacity, vm.listItemShade, RoundedCornerShape(8.dp)))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(MainViewModel.speedLabel(vm.playbackSpeed), style = MaterialTheme.typography.labelLarge)
        }
        IconButton(
            onClick = { vm.stepPlaybackSpeed(1) },
            enabled = vm.canSpeedUp,
            modifier = Modifier.semantics { contentDescription = "Faster" },
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * The seek slider, position / duration and −15 s / Play-Pause / +15 s for whatever is in the
 * player (an entry or a Bible chapter). [loaded] says whether this screen's item is the one loaded;
 * [key] identifies the item (resets the slider's drag state).
 */
@Composable
fun PlaybackControls(vm: MainViewModel, key: Any, loaded: Boolean, playLabel: String, onPlayPause: () -> Unit) {
    val duration = if (loaded) vm.durationMs else 0L
    val position = if (loaded) vm.positionMs else 0L
    val playing = loaded && vm.isPlaying
    val canSeek = loaded && duration > 0L

    // While the thumb is being dragged the slider follows the finger, not the player.
    var dragValue by remember(key) { mutableStateOf<Float?>(null) }
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
        Button(onClick = onPlayPause) {
            Text(if (playing) "Pause" else playLabel)
        }
        TextButton(onClick = { vm.skip(15_000L) }, enabled = canSeek) { Text("+15 s") }
    }
}
