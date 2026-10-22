package com.arkadia.sonata

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
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
 * Text in, speech out. Voice and speed are one quiet row; everything optional is
 * behind [binding.panelMore] rather than a second activity, because the previous
 * split between a main screen and a settings screen asked the user to configure a
 * backend URL before the neural voice would work — configuration the app can carry
 * itself ([BackendConfig]).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var speechService: SpeechService? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            speechService = (binder as SpeechService.LocalBinder).getService()
            bound = true
            speechService?.onStart    = { render() }
            speechService?.onPause    = { render() }
            speechService?.onResume   = { render() }
            speechService?.onComplete = { render() }
            speechService?.onError    = { msg -> showError(msg) }
            render()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            speechService = null
            bound = false
            render()
        }
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
        refreshCacheLabel()
        requestNotificationPermissionIfNeeded()
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
     */
    private fun consumeIncomingText(intent: Intent?) {
        val incoming = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?: return
        val text = incoming.toString().trim()
        if (text.isEmpty()) return

        binding.etText.setText(text)
        if (prefs.autoRead) read()
    }

    // ── Controls ──────────────────────────────────────────────────────────

    private fun setupVoice() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            BackendConfig.VOICES.map { it.toString() },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVoice.adapter = adapter
        binding.spinnerVoice.setSelection(BackendConfig.indexOf(prefs.voice))
        binding.spinnerVoice.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                    prefs.voice = BackendConfig.VOICES[pos].id
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
            }
    }

    private fun setupSpeed() {
        binding.seekSpeed.max = SpeedSteps.SPEEDS.size - 1
        binding.seekSpeed.progress = SpeedSteps.indexOf(prefs.speed)
        showSpeed(prefs.speed)
        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val s = SpeedSteps.SPEEDS[progress]
                prefs.speed = s
                showSpeed(s)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // The compact label on the main screen steps the speed without opening More.
        binding.tvSpeed.setOnClickListener {
            val next = SpeedSteps.next(prefs.speed)
            prefs.speed = next
            binding.seekSpeed.progress = SpeedSteps.indexOf(next)
            showSpeed(next)
        }
    }

    private fun showSpeed(speed: Float) {
        binding.tvSpeed.text = "$speed×"
        binding.tvSpeedValue.text = "$speed× — tap the label on the main screen to step"
    }

    private fun setupPitch() {
        binding.seekPitch.max = 20
        binding.seekPitch.progress = ((prefs.pitch - 0.5f) * 20f / 1.5f).toInt().coerceIn(0, 20)
        showPitch(prefs.pitch)
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val p = 0.5f + progress * 1.5f / 20f
                prefs.pitch = p
                showPitch(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun showPitch(pitch: Float) {
        binding.tvPitchValue.text = String.format("%.2f×", pitch)
    }

    private fun setupActions() {
        binding.btnPlay.setOnClickListener {
            if (speechService?.isPaused == true) speechService?.resume() else read()
        }
        binding.btnStop.setOnClickListener {
            speechService?.stop()
            render()
        }
        binding.btnMore.setOnClickListener {
            val open = binding.panelMore.visibility == View.VISIBLE
            binding.panelMore.visibility = if (open) View.GONE else View.VISIBLE
            if (!open) refreshCacheLabel()
        }
        binding.btnFloat.setOnClickListener { floatOverlay() }
    }

    private fun read() {
        val text = binding.etText.text?.toString()
        when (val validation = TextLimits.validate(text)) {
            is TextValidation.Empty -> showError("Nothing to read — paste or type some text")
            is TextValidation.Truncated -> {
                showError("Reading the first $MAX_TTS_CHARS characters")
                dispatch(validation.text)
            }
            is TextValidation.Ready -> dispatch(validation.text)
        }
    }

    private fun dispatch(text: String) {
        clearError()
        binding.tvState.text = "Preparing…"
        val intent = Intent(this, SpeechService::class.java).apply {
            action = SpeechService.ACTION_SPEAK
            putExtra(SpeechService.EXTRA_TEXT, text)
            putExtra(SpeechService.EXTRA_LABEL, "SONATA")
        }
        startForegroundService(intent)
    }

    /**
     * Collapse to the floating player so the read continues over other apps.
     *
     * Requires the overlay permission; when it is absent the user is told where to
     * grant it rather than the button silently doing nothing.
     */
    private fun floatOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow 'Display over other apps' to use the floating player",
                Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startService(
            Intent(this, FloatingOverlayService::class.java).apply {
                putExtra(FloatingOverlayService.EXTRA_LABEL, "SONATA")
                putExtra(FloatingOverlayService.EXTRA_PREVIEW, binding.etText.text?.toString().orEmpty())
            }
        )
        moveTaskToBack(true)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, SpeechService::class.java), conn, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            runCatching { unbindService(conn) }
            bound = false
        }
    }

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
            if (!granted) Toast.makeText(
                this, "Notifications are off — playback controls will not appear", Toast.LENGTH_LONG
            ).show()
        }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun render() {
        val svc = speechService
        val playing = svc?.isPlaying == true
        val paused = svc?.isPaused == true

        binding.tvState.text = when {
            playing -> "Speaking…"
            paused -> "Paused"
            bound -> "Ready"
            else -> "Starting…"
        }
        binding.btnPlay.text = if (paused) "▶  Resume" else "▶  Read"
        binding.btnStop.isEnabled = playing || paused
    }

    private fun showError(msg: String) {
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.text = msg
        binding.tvState.text = "Could not read"
    }

    private fun clearError() {
        binding.tvError.visibility = View.GONE
        binding.tvError.text = ""
    }

    private fun refreshCacheLabel() {
        val bytes = cacheDir.walkTopDown().filter { it.isFile && it.name.startsWith("sonata_") }
            .sumOf { it.length() }
        binding.tvCache.text = "Cached audio: ${bytes / 1024} KB"
        binding.tvCache.setOnClickListener {
            cacheDir.walkTopDown().filter { it.isFile && it.name.startsWith("sonata_") }
                .forEach { it.delete() }
            refreshCacheLabel()
        }
    }
}
