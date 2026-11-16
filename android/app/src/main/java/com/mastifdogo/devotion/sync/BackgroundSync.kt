package com.mastifdogo.devotion.sync

import android.content.Context
import com.mastifdogo.devotion.data.AnnotationFile
import com.mastifdogo.devotion.data.AnnotationStore
import com.mastifdogo.devotion.data.ApiClient
import com.mastifdogo.devotion.data.ApiException
import com.mastifdogo.devotion.data.AppJson
import com.mastifdogo.devotion.data.BibleStore
import com.mastifdogo.devotion.data.Entry
import com.mastifdogo.devotion.data.EntryStore
import com.mastifdogo.devotion.data.SettingsStore
import com.mastifdogo.devotion.data.SyncLock
import com.mastifdogo.devotion.data.VoicesResponse
import com.mastifdogo.devotion.logic.AudioLogic
import com.mastifdogo.devotion.logic.ServerAddress
import com.mastifdogo.devotion.logic.TextLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The full sync without any UI, for the background worker: entries, voices, the profile's notes
 * and, when "Download audio automatically" is on, each entry's audio in the preferred voice.
 * It uses the same stores and rules as the in-app sync. Failures are recorded in the settings
 * ("last error") and never thrown, except cancellation.
 */
class BackgroundSync(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)

    /** Runs the sync; returns the error message, or null on success or when no server is set. */
    suspend fun run(): String? {
        val server = settings.serverUrl
        if (ServerAddress.normalize(server).isEmpty()) return null
        val key = ServerAddress.key(server)
        val api = ApiClient()
        return try {
            SyncLock.mutex.withLock {
                withContext(Dispatchers.IO) {
                    val store = EntryStore(appContext.filesDir)
                    store.load()
                    val cache = store.sync(api, server)
                    syncVoices(api, server, key)
                    val profile = settings.profileFor(key)
                    if (profile != null) syncAnnotations(api, server, key, profile.id)
                    // Only the Settings voice is kept: drop other voices (archived audio stays) ...
                    val voice = settingsVoice(key)
                    for (name in AudioLogic.filesForOtherVoices(store.cachedAudioFiles(), cache.entries, voice)) {
                        store.deleteAudioFile(name)
                    }
                    // ... and download that voice.
                    if (settings.autoDownloadAudio) downloadAudio(store, api, server, voice, cache.entries)
                    if (profile != null) reportBibles(api, server, key, profile.id)
                }
            }
            settings.lastSyncMillis = System.currentTimeMillis()
            settings.lastSyncError = ""
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "Background sync failed: " + (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            settings.lastSyncError = message
            message
        }
    }

    private fun syncVoices(api: ApiClient, server: String, key: String) {
        try {
            val voices = api.voices(server)
            val json = if (voices == null) "" else AppJson.encodeToString(VoicesResponse.serializer(), voices)
            settings.setVoicesJson(key, json)
        } catch (e: Exception) {
            // The voice list is only names; keep the cached one.
        }
    }

    private fun syncAnnotations(api: ApiClient, server: String, key: String, profileId: Long) {
        val annotationStore = AnnotationStore(appContext.filesDir)
        val file = annotationStore.load(key, profileId)
        var list = file.annotations
        for (item in list.filter { it.dirty }.map { it.annotation }) {
            val stored = api.putAnnotation(server, profileId, item)
            list = TextLogic.mergeRemote(list, listOf(stored))
        }
        val response = api.annotations(server, profileId, file.serverTime)
        list = TextLogic.mergeRemote(list, response.annotations)
        annotationStore.save(key, profileId, AnnotationFile(list, response.serverTime ?: file.serverTime))
    }

    /** The voice chosen in Settings, else the server default; null when not known. */
    private fun settingsVoice(key: String): String? {
        settings.preferredVoice(key)?.let { return it }
        val json = settings.voicesJson(key)?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            AppJson.decodeFromString(VoicesResponse.serializer(), json).defaultVoice?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun downloadAudio(store: EntryStore, api: ApiClient, server: String, voice: String?, entries: List<Entry>) {
        for (entry in entries) {
            val keep = AudioLogic.voiceToKeep(entry, voice) ?: continue
            if (store.audioFile(entry.id, keep).isFile) continue
            val variant = AudioLogic.variants(entry).firstOrNull { it.voice == keep } ?: continue
            store.downloadAudio(api, server, entry, variant) { }
        }
    }

    /**
     * Tells the server which Bible versions this profile has on the phone. Best-effort: a 404 only
     * means "profile gone" when the profile list no longer has it (older servers answer 404 too).
     */
    private fun reportBibles(api: ApiClient, server: String, key: String, profileId: Long) {
        val ids = BibleStore(appContext.filesDir).downloaded().map { it.id }
        try {
            api.putProfileBibles(server, profileId, ids)
        } catch (e: ApiException) {
            if (e.httpCode == 404) {
                val gone = try {
                    api.profiles(server).none { it.id == profileId }
                } catch (x: Exception) {
                    false
                }
                // The app asks for a profile again on its next sync.
                if (gone) settings.setProfileFor(key, null)
            }
        } catch (e: Exception) {
            // Silent: the list is sent again on the next sync.
        }
    }
}
