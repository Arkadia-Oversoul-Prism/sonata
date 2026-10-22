package com.arkadia.sonata

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import com.arkadia.sonata.databinding.OverlayBubbleBinding
import com.arkadia.sonata.databinding.OverlayPlayerBinding

/**
 * The floating player: a draggable glass panel that collapses to a small bubble so
 * a read continues while the user works in other apps.
 *
 * Two views share one window slot. Collapsing swaps the panel for a bubble and keeps
 * the position, so the player stays where the user put it; expanding restores the
 * panel. Both states are draggable, and both drive the same bound [SpeechService] as
 * the main screen and the notification.
 */
class FloatingOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var binding: OverlayPlayerBinding
    private lateinit var bubble: OverlayBubbleBinding
    private var overlayView: View? = null

    /** Which view is currently attached to the window. */
    private var collapsed = false

    private var speechService: SpeechService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            speechService = (binder as SpeechService.LocalBinder).getService()
            speechService?.onStart    = { updatePlayPause() }
            speechService?.onPause    = { updatePlayPause() }
            speechService?.onResume   = { updatePlayPause() }
            speechService?.onComplete = { stopSelf() }
        }
        override fun onServiceDisconnected(name: ComponentName) { speechService = null }
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
        x = 80
        y = 200
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)

        val inflater = LayoutInflater.from(this)
        binding = OverlayPlayerBinding.inflate(inflater)
        bubble = OverlayBubbleBinding.inflate(inflater)

        setupDrag(binding.root)
        setupDrag(bubble.root)
        setupButtons()
        setupBubble()

        overlayView = binding.root
        wm.addView(overlayView, params)

        // Bind to SpeechService
        bindService(
            Intent(this, SpeechService::class.java),
            conn,
            BIND_AUTO_CREATE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LABEL)?.let { binding.tvLabel.text = it }
        intent?.getStringExtra(EXTRA_PREVIEW)?.let { binding.tvPreview.text = it.take(60) + "…" }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unbindService(conn)
        overlayView?.let { wm.removeView(it) }
        super.onDestroy()
    }

    // ── Collapse / expand ─────────────────────────────────────────────────

    /**
     * Swap the attached view, carrying the position across so the player does not
     * jump when it is collapsed or reopened.
     */
    private fun setCollapsed(collapse: Boolean) {
        if (collapsed == collapse) return
        val current = overlayView ?: return
        val next: View = if (collapse) bubble.root else binding.root

        wm.removeView(current)
        overlayView = next
        collapsed = collapse
        if (collapse) updateBubble() else updatePlayPause()
        wm.addView(next, params)
    }

    private fun setupBubble() {
        // A tap expands; a drag moves. Distinguishing them needs the touch distance,
        // which setupDrag records, so the tap is only acted on when the pointer
        // barely moved.
        bubble.root.setOnClickListener {
            if (!dragged) setCollapsed(false)
        }
    }

    /** The bubble reflects playing state so a glance is enough. */
    private fun updateBubble() {
        bubble.ivBubble.alpha = if (speechService?.isPlaying == true) 1.0f else 0.55f
    }

    // ── Drag ──────────────────────────────────────────────────────────────

    private var initX = 0; private var initY = 0
    private var initTouchX = 0f; private var initTouchY = 0f

    /** Set when the pointer travelled far enough that this was a drag, not a tap. */
    private var dragged = false

    private fun setupDrag(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = event.rawX; initTouchY = event.rawY
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
                    wm.updateViewLayout(overlayView, params)
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
            val next  = SpeedSteps.next(prefs.speed)
            prefs.speed = next
            binding.btnSpeed.text = "${next}×"
        }
    }

    private fun updatePlayPause() {
        val playing = speechService?.isPlaying == true
        binding.btnPlayPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause
            else         android.R.drawable.ic_media_play
        )
        updateBubble()
    }

    companion object {
        const val EXTRA_LABEL   = "label"
        const val EXTRA_PREVIEW = "preview"

        /** Touch travel, in px, above which a gesture is a drag rather than a tap. */
        private const val TAP_SLOP = 12f
    }
}
