package com.arkadia.sonata

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.arkadia.sonata.databinding.OverlayBubbleBinding
import com.arkadia.sonata.databinding.OverlayPlayerBinding

private const val TAG = "SonataOverlay"

/**
 * The floating player: a draggable glass panel that collapses to a small bubble so
 * a read continues while the user works in other apps.
 *
 * Two views share one window slot. Collapsing swaps the panel for a bubble and keeps
 * the position, so the player stays where the user put it; expanding restores the
 * panel. Both states are draggable, and both drive the same bound [SpeechService] as
 * the main screen and the notification.
 *
 * Three defects made this service invisible to the user — it "did not open or
 * float" — and each is guarded here:
 *
 *  1. Without the `SYSTEM_ALERT_WINDOW` grant, `addView` throws `BadTokenException`
 *     out of `onCreate` and the service crashes. A crash looks exactly like a button
 *     that does nothing, so the grant is checked and the service exits cleanly with
 *     a log instead.
 *  2. The drag listener consumed `ACTION_DOWN`, which suppresses the framework's
 *     click detection, so the bubble's `OnClickListener` never fired and a collapsed
 *     player could not be reopened. The tap is now recognised inside the touch
 *     handler itself.
 *  3. `onDestroy` unbound unconditionally and removed the view last. If the service
 *     was stopped before the bind completed, the unbind threw and the view was never
 *     removed — a leaked, unresponsive window. Unbinding is now conditional and the
 *     view is removed first.
 */
class FloatingOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var binding: OverlayPlayerBinding
    private lateinit var bubble: OverlayBubbleBinding
    private var overlayView: View? = null

    /** Which view is currently attached to the window. */
    private var collapsed = false

    /** Whether [bindService] completed; [unbindService] must not be called if not. */
    private var boundToSpeech = false

    /** True once the window was successfully added, so teardown only removes a view. */
    private var attached = false

    private val ticker = Handler(Looper.getMainLooper())

    private var speechService: SpeechService? = null

    /** The passage preview last set from the launching intent. */
    private var lastPreview: String = ""

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as SpeechService.LocalBinder).getService()
            speechService = svc
            boundToSpeech = true
            svc.addClient(client)
            updatePlayPause()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            speechService?.removeClient(client)
            speechService = null
            boundToSpeech = false
        }
    }

    /**
     * The player's interest in playback lifecycle.
     *
     * A client object rather than the service's old single callback slots: the main
     * screen is bound at the same time, and with shared slots the overlay stopped
     * receiving `onComplete` as soon as the user returned to the app — so it stayed
     * on screen over a finished read.
     */
    private val client = object : SpeechService.Client {
        override fun onStart() { updatePlayPause() }
        override fun onPause() { updatePlayPause() }
        override fun onResume() { updatePlayPause() }
        override fun onComplete() { stopSelf() }
        override fun onError(msg: String) {
            binding.tvPreview.text = msg
        }
    }

    // ── Window params ──────────────────────────────────────────────────────

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 60
        y = 220
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // Guard before touching the window: an ungranted overlay permission makes
        // addView throw, and a crashing service is indistinguishable from a dead
        // button. Exiting cleanly leaves the read playing in the notification, which
        // is a usable fallback rather than a failure.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission not granted; floating player will not start")
            stopSelf()
            return
        }

        wm = getSystemService(WindowManager::class.java)

        val inflater = LayoutInflater.from(this)
        binding = OverlayPlayerBinding.inflate(inflater)
        bubble = OverlayBubbleBinding.inflate(inflater)

        setupDrag(binding.root)
        setupDrag(bubble.root)
        setupButtons()
        setupBubble()

        overlayView = binding.root
        try {
            wm.addView(overlayView, params)
            attached = true
        } catch (e: Exception) {
            // Permission revoked between the check and here, or an OEM window policy.
            // Report and exit rather than leaving a half-created service.
            Log.w(TAG, "could not attach the floating player: ${e.message}")
            overlayView = null
            stopSelf()
            return
        }

        bindService(Intent(this, SpeechService::class.java), conn, Context.BIND_AUTO_CREATE)

        // Poll while visible so the bubble's opacity and the play/pause glyph track
        // the engine's real state — including changes made from the notification or
        // the main screen, which do not pass through this service.
        startTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!attached) return START_NOT_STICKY
        intent?.getStringExtra(EXTRA_LABEL)?.let { binding.tvLabel.text = it }
        intent?.getStringExtra(EXTRA_PREVIEW)?.let {
            lastPreview = preview(it)
            binding.tvPreview.text = lastPreview
        }
        updatePlayPause()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTicker()
        // Remove the window first: if anything below throws, a leaked, unresponsive
        // overlay is the worst outcome — worse than a leaked service.
        if (attached) {
            overlayView?.let { runCatching { wm.removeView(it) } }
            attached = false
        }
        overlayView = null
        speechService?.removeClient(client)
        // unbindService throws IllegalArgumentException when the bind never completed,
        // which happens whenever the service is stopped quickly.
        if (boundToSpeech) {
            runCatching { unbindService(conn) }
            boundToSpeech = false
        }
        speechService = null
        super.onDestroy()
    }

    /** Truncated preview text, with the ellipsis only when something was cut. */
    private fun preview(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS) + "…"
    }

    // ── Collapse / expand ─────────────────────────────────────────────────

    /**
     * Swap the attached view, carrying the position across so the player does not
     * jump when it is collapsed or reopened.
     */
    private fun setCollapsed(collapse: Boolean) {
        if (!attached || collapsed == collapse) return
        val current = overlayView ?: return
        val next: View = if (collapse) bubble.root else binding.root

        wm.removeView(current)
        overlayView = next
        collapsed = collapse
        if (collapse) updateBubble() else updatePlayPause()
        wm.addView(next, params)
    }

    private fun setupBubble() {
        // The tap is recognised in setupDrag's ACTION_UP, not through an
        // OnClickListener: the drag listener consumes ACTION_DOWN, which suppresses
        // the framework's own click detection, so a listener here would never fire.
        bubble.root.contentDescription = "Sonata player — tap to expand"
    }

    /** The bubble reflects playing state so a glance is enough. */
    private fun updateBubble() {
        val playing = speechService?.isPlaying == true
        bubble.ivBubble.alpha = if (playing) 1.0f else 0.55f
    }

    // ── Drag and tap ──────────────────────────────────────────────────────

    private var initX = 0
    private var initY = 0
    private var initTouchX = 0f
    private var initTouchY = 0f

    /** Set when the pointer travelled far enough that this was a drag, not a tap. */
    private var dragged = false

    private fun setupDrag(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    initTouchX = event.rawX
                    initTouchY = event.rawY
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY
                    if (kotlin.math.abs(dx) > TAP_SLOP || kotlin.math.abs(dy) > TAP_SLOP) {
                        dragged = true
                    }
                    params.x = initX + dx.toInt()
                    params.y = initY + dy.toInt()
                    if (attached) wm.updateViewLayout(overlayView, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // A tap, not a drag: the collapsed bubble expands. Handled here
                    // because ACTION_DOWN was consumed above, so an OnClickListener
                    // on the same view would never be invoked.
                    if (!dragged && collapsed) setCollapsed(false)
                    v.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragged = false
                    true
                }

                else -> false
            }
        }
    }

    // ── Buttons ──────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnPlayPause.setOnClickListener {
            val svc = speechService ?: return@setOnClickListener
            // Resume only when there is something to resume; otherwise the tap
            // would claim a resume that cannot happen.
            if (svc.isPlaying) svc.pause() else if (svc.isPaused) svc.resume()
            updatePlayPause()
        }
        binding.btnStop.setOnClickListener {
            speechService?.stop()
            stopSelf()
        }
        binding.btnClose.setOnClickListener { stopSelf() }
        binding.btnCollapse.setOnClickListener { setCollapsed(true) }

        binding.btnSpeed.setOnClickListener {
            val prefs = Prefs(this)
            val next = SpeedSteps.next(prefs.speed)
            prefs.speed = next
            binding.btnSpeed.text = "${next}×"
        }
    }

    private fun updatePlayPause() {
        if (!attached) return
        val playing = speechService?.isPlaying == true
        val paused = speechService?.isPaused == true

        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
        )
        // A control that cannot do anything must not look like one that can.
        binding.btnPlayPause.isEnabled = playing || paused
        binding.btnStop.isEnabled = playing || paused

        // While the next piece is being fetched there is genuinely nothing to hear,
        // so say so rather than showing a play button over silence.
        if (speechService?.isBufferingNext == true) {
            binding.tvPreview.text = "Buffering…"
        } else if (binding.tvPreview.text == "Buffering…") {
            binding.tvPreview.text = lastPreview
        }
        updateBubble()
    }

    private fun startTicker() {
        ticker.post(object : Runnable {
            override fun run() {
                updatePlayPause()
                ticker.postDelayed(this, TICK_MS)
            }
        })
    }

    private fun stopTicker() {
        ticker.removeCallbacksAndMessages(null)
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_PREVIEW = "preview"

        /** Touch travel, in px, above which a gesture is a drag rather than a tap. */
        private const val TAP_SLOP = 12f

        private const val PREVIEW_CHARS = 64
        private const val TICK_MS = 600L
    }
}
