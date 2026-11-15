package com.mastifdogo.devotion

import android.app.Application
import android.app.WallpaperManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.data.AppJson
import com.mastifdogo.devotion.data.AudioVariant
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
import com.mastifdogo.devotion.data.SyncLock
import com.mastifdogo.devotion.data.Voice
import com.mastifdogo.devotion.data.WallpaperStore
import com.mastifdogo.devotion.data.VoicesResponse
import com.mastifdogo.devotion.logic.AudioLogic
import com.mastifdogo.devotion.logic.AutoSync
import com.mastifdogo.devotion.logic.BibleRefLogic
import com.mastifdogo.devotion.logic.EntryKinds
import com.mastifdogo.devotion.logic.EntryTab
import com.mastifdogo.devotion.logic.ServerAddress
import com.mastifdogo.devotion.logic.ServerWallpaper
import com.mastifdogo.devotion.logic.SyncScheduleLogic
import com.mastifdogo.devotion.logic.TextLogic
import com.mastifdogo.devotion.logic.WallTopic
import com.mastifdogo.devotion.logic.WallpaperLibrary
import com.mastifdogo.devotion.logic.WallpaperLogic
import com.mastifdogo.devotion.logic.WindowLogic
import com.mastifdogo.devotion.ui.ThemeMode
import com.mastifdogo.devotion.update.ReleaseAsset
import com.mastifdogo.devotion.update.UpdateCheck
import com.mastifdogo.devotion.update.UpdateLogic
import com.mastifdogo.devotion.sync.SyncScheduler
import com.mastifdogo.devotion.update.Updater
import coil.ImageLoader
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class Screen {
    /** The main screen with its tabs (Daily readings, Lessons, Bible). */
    data object EntryList : Screen()
    data class Detail(val id: Long) : Screen()
    data object Settings : Screen()
    /** Browsing the server's wallpapers (opened from Settings). */
    data object Wallpapers : Screen()
}

/** Where to return when the Bible tab was opened from an entry (a reference or "Open in Bible"). */
private data class BibleReturn(val entryId: Long, val tab: EntryTab)

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
    private val wallpaperStore = WallpaperStore(application.filesDir)

    // ---- navigation -------------------------------------------------------------------------

    var screen: Screen by mutableStateOf(Screen.EntryList)
        private set

    /** The selected tab of the main screen; remembered across restarts. */
    var selectedTab: EntryTab by mutableStateOf(EntryTab.fromKey(settings.entryTab))
        private set

    fun open(target: Screen) {
        screen = target
        if (target is Screen.Settings) {
            refreshVoices()
            refreshSyncStatus()
        }
        if (target is Screen.Wallpapers) {
            wallPreview = null
            wallMessage = null
            refreshWallpapers()
        }
    }

    fun selectTab(tab: EntryTab) {
        bibleReturn = null
        if (tab == selectedTab) return
        selectedTab = tab
        settings.entryTab = tab.key
        if (tab == EntryTab.BIBLE) enterBibleTab()
    }

    /** Opens the newest lesson entry with [number], if the app has one. */
    fun openLesson(number: Int) {
        val lesson = EntryKinds.newestLesson(entries, number) ?: return
        screen = Screen.Detail(lesson.id)
    }

    /** True when Back has somewhere to go inside the app; otherwise the system closes the app. */
    val canGoBack: Boolean
        get() = when {
            screen != Screen.EntryList -> true
            selectedTab != EntryTab.BIBLE -> false
            else -> bibleReturn != null || bibleLoading || effectiveBibleView != BibleView.VERSIONS
        }

    /** One step back; leaving Settings triggers a sync so a new server address takes effect. */
    fun back() {
        when (screen) {
            is Screen.Settings -> {
                screen = Screen.EntryList
                if (isServerConfigured) sync()
            }
            is Screen.Detail -> screen = Screen.EntryList
            is Screen.Wallpapers -> if (wallPreview != null) closeWallpaperPreview() else screen = Screen.Settings
            is Screen.EntryList -> if (selectedTab == EntryTab.BIBLE) bibleBack()
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

    /** Colour the selection menu's "Highlight" uses: the last one chosen. */
    var lastHighlightColor: String by mutableStateOf(
        settings.lastHighlightColor.takeIf { it in TextLogic.HIGHLIGHT_COLORS } ?: TextLogic.HIGHLIGHT_COLORS.first(),
    )
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
        loadVoiceContext()
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
        if (cleanColor != TextLogic.COLOR_NONE) {
            lastHighlightColor = cleanColor
            settings.lastHighlightColor = cleanColor
        }
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

    /** Highlights body[[start], [end]) of an entry in the last used colour (always a new annotation). */
    fun highlightRange(entryId: Long, start: Int, end: Int): Boolean {
        if (start >= end) return true
        return saveAnnotation(entryId, start, end, null, lastHighlightColor, "")
    }

    /** Deletes an annotation (highlight and comment). */
    fun deleteAnnotation(existing: Annotation) {
        if (profile == null) return
        val now = TextLogic.nextTimestamp(existing.updatedAt, System.currentTimeMillis())
        annotations = TextLogic.upsertLocal(annotations, existing.copy(updatedAt = now, deleted = true))
        persistAnnotations()
        syncAnnotations(reportErrors = false)
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

    /** File names (see [AudioLogic.fileName]) of the audio variants that are on the device. */
    var cachedAudio: Set<String> by mutableStateOf(emptySet())
        private set

    /** Running audio downloads: file name -> 0..1 fraction, or null while the size is unknown. */
    var downloads: Map<String, Float?> by mutableStateOf(emptyMap())
        private set

    var audioError: String? by mutableStateOf(null)
        private set

    private val downloadJobs = HashMap<String, Deferred<Boolean>>()
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
                    SyncLock.mutex.withLock {
                        val cache = store.sync(api, url)
                        cache to store.cachedAudioFiles()
                    }
                }
                entries = result.first.entries
                lastSyncMillis = result.first.lastSyncMillis
                cachedAudio = result.second
                settings.lastSyncMillis = result.first.lastSyncMillis
                settings.lastSyncError = ""
                lastSyncErrorText = null
                // The file behind the player may have been replaced or removed by the sync.
                val loadedId = playerEntryId
                val loadedVoice = playerVoice
                if (loadedId != null && loadedVoice != null &&
                    AudioLogic.fileName(loadedId, loadedVoice) !in cachedAudio
                ) {
                    stopPlayback()
                }
                refreshVoices()
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
                settings.lastSyncError = syncError ?: ""
                lastSyncErrorText = syncError
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

    // ---- automatic sync ----------------------------------------------------------------------

    var autoSync: AutoSync by mutableStateOf(AutoSync.fromKey(settings.autoSync))
        private set
    /** Time of the daily sync, minutes after midnight. */
    var dailySyncMinutes: Int by mutableStateOf(SyncScheduleLogic.normalizeMinutes(settings.dailySyncMinutes))
        private set
    /** The last failed sync's message (in the app or in the background), or null. */
    var lastSyncErrorText: String? by mutableStateOf(settings.lastSyncError.takeIf { it.isNotBlank() })
        private set

    fun updateAutoSync(mode: AutoSync) {
        autoSync = mode
        settings.autoSync = mode.key
        applySchedule()
    }

    fun updateDailySyncTime(hour: Int, minute: Int) {
        val minutes = SyncScheduleLogic.normalizeMinutes(hour * 60 + minute)
        dailySyncMinutes = minutes
        settings.dailySyncMinutes = minutes
        autoSync = AutoSync.DAILY
        settings.autoSync = AutoSync.DAILY.key
        applySchedule()
    }

    private fun applySchedule() {
        try {
            SyncScheduler.apply(getApplication())
        } catch (e: Exception) {
            lastSyncErrorText = "Could not schedule the automatic sync: ${describe(e)}"
        }
    }

    /** Picks up what a background sync recorded while the app was open. */
    private fun refreshSyncStatus() {
        val background = settings.lastSyncMillis
        if (background > lastSyncMillis) lastSyncMillis = background
        if (!syncing) lastSyncErrorText = settings.lastSyncError.takeIf { it.isNotBlank() }
    }

    // ---- voices -----------------------------------------------------------------------------

    /** Voices the server offers (from the last /api/voices answer, kept per server). */
    var voices: List<Voice> by mutableStateOf(emptyList())
        private set
    var serverDefaultVoice: String? by mutableStateOf(null)
        private set
    /** False when the server does not offer voices (older server) or they were never loaded. */
    var voicesSupported: Boolean by mutableStateOf(false)
        private set
    /** The voice the user chose for this server, or null to follow the server default. */
    var preferredVoice: String? by mutableStateOf(null)
        private set

    /** Voice chosen in the player for individual entries during this session. */
    private var chosenVoices: Map<Long, String> by mutableStateOf(emptyMap())
    private var voicesJob: Job? = null

    private fun loadVoiceContext() {
        val key = serverKey
        preferredVoice = if (isServerConfigured) settings.preferredVoice(key) else null
        applyVoicesJson(if (isServerConfigured) settings.voicesJson(key) else null)
    }

    private fun applyVoicesJson(json: String?) {
        val parsed = if (json.isNullOrEmpty()) {
            null
        } else {
            try {
                AppJson.decodeFromString(VoicesResponse.serializer(), json)
            } catch (e: Exception) {
                null
            }
        }
        voicesSupported = parsed != null
        voices = parsed?.voices ?: emptyList()
        serverDefaultVoice = parsed?.defaultVoice?.takeIf { it.isNotBlank() }
    }

    /** Reloads the voice list from the server; offline the last known list stays. */
    fun refreshVoices() {
        if (!isServerConfigured || voicesJob?.isActive == true) return
        val url = serverUrl
        val key = serverKey
        voicesJob = viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { api.voices(url) }
                if (serverKey == key) {
                    val json = if (response == null) "" else AppJson.encodeToString(VoicesResponse.serializer(), response)
                    settings.setVoicesJson(key, json)
                    applyVoicesJson(json)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or server error: keep the cached list.
            }
        }
    }

    /** The voice shown as selected in Settings: the user's choice, else the server default. */
    val selectedSettingsVoice: String?
        get() = preferredVoice ?: serverDefaultVoice

    fun updatePreferredVoice(voice: String) {
        preferredVoice = voice
        settings.setPreferredVoice(serverKey, voice)
        if (autoDownload) {
            autoDownloadJob?.cancel()
            startAutoDownload()
        }
    }

    /** A voice's name from /api/voices, falling back to its id. */
    fun voiceLabel(voice: String): String {
        if (voice.isEmpty()) return "Default voice"
        return voices.firstOrNull { it.id == voice }?.name?.takeIf { it.isNotBlank() } ?: voice
    }

    /** The voices [entry] can be played in (for an archived entry only the cached ones). */
    fun entryVoices(entry: Entry): List<AudioVariant> = AudioLogic.playableVariants(entry, cachedAudio)

    /** The voice [entry] plays in: chosen in the player, else preferred, else server default, else first. */
    fun voiceFor(entry: Entry): String? {
        val playable = entryVoices(entry)
        val chosen = chosenVoices[entry.id]
        if (chosen != null && playable.any { it.voice == chosen }) return chosen
        return AudioLogic.pickVoice(playable, preferredVoice, serverDefaultVoice)
    }

    /** Whether the audio [entry] would play (see [voiceFor]) is on the device. */
    fun isAudioCached(entry: Entry): Boolean {
        val voice = voiceFor(entry) ?: return false
        return AudioLogic.fileName(entry.id, voice) in cachedAudio
    }

    fun isAudioDownloading(entry: Entry): Boolean {
        val voice = voiceFor(entry) ?: return false
        return downloads.containsKey(AudioLogic.fileName(entry.id, voice))
    }

    /** Progress of the running download of [entry]'s current voice (null while unknown). */
    fun audioDownloadFraction(entry: Entry): Float? {
        val voice = voiceFor(entry) ?: return null
        return downloads[AudioLogic.fileName(entry.id, voice)]
    }

    /**
     * Switches [entry] to [voice]. When that entry is loaded in the player the track is switched
     * (keeping the position); otherwise the voice is downloaded if it is not on the device yet.
     */
    fun chooseVoice(entry: Entry, voice: String) {
        chosenVoices = chosenVoices + (entry.id to voice)
        audioError = null
        val p = player
        if (p != null && playerEntryId == entry.id) {
            if (playerVoice == voice) return
            val position = p.currentPosition.coerceAtLeast(0L)
            val wasPlaying = p.isPlaying
            p.pause()
            pendingPlay = entry.id to voice
            viewModelScope.launch {
                val ok = ensureAudio(entry, voice, reportErrors = true).await()
                if (ok && pendingPlay == (entry.id to voice)) {
                    pendingPlay = null
                    startPlayback(entry.id, voice, position, wasPlaying)
                }
            }
        } else {
            ensureAudio(entry, voice, reportErrors = true)
        }
    }

    private fun startAutoDownload() {
        if (autoDownloadJob?.isActive == true) return
        autoDownloadJob = viewModelScope.launch {
            for (entry in entries.toList()) {
                if (!autoDownload) break
                val voice = voiceFor(entry) ?: continue
                if (AudioLogic.fileName(entry.id, voice) !in cachedAudio) {
                    val ok = ensureAudio(entry, voice, reportErrors = false).await()
                    // Do not hammer an unreachable server with one failing request per entry.
                    if (!ok) break
                }
            }
        }
    }

    /** Returns a Deferred that completes with true once [voice] of [entry] is on disk. */
    private fun ensureAudio(entry: Entry, voice: String, reportErrors: Boolean): Deferred<Boolean> {
        val name = AudioLogic.fileName(entry.id, voice)
        // Files only get their final name after their hash was checked (also by the background sync).
        if (store.audioFile(entry.id, voice).isFile) {
            if (name !in cachedAudio) cachedAudio = cachedAudio + name
            return CompletableDeferred(true)
        }
        downloadJobs[name]?.let { return it }
        val variant = AudioLogic.variants(entry).firstOrNull { it.voice == voice }
        if (variant == null) {
            if (reportErrors) audioError = "This entry has no audio in that voice."
            return CompletableDeferred(false)
        }
        val url = serverUrl
        val id = entry.id
        val job = viewModelScope.async {
            downloads = downloads + (name to null)
            try {
                store.downloadAudio(api, url, entry, variant) { fraction ->
                    viewModelScope.launch {
                        if (downloads.containsKey(name)) downloads = downloads + (name to fraction)
                    }
                }
                // If a sync changed the audio while it was downloading, this copy is already stale.
                val current = entryById(id)?.let { e -> AudioLogic.variants(e).firstOrNull { it.voice == voice } }
                if (current == null || current.sha256 != variant.sha256) {
                    withContext(Dispatchers.IO) { store.deleteAudioFile(name) }
                    false
                } else {
                    cachedAudio = cachedAudio + name
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (reportErrors) audioError = "Audio download failed: ${describe(e)}"
                false
            } finally {
                downloads = downloads - name
                downloadJobs.remove(name)
            }
        }
        if (!job.isCompleted) downloadJobs[name] = job
        return job
    }

    // ---- playback ---------------------------------------------------------------------------

    private var player: ExoPlayer? = null
    private var positionJob: Job? = null
    private var pendingPlay: Pair<Long, String>? = null

    /** Entry currently loaded in the player, if any. */
    var playerEntryId: Long? by mutableStateOf(null)
        private set
    /** Voice of the loaded track. */
    var playerVoice: String? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var positionMs: Long by mutableStateOf(0L)
        private set
    var durationMs: Long by mutableStateOf(0L)
        private set

    /** Play/pause for [entry] in its current voice; downloads that audio first when needed. */
    fun playPause(entry: Entry) {
        val voice = voiceFor(entry)
        if (voice == null) {
            audioError = "No audio for this entry."
            return
        }
        val current = player
        if (current != null && playerEntryId == entry.id && playerVoice == voice) {
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
        pendingPlay = entry.id to voice
        viewModelScope.launch {
            val ok = ensureAudio(entry, voice, reportErrors = true).await()
            if (ok && pendingPlay == (entry.id to voice)) {
                pendingPlay = null
                startPlayback(entry.id, voice, 0L, true)
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

    private fun startPlayback(id: Long, voice: String, startMs: Long, play: Boolean) {
        val file = store.audioFile(id, voice)
        if (!file.isFile) {
            cachedAudio = cachedAudio - AudioLogic.fileName(id, voice)
            audioError = "The audio file is missing; please try again."
            return
        }
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), startMs)
        p.setPlaybackSpeed(playbackSpeed)
        p.prepare()
        if (play) p.play()
        playerEntryId = id
        playerVoice = voice
        positionMs = startMs
        durationMs = 0L
    }

    private fun stopPlayback() {
        pendingPlay = null
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        playerEntryId = null
        playerVoice = null
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

    private var bibleReturn: BibleReturn? = null
    private var bibleLoadJob: Job? = null

    /** The view the Bible tab really shows (the version list while no version is open). */
    val effectiveBibleView: BibleView
        get() = if (openBible == null) BibleView.VERSIONS else bibleView

    private fun preferredBibleId(): String? {
        val saved = settings.bibleId
        return downloadedBibles.firstOrNull { it.id == saved }?.id ?: downloadedBibles.firstOrNull()?.id
    }

    /** The Bible tab was selected: open the remembered version and place, or the version list. */
    private fun enterBibleTab() {
        bibleError = null
        bibleNotice = null
        val preferred = preferredBibleId()
        when {
            openBible != null || bibleLoading -> if (effectiveBibleView == BibleView.VERSIONS) refreshServerBibles()
            preferred != null -> openBibleVersion(preferred)
            else -> showBibleVersions()
        }
    }

    /** Switches to the Bible tab for a reference; Back from there returns to the entry it came from. */
    private fun goToBibleTab() {
        val current = screen
        bibleReturn = if (current is Screen.Detail) BibleReturn(current.id, selectedTab) else null
        if (selectedTab != EntryTab.BIBLE) {
            selectedTab = EntryTab.BIBLE
            settings.entryTab = EntryTab.BIBLE.key
        }
        screen = Screen.EntryList
        bibleError = null
        bibleNotice = null
    }

    /** Opens the reader at the first reference in [scripture]; falls back to the book list. */
    fun openBibleAt(scripture: String) {
        val id = preferredBibleId()
        if (id == null) {
            openBibleWithoutVersion()
            return
        }
        goToBibleTab()
        loadBible(id) { bible ->
            val location = BibleRefLogic.resolve(
                scripture,
                bible.books.map { it.name },
                bible.books.map { it.chapters.size },
            )
            if (location != null) {
                showChapter(location.book, location.chapter, location.verse)
            } else {
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
        goToBibleTab()
        loadBible(id) { bible ->
            val byIndex = if (bible.books.size == 66 && reference.bookIndex in 0..65) reference.bookIndex else -1
            val book = if (byIndex >= 0) byIndex else BibleRefLogic.findBook(bible.books.map { it.name }, reference.book)
            if (book < 0) {
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
        goToBibleTab()
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

    /**
     * Back inside the Bible tab: to the entry it was opened from (once), else
     * chapter list -> book list, reader -> book list, book list -> version list.
     */
    private fun bibleBack() {
        val ret = bibleReturn
        if (ret != null) {
            bibleReturn = null
            selectedTab = ret.tab
            settings.entryTab = ret.tab.key
            if (entryById(ret.entryId) != null) screen = Screen.Detail(ret.entryId)
            return
        }
        if (bibleLoading) {
            bibleLoadJob?.cancel()
            bibleLoading = false
            showBibleVersions()
            return
        }
        when (effectiveBibleView) {
            BibleView.CHAPTERS, BibleView.READER -> bibleView = BibleView.BOOKS
            BibleView.BOOKS -> showBibleVersions()
            BibleView.VERSIONS -> Unit
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

    // ---- wallpapers ---------------------------------------------------------------------------

    /**
     * Loads the browsing thumbnails and previews from the server. Memory cache only: nothing
     * the user merely browses is written to disk.
     */
    private var wallLoaderCreated = false

    val imageLoader: ImageLoader by lazy {
        wallLoaderCreated = true
        ImageLoader.Builder(getApplication<Application>())
            .diskCache { null }
            .crossfade(true)
            .build()
    }

    /** The server's library; null until loaded (or when it could not be loaded). */
    var wallLibrary: WallpaperLibrary? by mutableStateOf(null)
        private set
    var wallLoading: Boolean by mutableStateOf(false)
        private set
    /** True when the server could not be reached: the screen shows only "Connect to your server". */
    var wallUnreachable: Boolean by mutableStateOf(false)
        private set
    /** True when the server answered 404 (too old to have wallpapers). */
    var wallUnsupported: Boolean by mutableStateOf(false)
        private set
    /** Selected topic chip; null = All. */
    var wallTopic: String? by mutableStateOf(settings.wallTopic.takeIf { it.isNotEmpty() })
        private set
    /** The wallpaper shown full screen, if any. */
    var wallPreview: ServerWallpaper? by mutableStateOf(null)
        private set
    /** Something running on the preview ("Saving…", "Setting the phone wallpaper…"). */
    var wallBusy: String? by mutableStateOf(null)
        private set
    /** Result of the last preview action, shown under its buttons. */
    var wallMessage: String? by mutableStateOf(null)
        private set

    /** The app background (decoded, at most ~1440 px on the long side), or null. */
    var appWallpaper: ImageBitmap? by mutableStateOf(null)
        private set
    var appWallpaperId: String by mutableStateOf(settings.wallpaperId)
        private set
    /** Opacity of the text window over the app wallpaper, 0.5..1. */
    var windowOpacity: Float by mutableStateOf(WindowLogic.clampOpacity(settings.textWindowOpacity))
        private set

    private var wallJob: Job? = null

    /** "On each app start": true = a new wallpaper from the server, false = keep mine. */
    var newWallpaperOnStart: Boolean by mutableStateOf(settings.newWallpaperOnStart)
        private set
    /** Topic the start-up wallpaper comes from; null = all topics. */
    var wallStartTopic: String? by mutableStateOf(settings.wallStartTopic.takeIf { it.isNotEmpty() })
        private set
    /** The server's topics from the last successful /api/wallpapers, for the "From" chips. */
    var wallTopicsCached: List<WallTopic> by mutableStateOf(WallpaperLogic.decodeTopics(settings.wallTopicsCache))
        private set

    fun updateNewWallpaperOnStart(value: Boolean) {
        newWallpaperOnStart = value
        settings.newWallpaperOnStart = value
    }

    fun updateWallStartTopic(topicId: String?) {
        wallStartTopic = topicId
        settings.wallStartTopic = topicId ?: ""
    }

    private fun rememberTopics(library: WallpaperLibrary) {
        wallTopicsCached = library.topics
        settings.wallTopicsCache = WallpaperLogic.encodeTopics(library.topics)
    }

    fun refreshWallpapers() {
        if (!isServerConfigured) {
            wallLibrary = null
            wallUnreachable = true
            return
        }
        if (wallJob?.isActive == true) return
        val url = serverUrl
        wallLoading = true
        wallJob = viewModelScope.launch {
            try {
                val library = withContext(Dispatchers.IO) { api.wallpapers(url) }
                wallUnreachable = false
                wallUnsupported = library == null
                wallLibrary = library
                if (library != null) {
                    wallTopic = WallpaperLogic.validTopic(library, wallTopic)
                    rememberTopics(library)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No cached library: without the server there is nothing to browse.
                wallLibrary = null
                wallUnreachable = true
            } finally {
                wallLoading = false
            }
        }
    }

    fun selectWallTopic(topicId: String?) {
        wallTopic = topicId
        settings.wallTopic = topicId ?: ""
    }

    fun wallThumbUrl(id: String): String = ApiClient.wallpaperUrl(serverUrl, id, thumb = true)

    fun wallImageUrl(id: String): String = ApiClient.wallpaperUrl(serverUrl, id, thumb = false)

    fun openWallpaperPreview(wallpaper: ServerWallpaper) {
        wallMessage = null
        wallPreview = wallpaper
    }

    fun closeWallpaperPreview() {
        wallPreview = null
        wallMessage = null
    }

    /** Downloads [wallpaper] as the app background (the only wallpaper stored on the phone). */
    fun useWallpaperInApp(wallpaper: ServerWallpaper) {
        if (wallBusy != null) return
        val url = serverUrl
        wallBusy = "Saving the wallpaper…"
        wallMessage = null
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val file = wallpaperStore.replace(api, url, wallpaper)
                    wallpaperStore.decode(file)
                } ?: throw IllegalStateException("The wallpaper could not be decoded.")
                appWallpaper = bitmap.asImageBitmap()
                appWallpaperId = wallpaper.id
                settings.wallpaperId = wallpaper.id
                settings.wallpaperPageUrl = wallpaper.sourceUrl ?: ""
                wallMessage = "Now the app background."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wallMessage = "Could not save the wallpaper: ${describe(e)}"
            } finally {
                wallBusy = null
            }
        }
    }

    /**
     * Streams [wallpaper] from the server straight into the system wallpaper.
     * [which] is WallpaperManager.FLAG_SYSTEM, FLAG_LOCK or both.
     */
    fun setPhoneWallpaper(wallpaper: ServerWallpaper, which: Int) {
        if (wallBusy != null) return
        val url = serverUrl
        val app = getApplication<Application>()
        wallBusy = "Setting the phone wallpaper…"
        wallMessage = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val manager = WallpaperManager.getInstance(app)
                    api.streamWallpaper(url, wallpaper.id) { stream ->
                        manager.setStream(stream, null, true, which)
                    }
                }
                wallMessage = when (which) {
                    WallpaperManager.FLAG_SYSTEM -> "Set as the home screen wallpaper."
                    WallpaperManager.FLAG_LOCK -> "Set as the lock screen wallpaper."
                    else -> "Set as the home and lock screen wallpaper."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wallMessage = "Could not set the phone wallpaper: ${describe(e)}"
            } finally {
                wallBusy = null
            }
        }
    }

    fun removeAppWallpaper() {
        appWallpaper = null
        appWallpaperId = ""
        settings.wallpaperId = ""
        settings.wallpaperPageUrl = ""
        viewModelScope.launch { withContext(Dispatchers.IO) { wallpaperStore.remove() } }
    }

    fun updateWindowOpacity(value: Float) {
        val v = WindowLogic.clampOpacity(value)
        windowOpacity = v
        settings.textWindowOpacity = v
    }

    /**
     * Shows the stored wallpaper; then, with "New wallpaper each time", fetches the server's list,
     * downloads a random one (like Use in app) and swaps it in. Any failure keeps the current one.
     */
    private fun loadAppWallpaper() {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    wallpaperStore.currentFile()?.let { wallpaperStore.decode(it) }
                } catch (e: Exception) {
                    null
                }
            }
            appWallpaper = bitmap?.asImageBitmap()
            if (newWallpaperOnStart && isServerConfigured) newWallpaperForStart()
        }
    }

    private suspend fun newWallpaperForStart() {
        val url = serverUrl
        val currentId = appWallpaperId.takeIf { it.isNotEmpty() }
        try {
            val result = withContext(Dispatchers.IO) {
                val library = api.wallpapers(url) ?: return@withContext null
                val pick = WallpaperLogic.pickRandom(library, wallStartTopic, currentId, Random.Default)
                if (pick == null || pick.id == currentId) {
                    Triple(library, null, null)
                } else {
                    val file = wallpaperStore.replace(api, url, pick)
                    Triple(library, pick, wallpaperStore.decode(file))
                }
            } ?: return
            rememberTopics(result.first)
            val pick = result.second
            val bitmap = result.third
            if (pick != null && bitmap != null) {
                appWallpaper = bitmap.asImageBitmap()
                appWallpaperId = pick.id
                settings.wallpaperId = pick.id
                settings.wallpaperPageUrl = pick.sourceUrl ?: ""
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Server unreachable or the download failed: keep the current wallpaper, silently.
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
        cachedAudio = store.cachedAudioFiles()
        downloadedBibles = bibleStore.downloaded()
        loadProfileContext()
        loadAppWallpaper()
        if (selectedTab == EntryTab.BIBLE) enterBibleTab()
        // Keep the background work in line with the setting (also after an app update).
        applySchedule()
        if (isServerConfigured) {
            if (SyncScheduleLogic.syncOnStart(autoSync)) sync()
        } else {
            screen = Screen.Settings
        }
    }

    override fun onCleared() {
        if (wallLoaderCreated) imageLoader.shutdown()
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
