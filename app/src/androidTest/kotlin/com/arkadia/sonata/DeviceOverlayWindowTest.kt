package com.arkadia.sonata

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The floating player's window, checked against the system rather than the service.
 *
 * This exists because every existing check of the overlay asserts the service's own
 * `attached` flag. That flag says the service *believes* it has a window. It does not
 * say the window is on screen, has a size, or sits anywhere the user can see — which is
 * exactly the defect the founder reported ("the floating player does not open or float
 * at all") and exactly the class a device can see and a unit test cannot.
 *
 * The distinction being pinned down: a window can be added successfully and still be
 * invisible — zero-sized, off-screen, or covered. Those are different failures with
 * different fixes, and only the platform's own view of its windows separates them. So
 * these tests ask the platform, via `UiAutomation.windows`, and never ask Sonata.
 */
@RunWith(AndroidJUnit4::class)
class DeviceOverlayWindowTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The window type the floating player uses. */
    private val OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @Before
    fun setUp() {
        if (!Settings.canDrawOverlays(context)) {
            // Apply the decision the user would apply, so the test measures the
            // overlay rather than the permission dialog. This is not a bypass: it is
            // the same grant, made by the harness, and `canDrawOverlays` is re-read to
            // confirm it took effect.
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                .close()
            SystemClock.sleep(1_000)
        }
        assumeTrue(
            "SYSTEM_ALERT_WINDOW is not granted, so the overlay itself cannot be tested",
            Settings.canDrawOverlays(context),
        )
    }

    @After
    fun tearDown() {
        context.stopService(Intent(context, FloatingOverlayService::class.java))
        SystemClock.sleep(500)
    }

    /**
     * Ask the window manager whether the floating player's window exists, and where.
     *
     * `UiAutomation.windows` was tried first and returns an empty list on a headless
     * emulator regardless of what is on screen, because the window list it exposes is
     * gated on accessibility flags the harness does not set. `dumpsys window` is the
     * window manager's own record and does not have that dependency, so it is the
     * reliable source — and it is the same thing a human runs with `adb shell` when
     * diagnosing a device, so the test and the manual procedure agree.
     *
     * Polled, because a window appears on a later main-thread pass and a fixed sleep is
     * a flake waiting to happen.
     */
    private fun windowDump(): String = runCatching {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys window windows")
        java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
    }.getOrDefault("(dumpsys unavailable)")

    /** The window manager's record of the floating player's window, if it has one. */
    private fun overlayWindowLines(): List<String> =
        windowDump().lines().filter { it.contains(FloatingOverlayService.WINDOW_TITLE) }

    /**
     * The on-screen rectangle the window manager reports for the floating player.
     *
     * Returns null when the window does not exist. A window that exists but has zero
     * width or height is reported as a rectangle of that size rather than as null,
     * because "attached but invisible" and "not attached" are different failures.
     */
    private fun overlayWindowBounds(): Rect? {
        val lines = overlayWindowLines()
        if (lines.isEmpty()) return null
        // The window block is `Window #N Window{hash u0 SonataFloatingPlayer}:` followed
        // by its geometry. Search the block, not just the title line, so the bounds are
        // the ones belonging to this window.
        val dump = windowDump()
        val idx = dump.indexOf(FloatingOverlayService.WINDOW_TITLE)
        if (idx < 0) return null
        val block = dump.substring(idx, minOf(idx + 4_000, dump.length))
        val m = Regex("""mBounds=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""").find(block)
            ?: Regex("""frame=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""").find(block)
            ?: return Rect(0, 0, 0, 0)
        return Rect(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
            m.groupValues[4].toInt(),
        )
    }

    /** Wait for the floating player's window to appear, returning its bounds. */
    private fun awaitOverlayWindow(timeoutMs: Long = 10_000): Rect? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val b = overlayWindowBounds()
            if (b != null && b.width() > 0 && b.height() > 0) return b
            SystemClock.sleep(250)
        }
        return overlayWindowBounds()
    }

    /** Wait for the floating player's window to be gone. */
    private fun awaitNoOverlayWindow(timeoutMs: Long = 8_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (overlayWindowLines().isEmpty()) return true
            SystemClock.sleep(250)
        }
        return overlayWindowLines().isEmpty()
    }

    /**
     * A diagnosis for a failure, so the next one is readable from CI alone.
     *
     * A bare "no window" assertion cost a round trip to interpret. The window list and
     * the window manager's own lines for this package make the cause visible at once.
     */
    private fun diagnose(): String {
        val dump = windowDump()
        val summary = dump.lines()
            .filter { it.contains("Window{") || it.contains("mCurrentFocus") }
            .take(20)
            .joinToString("\n")
        val pkg = dump.lines()
            .filter { it.contains(context.packageName) }
            .take(10)
            .joinToString("\n")
        return "window summary:\n$summary\nlines for ${context.packageName}:\n$pkg"
    }

    private fun startOverlay() {
        context.startForegroundService(
            Intent(context, FloatingOverlayService::class.java)
                .putExtra(FloatingOverlayService.EXTRA_LABEL, "Overlay test")
                .putExtra(FloatingOverlayService.EXTRA_PREVIEW, "A short line of the passage"),
        )
    }

    private fun display(): Rect =
        context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds

    /**
     * The window must be a real, visible, on-screen rectangle.
     *
     * Zero width or height is the classic "it attached but you cannot see it" failure;
     * a position entirely off the display is the other. Both pass a flag-based
     * assertion and both fail the user.
     */
    /**
     * The window must be a real, visible, on-screen rectangle.
     *
     * Zero width or height is the classic "it attached but you cannot see it" failure;
     * a position entirely off the display is the other. Both pass a flag-based
     * assertion and both fail the user.
     */
    @Test
    fun p4_1_the_floating_window_is_actually_visible_and_on_screen() {
        startOverlay()

        val bounds = awaitOverlayWindow()
        assertTrue(
            "the window manager has no record of a visible " +
                "${FloatingOverlayService.WINDOW_TITLE} window, so the floating player " +
                "has no window on screen.\n" + diagnose(),
            bounds != null && bounds.width() > 0 && bounds.height() > 0,
        )

        val screen = display()
        assertTrue(
            "the overlay is entirely off the ${screen.width()}x${screen.height()} display: " +
                "$bounds",
            bounds!!.left < screen.width() &&
                bounds.top < screen.height() &&
                bounds.right > 0 &&
                bounds.bottom > 0,
        )
    }

    /** Collapsing must leave a visible window, not a zero-sized or removed one. */
    @Test
    fun p4_2_collapsing_leaves_a_visible_window() {
        startOverlay()
        assertTrue(
            "the overlay should be visible before collapsing.\n" + diagnose(),
            awaitOverlayWindow() != null,
        )

        // Collapse through the same entry point a tap uses.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            FloatingOverlayService.liveInstanceForTest()?.collapseForTest()
        }

        val bounds = awaitOverlayWindow()
        assertTrue(
            "a collapsed overlay must still be a visible window, not a zero-sized or " +
                "removed one.\n" + diagnose(),
            bounds != null && bounds.width() > 0 && bounds.height() > 0,
        )
    }

    /** Closing must remove the window rather than leave it on screen. */
    @Test
    fun p4_3_closing_removes_the_window() {
        startOverlay()
        assertTrue(
            "the overlay should be visible before closing.\n" + diagnose(),
            awaitOverlayWindow() != null,
        )

        context.startService(
            Intent(context, FloatingOverlayService::class.java)
                .setAction(FloatingOverlayService.ACTION_CLOSE),
        )

        assertTrue(
            "closing must remove the window, not leave it on screen.\n" + diagnose(),
            awaitNoOverlayWindow(),
        )
    }
}
