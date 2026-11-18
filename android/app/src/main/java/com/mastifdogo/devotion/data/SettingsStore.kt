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

    // ---- wallpapers -----------------------------------------------------------------------------

    /** Server id and wallhaven.cc source page of the app wallpaper ("" when none). */
    var wallpaperId: String
        get() = prefs.getString(KEY_WALL_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WALL_ID, value).apply()

    var wallpaperPageUrl: String
        get() = prefs.getString(KEY_WALL_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WALL_URL, value).apply()

    /**
     * Opacity of the text window over the app wallpaper, 0.5..1 (default 1). The round-four
     * "wallpaper_strength" key is no longer read.
     */
    var textWindowOpacity: Float
        get() = prefs.getFloat(KEY_WINDOW_OPACITY, 1f)
        set(value) = prefs.edit().putFloat(KEY_WINDOW_OPACITY, value).apply()

    /**
     * Raw "list item shade" (stored under the old opacity key; see WindowLogic.migrateShade).
     * Unset reads as 1.0, the old default.
     */
    val listItemShadeRaw: Float
        get() = prefs.getFloat(KEY_ITEM_OPACITY, 1f)

    /** True once the shade was saved under its new meaning. */
    val listItemShadeMigrated: Boolean
        get() = prefs.getBoolean(KEY_ITEM_SHADE_MIGRATED, false)

    fun setListItemShade(value: Float) {
        prefs.edit().putFloat(KEY_ITEM_OPACITY, value).putBoolean(KEY_ITEM_SHADE_MIGRATED, true).apply()
    }

    /** Topic chip selected on the Wallpapers screen ("" = All). */
    var wallTopic: String
        get() = prefs.getString(KEY_WALL_TOPIC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WALL_TOPIC, value).apply()

    // ---- sync ---------------------------------------------------------------------------------

    /** Key of the "Sync automatically" choice (see AutoSync). */
    var autoSync: String
        get() = prefs.getString(KEY_AUTO_SYNC, "on_open") ?: "on_open"
        set(value) = prefs.edit().putString(KEY_AUTO_SYNC, value).apply()

    /** Time of the daily sync, minutes after midnight (default 06:30). */
    var dailySyncMinutes: Int
        get() = prefs.getInt(KEY_DAILY_SYNC, 6 * 60 + 30)
        set(value) = prefs.edit().putInt(KEY_DAILY_SYNC, value).apply()

    /** Device time of the last successful sync, in the app or in the background. */
    var lastSyncMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /** Message of the last failed sync; "" after a successful one. */
    var lastSyncError: String
        get() = prefs.getString(KEY_LAST_SYNC_ERROR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_ERROR, value).apply()

    // ---- wallpaper on app start -----------------------------------------------------------------

    /** True: a new wallpaper from the server on each app start; false: keep the current one. */
    var newWallpaperOnStart: Boolean
        get() = prefs.getBoolean(KEY_WALL_ON_START, false)
        set(value) = prefs.edit().putBoolean(KEY_WALL_ON_START, value).apply()

    /** Topic the start-up wallpaper is picked from ("" = all topics). */
    var wallStartTopic: String
        get() = prefs.getString(KEY_WALL_START_TOPIC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WALL_START_TOPIC, value).apply()

    /** The server's topics from the last /api/wallpapers (see WallpaperLogic.encodeTopics). */
    var wallTopicsCache: String
        get() = prefs.getString(KEY_WALL_TOPICS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WALL_TOPICS, value).apply()

    /** True once the app has asked for the notification permission (Android 13+), so it asks only once. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()

    // ---- follow along, reminders, evening wallpapers ------------------------------------------

    /** "Follow" in the audio bar: scroll the text along with the narration (default off). */
    var followAlong: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW, false)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW, value).apply()

    /** Auto scroll speed, dp per second (default 22). */
    var autoScrollSpeed: Int
        get() = prefs.getInt(KEY_AUTO_SCROLL, 22)
        set(value) = prefs.edit().putInt(KEY_AUTO_SCROLL, value).apply()

    /** "Remind me about unread readings" (default on). */
    var remindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMIND, true)
        set(value) = prefs.edit().putBoolean(KEY_REMIND, value).apply()

    /** No reminders from/until these times, minutes after midnight (default 21:00–07:00). */
    var quietFromMinutes: Int
        get() = prefs.getInt(KEY_QUIET_FROM, 21 * 60)
        set(value) = prefs.edit().putInt(KEY_QUIET_FROM, value).apply()

    var quietUntilMinutes: Int
        get() = prefs.getInt(KEY_QUIET_UNTIL, 7 * 60)
        set(value) = prefs.edit().putInt(KEY_QUIET_UNTIL, value).apply()

    /** "Prefer dark wallpapers in the evening" (default off) and its window (default 19:00–07:00). */
    var darkWallpapersInEvening: Boolean
        get() = prefs.getBoolean(KEY_DARK_EVENING, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK_EVENING, value).apply()

    var darkFromMinutes: Int
        get() = prefs.getInt(KEY_DARK_FROM, 19 * 60)
        set(value) = prefs.edit().putInt(KEY_DARK_FROM, value).apply()

    var darkUntilMinutes: Int
        get() = prefs.getInt(KEY_DARK_UNTIL, 7 * 60)
        set(value) = prefs.edit().putInt(KEY_DARK_UNTIL, value).apply()

    /** Horizontal crop of the app wallpaper: 0 = left edge, 0.5 = centre, 1 = right edge. */
    var wallpaperBias: Float
        get() = prefs.getFloat(KEY_WALL_BIAS, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_WALL_BIAS, value).apply()

    /** The server's last known "Bible audio is on" flag (kept so cached chapters work offline). */
    var bibleAudioAvailable: Boolean
        get() = prefs.getBoolean(KEY_BIBLE_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_BIBLE_AUDIO, value).apply()

    /** The name of the speech service's voice the server last offered for the Bible; "" when it offers none. */
    var biblePremiumName: String
        get() = prefs.getString(KEY_BIBLE_PREMIUM_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BIBLE_PREMIUM_NAME, value).apply()

    /** Whether the user asked for the speech service's voice for the Bible on this server (off until they do). */
    fun biblePremium(serverKey: String): Boolean = prefs.getBoolean(KEY_BIBLE_PREMIUM + serverKey, false)

    fun setBiblePremium(serverKey: String, value: Boolean) {
        prefs.edit().putBoolean(KEY_BIBLE_PREMIUM + serverKey, value).apply()
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
        const val KEY_VOICE = "voice_"
        const val KEY_VOICES_JSON = "voices_json_"
        const val KEY_LAST_COLOR = "last_highlight_color"
        const val KEY_ENTRY_TAB = "entry_tab"
        const val KEY_WALL_ID = "wallpaper_id"
        const val KEY_WALL_URL = "wallpaper_url"
        const val KEY_WINDOW_OPACITY = "text_window_opacity"
        const val KEY_ITEM_OPACITY = "list_item_opacity"
        const val KEY_ITEM_SHADE_MIGRATED = "list_item_shade_v2"
        const val KEY_WALL_TOPIC = "wall_topic"
        const val KEY_AUTO_SYNC = "auto_sync"
        const val KEY_DAILY_SYNC = "daily_sync_minutes"
        const val KEY_LAST_SYNC = "last_sync_millis"
        const val KEY_LAST_SYNC_ERROR = "last_sync_error"
        const val KEY_WALL_ON_START = "wallpaper_new_on_start"
        const val KEY_WALL_START_TOPIC = "wallpaper_start_topic"
        const val KEY_WALL_TOPICS = "wallpaper_topics_cache"
        const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
        const val KEY_FOLLOW = "follow_along"
        const val KEY_AUTO_SCROLL = "auto_scroll_speed"
        const val KEY_REMIND = "reading_reminders"
        const val KEY_QUIET_FROM = "reminder_quiet_from"
        const val KEY_QUIET_UNTIL = "reminder_quiet_until"
        const val KEY_DARK_EVENING = "wallpaper_dark_evening"
        const val KEY_DARK_FROM = "wallpaper_dark_from"
        const val KEY_DARK_UNTIL = "wallpaper_dark_until"
        const val KEY_WALL_BIAS = "wallpaper_bias"
        const val KEY_BIBLE_AUDIO = "bible_audio_available"
        const val KEY_BIBLE_PREMIUM_NAME = "bible_premium_name"
        const val KEY_BIBLE_PREMIUM = "bible_premium_"
        const val KEY_BIBLE_ID = "bible_id"
        const val KEY_BIBLE_BOOK = "bible_book"
        const val KEY_BIBLE_CHAPTER = "bible_chapter"
    }
}
