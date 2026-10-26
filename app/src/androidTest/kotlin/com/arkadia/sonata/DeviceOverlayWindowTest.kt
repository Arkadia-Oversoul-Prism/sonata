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

    /** One window the platform reports for this app, with its real on-screen bounds. */
    private data class PlatformWindow(val type: Int, val bounds: Rect)

    /**
     * Ask the platform which windows this app actually has.
     *
     * Read from `UiAutomation`, which reports what the window manager composited — not
     * what the app intended. Polled, because a window appears on a later main-thread
     * pass and a fixed sleep is a flake waiting to happen.
     */
    private fun platformWindows(timeoutMs: Long = 8_000): List<PlatformWindow> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var found = emptyList<PlatformWindow>()
        while (SystemClock.uptimeMillis() < deadline) {
            val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            found = windows.mapNotNull { w ->
                val root = w.root ?: return@mapNotNull null
                if (root.packageName != context.packageName) return@mapNotNull null
                val r = Rect()
                w.getBoundsInScreen(r)
                PlatformWindow(w.type, r)
            }
            if (found.any { it.bounds.width() > 0 && it.bounds.height() > 0 }) break
            SystemClock.sleep(250)
        }
        return found
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
    @Test
    fun p4_1_the_floating_window_is_actually_visible_and_on_screen() {
        startOverlay()

        val windows = platformWindows()
        val shown = windows.filter { it.bounds.width() > 0 && it.bounds.height() > 0 }
        assertTrue(
            "the app has no non-empty window on screen; the platform reports " +
                windows.map { "type=${it.type} ${it.bounds}" },
            shown.isNotEmpty(),
        )

        val screen = display()
        val overlay = shown.first()
        assertTrue(
            "the overlay is entirely off the ${screen.width()}x${screen.height()} display: " +
                "type=${overlay.type} ${overlay.bounds}",
            overlay.bounds.left < screen.width() &&
                overlay.bounds.top < screen.height() &&
                overlay.bounds.right > 0 &&
                overlay.bounds.bottom > 0,
        )
    }

    /** Collapsing must leave a visible window, not a zero-sized or detached one. */
    @Test
    fun p4_2_collapsing_leaves_a_visible_window() {
        startOverlay()
        assertTrue(
            "the overlay should be visible before collapsing",
            platformWindows().any { it.bounds.width() > 0 && it.bounds.height() > 0 },
        )

        // Collapse through the same entry point a tap uses.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            FloatingOverlayService.liveInstanceForTest()?.collapseForTest()
        }

        val shown = platformWindows().filter { it.bounds.width() > 0 && it.bounds.height() > 0 }
        assertTrue(
            "a collapsed overlay must still be a visible window, not a zero-sized or " +
                "removed one; the platform reports " + shown.map { it.bounds },
            shown.isNotEmpty(),
        )
    }

    /** Closing must remove the window rather than leave it on screen. */
    @Test
    fun p4_3_closing_removes_the_window() {
        startOverlay()
        assertTrue(
            "the overlay should be visible before closing",
            platformWindows().any { it.bounds.width() > 0 && it.bounds.height() > 0 },
        )

        context.startService(
            Intent(context, FloatingOverlayService::class.java)
                .setAction(FloatingOverlayService.ACTION_CLOSE),
        )
        SystemClock.sleep(2_000)

        assertTrue(
            "closing must remove the window, not leave it on screen; the platform still " +
                "reports " + platformWindows().filter { it.bounds.width() > 0 },
            platformWindows().none { it.bounds.width() > 0 && it.bounds.height() > 0 },
        )
    }
}
