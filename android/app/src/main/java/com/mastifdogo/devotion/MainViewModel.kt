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
import com.mastifdogo.devotion.data.ApiClient
import com.mastifdogo.devotion.data.Entry
import com.mastifdogo.devotion.data.EntryStore
import com.mastifdogo.devotion.data.SettingsStore
import com.mastifdogo.devotion.ui.ThemeMode
import com.mastifdogo.devotion.update.ReleaseAsset
import com.mastifdogo.devotion.update.UpdateCheck
import com.mastifdogo.devotion.update.UpdateLogic
import com.mastifdogo.devotion.update.Updater
import java.io.File
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
}

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
    private val api = ApiClient()
    private val updater = Updater(application.cacheDir)

    // ---- navigation -------------------------------------------------------------------------

    var screen: Screen by mutableStateOf(Screen.EntryList)
        private set

    fun open(target: Screen) {
        screen = target
    }

    /** Returns to the list; leaving Settings triggers a sync so new server details take effect. */
    fun back() {
        val leavingSettings = screen is Screen.Settings
        screen = Screen.EntryList
        if (leavingSettings && isServerConfigured) sync()
    }

    // ---- settings ---------------------------------------------------------------------------

    var serverUrl: String by mutableStateOf(settings.serverUrl)
        private set
    var apiToken: String by mutableStateOf(settings.apiToken)
        private set
    var githubToken: String by mutableStateOf(settings.githubToken)
        private set
    var autoDownload: Boolean by mutableStateOf(settings.autoDownloadAudio)
        private set

    var themeMode: ThemeMode by mutableStateOf(ThemeMode.fromKey(settings.themeMode))
        private set

    val isServerConfigured: Boolean
        get() = serverUrl.isNotBlank()

    fun updateThemeMode(value: ThemeMode) {
        themeMode = value
        settings.themeMode = value.key
    }

    fun updateServerUrl(value: String) {
        serverUrl = value
        settings.serverUrl = value.trim()
        testResult = null
    }

    fun updateApiToken(value: String) {
        apiToken = value
        settings.apiToken = value.trim()
        testResult = null
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

    var testing: Boolean by mutableStateOf(false)
        private set
    var testResult: String? by mutableStateOf(null)
        private set

    fun testConnection() {
        if (testing) return
        val url = serverUrl
        val token = apiToken
        testing = true
        testResult = null
        viewModelScope.launch {
            testResult = try {
                withContext(Dispatchers.IO) {
                    val health = api.health(url)
                    if (!health.ok) throw IllegalStateException("The server reports it is not healthy.")
                    val entries = api.entries(url, token, null)
                    val count = entries.entries.count { !it.deleted }
                    "Connected. Server version ${health.version ?: "unknown"}, $count entries, token accepted."
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
            syncError = "Set the server URL in Settings first."
            return
        }
        val url = serverUrl
        val token = apiToken
        syncing = true
        syncError = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cache = store.sync(api, url, token)
                    cache to store.cachedAudioIds()
                }
                entries = result.first.entries
                lastSyncMillis = result.first.lastSyncMillis
                cachedAudio = result.second
                // The file behind the player may have been replaced or removed by the sync.
                val loaded = playerEntryId
                if (loaded != null && loaded !in cachedAudio) stopPlayback()
                if (autoDownload) startAutoDownload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                syncError = "Sync failed: ${describe(e)}"
            } finally {
                syncing = false
            }
        }
    }

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
        val token = apiToken
        val id = entry.id
        val job = viewModelScope.async {
            downloads = downloads + (id to null)
            try {
                store.downloadAudio(api, url, token, entry) { fraction ->
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

    /** Downloads the audio of [entry] without starting playback. */
    fun downloadAudio(entry: Entry) {
        audioError = null
        ensureAudio(entry, reportErrors = true)
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
}
