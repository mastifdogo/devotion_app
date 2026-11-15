package com.mastifdogo.devotion.data

import android.content.Context
import android.content.SharedPreferences

/** Thin wrapper over plain SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The server address exactly as the user typed it. */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

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

    var playbackSpeed: Float
        get() = prefs.getFloat(KEY_PLAYBACK_SPEED, 1f)
        set(value) = prefs.edit().putFloat(KEY_PLAYBACK_SPEED, value).apply()

    // ---- profile, remembered per server address ---------------------------------------------

    fun profileFor(serverKey: String): Profile? {
        val id = prefs.getLong(KEY_PROFILE_ID + serverKey, -1L)
        if (id < 0L) return null
        return Profile(id = id, name = prefs.getString(KEY_PROFILE_NAME + serverKey, "") ?: "")
    }

    fun setProfileFor(serverKey: String, profile: Profile?) {
        val editor = prefs.edit()
        if (profile == null) {
            editor.remove(KEY_PROFILE_ID + serverKey).remove(KEY_PROFILE_NAME + serverKey)
        } else {
            editor.putLong(KEY_PROFILE_ID + serverKey, profile.id)
                .putString(KEY_PROFILE_NAME + serverKey, profile.name)
        }
        editor.apply()
    }

    // ---- Bible reader position --------------------------------------------------------------

    var bibleId: String
        get() = prefs.getString(KEY_BIBLE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BIBLE_ID, value).apply()

    var bibleBook: Int
        get() = prefs.getInt(KEY_BIBLE_BOOK, 0)
        set(value) = prefs.edit().putInt(KEY_BIBLE_BOOK, value).apply()

    var bibleChapter: Int
        get() = prefs.getInt(KEY_BIBLE_CHAPTER, 0)
        set(value) = prefs.edit().putInt(KEY_BIBLE_CHAPTER, value).apply()

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_AUTO_DOWNLOAD = "auto_download_audio"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_PLAYBACK_SPEED = "playback_speed"
        const val KEY_PROFILE_ID = "profile_id_"
        const val KEY_PROFILE_NAME = "profile_name_"
        const val KEY_BIBLE_ID = "bible_id"
        const val KEY_BIBLE_BOOK = "bible_book"
        const val KEY_BIBLE_CHAPTER = "bible_chapter"
    }
}
