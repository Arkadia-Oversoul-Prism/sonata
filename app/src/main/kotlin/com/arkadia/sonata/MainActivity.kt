package com.arkadia.sonata

import android.Manifest
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arkadia.sonata.databinding.ActivityMainBinding

/**
 * The whole application, on one screen.
 *
 * Text in, speech out. The screen is organised around the five questions a
 * listening user has — what am I reading, what is happening, how do I control it,
 * which voice is this, how do I keep listening while I do something else — rather
 * than around the app's internal structure. Voice, speed and the rest are one
 * quiet row or behind More; there is no second screen, because the previous split
 * asked the user to configure a backend URL before the neural voice would work,
 * and configuration the app can carry itself ([BackendConfig]) is not a setting.
 *
 * The mini-player is the in-app half of the floating player: once a read is under
 * way, playback controls stay reachable instead of scrolling out of view.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var speechService: SpeechService? = null
    private var bound = false

    /** Drives the progress read while a segmented read is playing. */
    private val ticker = Handler(Looper.getMainLooper())
    private var tickerRunning = false

    /** The passage currently being read, as dispatched. */
    private var readingPassage = ""

    private var lastCheckpointAt = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            speechService = (binder as SpeechService.LocalBinder).getService()
            bound = true
            speechService?.addClient(uiClient)
            render()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            speechService?.removeClient(uiClient)
            speechService = null
            bound = false
            render()
        }
    }

    /**
     * The screen's interest in playback lifecycle.
     *
     * A client object rather than the single callback slots the service used to
     * expose: the floating player is bound at the same time, and shared slots meant
     * whichever bound last took the events from the other.
     */
    private val uiClient = object : SpeechService.Client {
        override fun onStart() { render(); startTicker() }
        override fun onPause() {
            // The moment that matters: leaving the app. Recorded here so a return
            // carries on even if the process is killed while backgrounded.
            rememberProgress()
            render()
        }
        override fun onResume() { render(); startTicker() }
        override fun onComplete() { stopTicker(); render() }
        override fun onError(msg: String) { showNotice(msg); render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVoice()
        setupSpeed()
        setupPitch()
        setupActions()
        setupPassageField()
        refreshStorageLabel()
        requestNotificationPermissionIfNeeded()
        restoreLastPassage()
        consumeIncomingText(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingText(intent)
    }

    /**
     * Text handed over by another app — a PROCESS_TEXT selection or a plain share.
     * Both are accepted so the read starts from whichever route the user took.
     *
     * The read starts immediately. Selecting text and choosing Sonata is an explicit
     * request to hear it; making the user then tap Read would contradict the whole
     * point of the handoff. (A relaunch with the restored passage deliberately does
     * *not* auto-read: coming back to the app is not a request to listen.)
     */
    private fun consumeIncomingText(intent: Intent?) {
        val incoming = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?: return
        val text = incoming.toString().trim()
        if (text.isEmpty()) return

        binding.etText.setText(text)
        renderWordCount()
        read()
    }

    /**
     * Restore the passage the user was last reading.
     *
     * A read-aloud app is used in bursts — the user leaves to do something else and
     * comes back. Losing the text on every launch would make that unusable, so the
     * last passage is kept unless the user cleared it.
     */
    private fun restoreLastPassage() {
        if (binding.etText.text?.isNotBlank() == true) return
        val last = prefs.lastPassage
        if (last.isNotBlank()) binding.etText.setText(last)
        renderWordCount()
    }

    private fun rememberPassage() {
        prefs.lastPassage = binding.etText.text?.toString().orEmpty()
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun setupVoice() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            BackendConfig.VOICES.map { it.toString() },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVoice.adapter = adapter
        binding.spinnerVoice.setSelection(BackendConfig.indexOf(prefs.voice))
        binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                prefs.voice = BackendConfig.VOICES[pos].id
                renderVoiceBadge()
            }

            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        renderVoiceBadge()
    }

    /** The voice is worth naming once, quietly, so the user knows what they hear. */
    private fun renderVoiceBadge() {
        val voice = BackendConfig.VOICES[BackendConfig.indexOf(prefs.voice)]
        binding.tvVoiceBadge.text = voice.name
    }

    private fun setupSpeed() {
        binding.seekSpeed.max = SpeedSteps.SPEEDS.size - 1
        binding.seekSpeed.progress = SpeedSteps.indexOf(prefs.speed)
        renderSpeed(prefs.speed)
        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val s = SpeedSteps.SPEEDS[progress]
                prefs.speed = s
                renderSpeed(s)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // The chip steps the speed without opening More: the most common
        // adjustment, one tap away.
        binding.btnSpeed.setOnClickListener {
            val next = SpeedSteps.next(prefs.speed)
            prefs.speed = next
            binding.seekSpeed.progress = SpeedSteps.indexOf(next)
            renderSpeed(next)
        }
    }

    private fun renderSpeed(speed: Float) {
        binding.btnSpeed.text = "${speed}×"
        binding.tvSpeedValue.text = when {
            speed < 0.9f -> "${speed}× — slower than normal"
            speed > 1.6f -> "${speed}× — much faster"
            speed > 1.05f -> "${speed}× — faster than normal"
            else -> "${speed}× — normal pace"
        }
    }

    private fun setupPitch() {
        binding.seekPitch.max = 20
        binding.seekPitch.progress = ((prefs.pitch - 0.5f) * 20f / 1.5f).toInt().coerceIn(0, 20)
        renderPitch(prefs.pitch)
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = 0.5f + progress * 1.5f / 20f
                prefs.pitch = p
                renderPitch(p)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun renderPitch(pitch: Float) {
        binding.tvPitchValue.text = String.format("%.2f×", pitch)
    }

    private fun setupPassageField() {
        binding.etText.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    renderWordCount()
                }
            },
        )
        renderWordCount()
    }

    /**
     * A count the user can act on. Characters matter for the read; words and the
     * approximate listening time matter for deciding whether to start it.
     */
    private fun renderWordCount() {
        val text = binding.etText.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            binding.tvWordCount.text = ""
            binding.btnClear.visibility = View.GONE
            return
        }
        binding.btnClear.visibility = View.VISIBLE

        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val minutes = words / WORDS_PER_MINUTE
        val duration = when {
            minutes < 1 -> "under a minute"
            minutes == 1 -> "about a minute"
            else -> "about $minutes minutes"
        }
        binding.tvWordCount.text = "$words words · $duration"
    }

    private fun setupActions() {
        binding.btnPlay.setOnClickListener {
            if (speechService?.isPaused == true) speechService?.resume() else read()
        }
        binding.btnStop.setOnClickListener { stopRead() }
        binding.btnMore.setOnClickListener { toggleMore() }
        binding.miniStop.setOnClickListener { stopRead() }
        binding.miniPlayPause.setOnClickListener { togglePause() }
        binding.miniFloat.setOnClickListener { floatOverlay() }

        binding.btnResume.setOnClickListener { resumeRead() }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnClear.setOnClickListener {
            binding.etText.setText("")
            rememberPassage()
            clearNotice()
            render()
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            ""
        }
        if (text.isBlank()) {
            showNotice(getString(R.string.clipboard_empty))
            return
        }
        binding.etText.setText(text)
        binding.etText.setSelection(binding.etText.text?.length ?: 0)
        clearNotice()
        render()
    }

    private fun toggleMore() {
        val open = binding.panelMore.visibility == View.VISIBLE
        binding.panelMore.visibility = if (open) View.GONE else View.VISIBLE
        if (!open) refreshStorageLabel()
    }

    private fun togglePause() {
        val svc = speechService ?: return
        if (svc.isPaused) svc.resume() else svc.pause()
        render()
    }

    /**
     * Where a "carry on" would resume, or 0 when there is nothing to carry on from.
     *
     * Returns 0 unless the stored passage is the one on screen, so a stale offset
     * from a different passage can never be applied. Near the end of a passage there
     * is nothing worth resuming, so the affordance disappears rather than offering
     * to read the last few characters.
     */
    private fun resumableOffset(): Int {
        val current = binding.etText.text?.toString().orEmpty()
        if (current.isBlank()) return 0
        if (prefs.lastPassage != current) return 0
        val at = prefs.lastSpokenChars
        if (at <= 0) return 0
        return if (at >= current.length - MIN_RESUME_CHARS) 0 else at
    }

    /** Carry on from the stored position in this passage. */
    private fun resumeRead() {
        val at = resumableOffset()
        if (at <= 0) return
        readFrom(at)
    }

    private fun stopRead() {
        rememberProgress()
        speechService?.stop()
        stopTicker()
        render()
    }

    private fun read() {
        readFrom(fromChar = 0)
    }

    /**
     * Start a read, optionally from a character offset.
     *
     * [fromChar] is what the "carry on" affordance passes. The engine clamps it, so
     * a stale value cannot start a read past the end of the text.
     */
    private fun readFrom(fromChar: Int) {
        val text = binding.etText.text?.toString()
        // Dismiss the keyboard: the user has committed to listening, and a
        // keyboard covering half the screen is the wrong thing to leave behind.
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.etText.windowToken, 0)

        when (val validation = TextLimits.validate(text)) {
            is TextValidation.Empty -> showNotice(getString(R.string.empty_passage))
            is TextValidation.Truncated -> {
                showNotice("Reading the first $MAX_INPUT_CHARS characters")
                dispatch(validation.text, fromChar)
            }
            is TextValidation.Ready -> dispatch(validation.text, fromChar)
        }
    }

    private fun dispatch(text: String, fromChar: Int = 0) {
        clearNotice()
        rememberPassage()
        readingPassage = text
        // A fresh read of the same passage restarts from the top, so any stored
        // position is no longer where the user is.
        prefs.rememberProgress(text, fromChar)
        binding.tvState.text = getString(R.string.state_preparing)
        binding.tvSubstate.text = "Starting the voice…"
        startForegroundService(
            Intent(this, SpeechService::class.java).apply {
                action = SpeechService.ACTION_SPEAK
                putExtra(SpeechService.EXTRA_TEXT, text)
                putExtra(SpeechService.EXTRA_LABEL, "SONATA")
                putExtra(SpeechService.EXTRA_FROM_CHAR, fromChar)
            },
        )
        startTicker()
    }

    /**
     * Collapse to the floating player so the read continues over other apps.
     *
     * Requires the overlay permission; when it is absent the user is told where to
     * grant it and taken there, rather than the button silently doing nothing —
     * which is what made the founder report that the floating player "does not open
     * or float at all".
     */
    private fun floatOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            showNotice(getString(R.string.overlay_permission_prompt))
            Toast.makeText(
                this,
                "Allow 'Display over other apps' to use the floating player",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            return
        }
        val preview = binding.etText.text?.toString().orEmpty()
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            putExtra(FloatingOverlayService.EXTRA_LABEL, "SONATA")
            putExtra(FloatingOverlayService.EXTRA_PREVIEW, preview)
        }
        // startForegroundService, not startService: the overlay is a foreground
        // service now, and Android requires the start to say so.
        //
        // Wrapped because a throw here used to take the whole app down: Android
        // throws ForegroundServiceStartNotAllowedException when the app is not
        // allowed to start a foreground service, and crashing on the button the user
        // just pressed is the worst possible answer. Saying why is a usable one.
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w(TAG, "could not start the floating player: ${e.message}")
            showNotice(getString(R.string.overlay_failed))
            return
        }

        // Deliberately *not* moveTaskToBack. Sending the app away immediately meant
        // that if the overlay failed to attach the user was left staring at their
        // home screen with no idea why — the failure mode that produced the report
        // "the floating player does not open or float at all". Leaving Sonata in
        // front shows the player appearing over it, so the user can see that it
        // worked and then switch apps themselves.
        showNotice(getString(R.string.overlay_started))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, SpeechService::class.java), conn, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        // The overlay permission may have been granted while the app was away.
        if (overlayDeniedNoticeShown && Settings.canDrawOverlays(this)) {
            overlayDeniedNoticeShown = false
            showNotice("Floating player enabled — tap Float to use it")
        }
        render()
        if (speechService?.isPlaying == true || speechService?.isPaused == true) startTicker()
    }

    override fun onStop() {
        super.onStop()
        stopTicker()
        rememberPassage()
        if (bound) {
            speechService?.removeClient(uiClient)
            runCatching { unbindService(conn) }
            bound = false
        }
    }

    private var overlayDeniedNoticeShown = false

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) showNotice(
                "Notifications are off — playback controls will not appear in the shade",
            )
        }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * Poll the engine's real progress while a read is playing.
     *
     * The engine exposes characters spoken, not a percentage, because a segmented
     * read has no single audio file to ask for a duration. The ticker is what turns
     * that into a bar and a position, and it stops the moment nothing is playing.
     */
    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        ticker.post(object : Runnable {
            override fun run() {
                if (!tickerRunning) return
                render()
                if (speechService?.isPlaying == true || speechService?.isPaused == true) {
                    // Checkpoint the position at a human pace, not on every frame. A
                    // SharedPreferences write allocates and schedules a disk write,
                    // and 2.5 of those a second is pure waste — the position only
                    // needs to be durable when the user leaves or the process dies.
                    if (elapsedSinceCheckpoint() >= CHECKPOINT_MS) rememberProgress()
                    ticker.postDelayed(this, TICK_MS)
                } else {
                    tickerRunning = false
                }
            }
        })
    }

    /** Record where the read is, so a return to this passage can carry on. */
    private fun rememberProgress() {
        val svc = speechService ?: return
        // The passage actually dispatched, not the editable field: if the user edits
        // the text mid-read, the stored offset belongs to what was read, and the
        // "carry on" rule compares against the stored passage — so an edited field
        // correctly stops offering a resume rather than resuming into new text.
        prefs.rememberProgress(readingPassage, svc.spokenChars)
        lastCheckpointAt = SystemClock.elapsedRealtime()
    }

    private fun elapsedSinceCheckpoint(): Long =
        SystemClock.elapsedRealtime() - lastCheckpointAt

    private fun stopTicker() {
        tickerRunning = false
        ticker.removeCallbacksAndMessages(null)
    }

    private fun render() {
        val svc = speechService
        val playing = svc?.isPlaying == true

        val paused = svc?.isPaused == true
        val active = playing || paused

        // ── State line ────────────────────────────────────────────────────────
        binding.tvState.text = when {
            playing && svc?.isBufferingNext == true -> getString(R.string.state_buffering)
            playing -> getString(R.string.state_speaking)
            paused -> getString(R.string.state_paused)
            bound -> getString(R.string.state_ready)
            else -> getString(R.string.state_starting)
        }

        // ── Substate: what is happening, specifically ─────────────────────────
        binding.tvSubstate.text = when {
            playing && svc?.isBufferingNext == true ->
                "Fetching the next part of the passage…"
            playing -> readProgressLabel(svc)
            paused -> "Tap play to carry on from here"
            bound -> getString(R.string.substate_ready)
            else -> "Connecting to the player…"
        }

        // ── Progress ──────────────────────────────────────────────────────────
        val total = svc?.totalChars ?: 0
        val spoken = svc?.spokenChars ?: 0
        val showProgress = active && total > 0
        binding.progressRead.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            binding.progressRead.progress = ((spoken.toFloat() / total) * PROGRESS_MAX).toInt()
                .coerceIn(0, PROGRESS_MAX)
        }

        // ── Carry on ──────────────────────────────────────────────────────────
        // Only offered when a stored position belongs to *this* text and there is
        // more of it left. A button that cannot do anything must not be shown, and
        // the offset must never be applied to different text.
        val resumeAt = resumableOffset()
        binding.btnResume.visibility = if (resumeAt > 0) View.VISIBLE else View.GONE
        if (resumeAt > 0) {
            binding.btnResume.text = getString(
                R.string.action_carry_on_from,
                percent(resumeAt, binding.etText.text?.length ?: 0),
            )
        }

        // ── Primary control ───────────────────────────────────────────────────
        binding.btnPlay.text = when {
            paused -> getString(R.string.action_resume)
            playing -> getString(R.string.action_read_again)
            else -> getString(R.string.action_read)
        }
        binding.btnStop.isEnabled = active

        // ── Mini-player ───────────────────────────────────────────────────────
        binding.miniPlayer.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            binding.miniPlayPause.setIconResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            )
            binding.miniTitle.text = readProgressLabel(svc)
            binding.miniProgress.progress = if (total > 0) {
                ((spoken.toFloat() / total) * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
            } else {
                0
            }
            reserveSpaceForMiniPlayer()
        } else {
            clearSpaceForMiniPlayer()
        }

        // ── Backend label, when More is open ──────────────────────────────────
        binding.tvBackend.text = if (prefs.preferEdgeTts) {
            "Voice: Arkadia neural (${prefs.backendUrl})"
        } else {
            "Voice: this device's speech engine"
        }
    }

    /** A concrete position, in the terms the user is reading in. */
    private fun readProgressLabel(svc: SpeechService?): String {
        val total = svc?.totalChars ?: 0
        val spoken = svc?.spokenChars ?: 0
        val segment = svc?.segmentIndex ?: 0
        val segments = svc?.segmentCount ?: 0
        return when {
            total <= 0 -> getString(R.string.state_speaking)
            segments > 1 -> "Part $segment of $segments · ${percent(spoken, total)}%"
            else -> "${percent(spoken, total)}% of the passage"
        }
    }

    /**
     * Keep the bottom of the passage clear of the mini-player.
     *
     * The mini-player floats over the scroll view, so without this it covers the
     * hint text and the tail of the passage — the part the user is most likely to
     * be reading when they started the read. The padding is measured from the
     * mini-player's own height, so it stays correct at any font scale.
     */
    private fun reserveSpaceForMiniPlayer() {
        binding.miniPlayer.post {
            val content = binding.scrollContent
            val needed = binding.miniPlayer.height +
                resources.getDimensionPixelSize(R.dimen.space_md) * 2 +
                resources.getDimensionPixelSize(R.dimen.space_xl)
            if (content.paddingBottom != needed) {
                content.setPadding(
                    content.paddingLeft, content.paddingTop,
                    content.paddingRight, needed,
                )
            }
        }
    }

    /** Put the bottom padding back when the mini-player goes away, so a finished
     *  read does not leave a large blank gap under the passage. */
    private fun clearSpaceForMiniPlayer() {
        val content = binding.scrollContent
        val base = resources.getDimensionPixelSize(R.dimen.space_xl)
        if (content.paddingBottom != base) {
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, base)
        }
    }

    private fun percent(spoken: Int, total: Int): Int =
        if (total <= 0) 0 else ((spoken.toFloat() / total) * 100).toInt().coerceIn(0, 100)

    private fun showNotice(msg: String) {
        binding.tvNotice.visibility = View.VISIBLE
        binding.tvNotice.text = msg
        if (msg.contains("Display over other apps", ignoreCase = true)) {
            overlayDeniedNoticeShown = true
        }
    }

    private fun clearNotice() {
        binding.tvNotice.visibility = View.GONE
        binding.tvNotice.text = ""
    }

    private fun refreshStorageLabel() {
        val bytes = cacheDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("sonata_") }
            .sumOf { it.length() }
        binding.tvCache.text = if (bytes == 0L) {
            "No audio stored"
        } else {
            "Clear ${bytes / 1024} KB of stored audio"
        }
        binding.tvCache.setOnClickListener {
            cacheDir.walkTopDown().filter { it.isFile && it.name.startsWith("sonata_") }
                .forEach { it.delete() }
            refreshStorageLabel()
        }
    }

    private companion object {
        const val TAG = "SonataMain"

        /** Used only for the "about N minutes" estimate on the count line. */
        const val WORDS_PER_MINUTE = 150

        /**
         * Do not offer to resume within this many characters of the end: there is
         * nothing left worth carrying on to.
         */
        const val MIN_RESUME_CHARS = 40

        const val PROGRESS_MAX = 1000
        const val TICK_MS = 400L

        /**
         * How often the reading position is written down during playback. Coarse on
         * purpose: the value is only needed when the user leaves.
         */
        const val CHECKPOINT_MS = 5_000L
    }
}
