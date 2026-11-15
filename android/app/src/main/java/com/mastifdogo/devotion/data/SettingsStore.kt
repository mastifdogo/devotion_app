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

    // ---- voices, remembered per server address --------------------------------------------------

    /** The voice the user chose for this server, or null to follow the server's default. */
    fun preferredVoice(serverKey: String): String? = prefs.getString(KEY_VOICE + serverKey, null)

    fun setPreferredVoice(serverKey: String, voice: String?) {
        val editor = prefs.edit()
        if (voice == null) editor.remove(KEY_VOICE + serverKey) else editor.putString(KEY_VOICE + serverKey, voice)
        editor.apply()
    }

    /**
     * The last /api/voices answer for this server as JSON, so names show offline: null when never
     * loaded, "" when the server does not support voices.
     */
    fun voicesJson(serverKey: String): String? = prefs.getString(KEY_VOICES_JSON + serverKey, null)

    fun setVoicesJson(serverKey: String, json: String) {
        prefs.edit().putString(KEY_VOICES_JSON + serverKey, json).apply()
    }

    // ---- highlights and tabs ----------------------------------------------------------------

    var lastHighlightColor: String
        get() = prefs.getString(KEY_LAST_COLOR, "yellow") ?: "yellow"
        set(value) = prefs.edit().putString(KEY_LAST_COLOR, value).apply()

    /** Key of the selected main-screen tab. */
    var entryTab: String
        get() = prefs.getString(KEY_ENTRY_TAB, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ENTRY_TAB, value).apply()

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
        const val KEY_VOICE = "voice_"
        const val KEY_VOICES_JSON = "voices_json_"
        const val KEY_LAST_COLOR = "last_highlight_color"
        const val KEY_ENTRY_TAB = "entry_tab"
        const val KEY_BIBLE_ID = "bible_id"
        const val KEY_BIBLE_BOOK = "bible_book"
        const val KEY_BIBLE_CHAPTER = "bible_chapter"
    }
}
