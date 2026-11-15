package com.mastifdogo.devotion.sync

import android.content.Context
import com.mastifdogo.devotion.data.AnnotationFile
import com.mastifdogo.devotion.data.AnnotationStore
import com.mastifdogo.devotion.data.ApiClient
import com.mastifdogo.devotion.data.AppJson
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
                    if (settings.autoDownloadAudio) downloadAudio(store, api, server, key, cache.entries)
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

    private suspend fun downloadAudio(store: EntryStore, api: ApiClient, server: String, key: String, entries: List<Entry>) {
        val preferred = settings.preferredVoice(key)
        val serverDefault = settings.voicesJson(key)?.takeIf { it.isNotEmpty() }?.let { json ->
            try {
                AppJson.decodeFromString(VoicesResponse.serializer(), json).defaultVoice
            } catch (e: Exception) {
                null
            }
        }
        for (entry in entries) {
            val cached = store.cachedAudioFiles()
            val playable = AudioLogic.playableVariants(entry, cached)
            val voice = AudioLogic.pickVoice(playable, preferred, serverDefault) ?: continue
            if (AudioLogic.fileName(entry.id, voice) in cached) continue
            val variant = playable.firstOrNull { it.voice == voice } ?: continue
            store.downloadAudio(api, server, entry, variant) { }
        }
    }
}
