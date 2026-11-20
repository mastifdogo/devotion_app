package com.mastifdogo.devotion.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mastifdogo.devotion.data.AppJson
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

sealed class UpdateCheck {
    data class UpToDate(val latestVersion: String) : UpdateCheck()
    data class Available(val release: GithubRelease, val asset: ReleaseAsset?) : UpdateCheck()
    data class Failed(val message: String) : UpdateCheck()
}

/** Manual update check against GitHub releases. All network methods block; call them off the main thread. */
class Updater(private val cacheDir: File) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun check(repo: String, githubToken: String, currentVersion: String): UpdateCheck {
        val token = githubToken.trim()
        val builder = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "devotion-android")
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")

        return try {
            client.newCall(builder.build()).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val text = response.body?.string().orEmpty()
                        val release = AppJson.decodeFromString(GithubRelease.serializer(), text)
                        if (release.tagName.isBlank()) {
                            UpdateCheck.Failed("GitHub returned a release without a tag.")
                        } else if (UpdateLogic.isNewer(release.tagName, currentVersion)) {
                            UpdateCheck.Available(release, UpdateLogic.pickApkAsset(release.assets))
                        } else {
                            UpdateCheck.UpToDate(UpdateLogic.displayVersion(release.tagName))
                        }
                    }
                    else -> UpdateCheck.Failed(describeHttpError(response, token.isNotEmpty()))
                }
            }
        } catch (e: IllegalArgumentException) {
            UpdateCheck.Failed("Could not read GitHub's answer.")
        } catch (e: IOException) {
            UpdateCheck.Failed("Could not reach GitHub: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Downloads the APK asset into cacheDir/updates/ and returns the file.
     * [onProgress] gets a 0..1 fraction, or null when the size is unknown; it may throw to abort.
     */
    fun download(asset: ReleaseAsset, githubToken: String, onProgress: (Float?) -> Unit): File {
        val token = githubToken.trim()
        val source = UpdateLogic.downloadFor(asset, token.isNotEmpty())
            ?: throw IOException("The release asset has no download URL.")
        val builder = Request.Builder().url(source.url).header("User-Agent", "devotion-android")
        if (source.accept != null) builder.header("Accept", source.accept)
        if (source.useToken && token.isNotEmpty()) builder.header("Authorization", "Bearer $token")

        val dir = File(cacheDir, "updates")
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("Could not create the update download folder.")
        val safeName = File(asset.name).name.ifBlank { "update.apk" }
        val dest = File(dir, safeName)
        val part = File(dir, "$safeName.part")

        try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(describeHttpError(response, token.isNotEmpty()))
                }
                val body = response.body ?: throw IOException("Empty download.")
                val total = if (body.contentLength() > 0) body.contentLength() else asset.size
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                var lastPercent = -1
                onProgress(if (total > 0) 0f else null)
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    }
                }
            }
            if (!part.renameTo(dest)) throw IOException("Could not finish the update download.")
            return dest
        } finally {
            part.delete()
        }
    }

    private fun describeHttpError(response: Response, hasToken: Boolean): String {
        val code = response.code
        if (code == 404) {
            return "No releases yet (or the repo is private and needs a token)."
        }
        if (code == 401) {
            return "GitHub rejected the token (401). Check the GitHub token in Settings."
        }
        if (code == 403 || code == 429) {
            val now = System.currentTimeMillis() / 1000
            val wait = UpdateLogic.rateLimitWaitSeconds(
                httpCode = code,
                remaining = response.header("x-ratelimit-remaining"),
                resetEpochSeconds = response.header("x-ratelimit-reset"),
                retryAfterSeconds = response.header("retry-after"),
                nowEpochSeconds = now,
            )
            if (wait != null) {
                val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((now + wait) * 1000))
                val hint = if (hasToken) "" else " Adding a GitHub token in Settings raises the limit."
                return "GitHub rate limit reached. Try again in ${UpdateLogic.describeWait(wait)} (after $at).$hint"
            }
            return "GitHub refused the request ($code). The token may lack access to this repository."
        }
        return "GitHub answered with HTTP $code."
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"

        /**
         * Throws unless [apk] is an update of this very app. A release could hold any APK under
         * a name ending in .apk; Android itself only refuses a different key when the package
         * name is the same, so one with another name would be offered as a new app, from this
         * app's own update button. Such a download is deleted and never handed to the installer.
         */
        fun requireThisApp(context: Context, apk: File) {
            val found = try {
                context.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName
            } catch (e: Exception) {
                null
            }
            if (found == context.packageName) return
            apk.delete()
            throw IOException(
                if (found == null) "The download is not an Android app, so it was not opened."
                else "The download is a different app ($found), not Devotion, so it was not opened.",
            )
        }

        /** Intent that hands [apk] (inside cacheDir/updates/) to the system package installer. */
        fun installIntent(context: Context, apk: File): Intent {
            requireThisApp(context, apk)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
