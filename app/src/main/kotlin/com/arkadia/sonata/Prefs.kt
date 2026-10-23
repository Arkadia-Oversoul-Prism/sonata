package com.arkadia.sonata

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin SharedPreferences wrapper.
 * Keys are typed; all reads have safe defaults.
 *
 * Implements [TtsSettings] so the engine reads settings through a narrow,
 * testable interface rather than depending on `SharedPreferences`.
 */
class Prefs(context: Context) : TtsSettings {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("sonata_prefs", Context.MODE_PRIVATE)

    override var voice: String
        get() = sp.getString(KEY_VOICE, "aria") ?: "aria"
        set(v) = sp.edit().putString(KEY_VOICE, v).apply()

    /** 0.5 – 2.0 */
    override var speed: Float
        get() = sp.getFloat(KEY_SPEED, 1.0f)
        set(v) = sp.edit().putFloat(KEY_SPEED, v).apply()

    /** 0.5 – 2.0  (Android TTS pitch; ignored for Edge TTS) */
    override var pitch: Float
        get() = sp.getFloat(KEY_PITCH, 1.0f)
        set(v) = sp.edit().putFloat(KEY_PITCH, v).apply()

    /** Automatically start reading when text received via PROCESS_TEXT */
    var autoRead: Boolean
        get() = sp.getBoolean(KEY_AUTO_READ, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_READ, v).apply()

    var darkMode: Boolean
        get() = sp.getBoolean(KEY_DARK_MODE, true)
        set(v) = sp.edit().putBoolean(KEY_DARK_MODE, v).apply()

    /**
     * Base URL of the Arkadia voice gateway. Defaults to [BackendConfig.DEFAULT_URL]
     * so the neural voice works on install — the user is not asked for a URL. An
     * empty value forces the offline device voice, which is the deliberate way to
     * opt out.
     */
    var edgeTtsUrl: String
        get() = sp.getString(KEY_EDGE_TTS_URL, BackendConfig.DEFAULT_URL) ?: BackendConfig.DEFAULT_URL
        set(v) = sp.edit().putString(KEY_EDGE_TTS_URL, v).apply()

    /** Prefer Edge TTS (online) when URL is configured */
    var preferEdgeTts: Boolean
        get() = sp.getBoolean(KEY_PREFER_EDGE, true)
        set(v) = sp.edit().putBoolean(KEY_PREFER_EDGE, v).apply()

    /**
     * The passage the user was last reading.
     *
     * Kept because a read-aloud app is used in bursts: the user leaves to do
     * something else and comes back. Starting from an empty field every time would
     * make that unusable, so the text survives unless the user clears it.
     */
    var lastPassage: String
        get() = sp.getString(KEY_LAST_PASSAGE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_PASSAGE, v).apply()

    /**
     * How far into [lastPassage] the read had got.
     *
     * Stored so returning to a long passage offers to carry on from where the user
     * stopped rather than from the top — the single most annoying thing about
     * leaving a read-aloud app and coming back.
     *
     * Reset to 0 whenever the passage changes, so a stale offset can never be
     * applied to different text. The engine also clamps, but the stored value is
     * meaningless across texts and should not survive one.
     */
    var lastSpokenChars: Int
        get() = sp.getInt(KEY_LAST_SPOKEN, 0)
        set(v) = sp.edit().putInt(KEY_LAST_SPOKEN, v).apply()

    /** Record a passage and where the read is, atomically with respect to readers. */
    fun rememberProgress(passage: String, spokenChars: Int) {
        sp.edit()
            .putString(KEY_LAST_PASSAGE, passage)
            .putInt(KEY_LAST_SPOKEN, spokenChars.coerceAtLeast(0))
            .apply()
    }

    // ── TtsSettings ────────────────────────────────────────────────────────
    // The engine reads through these narrow names; the stored keys above keep
    // their original names so existing installs keep their preferences.

    override val backendUrl: String get() = edgeTtsUrl

    override val preferOnline: Boolean get() = preferEdgeTts

    companion object {
        private const val KEY_LAST_SPOKEN = "last_spoken_chars"
        private const val KEY_VOICE         = "voice"
        private const val KEY_SPEED         = "speed"
        private const val KEY_PITCH         = "pitch"
        private const val KEY_AUTO_READ     = "auto_read"
        private const val KEY_DARK_MODE     = "dark_mode"
        private const val KEY_EDGE_TTS_URL  = "edge_tts_url"
        private const val KEY_PREFER_EDGE   = "prefer_edge_tts"
        private const val KEY_LAST_PASSAGE  = "last_passage"
    }
}
