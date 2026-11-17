package com.mastifdogo.devotion

import android.app.Application
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.data.AppJson
import com.mastifdogo.devotion.data.AudioVariant
import com.mastifdogo.devotion.data.AnnotationFile
import com.mastifdogo.devotion.data.AnnotationStore
import com.mastifdogo.devotion.data.ApiClient
import com.mastifdogo.devotion.data.ApiException
import com.mastifdogo.devotion.data.Bible
import com.mastifdogo.devotion.data.BibleAudioStore
import com.mastifdogo.devotion.data.BibleInfo
import com.mastifdogo.devotion.data.BibleStore
import com.mastifdogo.devotion.data.Entry
import com.mastifdogo.devotion.data.EntryReference
import com.mastifdogo.devotion.data.EntryStore
import com.mastifdogo.devotion.data.Profile
import com.mastifdogo.devotion.data.ReadStore
import com.mastifdogo.devotion.data.SettingsStore
import com.mastifdogo.devotion.data.StoredAnnotation
import com.mastifdogo.devotion.data.SyncLock
import com.mastifdogo.devotion.data.Voice
import com.mastifdogo.devotion.data.WallpaperStore
import com.mastifdogo.devotion.data.VoicesResponse
import com.mastifdogo.devotion.logic.AudioLogic
import com.mastifdogo.devotion.logic.AutoSync
import com.mastifdogo.devotion.logic.BibleAudioLogic
import com.mastifdogo.devotion.logic.BibleRef
import com.mastifdogo.devotion.logic.ChapterTimings
import com.mastifdogo.devotion.logic.BibleRefLogic
import com.mastifdogo.devotion.logic.EntryKinds
import com.mastifdogo.devotion.logic.EntryTab
import com.mastifdogo.devotion.logic.FollowLogic
import com.mastifdogo.devotion.logic.MiniPlayerLogic
import com.mastifdogo.devotion.logic.NowPlaying
import com.mastifdogo.devotion.logic.PlayerOffer
import com.mastifdogo.devotion.logic.ServerAddress
import com.mastifdogo.devotion.logic.ServerWallpaper
import com.mastifdogo.devotion.logic.SyncScheduleLogic
import com.mastifdogo.devotion.logic.TextLogic
import com.mastifdogo.devotion.logic.TimeWindow
import com.mastifdogo.devotion.logic.WallTopic
import com.mastifdogo.devotion.logic.WallpaperLibrary
import com.mastifdogo.devotion.logic.WallpaperLogic
import com.mastifdogo.devotion.logic.WindowLogic
import com.mastifdogo.devotion.ui.ThemeMode
import com.mastifdogo.devotion.update.ReleaseAsset
import com.mastifdogo.devotion.update.UpdateCheck
import com.mastifdogo.devotion.update.UpdateLogic
import com.mastifdogo.devotion.playback.PlaybackService
import com.mastifdogo.devotion.reminder.ReadingReminder
import com.mastifdogo.devotion.sync.SyncScheduler
import com.mastifdogo.devotion.update.Updater
import coil.ImageLoader
import java.io.File
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
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
    private val readStore = ReadStore(application.filesDir)
    private val bibleAudioStore = BibleAudioStore(application.filesDir)

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
            refreshChapterAudioCache()
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
        controller?.setPlaybackSpeed(speed)
    }

    val canSlowDown: Boolean
        get() = PLAYBACK_SPEEDS.indexOf(playbackSpeed) > 0

    val canSpeedUp: Boolean
        get() = PLAYBACK_SPEEDS.indexOf(playbackSpeed) < PLAYBACK_SPEEDS.size - 1

    /** One step slower (-1) or faster (+1) through [PLAYBACK_SPEEDS], stopping at the ends. */
    fun stepPlaybackSpeed(direction: Int) {
        val index = PLAYBACK_SPEEDS.indexOf(playbackSpeed).coerceAtLeast(0)
        val next = (index + direction).coerceIn(0, PLAYBACK_SPEEDS.size - 1)
        if (next != index) updatePlaybackSpeed(PLAYBACK_SPEEDS[next])
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
        // Read marks are kept per server and profile (profile 0 while none is chosen).
        readIds = if (isServerConfigured) readStore.load(key, stored?.id ?: 0L) else emptySet()
        loadVoiceContext()
    }

    // ---- read tracking and the reading reminder -----------------------------------------------

    /** Ids of the entries that count as read (scrolled to the end, or listened to the end). */
    var readIds: Set<Long> by mutableStateOf(emptySet())
        private set

    var remindersEnabled: Boolean by mutableStateOf(settings.remindersEnabled)
        private set
    /** No reminders between these times (minutes after midnight). */
    var quietFromMinutes: Int by mutableStateOf(SyncScheduleLogic.normalizeMinutes(settings.quietFromMinutes))
        private set
    var quietUntilMinutes: Int by mutableStateOf(SyncScheduleLogic.normalizeMinutes(settings.quietUntilMinutes))
        private set

    fun markRead(entryId: Long) {
        if (entryId in readIds || !isServerConfigured) return
        val updated = readIds + entryId
        readIds = updated
        try {
            readStore.save(serverKey, profile?.id ?: 0L, updated)
        } catch (e: Exception) {
            // Kept in memory; written again with the next entry read.
        }
        // Removes the reminder at once if this was the entry it was about.
        refreshReminder()
    }

    /** Re-evaluates the reminder from what is on disk (posts, replaces or removes it). */
    private fun refreshReminder() {
        val app = getApplication<Application>()
        viewModelScope.launch { withContext(Dispatchers.IO) { ReadingReminder.evaluate(app) } }
    }

    private fun scheduleReminder() {
        try {
            ReadingReminder.schedule(getApplication())
        } catch (e: Exception) {
            // WorkManager unavailable: no reminders, nothing else is affected.
        }
    }

    fun updateRemindersEnabled(value: Boolean) {
        remindersEnabled = value
        settings.remindersEnabled = value
        scheduleReminder()
        if (value) {
            requestNotificationPermission()
            refreshReminder()
        }
    }

    fun updateQuietFrom(hour: Int, minute: Int) {
        quietFromMinutes = SyncScheduleLogic.normalizeMinutes(hour * 60 + minute)
        settings.quietFromMinutes = quietFromMinutes
        refreshReminder()
    }

    fun updateQuietUntil(hour: Int, minute: Int) {
        quietUntilMinutes = SyncScheduleLogic.normalizeMinutes(hour * 60 + minute)
        settings.quietUntilMinutes = quietUntilMinutes
        refreshReminder()
    }

    /** Asks for the notification permission (Android 13+) when it is missing. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(getApplication<Application>(), POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        settings.notificationPermissionAsked = true
        askNotificationPermission = true
    }

    /** Opens an entry by id (the reading reminder was tapped). */
    fun openEntryById(entryId: Long) {
        if (entryById(entryId) != null) screen = Screen.Detail(entryId)
    }

    // ---- follow along -------------------------------------------------------------------------

    /** The "Follow" preference: entries start following the narration when it is on. */
    var followAlong: Boolean by mutableStateOf(settings.followAlong)
        private set

    fun updateFollowAlong(value: Boolean) {
        followAlong = value
        settings.followAlong = value
    }

    /** Auto scroll speed in dp per second (one of FollowLogic.AUTO_SPEEDS). */
    var autoScrollSpeed: Int by mutableStateOf(FollowLogic.sanitizeSpeed(settings.autoScrollSpeed))
        private set

    val canAutoScrollSlower: Boolean
        get() = FollowLogic.canSlowDown(autoScrollSpeed)

    val canAutoScrollFaster: Boolean
        get() = FollowLogic.canSpeedUp(autoScrollSpeed)

    fun stepAutoScrollSpeed(direction: Int) {
        autoScrollSpeed = FollowLogic.stepSpeed(autoScrollSpeed, direction)
        settings.autoScrollSpeed = autoScrollSpeed
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
        reportBibles()
    }

    /**
     * Tells the server which Bible versions this profile has on the phone (one PUT replaces the
     * whole list). Silent on failure. A 404 means "profile gone" only if the profile list no
     * longer has it (older servers answer 404 for this call too); then it is cleared and asked for.
     */
    private fun reportBibles() {
        val current = profile ?: return
        if (!isServerConfigured) return
        val url = serverUrl
        val key = serverKey
        val ids = downloadedBibles.map { it.id }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.putProfileBibles(url, current.id, ids) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.httpCode != 404) return@launch
                val gone = try {
                    withContext(Dispatchers.IO) { api.profiles(url) }.none { it.id == current.id }
                } catch (x: CancellationException) {
                    throw x
                } catch (x: Exception) {
                    false
                }
                if (gone && sameProfileContext(key, current.id)) {
                    settings.setProfileFor(key, null)
                    loadProfileContext()
                    openProfilePrompt()
                }
            } catch (e: Exception) {
                // Best-effort: sent again after the next sync.
            }
        }
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
        annotations.map { it.annotation }.filter { TextLogic.isEntryAnnotation(it) && it.entryId == entryId && !it.deleted }

    /** Live annotations of one Bible chapter in one version ([ref] chapter is 1-based). */
    fun bibleAnnotations(ref: BibleRef): List<Annotation> =
        annotations.map { it.annotation }.filter { !it.deleted && TextLogic.isOnChapter(it, ref.bibleId, ref.book, ref.chapter) }

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
        bible: BibleRef? = null,
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
                // Bible annotations have entry_id 0 and say which version, book and chapter.
                entryId = if (bible != null) 0L else entryId,
                start = start,
                end = end,
                color = cleanColor,
                comment = text,
                updatedAt = now,
                deleted = false,
                bibleId = bible?.bibleId,
                book = bible?.book,
                chapter = bible?.chapter,
            )
        }
        annotations = TextLogic.upsertLocal(annotations, edited)
        persistAnnotations()
        syncAnnotations(reportErrors = false)
        return true
    }

    /** Highlights body[[start], [end]) of an entry in the last used colour (always a new annotation). */
    fun highlightRange(entryId: Long, start: Int, end: Int, bible: BibleRef? = null): Boolean {
        if (start >= end) return true
        return saveAnnotation(entryId, start, end, null, lastHighlightColor, "", bible)
    }

    /** Saves the note on a whole Bible chapter (start = end = 0); an empty text removes it. */
    fun saveChapterNote(ref: BibleRef, text: String): Boolean {
        val existing = TextLogic.chapterNote(annotations.map { it.annotation }, ref.bibleId, ref.book, ref.chapter)
        return saveAnnotation(0L, 0, 0, existing, TextLogic.COLOR_NONE, text, ref)
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
            // Uploaded: clean unless it was edited again meanwhile (then it uploads next round).
            annotations = TextLogic.markUploaded(annotations, item, stored)
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
                deleteOtherVoices()
                if (autoDownload) startAutoDownload()
                reportBibles()
                // A new latest entry may have arrived (or the unread one gone).
                refreshReminder()
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
                    val before = selectedSettingsVoice
                    val json = if (response == null) "" else AppJson.encodeToString(VoicesResponse.serializer(), response)
                    settings.setVoicesJson(key, json)
                    applyVoicesJson(json)
                    // Following the server default, which changed: switch the kept voice too.
                    if (selectedSettingsVoice != before) onSettingsVoiceChanged()
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
        if (voice == preferredVoice) return
        preferredVoice = voice
        settings.setPreferredVoice(serverKey, voice)
        onSettingsVoiceChanged()
    }

    /** The phone keeps one voice: delete the others (archived audio stays), then fetch the new one. */
    private fun onSettingsVoiceChanged() {
        autoDownloadJob?.cancel()
        deleteOtherVoices()
        if (autoDownload) startAutoDownload()
    }

    /** Deletes cached audio of voices other than the Settings voice (see AudioLogic.filesForOtherVoices). */
    private fun deleteOtherVoices() {
        val voice = selectedSettingsVoice ?: return
        val list = entries
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) {
                SyncLock.mutex.withLock {
                    for (name in AudioLogic.filesForOtherVoices(store.cachedAudioFiles(), list, voice)) {
                        store.deleteAudioFile(name)
                    }
                    store.cachedAudioFiles()
                }
            }
            cachedAudio = remaining
            val loadedId = playerEntryId
            val loadedVoice = playerVoice
            if (loadedId != null && loadedVoice != null && AudioLogic.fileName(loadedId, loadedVoice) !in remaining) {
                stopPlayback()
            }
        }
    }

    /** A voice's name from /api/voices, falling back to its id. */
    fun voiceLabel(voice: String): String {
        if (voice.isEmpty()) return "Default voice"
        return voices.firstOrNull { it.id == voice }?.name?.takeIf { it.isNotBlank() } ?: voice
    }

    /** What the player offers for [entry]: the Settings voice only (archived entries: what is cached). */
    fun playerOffer(entry: Entry): PlayerOffer = AudioLogic.playerOffer(entry, selectedSettingsVoice, cachedAudio)

    /** The voice [entry] plays in, or null when there is nothing to play. */
    fun voiceFor(entry: Entry): String? = (playerOffer(entry) as? PlayerOffer.Play)?.voice

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

    private fun startAutoDownload() {
        if (autoDownloadJob?.isActive == true) return
        autoDownloadJob = viewModelScope.launch {
            for (entry in entries.toList()) {
                if (!autoDownload) break
                // Only the Settings voice; archived entries cannot be downloaded any more.
                val voice = AudioLogic.voiceToKeep(entry, selectedSettingsVoice) ?: continue
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
                // Stale if a sync changed the audio, or the Settings voice changed, while it downloaded.
                val currentEntry = entryById(id)
                val current = currentEntry?.let { e -> AudioLogic.variants(e).firstOrNull { it.voice == voice } }
                val stillWanted = currentEntry != null && AudioLogic.voiceToKeep(currentEntry, selectedSettingsVoice) == voice
                if (current == null || current.sha256 != variant.sha256 || !stillWanted) {
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
    //
    // The ExoPlayer lives in PlaybackService (so audio survives leaving the app); this is a
    // MediaController connected to it. It is connected when the ViewModel is created, which also
    // lets a new ViewModel pick up audio that is already playing, and released in onCleared.

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionJob: Job? = null
    private var pendingPlay: Pair<Long, String>? = null

    /** A start request made before the controller was connected; run once it is. */
    private var afterConnect: (() -> Unit)? = null

    /** The media notification asked for the playing entry before the controller was connected. */
    private var openPlayingWhenConnected = false

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

    /**
     * Android 13+: true once, the first time the user presses play, so the UI asks for the
     * notification permission (for the media notification). Playback works without it.
     */
    var askNotificationPermission: Boolean by mutableStateOf(false)
        private set

    fun notificationPermissionHandled() {
        askNotificationPermission = false
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@MainViewModel.isPlaying = isPlaying
            refreshPosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // Listened to the end: the entry counts as read.
                playerEntryId?.let { markRead(it) }
                // Rewind so the next Play starts from the beginning.
                controller?.let {
                    it.pause()
                    it.seekTo(0L)
                }
            }
            refreshPosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            adoptCurrentItem()
        }

        override fun onPlayerError(error: PlaybackException) {
            audioError = "Playback failed: ${error.message ?: error.errorCodeName}"
        }
    }

    /** Connects to PlaybackService (starting it if needed). Safe to call more than once. */
    private fun connectController() {
        if (controllerFuture != null) return
        val app = getApplication<Application>()
        try {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val future = MediaController.Builder(app, token).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    try {
                        val connected = future.get()
                        controller = connected
                        connected.addListener(playerListener)
                        adoptCurrentItem()
                        startPositionTicker()
                        val pending = afterConnect
                        afterConnect = null
                        pending?.invoke()
                        if (openPlayingWhenConnected) {
                            openPlayingWhenConnected = false
                            openPlayingEntry()
                        }
                    } catch (e: Exception) {
                        // Cancelled (ViewModel cleared) or the service could not be reached.
                        if (controllerFuture === future) controllerFuture = null
                        if (afterConnect != null) {
                            afterConnect = null
                            audioError = "Could not start the audio player: ${describe(e)}"
                        }
                    }
                },
                ContextCompat.getMainExecutor(app),
            )
        } catch (e: Exception) {
            controllerFuture = null
            afterConnect = null
            audioError = "Could not start the audio player: ${describe(e)}"
        }
    }

    /** Takes over what the session is playing (e.g. started before this ViewModel existed). */
    private fun adoptCurrentItem() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val mediaId = c.currentMediaItem?.mediaId
        val chapter = BibleAudioLogic.parseMediaId(mediaId)
        if (chapter != null) {
            // A Bible chapter: none of the entry logic (read marks, voices, follow) applies to it.
            playerEntryId = null
            playerVoice = null
            playerBibleRef = chapter
            val metadata = c.mediaMetadata
            playerBibleTitle = metadata.title?.toString().orEmpty()
            playerBibleSubtitle = metadata.artist?.toString().orEmpty()
            loadChapterTimings(chapter)
        } else {
            val playing = MiniPlayerLogic.parseMediaId(mediaId) ?: return
            playerBibleRef = null
            playerEntryId = playing.first
            playerVoice = playing.second
        }
        isPlaying = c.isPlaying
        refreshPosition()
    }

    private fun startPositionTicker() {
        if (positionJob?.isActive == true) return
        positionJob = viewModelScope.launch {
            while (isActive) {
                refreshPosition()
                delay(500L)
            }
        }
    }

    /** Play/pause for [entry] in its current voice; downloads that audio first when needed. */
    fun playPause(entry: Entry) {
        val voice = voiceFor(entry)
        if (voice == null) {
            audioError = "No audio for this entry."
            return
        }
        if (controller != null && playerEntryId == entry.id && playerVoice == voice) {
            togglePlayback()
            return
        }
        audioError = null
        requestNotificationPermissionOnce()
        pendingPlay = entry.id to voice
        viewModelScope.launch {
            val ok = ensureAudio(entry, voice, reportErrors = true).await()
            if (ok && pendingPlay == (entry.id to voice)) {
                pendingPlay = null
                startPlayback(entry.id, voice, 0L, true)
            }
        }
    }

    /** Play/pause of whatever is loaded (the mini player's button). */
    fun togglePlayback() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            requestNotificationPermissionOnce()
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0L)
            c.play()
        }
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33 || settings.notificationPermissionAsked) return
        settings.notificationPermissionAsked = true
        val granted = ContextCompat.checkSelfPermission(getApplication<Application>(), POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotificationPermission = true
    }

    fun seekTo(ms: Long) {
        val c = controller ?: return
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        c.seekTo(ms.coerceIn(0L, upper))
        refreshPosition()
    }

    fun skip(deltaMs: Long) {
        val c = controller ?: return
        seekTo(c.currentPosition + deltaMs)
    }

    private fun startPlayback(id: Long, voice: String, startMs: Long, play: Boolean) {
        val file = store.audioFile(id, voice)
        if (!file.isFile) {
            cachedAudio = cachedAudio - AudioLogic.fileName(id, voice)
            audioError = "The audio file is missing; please try again."
            return
        }
        val c = controller
        if (c == null) {
            // Not connected yet: start as soon as the controller is there.
            afterConnect = { startPlayback(id, voice, startMs, play) }
            connectController()
            return
        }
        val entry = entryById(id)
        val heading = entry?.let { EntryKinds.heading(it, Locale.getDefault(), ZoneId.systemDefault()) }
        loadIntoPlayer(
            c,
            MiniPlayerLogic.mediaId(id, voice),
            file,
            MiniPlayerLogic.notificationTitle(heading, entry?.title),
            MiniPlayerLogic.notificationSubtitle(heading),
            startMs,
            play,
        )
        playerBibleRef = null
        chapterTimings = null
        playerEntryId = id
        playerVoice = voice
        positionMs = startMs
        durationMs = 0L
    }

    /** Loads a file into the session's player with what the notification shows, and optionally starts it. */
    private fun loadIntoPlayer(
        c: MediaController,
        mediaId: String,
        file: File,
        title: String,
        artist: String,
        startMs: Long,
        play: Boolean,
    ) {
        val uri = Uri.fromFile(file)
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            // The URI itself does not travel to the session; the service restores it from here.
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            // Shown by the media notification and the lock screen.
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
            .build()
        c.setMediaItem(item, startMs)
        c.setPlaybackSpeed(playbackSpeed)
        c.prepare()
        if (play) c.play()
    }

    private fun stopPlayback() {
        pendingPlay = null
        playerBibleRef = null
        chapterTimings = null
        afterConnect = null
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
        playerEntryId = null
        playerVoice = null
        isPlaying = false
        positionMs = 0L
        durationMs = 0L
    }

    private fun refreshPosition() {
        val c = controller ?: return
        if (playerEntryId == null && playerBibleRef == null) return
        positionMs = c.currentPosition.coerceAtLeast(0L)
        val duration = c.duration
        durationMs = if (duration == C.TIME_UNSET || duration < 0L) 0L else duration
    }

    // ---- mini player ------------------------------------------------------------------------

    /** Shown on every screen but the playing entry's own, while playing or paused part-way. */
    val miniPlayerVisible: Boolean
        get() = MiniPlayerLogic.isVisible(playerEntryId, (screen as? Screen.Detail)?.id, isPlaying, positionMs) ||
            BibleAudioLogic.isMiniPlayerVisible(playerBibleRef, shownChapter, isPlaying, positionMs)

    /** The chapter open in the Bible reader right now, or null on any other screen. */
    val shownChapter: BibleRef?
        get() {
            val bible = openBible ?: return null
            if (screen != Screen.EntryList || selectedTab != EntryTab.BIBLE) return null
            if (bibleLoading || effectiveBibleView != BibleView.READER) return null
            return BibleRef(bible.id, bibleBook, bibleChapter + 1)
        }

    /** The two lines describing what is loaded in the player. */
    val nowPlaying: NowPlaying
        get() {
            if (playerBibleRef != null) {
                return NowPlaying(playerBibleTitle.ifBlank { "Bible" }, playerBibleSubtitle)
            }
            val entry = playerEntryId?.let { entryById(it) }
            val heading = entry?.let { EntryKinds.heading(it, Locale.getDefault(), ZoneId.systemDefault()) }
            return MiniPlayerLogic.nowPlaying(heading, entry?.title)
        }

    /** The mini player's close button: stops playback (the service leaves the foreground). */
    fun stopFromMiniPlayer() {
        stopPlayback()
    }

    /** Opens the entry that is playing (mini player title, media notification). */
    fun openPlayingEntry() {
        if (controller == null) {
            openPlayingWhenConnected = true
            return
        }
        val chapter = playerBibleRef
        if (chapter != null) {
            // Open that chapter in the Bible tab (if its version is still on the phone).
            if (downloadedBibles.none { it.id == chapter.bibleId }) return
            bibleReturn = null
            if (selectedTab != EntryTab.BIBLE) {
                selectedTab = EntryTab.BIBLE
                settings.entryTab = EntryTab.BIBLE.key
            }
            screen = Screen.EntryList
            loadBible(chapter.bibleId) { showChapter(chapter.book, chapter.chapter - 1, null) }
            return
        }
        val id = playerEntryId ?: return
        if (entryById(id) != null) screen = Screen.Detail(id)
    }

    // ---- listening to Bible chapters --------------------------------------------------------

    /** The server's last known "Bible audio is on" flag. */
    var bibleAudioAvailable: Boolean by mutableStateOf(settings.bibleAudioAvailable)
        private set
    /** The chapter loaded in the player, if it is a Bible chapter (then [playerEntryId] is null). */
    var playerBibleRef: BibleRef? by mutableStateOf(null)
        private set
    private var playerBibleTitle: String by mutableStateOf("")
    private var playerBibleSubtitle: String by mutableStateOf("")
    /** File names of the chapters whose audio is on the phone. */
    var cachedChapterAudio: Set<String> by mutableStateOf(emptySet())
        private set
    var bibleAudioBytes: Long by mutableStateOf(0L)
        private set
    /** The chapter whose audio is being prepared/downloaded, if any. */
    var preparingChapter: BibleRef? by mutableStateOf(null)
        private set
    /** The last failure, shown in that chapter's audio bar with Retry. */
    var chapterAudioError: Pair<BibleRef, String>? by mutableStateOf(null)
        private set
    private var chapterAudioJob: Job? = null

    fun isChapterAudioCached(ref: BibleRef): Boolean = BibleAudioLogic.fileName(ref) in cachedChapterAudio

    private fun refreshChapterAudioCache() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bibleAudioStore.cachedNames() to bibleAudioStore.totalBytes() }
            cachedChapterAudio = result.first
            bibleAudioBytes = result.second
        }
    }

    /**
     * Listen / play / pause for a chapter. A chapter that is not on the phone yet is asked for
     * from the server (which may still have to make it), downloaded, then played.
     */
    fun playChapter(ref: BibleRef, title: String, versionName: String, fromVerse: Int? = null) {
        if (controller != null && playerBibleRef == ref) {
            if (fromVerse != null) seekToVerse(fromVerse) else togglePlayback()
            return
        }
        if (preparingChapter == ref) return
        chapterAudioError = null
        requestNotificationPermissionOnce()
        chapterAudioJob?.cancel()
        val url = serverUrl
        chapterAudioJob = viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { bibleAudioStore.file(ref).isFile }
                if (!cached) {
                    preparingChapter = ref
                    bibleAudioStore.fetch(api, url, ref)
                }
                preparingChapter = null
                // Where the tapped verse starts, if the chapter has verse times.
                val timings = withContext(Dispatchers.IO) { bibleAudioStore.loadTimings(ref) }
                val startMs = fromVerse?.let { BibleAudioLogic.seekTarget(timings, it) } ?: 0L
                startChapterPlayback(ref, title, versionName, timings, startMs)
            } catch (e: CancellationException) {
                if (preparingChapter == ref) preparingChapter = null
                throw e
            } catch (e: Exception) {
                preparingChapter = null
                chapterAudioError = ref to describe(e)
            }
        }
    }

    /** Cancel while "Preparing the audio…". */
    fun cancelChapterAudio() {
        chapterAudioJob?.cancel()
        chapterAudioJob = null
        preparingChapter = null
    }

    /** The verse times of the chapter loaded in the player; null when it has none. */
    var chapterTimings: ChapterTimings? by mutableStateOf(null)
        private set

    /** The verse being read (1-based) in the loaded chapter, or null without verse times. */
    val readingVerse: Int?
        get() {
            if (playerBibleRef == null) return null
            if (!isPlaying && positionMs <= 0L) return null
            return BibleAudioLogic.verseAt(chapterTimings, positionMs)
        }

    /** Start reading the loaded chapter from [verse] (a tap on its number): seek there and play. */
    private fun seekToVerse(verse: Int) {
        val c = controller ?: return
        val target = BibleAudioLogic.seekTarget(chapterTimings, verse) ?: return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.seekTo(target)
        c.play()
        refreshPosition()
    }

    /** Reads the stored verse times of [ref]; a cached MP3 without them gets one quiet attempt to fetch them. */
    private fun loadChapterTimings(ref: BibleRef) {
        val url = serverUrl
        val canAsk = isServerConfigured
        viewModelScope.launch {
            val timings = withContext(Dispatchers.IO) {
                bibleAudioStore.loadTimings(ref)
                    ?: if (canAsk && bibleAudioStore.fetchTimingsQuietly(api, url, ref)) bibleAudioStore.loadTimings(ref) else null
            }
            if (playerBibleRef == ref) chapterTimings = timings
        }
    }

    private fun startChapterPlayback(
        ref: BibleRef,
        title: String,
        versionName: String,
        timings: ChapterTimings?,
        startMs: Long,
    ) {
        val file = bibleAudioStore.file(ref)
        if (!file.isFile) {
            chapterAudioError = ref to "The audio file is missing; please try again."
            return
        }
        val c = controller
        if (c == null) {
            afterConnect = { startChapterPlayback(ref, title, versionName, timings, startMs) }
            connectController()
            return
        }
        pendingPlay = null
        loadIntoPlayer(c, BibleAudioLogic.mediaId(ref), file, title, versionName, startMs, true)
        playerEntryId = null
        playerVoice = null
        playerBibleRef = ref
        playerBibleTitle = title
        playerBibleSubtitle = versionName
        positionMs = startMs
        durationMs = 0L
        chapterTimings = timings
        // An older download without verse times: try once, quietly, to get them.
        if (timings == null) loadChapterTimings(ref)
        // Played now: least recently played chapters go first when the cache is over its limit.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                bibleAudioStore.touch(ref)
                bibleAudioStore.trim(keep = ref)
            }
            refreshChapterAudioCache()
        }
    }

    /** Settings: "Clear Bible audio". */
    fun clearBibleAudio() {
        cancelChapterAudio()
        if (playerBibleRef != null) stopPlayback()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { bibleAudioStore.clear() }
            refreshChapterAudioCache()
        }
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
                val response = withContext(Dispatchers.IO) { api.biblesResponse(url) }
                serverBibles = response.bibles
                serverBiblesLoaded = true
                // Remembered so chapters already on the phone can be listened to offline.
                bibleAudioAvailable = response.audio
                settings.bibleAudioAvailable = response.audio
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
                reportBibles()
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
                reportBibles()
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
    /** How much darker than the window the list item cards are, 0..1 (default 0.4). */
    var listItemShade: Float by mutableStateOf(
        WindowLogic.migrateShade(settings.listItemShadeRaw, settings.listItemShadeMigrated),
    )
        private set

    fun updateListItemShade(value: Float) {
        val v = WindowLogic.clampShade(value)
        listItemShade = v
        settings.setListItemShade(v)
    }

    /** Opacity of the text window over the app wallpaper, 0.5..1. */
    var windowOpacity: Float by mutableStateOf(WindowLogic.clampOpacity(settings.textWindowOpacity))
        private set

    private var wallJob: Job? = null

    /** Horizontal crop of the app wallpaper (0 = left, 0.5 = centre, 1 = right); see WallpaperLogic.cropRect. */
    var wallpaperBias: Float by mutableStateOf(WallpaperLogic.clampBias(settings.wallpaperBias))
        private set

    var darkWallpapersInEvening: Boolean by mutableStateOf(settings.darkWallpapersInEvening)
        private set
    var darkFromMinutes: Int by mutableStateOf(SyncScheduleLogic.normalizeMinutes(settings.darkFromMinutes))
        private set
    var darkUntilMinutes: Int by mutableStateOf(SyncScheduleLogic.normalizeMinutes(settings.darkUntilMinutes))
        private set

    fun updateDarkWallpapersInEvening(value: Boolean) {
        darkWallpapersInEvening = value
        settings.darkWallpapersInEvening = value
    }

    fun updateDarkFrom(hour: Int, minute: Int) {
        darkFromMinutes = SyncScheduleLogic.normalizeMinutes(hour * 60 + minute)
        settings.darkFromMinutes = darkFromMinutes
    }

    fun updateDarkUntil(hour: Int, minute: Int) {
        darkUntilMinutes = SyncScheduleLogic.normalizeMinutes(hour * 60 + minute)
        settings.darkUntilMinutes = darkUntilMinutes
    }

    private fun rememberWallpaperBias(value: Float) {
        wallpaperBias = WallpaperLogic.clampBias(value)
        settings.wallpaperBias = wallpaperBias
    }

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
                // A wallpaper picked by hand is centred.
                rememberWallpaperBias(0.5f)
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
        // In the evening (when asked for) only dark wallpapers, if the server has any.
        val now = LocalTime.now()
        val darkOnly = darkWallpapersInEvening &&
            TimeWindow.contains(now.hour * 60 + now.minute, darkFromMinutes, darkUntilMinutes)
        try {
            val result = withContext(Dispatchers.IO) {
                val library = api.wallpapers(url) ?: return@withContext null
                val pick = WallpaperLogic.pickRandom(library, wallStartTopic, currentId, Random.Default, darkOnly)
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
                // A random horizontal crop for a wallpaper wider than the screen.
                rememberWallpaperBias(Random.Default.nextFloat())
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
        // Also picks up audio that is already playing in the background.
        connectController()
        scheduleReminder()
        if (remindersEnabled && isServerConfigured) requestNotificationPermissionOnce()
        refreshChapterAudioCache()
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
        // Release the controller (and a connection still in flight); the service keeps playing.
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
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

        /** Manifest.permission.POST_NOTIFICATIONS (API 33). */
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

        private fun sanitizeSpeed(value: Float): Float =
            PLAYBACK_SPEEDS.minByOrNull { kotlin.math.abs(it - value) } ?: 1f

        /** 1f -> "1×", 1.25f -> "1.25×". */
        fun speedLabel(speed: Float): String {
            val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
            return "$text×"
        }
    }
}
