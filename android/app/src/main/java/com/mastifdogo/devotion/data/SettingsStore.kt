package com.mastifdogo.devotion.data

import android.content.Context
import android.content.SharedPreferences

/** Thin wrapper over plain SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var apiToken: String
        get() = prefs.getString(KEY_API_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_TOKEN, value).apply()

    var githubToken: String
        get() = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_TOKEN, value).apply()

    var autoDownloadAudio: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, value).apply()

    /** "system", "light" or "dark". */
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_TOKEN = "api_token"
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_AUTO_DOWNLOAD = "auto_download_audio"
    }
}
