package com.mastifdogo.devotion

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.data.AnnotationFile
import com.mastifdogo.devotion.data.AnnotationStore
import com.mastifdogo.devotion.data.ApiClient
import com.mastifdogo.devotion.data.ApiException
import com.mastifdogo.devotion.data.Bible
import com.mastifdogo.devotion.data.BibleInfo
import com.mastifdogo.devotion.data.BibleStore
import com.mastifdogo.devotion.data.Entry
import com.mastifdogo.devotion.data.EntryReference
import com.mastifdogo.devotion.data.EntryStore
import com.mastifdogo.devotion.data.Profile
import com.mastifdogo.devotion.data.SettingsStore
import com.mastifdogo.devotion.data.StoredAnnotation
import com.mastifdogo.devotion.logic.BibleRefLogic
import com.mastifdogo.devotion.logic.ServerAddress
import com.mastifdogo.devotion.logic.TextLogic
import com.mastifdogo.devotion.ui.ThemeMode
import com.mastifdogo.devotion.update.ReleaseAsset
import com.mastifdogo.devotion.update.UpdateCheck
import com.mastifdogo.devotion.update.UpdateLogic
import com.mastifdogo.devotion.update.Updater
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    data object EntryList : Screen()
    data class Detail(val id: Long) : Screen()
    data object Settings : Screen()
    data object Bible : Screen()
}

/** What the Bible screen is showing. */
enum class BibleView { VERSIONS, BOOKS, CHAPTERS, READER }

sealed class UpdateUi {
    data object Idle : UpdateUi()
    data object Checking : UpdateUi()
    data class UpToDate(val latestVersion: String) : UpdateUi()
    data class Available(val version: String, val notes: String, val asset: ReleaseAsset?) : UpdateUi()
    data class Downloading(val version: String, val progress: Float?) : UpdateUi()
    data class ReadyToInstall(val version: String, val file: File) : UpdateUi()
    data class Error(val message: String) : UpdateUi()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val store = EntryStore(application.filesDir)
    private val annotationStore = AnnotationStore(application.filesDir)
    private val bibleStore = BibleStore(application.filesDir)
    private val api = ApiClient()
    private val updater = Updater(application.cacheDir)

    // ---- navigation -------------------------------------------------------------------------

    var screen: Screen by mutableStateOf(Screen.EntryList)
        private set

    fun open(target: Screen) {
        if (target is Screen.Bible) openBibleScreen() else screen = target
    }

    /** One step back; leaving Settings triggers a sync so a new server address takes effect. */
    fun back() {
        when (screen) {
            is Screen.Bible -> bibleBack()
            is Screen.Settings -> {
                screen = Screen.EntryList
                if (isServerConfigured) sync()
            }
            else -> screen = Screen.EntryList
        }
    }

    // ---- settings ---------------------------------------------------------------------------

    /** The server address as typed; see [ServerAddress.normalize] for what is actually used. */
    var serverUrl: String by mutableStateOf(settings.serverUrl)
        private set
    var githubToken: String by mutableStateOf(settings.githubToken)
        private set
    var autoDownload: Boolean by mutableStateOf(settings.autoDownloadAudio)
        private set
    var themeMode: ThemeMode by mutableStateOf(ThemeMode.fromKey(settings.themeMode))
        private set
    var playbackSpeed: Float by mutableStateOf(sanitizeSpeed(settings.playbackSpeed))
        private set

    val isServerConfigured: Boolean
        get() = ServerAddress.normalize(serverUrl).isNotEmpty()

    /** The address requests really go to, e.g. "http://192.168.1.50:8787" ("" when none). */
    val effectiveServerUrl: String
        get() = ServerAddress.normalize(serverUrl)

    private val serverKey: String
        get() = ServerAddress.key(serverUrl)

    fun updateServerUrl(value: String) {
        serverUrl = value
        settings.serverUrl = value.trim()
        testResult = null
        loadProfileContext()
    }

    fun updateGithubToken(value: String) {
        githubToken = value
        settings.githubToken = value.trim()
    }

    fun updateAutoDownload(value: Boolean) {
        autoDownload = value
        settings.autoDownloadAudio = value
        if (value) startAutoDownload() else autoDownloadJob?.cancel()
    }

    fun updateThemeMode(value: ThemeMode) {
        themeMode = value
        settings.themeMode = value.key
    }

    fun updatePlaybackSpeed(value: Float) {
        val speed = sanitizeSpeed(value)
        playbackSpeed = speed
        settings.playbackSpeed = speed
        player?.setPlaybackSpeed(speed)
    }

    /** Steps to the next speed in [PLAYBACK_SPEEDS], wrapping around. */
    fun cyclePlaybackSpeed() {
        val index = PLAYBACK_SPEEDS.indexOf(playbackSpeed)
        updatePlaybackSpeed(PLAYBACK_SPEEDS[(index + 1) % PLAYBACK_SPEEDS.size])
    }

    var testing: Boolean by mutableStateOf(false)
        private set
    var testResult: String? by mutableStateOf(null)
        private set

    fun testConnection() {
        if (testing) return
        val url = serverUrl
        testing = true
        testResult = null
        viewModelScope.launch {
            testResult = try {
                withContext(Dispatchers.IO) {
                    val health = api.health(url)
                    if (!health.ok) throw IllegalStateException("The server reports it is not healthy.")
                    val entries = api.entries(url, null)
                    val count = entries.entries.count { !it.deleted }
                    "Connected to ${ServerAddress.normalize(url)}. Server version ${health.version ?: "unknown"}, $count entries."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${describe(e)}"
            } finally {
                testing = false
            }
        }
    }

    // ---- profile ----------------------------------------------------------------------------

    /** The profile chosen for the current server address, or null. */
    var profile: Profile? by mutableStateOf(null)
        private set
    var profilePromptOpen: Boolean by mutableStateOf(false)
        private set
    /** Existing profiles on the server, offered in the prompt. */
    var profileChoices: List<Profile> by mutableStateOf(emptyList())
        private set
    var profileBusy: Boolean by mutableStateOf(false)
        private set
    var profileError: String? by mutableStateOf(null)
        private set

    /** Server for which the user said "Not now"; the prompt is not forced again this session. */
    private var profilePromptDeclinedKey: String? = null

    /** Loads the stored profile and its annotations for the current server address. */
    private fun loadProfileContext() {
        val key = serverKey
        val stored = if (isServerConfigured) settings.profileFor(key) else null
        profile = stored
        if (stored == null) {
            annotations = emptyList()
            annotationServerTime = null
        } else {
            val file = annotationStore.load(key, stored.id)
            annotations = file.annotations
            annotationServerTime = file.serverTime
        }
    }

    /** Opens the "What's your name?" prompt and loads the existing profiles from the server. */
    fun openProfilePrompt() {
        if (!isServerConfigured) {
            syncError = "Set the server address in Settings first."
            return
        }
        profilePromptOpen = true
        profileError = null
        val url = serverUrl
        val key = serverKey
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { api.profiles(url) }
                if (serverKey == key) profileChoices = list
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (serverKey == key && profilePromptOpen) {
                    profileError = "Could not load the profiles: ${describe(e)}"
                }
            }
        }
    }

    fun dismissProfilePrompt() {
        profilePromptOpen = false
        profilePromptDeclinedKey = serverKey
    }

    /** Creates the profile on the server (or gets the existing one with that name) and selects it. */
    fun submitProfileName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            profileError = "Please type your name."
            return
        }
        if (profileBusy) return
        val url = serverUrl
        val key = serverKey
        profileBusy = true
        profileError = null
        viewModelScope.launch {
            try {
                val created = withContext(Dispatchers.IO) { api.createProfile(url, trimmed) }
                if (serverKey == key) selectProfile(created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                profileError = describe(e)
            } finally {
                profileBusy = false
            }
        }
    }

    fun selectProfile(chosen: Profile) {
        settings.setProfileFor(serverKey, Profile(id = chosen.id, name = chosen.name))
        profilePromptOpen = false
        profileError = null
        loadProfileContext()
        syncAnnotations(reportErrors = true)
    }

    // ---- annotations (highlights and comments) ----------------------------------------------

    /** Annotations of the current profile, including not yet uploaded deletions. */
    var annotations: List<StoredAnnotation> by mutableStateOf(emptyList())
        private set
    private var annotationServerTime: String? = null
    private var annotationJob: Job? = null
    private var annotationSyncAgain = false

    /** Live annotations of one entry. */
    fun annotationsFor(entryId: Long): List<Annotation> =
        annotations.map { it.annotation }.filter { it.entryId == entryId && !it.deleted }

    /**
     * Creates, changes or removes the annotation of a passage. [existing] is the annotation being
     * edited (its range is kept); without one a new annotation for [start, end) is made.
     * No colour and no comment means "remove". Returns false when a profile has to be chosen first.
     */
    fun saveAnnotation(
        entryId: Long,
        start: Int,
        end: Int,
        existing: Annotation?,
        color: String,
        comment: String,
    ): Boolean {
        if (profile == null) {
            openProfilePrompt()
            return false
        }
        val text = comment.trim()
        val cleanColor = if (color in TextLogic.HIGHLIGHT_COLORS) color else TextLogic.COLOR_NONE
        val remove = cleanColor == TextLogic.COLOR_NONE && text.isEmpty()
        if (remove && existing == null) return true
        val now = TextLogic.nextTimestamp(existing?.updatedAt, System.currentTimeMillis())
        val edited = if (existing != null) {
            existing.copy(color = cleanColor, comment = text, updatedAt = now, deleted = remove)
        } else {
            Annotation(
                id = UUID.randomUUID().toString(),
                entryId = entryId,
                start = start,
                end = end,
                color = cleanColor,
                comment = text,
                updatedAt = now,
                deleted = false,
            )
        }
        annotations = TextLogic.upsertLocal(annotations, edited)
        persistAnnotations()
        syncAnnotations(reportErrors = false)
        return true
    }

    /** Saves the note on the whole entry (start = end = 0); an empty text removes it. */
    fun saveEntryNote(entryId: Long, text: String): Boolean {
        val existing = TextLogic.entryNote(annotations.map { it.annotation }, entryId)
        return saveAnnotation(entryId, 0, 0, existing, TextLogic.COLOR_NONE, text)
    }

    private fun persistAnnotations() {
        val current = profile ?: return
        try {
            annotationStore.save(serverKey, current.id, AnnotationFile(annotations, annotationServerTime))
        } catch (e: Exception) {
            syncError = "Could not save notes on this device: ${describe(e)}"
        }
    }

    private fun sameProfileContext(key: String, id: Long): Boolean =
        serverKey == key && profile?.id == id

    /** Uploads unsent annotations, then fetches what changed on the server. */
    private fun syncAnnotations(reportErrors: Boolean) {
        val current = profile ?: return
        if (!isServerConfigured) return
        if (annotationJob?.isActive == true) {
            annotationSyncAgain = true
            return
        }
        val url = serverUrl
        val key = serverKey
        annotationJob = viewModelScope.launch {
            try {
                do {
                    annotationSyncAgain = false
                    runAnnotationSync(url, key, current.id)
                } while (annotationSyncAgain && sameProfileContext(key, current.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiException && e.httpCode == 404) {
                    // The profile no longer exists on the server: forget it and ask again.
                    settings.setProfileFor(key, null)
                    if (serverKey == key) {
                        loadProfileContext()
                        openProfilePrompt()
                    }
                } else if (reportErrors) {
                    syncError = "Notes sync failed: ${describe(e)}"
                }
            }
        }
    }

    private suspend fun runAnnotationSync(url: String, key: String, profileId: Long) {
        val unsent = annotations.filter { it.dirty }.map { it.annotation }
        for (item in unsent) {
            val stored = withContext(Dispatchers.IO) { api.putAnnotation(url, profileId, item) }
            if (!sameProfileContext(key, profileId)) return
            // The stored copy is at least as new as what was sent; a newer local edit stays dirty.
            annotations = TextLogic.mergeRemote(annotations, listOf(stored))
        }
        if (unsent.isNotEmpty()) persistAnnotations()

        val since = annotationServerTime
        val response = withContext(Dispatchers.IO) { api.annotations(url, profileId, since) }
        if (!sameProfileContext(key, profileId)) return
        annotations = TextLogic.mergeRemote(annotations, response.annotations)
        annotationServerTime = response.serverTime ?: since
        persistAnnotations()
    }

    // ---- entries & sync ---------------------------------------------------------------------

    var entries: List<Entry> by mutableStateOf(emptyList())
        private set
    var lastSyncMillis: Long by mutableStateOf(0L)
        private set
    var syncing: Boolean by mutableStateOf(false)
        private set
    var syncError: String? by mutableStateOf(null)
        private set

    /** Ids of entries whose audio file is on the device. */
    var cachedAudio: Set<Long> by mutableStateOf(emptySet())
        private set

    /** Running audio downloads: entry id -> 0..1 fraction, or null while the size is unknown. */
    var downloads: Map<Long, Float?> by mutableStateOf(emptyMap())
        private set

    var audioError: String? by mutableStateOf(null)
        private set

    private val downloadJobs = HashMap<Long, Deferred<Boolean>>()
    private var autoDownloadJob: Job? = null

    fun entryById(id: Long): Entry? = entries.firstOrNull { it.id == id }

    fun sync() {
        if (syncing) return
        if (!isServerConfigured) {
            syncError = "Set the server address in Settings first."
            return
        }
        val url = serverUrl
        val key = serverKey
        syncing = true
        syncError = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cache = store.sync(api, url)
                    cache to store.cachedAudioIds()
                }
                entries = result.first.entries
                lastSyncMillis = result.first.lastSyncMillis
                cachedAudio = result.second
                // The file behind the player may have been replaced or removed by the sync.
                val loaded = playerEntryId
                if (loaded != null && loaded !in cachedAudio) stopPlayback()
                if (autoDownload) startAutoDownload()
                if (serverKey == key) {
                    if (profile != null) {
                        syncAnnotations(reportErrors = true)
                    } else if (profilePromptDeclinedKey != key && !profilePromptOpen) {
                        // Connected to a server for which no profile has been chosen yet.
                        openProfilePrompt()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                syncError = "Sync failed: ${describe(e)}"
            } finally {
                syncing = false
            }
        }
    }

    /** Removes cached entries, audio and update downloads. Notes and Bibles are kept. */
    fun clearCache() {
        stopPlayback()
        autoDownloadJob?.cancel()
        downloadJobs.values.toList().forEach { it.cancel() }
        downloadJobs.clear()
        downloads = emptyMap()
        entries = emptyList()
        cachedAudio = emptySet()
        lastSyncMillis = 0L
        syncError = null
        audioError = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.clear()
                File(getApplication<Application>().cacheDir, "updates").deleteRecursively()
            }
            if (update is UpdateUi.ReadyToInstall) update = UpdateUi.Idle
        }
    }

    private fun startAutoDownload() {
        if (autoDownloadJob?.isActive == true) return
        autoDownloadJob = viewModelScope.launch {
            for (entry in entries.toList()) {
                if (!autoDownload) break
                if (entry.hasAudio && entry.id !in cachedAudio) {
                    val ok = ensureAudio(entry, reportErrors = false).await()
                    // Do not hammer an unreachable server with one failing request per entry.
                    if (!ok) break
                }
            }
        }
    }

    /** Returns a Deferred that completes with true once the audio of [entry] is on disk. */
    private fun ensureAudio(entry: Entry, reportErrors: Boolean): Deferred<Boolean> {
        if (entry.id in cachedAudio && store.audioFile(entry.id).isFile) {
            return CompletableDeferred(true)
        }
        downloadJobs[entry.id]?.let { return it }
        val url = serverUrl
        val id = entry.id
        val job = viewModelScope.async {
            downloads = downloads + (id to null)
            try {
                store.downloadAudio(api, url, entry) { fraction ->
                    viewModelScope.launch {
                        if (downloads.containsKey(id)) downloads = downloads + (id to fraction)
                    }
                }
                // If a sync changed the audio while it was downloading, this copy is already stale.
                val current = entryById(id)
                if (current == null || current.audioSha256 != entry.audioSha256) {
                    withContext(Dispatchers.IO) { store.deleteAudio(id) }
                    false
                } else {
                    cachedAudio = cachedAudio + id
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (reportErrors) audioError = "Audio download failed: ${describe(e)}"
                false
            } finally {
                downloads = downloads - id
                downloadJobs.remove(id)
            }
        }
        if (!job.isCompleted) downloadJobs[id] = job
        return job
    }

    // ---- playback ---------------------------------------------------------------------------

    private var player: ExoPlayer? = null
    private var positionJob: Job? = null
    private var pendingPlayId: Long? = null

    /** Entry currently loaded in the player, if any. */
    var playerEntryId: Long? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var positionMs: Long by mutableStateOf(0L)
        private set
    var durationMs: Long by mutableStateOf(0L)
        private set

    /** Play/pause for [entry]; downloads its audio first when it is not cached yet. */
    fun playPause(entry: Entry) {
        val current = player
        if (current != null && playerEntryId == entry.id) {
            if (current.isPlaying) {
                current.pause()
            } else {
                if (current.playbackState == Player.STATE_IDLE) current.prepare()
                if (current.playbackState == Player.STATE_ENDED) current.seekTo(0L)
                current.play()
            }
            return
        }
        audioError = null
        pendingPlayId = entry.id
        viewModelScope.launch {
            val ok = ensureAudio(entry, reportErrors = true).await()
            if (ok && pendingPlayId == entry.id) {
                pendingPlayId = null
                startPlayback(entry.id)
            }
        }
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        p.seekTo(ms.coerceIn(0L, upper))
        refreshPosition()
    }

    fun skip(deltaMs: Long) {
        val p = player ?: return
        seekTo(p.currentPosition + deltaMs)
    }

    private fun startPlayback(id: Long) {
        val file = store.audioFile(id)
        if (!file.isFile) {
            cachedAudio = cachedAudio - id
            audioError = "The audio file is missing; please try again."
            return
        }
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.setPlaybackSpeed(playbackSpeed)
        p.prepare()
        p.play()
        playerEntryId = id
        positionMs = 0L
        durationMs = 0L
    }

    private fun stopPlayback() {
        pendingPlayId = null
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        playerEntryId = null
        isPlaying = false
        positionMs = 0L
        durationMs = 0L
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(getApplication<Application>()).build()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        created.setAudioAttributes(attributes, true)
        created.setHandleAudioBecomingNoisy(true)
        created.setPlaybackSpeed(playbackSpeed)
        created.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@MainViewModel.isPlaying = isPlaying
                refreshPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // Rewind so the next Play starts from the beginning.
                    created.pause()
                    created.seekTo(0L)
                }
                refreshPosition()
            }

            override fun onPlayerError(error: PlaybackException) {
                audioError = "Playback failed: ${error.message ?: error.errorCodeName}"
            }
        })
        player = created
        positionJob = viewModelScope.launch {
            while (isActive) {
                refreshPosition()
                delay(500L)
            }
        }
        return created
    }

    private fun refreshPosition() {
        val p = player ?: return
        if (playerEntryId == null) return
        positionMs = p.currentPosition.coerceAtLeast(0L)
        val duration = p.duration
        durationMs = if (duration == C.TIME_UNSET || duration < 0L) 0L else duration
    }

    // ---- Bible ------------------------------------------------------------------------------

    var bibleView: BibleView by mutableStateOf(BibleView.VERSIONS)
        private set
    /** Versions the server offers (empty until loaded, or when offline). */
    var serverBibles: List<BibleInfo> by mutableStateOf(emptyList())
        private set
    var serverBiblesLoading: Boolean by mutableStateOf(false)
        private set
    /** True once the server's list has been fetched successfully (so "empty" really means none). */
    var serverBiblesLoaded: Boolean by mutableStateOf(false)
        private set
    /** An explanation shown above the version list, e.g. why the reader could not be opened. */
    var bibleNotice: String? by mutableStateOf(null)
        private set
    /** Versions on this device. */
    var downloadedBibles: List<BibleInfo> by mutableStateOf(emptyList())
        private set
    /** Running Bible downloads: version id -> 0..1 fraction, or null while the size is unknown. */
    var bibleDownloads: Map<String, Float?> by mutableStateOf(emptyMap())
        private set
    var bibleError: String? by mutableStateOf(null)
        private set
    /** The one version that is parsed and held in memory. */
    var openBible: Bible? by mutableStateOf(null)
        private set
    var bibleLoading: Boolean by mutableStateOf(false)
        private set
    var bibleBook: Int by mutableStateOf(0)
        private set
    var bibleChapter: Int by mutableStateOf(0)
        private set
    /** Verse (1-based) to scroll to and mark in the reader, if any. */
    var bibleVerse: Int? by mutableStateOf(null)
        private set
    /** Last verse (1-based, inclusive) of the marked range; null marks only [bibleVerse]. */
    var bibleVerseEnd: Int? by mutableStateOf(null)
        private set
    /** Book whose chapters are listed in the CHAPTERS view. */
    var biblePickBook: Int by mutableStateOf(0)
        private set

    private var bibleReturn: Screen = Screen.EntryList
    private var bibleBooksOpenedFromReader = false
    private var bibleLoadJob: Job? = null

    private fun preferredBibleId(): String? {
        val saved = settings.bibleId
        return downloadedBibles.firstOrNull { it.id == saved }?.id ?: downloadedBibles.firstOrNull()?.id
    }

    private fun openBibleScreen() {
        bibleReturn = screen.takeIf { it !is Screen.Bible } ?: Screen.EntryList
        bibleError = null
        bibleNotice = null
        screen = Screen.Bible
        val loaded = openBible
        val preferred = preferredBibleId()
        when {
            loaded != null -> bibleView = BibleView.READER
            preferred != null -> openBibleVersion(preferred)
            else -> showBibleVersions()
        }
    }

    /** Opens the reader at the first reference in [scripture]; falls back to the book list. */
    fun openBibleAt(scripture: String) {
        val id = preferredBibleId()
        if (id == null) {
            openBibleWithoutVersion()
            return
        }
        bibleReturn = screen.takeIf { it !is Screen.Bible } ?: Screen.EntryList
        bibleError = null
        bibleNotice = null
        screen = Screen.Bible
        loadBible(id) { bible ->
            val location = BibleRefLogic.resolve(
                scripture,
                bible.books.map { it.name },
                bible.books.map { it.chapters.size },
            )
            if (location != null) {
                showChapter(location.book, location.chapter, location.verse)
            } else {
                bibleBooksOpenedFromReader = false
                bibleView = BibleView.BOOKS
            }
        }
    }

    /**
     * Opens the reader at a verse reference from an entry body. The book is found by its index in
     * the standard 66-book order, or by name when the version has a different number of books.
     */
    fun openBibleReference(reference: EntryReference) {
        val id = preferredBibleId()
        if (id == null) {
            openBibleWithoutVersion()
            return
        }
        bibleReturn = screen.takeIf { it !is Screen.Bible } ?: Screen.EntryList
        bibleError = null
        bibleNotice = null
        screen = Screen.Bible
        loadBible(id) { bible ->
            val byIndex = if (bible.books.size == 66 && reference.bookIndex in 0..65) reference.bookIndex else -1
            val book = if (byIndex >= 0) byIndex else BibleRefLogic.findBook(bible.books.map { it.name }, reference.book)
            if (book < 0) {
                bibleBooksOpenedFromReader = false
                bibleView = BibleView.BOOKS
            } else {
                val chapters = bible.books[book].chapters.size
                if (reference.chapter in 1..chapters) {
                    showChapter(book, reference.chapter - 1, reference.verseStart, reference.verseEnd)
                } else {
                    showChapter(book, 0, null)
                }
            }
        }
    }

    /** A reference was tapped but no version is on the device: show the list and say why. */
    private fun openBibleWithoutVersion() {
        bibleReturn = screen.takeIf { it !is Screen.Bible } ?: Screen.EntryList
        bibleError = null
        screen = Screen.Bible
        showBibleVersions()
        bibleNotice = "Download a Bible version first, then the reference can be opened."
    }

    fun showBibleVersions() {
        bibleNotice = null
        bibleView = BibleView.VERSIONS
        refreshServerBibles()
    }

    fun showBibleBooks() {
        if (openBible == null) return
        bibleBooksOpenedFromReader = bibleView == BibleView.READER
        bibleView = BibleView.BOOKS
    }

    /** Opens a downloaded version at the remembered place (or its beginning). */
    fun openBibleVersion(id: String) {
        val remembered = settings.bibleId == id
        val book = if (remembered) settings.bibleBook else 0
        val chapter = if (remembered) settings.bibleChapter else 0
        loadBible(id) { showChapter(book, chapter, null) }
    }

    /** Makes sure version [id] is the one in memory, then runs [then] on the main thread. */
    private fun loadBible(id: String, then: (Bible) -> Unit) {
        val loaded = openBible
        if (loaded != null && loaded.id == id) {
            then(loaded)
            return
        }
        bibleLoadJob?.cancel()
        bibleLoading = true
        bibleError = null
        // Free the previous version before parsing the next one.
        openBible = null
        bibleLoadJob = viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { bibleStore.load(id) }
                // Files are named by id; trust the file over an "id" field that might be missing.
                val bible = if (parsed.id == id) parsed else parsed.copy(id = id)
                openBible = bible
                bibleLoading = false
                then(bible)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bibleLoading = false
                bibleError = describe(e)
                bibleView = BibleView.VERSIONS
            }
        }
    }

    fun pickBibleBook(book: Int) {
        val bible = openBible ?: return
        val chapters = bible.books.getOrNull(book)?.chapters?.size ?: return
        if (chapters <= 1) {
            showChapter(book, 0, null)
        } else {
            biblePickBook = book
            bibleView = BibleView.CHAPTERS
        }
    }

    /** Shows a chapter in the reader (indexes are clamped to what the version has) and remembers it. */
    fun showChapter(book: Int, chapter: Int, verse: Int?, verseEnd: Int? = null) {
        val bible = openBible ?: return
        if (bible.books.isEmpty()) {
            bibleError = "This Bible version has no books."
            bibleView = BibleView.VERSIONS
            return
        }
        val b = book.coerceIn(0, bible.books.size - 1)
        val chapterCount = bible.books[b].chapters.size
        val c = if (chapterCount > 0) chapter.coerceIn(0, chapterCount - 1) else 0
        bibleBook = b
        bibleChapter = c
        bibleVerse = verse
        bibleVerseEnd = if (verse != null && verseEnd != null && verseEnd > verse) verseEnd else null
        bibleView = BibleView.READER
        settings.bibleId = bible.id
        settings.bibleBook = b
        settings.bibleChapter = c
    }

    val hasNextChapter: Boolean
        get() {
            val bible = openBible ?: return false
            val chapters = bible.books.getOrNull(bibleBook)?.chapters?.size ?: return false
            return bibleChapter < chapters - 1 || bibleBook < bible.books.size - 1
        }

    val hasPreviousChapter: Boolean
        get() = openBible != null && (bibleChapter > 0 || bibleBook > 0)

    fun nextChapter() {
        val bible = openBible ?: return
        val chapters = bible.books.getOrNull(bibleBook)?.chapters?.size ?: return
        when {
            bibleChapter < chapters - 1 -> showChapter(bibleBook, bibleChapter + 1, null)
            bibleBook < bible.books.size - 1 -> showChapter(bibleBook + 1, 0, null)
        }
    }

    fun previousChapter() {
        val bible = openBible ?: return
        when {
            bibleChapter > 0 -> showChapter(bibleBook, bibleChapter - 1, null)
            bibleBook > 0 -> {
                val previousChapters = bible.books[bibleBook - 1].chapters.size
                showChapter(bibleBook - 1, (previousChapters - 1).coerceAtLeast(0), null)
            }
        }
    }

    private fun bibleBack() {
        when {
            bibleView == BibleView.CHAPTERS -> bibleView = BibleView.BOOKS
            bibleView == BibleView.BOOKS && bibleBooksOpenedFromReader && openBible != null ->
                bibleView = BibleView.READER
            else -> {
                val target = bibleReturn
                screen = if (target is Screen.Detail && entryById(target.id) == null) Screen.EntryList else target
            }
        }
    }

    fun refreshServerBibles() {
        if (serverBiblesLoading) return
        if (!isServerConfigured) {
            serverBibles = emptyList()
            serverBiblesLoaded = false
            return
        }
        val url = serverUrl
        serverBiblesLoading = true
        viewModelScope.launch {
            try {
                serverBibles = withContext(Dispatchers.IO) { api.bibles(url) }
                serverBiblesLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bibleError = "Could not load the versions on the server: ${describe(e)}"
            } finally {
                serverBiblesLoading = false
            }
        }
    }

    fun downloadBible(info: BibleInfo) {
        if (bibleDownloads.containsKey(info.id)) return
        val url = serverUrl
        val id = info.id
        bibleError = null
        bibleDownloads = bibleDownloads + (id to null)
        viewModelScope.launch {
            try {
                bibleStore.download(api, url, info) { fraction ->
                    viewModelScope.launch {
                        if (bibleDownloads.containsKey(id)) bibleDownloads = bibleDownloads + (id to fraction)
                    }
                }
                downloadedBibles = withContext(Dispatchers.IO) { bibleStore.downloaded() }
                // A re-downloaded version must be parsed again.
                if (openBible?.id == id) openBible = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bibleError = "Bible download failed: ${describe(e)}"
            } finally {
                bibleDownloads = bibleDownloads - id
            }
        }
    }

    fun deleteBible(info: BibleInfo) {
        if (openBible?.id == info.id) {
            bibleLoadJob?.cancel()
            openBible = null
            bibleLoading = false
            bibleView = BibleView.VERSIONS
        }
        viewModelScope.launch {
            try {
                downloadedBibles = withContext(Dispatchers.IO) {
                    bibleStore.delete(info.id)
                    bibleStore.downloaded()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bibleError = describe(e)
            }
        }
    }

    // ---- manual update check ----------------------------------------------------------------

    var update: UpdateUi by mutableStateOf(UpdateUi.Idle)
        private set

    fun checkForUpdates() {
        if (update is UpdateUi.Checking || update is UpdateUi.Downloading) return
        val token = githubToken
        update = UpdateUi.Checking
        viewModelScope.launch {
            update = try {
                val result = withContext(Dispatchers.IO) {
                    updater.check(BuildConfig.GITHUB_REPO, token, BuildConfig.VERSION_NAME)
                }
                when (result) {
                    is UpdateCheck.UpToDate -> UpdateUi.UpToDate(result.latestVersion)
                    is UpdateCheck.Failed -> UpdateUi.Error(result.message)
                    is UpdateCheck.Available -> UpdateUi.Available(
                        version = UpdateLogic.displayVersion(result.release.tagName),
                        notes = result.release.body.orEmpty().trim(),
                        asset = result.asset,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateUi.Error(describe(e))
            }
        }
    }

    fun downloadUpdate() {
        val available = update as? UpdateUi.Available ?: return
        val asset = available.asset ?: return
        val token = githubToken
        update = UpdateUi.Downloading(available.version, null)
        viewModelScope.launch {
            update = try {
                val file = withContext(Dispatchers.IO) {
                    updater.download(asset, token) { fraction ->
                        viewModelScope.launch {
                            if (update is UpdateUi.Downloading) {
                                update = UpdateUi.Downloading(available.version, fraction)
                            }
                        }
                    }
                }
                // Hand the APK straight to the installer; the "Install" button stays as a fallback.
                try {
                    val app = getApplication<Application>()
                    app.startActivity(Updater.installIntent(app, file))
                } catch (e: Exception) {
                    // Ignored: the user can still press "Install".
                }
                UpdateUi.ReadyToInstall(available.version, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateUi.Error("Update download failed: ${describe(e)}")
            }
        }
    }

    fun reportUpdateError(message: String) {
        update = UpdateUi.Error(message)
    }

    // ---- lifecycle --------------------------------------------------------------------------

    init {
        val cached = store.load()
        entries = cached.entries
        lastSyncMillis = cached.lastSyncMillis
        cachedAudio = store.cachedAudioIds()
        downloadedBibles = bibleStore.downloaded()
        loadProfileContext()
        if (isServerConfigured) {
            sync()
        } else {
            screen = Screen.Settings
        }
    }

    override fun onCleared() {
        positionJob?.cancel()
        player?.release()
        player = null
        super.onCleared()
    }

    private fun describe(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return when (e) {
            is java.net.UnknownHostException -> "Cannot find the server (${message ?: "unknown host"})."
            is java.net.ConnectException -> "Cannot connect to the server (${message ?: "connection refused"})."
            is java.net.SocketTimeoutException -> "The server did not answer in time."
            else -> message ?: e.javaClass.simpleName
        }
    }

    companion object {
        val PLAYBACK_SPEEDS: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        private fun sanitizeSpeed(value: Float): Float =
            PLAYBACK_SPEEDS.minByOrNull { kotlin.math.abs(it - value) } ?: 1f

        /** 1f -> "1×", 1.25f -> "1.25×". */
        fun speedLabel(speed: Float): String {
            val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
            return "$text×"
        }
    }
}
