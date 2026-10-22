package com.arkadia.sonata

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arkadia.sonata.databinding.ActivityPlayerBinding

/**
 * The visible face of a PROCESS_TEXT read.
 *
 * Before this existed, selecting "Sonata" in the text-selection menu started a
 * foreground service and finished immediately: the user saw nothing at all, had no
 * way to tell whether synthesis had started, and no way to stop it. The activity is
 * the feedback the workflow was missing.
 *
 * It binds to the same [SpeechService] instance the service path uses, so the state
 * shown here is the engine's actual state rather than a local guess, and the
 * controls drive the same engine the notification does.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
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
            speechService?.onError    = { msg -> renderError(msg) }
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
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvPreview.text = intent.getStringExtra(EXTRA_PREVIEW).orEmpty()
        binding.tvHint.text = "Read aloud by Sonata. Controls also appear in your notifications."

        binding.btnPlayPause.setOnClickListener {
            val svc = speechService ?: return@setOnClickListener
            if (svc.isPaused) svc.resume() else svc.pause()
        }
        binding.btnStop.setOnClickListener {
            speechService?.stop()
            finish()
        }
        binding.btnSpeed.setOnClickListener { cycleSpeed() }

        requestNotificationPermissionIfNeeded()
    }

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

    /**
     * The notification carries the pause/resume/stop controls. On API 33+ it is
     * invisible without this permission, so a PROCESS_TEXT read that only ever ran
     * from the selection menu would be uncontrollable once this activity closed.
     * Asking here — rather than only in [MainActivity] — is what makes the
     * selection-menu workflow controllable, because a user may never open the app.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notifications are off — playback controls will not appear",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    private fun cycleSpeed() {
        val next = SpeedSteps.next(prefs.speed)
        prefs.speed = next
        binding.btnSpeed.text = "$next×"
        // Apply to the in-flight utterance where the engine supports it; otherwise
        // it takes effect on the next read, which the hint states.
        speechService?.applySpeed(next)
        binding.tvHint.text =
            "Speed set to $next×. Applies from the next read."
    }

    private fun render() {
        val svc = speechService
        val playing = svc?.isPlaying == true
        val paused = svc?.isPaused == true

        binding.btnPlayPause.text = if (paused) "Resume" else "Pause"
        binding.btnPlayPause.isEnabled = playing || paused
        binding.btnStop.isEnabled = playing || paused
        binding.btnSpeed.text = "${prefs.speed}×"

        val status = when {
            playing -> "Speaking…"
            paused -> "Paused"
            bound -> "Ready"
            else -> "Starting…"
        }
        binding.tvStatus.text = status
        binding.tvEngine.text = when (svc?.engineInUse) {
            TtsEngineKind.ONLINE -> "Neural voice"
            TtsEngineKind.OFFLINE ->
                if (svc?.fellBackOffline == true) "Device voice — backend unavailable"
                else "Device voice"
            null -> if (bound) "Idle" else ""
        }
    }

    private fun renderError(msg: String) {
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.text = msg
        binding.tvStatus.text = "Could not read"
    }

    companion object {
        /** Text shown while the read runs; not the full payload. */
        const val EXTRA_PREVIEW = "preview"
    }
}
