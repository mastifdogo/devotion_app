package com.mastifdogo.devotion.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** An error with a message that is fit to show to the user as-is. */
class ApiException(message: String, val httpCode: Int = 0) : IOException(message)

/**
 * Blocking client for the Devotion server. Call from a background thread
 * (the callers wrap everything in Dispatchers.IO).
 */
class ApiClient {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** GET /api/health (no auth). */
    fun health(baseUrl: String): HealthResponse {
        val url = apiUrl(baseUrl).addPathSegments("api/health").build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response, "Health check")
            val text = response.body?.string().orEmpty()
            return decode("health") { AppJson.decodeFromString(HealthResponse.serializer(), text) }
        }
    }

    /** GET /api/entries[?since=...]. */
    fun entries(baseUrl: String, token: String, since: String?): EntriesResponse {
        val builder = apiUrl(baseUrl).addPathSegments("api/entries")
        if (!since.isNullOrBlank()) builder.addQueryParameter("since", since)
        val request = authorized(Request.Builder().url(builder.build()), token)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response, "Loading entries")
            val text = response.body?.string().orEmpty()
            return decode("entries") { AppJson.decodeFromString(EntriesResponse.serializer(), text) }
        }
    }

    /**
     * GET /api/entries/{id}/audio into [dest]. Returns the lowercase hex SHA-256 of the bytes written.
     * [onProgress] receives (bytesRead, totalBytes or -1 when unknown); it may throw to abort.
     */
    fun downloadAudio(
        baseUrl: String,
        token: String,
        id: Long,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val url = apiUrl(baseUrl).addPathSegments("api/entries/$id/audio").build()
        val request = authorized(Request.Builder().url(url), token).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw ApiException("The server has no audio for this entry yet.", 404)
            if (!response.isSuccessful) throw httpError(response, "Downloading audio")
            val body = response.body ?: throw ApiException("Downloading audio failed: empty response.")
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

    private fun authorized(builder: Request.Builder, token: String): Request.Builder {
        val trimmed = token.trim()
        if (trimmed.isNotEmpty()) builder.header("Authorization", "Bearer $trimmed")
        return builder
    }

    private fun httpError(response: Response, what: String): ApiException = when (response.code) {
        401 -> ApiException("Unauthorized (401): the API token is missing or wrong.", 401)
        403 -> ApiException("Forbidden (403): the server rejected the API token.", 403)
        else -> ApiException("$what failed: HTTP ${response.code}.", response.code)
    }

    private inline fun <T> decode(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            throw ApiException("Unexpected $what response from the server (is the URL right?).")
        }

    companion object {
        /** Trims, adds "http://" when no scheme is given, and strips trailing slashes. */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return ""
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }

        private fun apiUrl(baseUrl: String): HttpUrl.Builder {
            val normalized = normalizeBaseUrl(baseUrl)
            if (normalized.isEmpty()) throw ApiException("No server URL is set.")
            val parsed = normalized.toHttpUrlOrNull()
                ?: throw ApiException("The server URL \"$normalized\" is not a valid http(s) URL.")
            return parsed.newBuilder()
        }
    }
}
