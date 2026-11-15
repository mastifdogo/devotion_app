package com.mastifdogo.devotion.data

import com.mastifdogo.devotion.logic.ServerAddress
import com.mastifdogo.devotion.logic.WallpaperLibrary
import com.mastifdogo.devotion.logic.WallpaperLogic
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** An error with a message that is fit to show to the user as-is. */
class ApiException(message: String, val httpCode: Int = 0) : IOException(message)

/**
 * Blocking client for the Devotion server (no authentication). Call from a background thread
 * (the callers wrap everything in Dispatchers.IO).
 */
class ApiClient {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** GET /api/health. */
    fun health(baseUrl: String): HealthResponse {
        val url = apiUrl(baseUrl).addPathSegments("api/health").build()
        return getJson(url, "Health check") { AppJson.decodeFromString(HealthResponse.serializer(), it) }
    }

    /** GET /api/entries[?since=...]. */
    fun entries(baseUrl: String, since: String?): EntriesResponse {
        val builder = apiUrl(baseUrl).addPathSegments("api/entries")
        if (!since.isNullOrBlank()) builder.addQueryParameter("since", since)
        return getJson(builder.build(), "Loading entries") {
            AppJson.decodeFromString(EntriesResponse.serializer(), it)
        }
    }

    /**
     * GET /api/entries/{id}/audio[?voice=...] into [dest]; voice "" means the server default.
     * Returns the lowercase hex SHA-256 of the bytes written.
     * [onProgress] receives (bytesRead, totalBytes or -1 when unknown); it may throw to abort.
     */
    fun downloadAudio(baseUrl: String, id: Long, voice: String, dest: File, onProgress: (Long, Long) -> Unit): String {
        val builder = apiUrl(baseUrl).addPathSegments("api/entries/$id/audio")
        if (voice.isNotEmpty()) builder.addQueryParameter("voice", voice)
        return downloadTo(
            builder.build(),
            dest,
            "Downloading audio",
            "The server has no audio for this entry in this voice.",
            onProgress,
        )
    }

    /** GET /api/voices; null when the server is too old to offer a choice of voice (404). */
    fun voices(baseUrl: String): VoicesResponse? {
        val url = apiUrl(baseUrl).addPathSegments("api/voices").build()
        return try {
            getJson(url, "Loading voices") { AppJson.decodeFromString(VoicesResponse.serializer(), it) }
        } catch (e: ApiException) {
            if (e.httpCode == 404) null else throw e
        }
    }

    // ---- profiles ---------------------------------------------------------------------------

    /** GET /api/profiles. */
    fun profiles(baseUrl: String): List<Profile> {
        val url = apiUrl(baseUrl).addPathSegments("api/profiles").build()
        return getJson(url, "Loading profiles") {
            AppJson.decodeFromString(ProfilesResponse.serializer(), it)
        }.profiles
    }

    /** POST /api/profiles; returns the new profile, or the existing one with the same name. */
    fun createProfile(baseUrl: String, name: String): Profile {
        val url = apiUrl(baseUrl).addPathSegments("api/profiles").build()
        val payload = AppJson.encodeToString(CreateProfileRequest.serializer(), CreateProfileRequest(name))
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError(response.code, text, "Saving the profile")
            return decode("profile") { AppJson.decodeFromString(Profile.serializer(), text) }
        }
    }

    // ---- annotations ------------------------------------------------------------------------

    /** GET /api/profiles/{id}/annotations[?since=...]. A 404 means the profile no longer exists. */
    fun annotations(baseUrl: String, profileId: Long, since: String?): AnnotationsResponse {
        val builder = apiUrl(baseUrl).addPathSegments("api/profiles/$profileId/annotations")
        if (!since.isNullOrBlank()) builder.addQueryParameter("since", since)
        return getJson(builder.build(), "Loading notes") {
            AppJson.decodeFromString(AnnotationsResponse.serializer(), it)
        }
    }

    /** PUT /api/profiles/{id}/annotations/{annotationId}; returns what the server stored. */
    fun putAnnotation(baseUrl: String, profileId: Long, annotation: Annotation): Annotation {
        val url = apiUrl(baseUrl)
            .addPathSegments("api/profiles/$profileId/annotations")
            .addPathSegment(annotation.id)
            .build()
        val payload = AppJson.encodeToString(Annotation.serializer(), annotation)
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .put(payload.toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError(response.code, text, "Saving a note")
            return decode("note") { AppJson.decodeFromString(Annotation.serializer(), text) }
        }
    }

    // ---- bibles -----------------------------------------------------------------------------

    /** GET /api/bibles. */
    fun bibles(baseUrl: String): List<BibleInfo> {
        val url = apiUrl(baseUrl).addPathSegments("api/bibles").build()
        return getJson(url, "Loading Bible versions") {
            AppJson.decodeFromString(BiblesResponse.serializer(), it)
        }.bibles
    }

    /** GET /api/bibles/{id} streamed into [dest]; returns the lowercase hex SHA-256 of the bytes written. */
    fun downloadBible(baseUrl: String, id: String, dest: File, onProgress: (Long, Long) -> Unit): String {
        val url = apiUrl(baseUrl).addPathSegments("api/bibles").addPathSegment(id).build()
        return downloadTo(url, dest, "Downloading the Bible", "The server no longer has this Bible version.", onProgress)
    }

    // ---- wallpapers -------------------------------------------------------------------------

    /** GET /api/wallpapers; null when the server is too old to have wallpapers (404). */
    fun wallpapers(baseUrl: String): WallpaperLibrary? {
        val url = apiUrl(baseUrl).addPathSegments("api/wallpapers").build()
        return try {
            getJson(url, "Loading wallpapers") { WallpaperLogic.parseLibrary(it) }
        } catch (e: ApiException) {
            if (e.httpCode == 404) null else throw e
        }
    }

    /** What a wallpaper download produced. */
    class Download(val sha256: String, val contentType: String?)

    /** GET /api/wallpapers/{id} into [dest]. */
    fun downloadWallpaper(baseUrl: String, id: String, dest: File): Download {
        val url = wallpaperUrl(baseUrl, id, thumb = false)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw ApiException("The server no longer has this wallpaper.", 404)
            if (!response.isSuccessful) throw httpError(response.code, "", "Downloading the wallpaper")
            val body = response.body ?: throw ApiException("Downloading the wallpaper failed: empty response.")
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
            return Download(sha, response.header("Content-Type"))
        }
    }

    /** Opens GET /api/wallpapers/{id} and hands the body stream to [consume] (no copy on disk). */
    fun streamWallpaper(baseUrl: String, id: String, consume: (InputStream) -> Unit) {
        val request = Request.Builder().url(wallpaperUrl(baseUrl, id, thumb = false)).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw ApiException("The server no longer has this wallpaper.", 404)
            if (!response.isSuccessful) throw httpError(response.code, "", "Loading the wallpaper")
            val body = response.body ?: throw ApiException("Loading the wallpaper failed: empty response.")
            body.byteStream().use { consume(it) }
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private inline fun <T> getJson(url: HttpUrl, what: String, parse: (String) -> T): T {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError(response.code, text, what)
            return decode(what) { parse(text) }
        }
    }

    private fun downloadTo(
        url: HttpUrl,
        dest: File,
        what: String,
        notFoundMessage: String,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw ApiException(notFoundMessage, 404)
            if (!response.isSuccessful) throw httpError(response.code, "", what)
            val body = response.body ?: throw ApiException("$what failed: empty response.")
            // -1 when unknown (always the case when OkHttp transparently un-gzips the body).
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var read = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        }
    }

    private fun httpError(code: Int, bodyText: String, what: String): ApiException {
        // The server answers errors as {"error":"..."}; show that text when there is one.
        val serverMessage = try {
            AppJson.decodeFromString(ErrorResponse.serializer(), bodyText).error
        } catch (e: Exception) {
            null
        }
        val detail = if (serverMessage.isNullOrBlank()) "HTTP $code" else serverMessage
        return ApiException("$what failed: $detail.".replace("..", "."), code)
    }

    private inline fun <T> decode(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            throw ApiException("Unexpected answer from the server ($what). Is the address right?")
        }

    companion object {
        /** See [ServerAddress.normalize]. */
        fun normalizeBaseUrl(raw: String): String = ServerAddress.normalize(raw)

        /** URL of a wallpaper's full image or its thumbnail, for the image loader. */
        fun wallpaperUrl(baseUrl: String, id: String, thumb: Boolean): String {
            val builder = apiUrl(baseUrl).addPathSegments("api/wallpapers").addPathSegment(id)
            if (thumb) builder.addPathSegment("thumb")
            return builder.build().toString()
        }

        private fun apiUrl(baseUrl: String): HttpUrl.Builder {
            val normalized = normalizeBaseUrl(baseUrl)
            if (normalized.isEmpty()) throw ApiException("No server address is set.")
            val parsed = normalized.toHttpUrlOrNull()
                ?: throw ApiException("\"$normalized\" is not a valid server address.")
            return parsed.newBuilder()
        }
    }
}
