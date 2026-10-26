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

    /** One window the platform reports, with its real on-screen bounds. */
    private data class PlatformWindow(val type: Int, val bounds: Rect) {
        override fun toString(): String = "type=$type $bounds"
    }

    /**
     * Ask the platform which windows exist.
     *
     * Read from `UiAutomation`, which reports what the window manager composited — not
     * what the app intended. Two deliberate choices here, both learned from a failed
     * run:
     *
     *  - The window *type* is read directly off the window, and the accessibility node
     *    tree is never touched. Requiring `getRoot() != null` silently discarded every
     *    window on a headless emulator, which made a working overlay look absent.
     *  - Windows are not filtered by package name. An overlay window's reported
     *    package is not guaranteed to equal the test's target package, and the type is
     *    the unambiguous signal: `TYPE_APPLICATION_OVERLAY` is what the floating player
     *    uses.
     *
     * Polled, because a window appears on a later main-thread pass and a fixed sleep is
     * a flake waiting to happen.
     */
    private fun platformWindows(timeoutMs: Long = 10_000): List<PlatformWindow> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var found = emptyList<PlatformWindow>()
        while (SystemClock.uptimeMillis() < deadline) {
            found = InstrumentationRegistry.getInstrumentation().uiAutomation.windows.map { w ->
                val r = Rect()
                w.getBoundsInScreen(r)
                PlatformWindow(w.type, r)
            }
            if (found.any { it.type == OVERLAY_TYPE && it.bounds.width() > 0 }) break
            SystemClock.sleep(250)
        }
        return found
    }

    /**
     * Wait until no overlay window remains, up to [timeoutMs].
     *
     * Separate from [platformWindows], which polls for presence: waiting for absence by
     * polling for presence would always burn the whole timeout before concluding.
     */
    private fun awaitNoOverlayWindow(timeoutMs: Long = 8_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (overlayWindows().isEmpty()) return true
            SystemClock.sleep(250)
        }
        return overlayWindows().isEmpty()
    }

    /** The overlay windows the platform reports, non-empty ones only. */
    private fun overlayWindows(): List<PlatformWindow> =
        platformWindows().filter { it.type == OVERLAY_TYPE && it.bounds.width() > 0 && it.bounds.height() > 0 }

    /**
     * A diagnosis for a failure, so the next one is readable from CI alone.
     *
     * A bare "no window" assertion cost a round trip to interpret; the window list and
     * the window manager's own view of this package make the cause visible immediately.
     */
    private fun diagnose(): String {
        val all = platformWindows().joinToString(", ") { it.toString() }
        val dumpsys: String = runCatching {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys window windows")
            // `executeShellCommand` hands back a ParcelFileDescriptor, not a stream.
            java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }.getOrDefault("(dumpsys unavailable)")
        val interesting = dumpsys.lines()
            .filter { it.contains(context.packageName) || it.contains("mCurrentFocus") }
            .take(12)
            .joinToString("\n")
        return "all windows: [$all]\ndumpsys lines for ${context.packageName}:\n$interesting"
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

        val shown = overlayWindows()
        assertTrue(
            "no TYPE_APPLICATION_OVERLAY window is on screen, so the floating player " +
                "has no visible window.\n" + diagnose(),
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
            "the overlay should be visible before collapsing.\n" + diagnose(),
            overlayWindows().isNotEmpty(),
        )

        // Collapse through the same entry point a tap uses.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            FloatingOverlayService.liveInstanceForTest()?.collapseForTest()
        }

        assertTrue(
            "a collapsed overlay must still be a visible window, not a zero-sized or " +
                "removed one.\n" + diagnose(),
            overlayWindows().isNotEmpty(),
        )
    }

    /** Closing must remove the window rather than leave it on screen. */
    @Test
    fun p4_3_closing_removes_the_window() {
        startOverlay()
        assertTrue(
            "the overlay should be visible before closing.\n" + diagnose(),
            overlayWindows().isNotEmpty(),
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
