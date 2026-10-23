package com.arkadia.sonata

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
// MediaSessionCompat / PlaybackStateCompat live in android.support.v4.media.session
// inside androidx.media:media:1.7.0 — there is no androidx.media.session package.
import android.support.v4.media.session.MediaSessionCompat
import java.util.concurrent.CopyOnWriteArrayList
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Foreground service that owns a [TtsEngine] instance.
 * Activities and the overlay bind to it via [LocalBinder].
 *
 * Commands (via startForegroundService + intent action):
 *   ACTION_SPEAK  — extras: EXTRA_TEXT, EXTRA_LABEL, EXTRA_FROM_CHAR
 *   ACTION_PAUSE
 *   ACTION_RESUME
 *   ACTION_STOP
 *
 * A SPEAK carrying no speakable text is refused (see [SpeechCommand.from]) rather
 * than promoting a foreground notification for work that will not happen.
 */
class SpeechService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): SpeechService = this@SpeechService
    }

    private val binder = LocalBinder()
    private lateinit var tts: TtsEngine
    private lateinit var prefs: Prefs
    private lateinit var mediaSession: MediaSessionCompat

    private var currentText  = ""
    private var currentLabel = "SONATA"

    /** The engine that produced the current utterance, for the notification. */
    private var currentEngine: TtsEngineKind? = null

    // ── Lifecycle callbacks exposed to bound clients ────────────────────────
    //
    // A list, not single slots. The main screen and the floating player are both
    // bound at once, and with one mutable slot per callback whichever client bound
    // last silently took the callback away from the other. The visible symptom was
    // a floating player that never dismissed itself when the read finished, and a
    // main screen whose state went stale while the overlay was up.
    private val clients = CopyOnWriteArrayList<Client>()

    /** A bound client's interest in playback lifecycle events. */
    interface Client {
        fun onStart() {}
        fun onPause() {}
        fun onResume() {}
        fun onComplete() {}
        fun onError(msg: String) {}
    }

    fun addClient(client: Client) {
        if (!clients.contains(client)) clients += client
    }

    fun removeClient(client: Client) {
        clients -= client
    }

    /** User-presentable message for the most recent failure, if any. */
    var lastError: String? = null
        private set

    /** Set when the current utterance is offline speech after an online failure. */
    var fellBackOffline: Boolean = false
        private set

    // ── Service lifecycle ──────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tts = TtsEngine(
            settings  = prefs,
            cacheDir  = cacheDir,
            transport = OkHttpTtsTransport(),
            player    = AndroidAudioPlayer(),
            offline   = AndroidOfflineVoice(this),
        )
        setupMediaSession()
        tts.listener = ttsListener
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val command = SpeechCommand.from(intent)) {
            is SpeechCommand.Speak -> {
                currentText   = command.text
                currentLabel  = command.label
                lastError     = null
                fellBackOffline = false
                promoteToForeground(playing = true)
                tts.speakFrom(command.text, command.fromChar)
            }

            SpeechCommand.Pause  -> { tts.pause();  updateNotification(playing = false) }
            SpeechCommand.Resume -> { tts.resume(); updateNotification(playing = true)  }
            SpeechCommand.Stop   -> stopSelf()

            // No action, or a SPEAK with no speakable text: do not claim
            // foreground for work that will not happen.
            SpeechCommand.None -> {
                if (intent?.action == ACTION_SPEAK) {
                    lastError = "Nothing to read"
                    clients.forEach { it.onError(lastError!!) }
                }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        tts.shutdown()
        mediaSession.release()
        super.onDestroy()
    }

    // ── Public control surface (used when bound) ───────────────────────────

    /**
     * Persist a new speed. The engine reads speed per request, so this applies
     * from the next read rather than mid-utterance — the UI says so rather than
     * implying an immediate change.
     */
    fun applySpeed(speed: Float) {
        prefs.speed = speed
    }

    fun pause()  { tts.pause();  updateNotification(playing = false) }
    fun resume() { tts.resume(); updateNotification(playing = true)  }
    fun stop()   { tts.stop();   stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    val isPlaying get() = tts.isPlaying
    val isPaused  get() = tts.isPaused

    /** True while the read has run past a piece and is fetching the next one. */
    val isBufferingNext: Boolean get() = tts.isBufferingNext

    /** Characters of the passage spoken so far, for a real progress read. */
    val spokenChars: Int get() = tts.spokenChars

    /** Characters in the passage being read. */
    val totalChars: Int get() = tts.totalChars

    /** How many pieces the current read is split into; 0 when offline or idle. */
    val segmentCount: Int get() = tts.segmentCount

    /** 1-based index of the piece playing; 0 when nothing is segmented. */
    val segmentIndex: Int get() = tts.segmentIndex

    /** Which tier is speaking; null when idle. */
    val engineInUse: TtsEngineKind? get() = tts.currentEngine

    // ── TTS listener ───────────────────────────────────────────────────────

    private val ttsListener = object : TtsEngine.Listener {
        override fun onStart(engine: TtsEngineKind) {
            currentEngine = engine
            fellBackOffline = engine == TtsEngineKind.OFFLINE
            updateNotification(playing = true)
            clients.forEach { it.onStart() }
        }

        override fun onPaused(engine: TtsEngineKind) {
            currentEngine = engine
            updateNotification(playing = false)
            clients.forEach { it.onPause() }
        }

        override fun onResumed(engine: TtsEngineKind) {
            currentEngine = engine
            updateNotification(playing = true)
            clients.forEach { it.onResume() }
        }

        override fun onComplete() {
            currentEngine = null
            lastError = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Notified before stopSelf(): the service is gone by the time the last
            // client runs, and a client that only learns of completion from a
            // destroyed service can never dismiss itself.
            clients.forEach { it.onComplete() }
            stopSelf()
        }

        override fun onError(msg: String, fellBackTo: TtsEngineKind?) {
            // Surface the failure instead of swallowing it and dying silently.
            lastError = msg
            fellBackTo?.let {
                currentEngine = it
                fellBackOffline = true
            }
            clients.forEach { it.onError(msg) }
            if (fellBackTo == null) {
                currentEngine = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun promoteToForeground(playing: Boolean) {
        val notification = buildNotification(playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SonataApp.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(SonataApp.NOTIF_ID, notification)
        }
    }

    private fun buildNotification(playing: Boolean): Notification {
        val pauseResumeAction = if (playing) ACTION_PAUSE else ACTION_RESUME
        val pauseResumeLabel  = if (playing) "Pause"     else "Resume"
        val pauseResumeIcon   = if (playing)
            android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val pauseResumeIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SpeechService::class.java).apply { action = pauseResumeAction },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SpeechService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING
                    else         PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )

        val preview = currentText.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 72) it else it.take(72) + "…"
        }
        val engineNote = when (currentEngine) {
            TtsEngineKind.ONLINE  -> "Neural voice"
            TtsEngineKind.OFFLINE -> if (fellBackOffline) "Offline voice (backend unavailable)"
                                     else "Offline voice"
            null                  -> null
        }
        // The position is worth showing: a long read is the normal case now, and
        // "how far in am I?" is the question the shade is opened to answer.
        val positionNote = if (tts.isBufferingNext) {
            "Buffering…"
        } else {
            val total = tts.totalChars
            if (total > 0 && tts.isPlaying) {
                val percent = ((tts.spokenChars.toFloat() / total) * 100).toInt().coerceIn(0, 100)
                if (tts.segmentCount > 1) "Part ${tts.segmentIndex} of ${tts.segmentCount} · $percent%"
                else "$percent%"
            } else {
                null
            }
        }
        val body = listOfNotNull(preview.ifBlank { null }, positionNote, engineNote)
            .joinToString(" · ")

        return NotificationCompat.Builder(this, SonataApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sonata)
            .setContentTitle(currentLabel)
            .setContentText(body)
            .setContentIntent(openIntent)
            .addAction(pauseResumeIcon, pauseResumeLabel, pauseResumeIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(playing: Boolean) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(SonataApp.NOTIF_ID, buildNotification(playing))
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "SonataSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()  { resume() }
                override fun onPause() { pause()  }
                override fun onStop()  { stop()   }
            })
            isActive = true
        }
    }

    companion object {
        const val ACTION_SPEAK  = "com.arkadia.sonata.SPEAK"
        const val ACTION_PAUSE  = "com.arkadia.sonata.PAUSE"
        const val ACTION_RESUME = "com.arkadia.sonata.RESUME"
        const val ACTION_STOP   = "com.arkadia.sonata.STOP"
        const val EXTRA_TEXT    = "text"
        const val EXTRA_LABEL   = "label"
        /** Character offset to begin reading from; 0 (the default) reads it all. */
        const val EXTRA_FROM_CHAR = "fromChar"
    }
}
