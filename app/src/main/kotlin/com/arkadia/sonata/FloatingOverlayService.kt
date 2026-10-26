package com.arkadia.sonata

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import android.util.Log
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
 *  4. It was a plain background service holding an overlay window. Android reclaims
 *     background processes, and the window goes with them, so the player appeared and
 *     then vanished — or never appeared at all when the process was killed on the way
 *     out of the app. It is now a `specialUse` foreground service, which is what keeps
 *     a window of this kind alive; the ongoing notification is the contract that
 *     requires, and doubles as the user's way to close the overlay.
 */
class FloatingOverlayService : Service() {

    private lateinit var wm: WindowManager

    /**
     * Holds both states at once. The window is attached once and collapse/expand
     * only toggles visibility, so no user gesture performs a window detach/attach —
     * two operations that can each throw, and a throw in a service kills the app.
     */
    private lateinit var container: FrameLayout
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
        // Both parameters are nullable on purpose. The platform declares them
        // nullable and *does* pass a null binder when a bind fails, so declaring
        // them non-null inserts a Kotlin null check that throws a
        // NullPointerException on the app's main thread — another way for the
        // player to take the whole app down.
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? SpeechService.LocalBinder ?: run {
                Log.w(TAG, "bound to SpeechService but got an unexpected binder")
                return
            }
            val svc = local.getService()
            speechService = svc
            boundToSpeech = true
            svc.addClient(client)
            updatePlayPause()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
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

    // ── Foreground ─────────────────────────────────────────────────────────

    /**
     * Hold the process at foreground priority so the window survives the user
     * leaving the app — which is the entire point of a floating player.
     */
    private fun promoteToForeground() {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val close = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingOverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, SonataApp.OVERLAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wave)
            .setContentTitle("Floating player")
            .setContentText("Tap to open Sonata, or close the player")
            .setContentIntent(open)
            .addAction(0, "Close player", close)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        // No foregroundServiceType argument: the declared `specialUse` type is taken
        // from the manifest, and passing one here that the manifest does not declare
        // throws on API 34.
        startForeground(SonataApp.OVERLAY_NOTIF_ID, notification)
    }

    /** Leave foreground and stop, in the one order that cannot crash. */
    private fun closeDown() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
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
        liveInstance = java.lang.ref.WeakReference(this)
        super.onCreate()

        // Go foreground before anything else. The service is started with
        // startForegroundService, and Android stops the app outright if it is not
        // foregrounded within a few seconds — including when the service decides to
        // exit. So the contract is satisfied first and the permission guard below
        // exits *through* it, rather than before it.
        // Step logging, so a failure on a device that cannot be debugged here can be
        // pinpointed from logcat alone: `adb logcat -s SonataOverlay`.
        Log.i(TAG, "starting floating player")
        promoteToForeground()
        Log.i(TAG, "foreground notification posted")

        // Guard before touching the window: an ungranted overlay permission makes
        // addView throw, and a crashing service is indistinguishable from a dead
        // button. Exiting cleanly leaves the read playing in the notification, which
        // is a usable fallback rather than a failure.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission not granted; floating player will not start")
            closeDown()
            return
        }
        Log.i(TAG, "overlay permission granted")

        wm = getSystemService(WindowManager::class.java)

        // Inflate under an explicit MaterialComponents theme.
        //
        // This was the crash. The player is built from a MaterialCardView and a
        // MaterialButton, and a Service's own context does not carry the app's
        // MaterialComponents theme — a theme only reaches an Activity by default. So
        // inflation threw
        //
        //   IllegalArgumentException: The style on this component requires your app
        //   theme to be Theme.MaterialComponents (or a descendant)
        //
        // out of onCreate, uncaught, on the app's main thread. That is exactly the
        // reported symptom: the foreground notification appeared and the whole app
        // closed immediately. A themed wrapper is the supported way to inflate
        // Material components outside an Activity.
        val themed = ContextThemeWrapper(this, R.style.Theme_Sonata)
        val inflater = LayoutInflater.from(themed)

        // Inflation is also inside the guard: whatever else it throws for on some OEM
        // theme, a failure to build the player must not take the app down.
        try {
            binding = OverlayPlayerBinding.inflate(inflater)
            bubble = OverlayBubbleBinding.inflate(inflater)

            setupDrag(binding.root)
            setupDrag(bubble.root)
            setupButtons()
            setupBubble()
        } catch (e: Exception) {
            Log.e(TAG, "could not build the floating player", e)
            closeDown()
            return
        }

        // Both states live in the window from the start; collapsing only toggles
        // which is visible. Swapping them by removeView/addView meant two window
        // operations per tap that can each throw, and it flickered.
        container = FrameLayout(this).apply {
            addView(binding.root)
            addView(bubble.root)
        }
        bubble.root.visibility = View.GONE

        overlayView = container
        try {
            wm.addView(container, params)
            attached = true
            Log.i(TAG, "window attached")
        } catch (e: Exception) {
            // Permission revoked between the check and here, or an OEM window policy.
            // Report and exit rather than leaving a half-created service.
            Log.w(TAG, "could not attach the floating player: ${e.message}")
            overlayView = null
            closeDown()
            return
        }

        bindService(Intent(this, SpeechService::class.java), conn, Context.BIND_AUTO_CREATE)

        // Poll while visible so the bubble's opacity and the play/pause glyph track
        // the engine's real state — including changes made from the notification or
        // the main screen, which do not pass through this service.
        startTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            stopSelf()
            return START_NOT_STICKY
        }
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
        liveInstance = null
        stopTicker()
        // The ongoing notification exists only while the window does.
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
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

    /** The largest x that still leaves the window fully on screen. */
    private fun maxX(): Int = (screenWidth() - (overlayView?.width ?: 0)).coerceAtLeast(0)

    /** The largest y that still leaves the window fully on screen. */
    private fun maxY(): Int = (screenHeight() - (overlayView?.height ?: 0)).coerceAtLeast(0)

    /**
     * Screen size in pixels.
     *
     * `WindowMetrics` is API 30, but this app supports 29, so the deprecated display
     * path is still needed. Using the newer call unguarded would crash on 29 — an
     * overlay that dies the moment it is dragged.
     */
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val metrics = resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }

    private fun screenWidth(): Int = screenSize().first

    private fun screenHeight(): Int = screenSize().second

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
        collapsed = collapse
        // Visibility only: no window is detached or attached, so a tap cannot throw
        // a window error. (A `BadTokenException` here would crash the app, not just
        // the player.)
        binding.root.visibility = if (collapse) View.GONE else View.VISIBLE
        bubble.root.visibility = if (collapse) View.VISIBLE else View.GONE
        if (collapse) updateBubble() else updatePlayPause()
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
                    // Clamp the *start* too: the stored position can be off-screen if
                    // the device was rotated or the window was resized while it sat
                    // at an edge, which would leave the player unreachable.
                    params.x = params.x.coerceIn(0, maxX())
                    params.y = params.y.coerceIn(0, maxY())
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY
                    if (kotlin.math.abs(dx) > TAP_SLOP || kotlin.math.abs(dy) > TAP_SLOP) {
                        dragged = true
                    }
                    // Clamp to the screen. Without this the player can be dragged
                    // past the edge and left there, where it is invisible and
                    // unreachable — indistinguishable from the player having closed
                    // itself, and with no way back.
                    params.x = (initX + dx.toInt()).coerceIn(0, maxX())
                    params.y = (initY + dy.toInt()).coerceIn(0, maxY())
                    // Called on every touch move; a throw here would kill the app
                    // mid-drag.
                    if (attached) runCatching { wm.updateViewLayout(overlayView, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // A tap, not a drag: the collapsed bubble expands. Handled here
                    // because ACTION_DOWN was consumed above, so an OnClickListener
                    // on the same view would never be invoked.
                    if (!dragged && collapsed) runCatching { setCollapsed(false) }
                    runCatching { v.performClick() }
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
        // Each handler is guarded: a throw in a click listener on the main thread of
        // a service takes the process down, so a bad state would present as "the app
        // crashes when I touch the player".
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
                // Guarded: this runs on the main thread every TICK_MS, so a single
                // throw would repeat forever and crash the app in a loop rather than
                // once.
                runCatching { updatePlayPause() }
                ticker.postDelayed(this, TICK_MS)
            }
        })
    }

    private fun stopTicker() {
        ticker.removeCallbacksAndMessages(null)
    }

    // ── Test seams ────────────────────────────────────────────────────────

    /**
     * Whether a window is currently attached.
     *
     * The overlay has failed on a real device while every JVM test passed, so the
     * service is now driven from tests. These expose state rather than behaviour:
     * they cannot make the tests pass by themselves.
     */
    @androidx.annotation.VisibleForTesting
    internal val isAttachedForTest: Boolean get() = attached

    @androidx.annotation.VisibleForTesting
    internal val isCollapsedForTest: Boolean get() = collapsed

    @androidx.annotation.VisibleForTesting
    internal val attachedViewForTest: View? get() = overlayView

    @androidx.annotation.VisibleForTesting
    internal fun collapseForTest() = setCollapsed(true)

    @androidx.annotation.VisibleForTesting
    internal fun expandForTest() = setCollapsed(false)

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_PREVIEW = "preview"

        /** Action from the notification's Close button. */
        const val ACTION_CLOSE = "com.arkadia.sonata.overlay.CLOSE"

        /**
         * The running instance, for instrumentation only.
         *
         * A device test has no binder to this service (it is started, not bound), so
         * without this there is no way to drive collapse/expand and observe the window
         * it owns. Held weakly and cleared in onDestroy, so it cannot keep a dead
         * service alive.
         */
        private var liveInstance: java.lang.ref.WeakReference<FloatingOverlayService>? = null

        internal fun liveInstanceForTest(): FloatingOverlayService? = liveInstance?.get()

        /** Touch travel, in px, above which a gesture is a drag rather than a tap. */
        private const val TAP_SLOP = 12f

        private const val PREVIEW_CHARS = 64
        private const val TICK_MS = 600L
    }
}
