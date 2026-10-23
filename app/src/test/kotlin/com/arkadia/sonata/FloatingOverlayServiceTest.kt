package com.arkadia.sonata

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The floating player, on the JVM.
 *
 * These exist because the overlay has failed on the founder's device three times
 * in a row — "it does not open", then "it does not float", then "the app closes
 * like something shut it down" — while every JVM test passed and the manifest was
 * verified in the built APK. Inspection was not catching it, so the service is now
 * actually constructed and started here.
 *
 * What this covers: the service reaches the point of holding a window, and does not
 * throw on the way. What it cannot cover: whether a real window ever becomes
 * visible to the user, and whether `TYPE_APPLICATION_OVERLAY` is honoured on a real
 * device. Robolectric has no compositor. A green test here is a necessary
 * condition, not proof the player appears.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FloatingOverlayServiceTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Robolectric keeps the service registered across tests.
        Robolectric.flushForegroundThreadScheduler()
    }

    @Test
    fun `with the overlay permission it reaches the window and stays alive`() {
        grantOverlayPermission()

        val controller = Robolectric.buildService(FloatingOverlayService::class.java).create()
        val service = controller.get()

        assertTrue(
            "the service should be holding a window once started",
            service.isAttachedForTest,
        )
    }

    @Test
    fun `without the overlay permission it exits without crashing`() {
        // The default in Robolectric is denied, which is the state a user is in
        // before they grant anything.
        val controller = Robolectric.buildService(FloatingOverlayService::class.java).create()
        val service = controller.get()

        assertTrue(
            "with no permission the service must not claim to hold a window",
            !service.isAttachedForTest,
        )
    }

    @Test
    fun `a start intent with a preview reaches the player without throwing`() {
        grantOverlayPermission()

        val intent = Intent(app, FloatingOverlayService::class.java).apply {
            putExtra(FloatingOverlayService.EXTRA_LABEL, "SONATA")
            putExtra(FloatingOverlayService.EXTRA_PREVIEW, "The quick brown fox jumps over the lazy dog.")
        }
        val controller = Robolectric.buildService(FloatingOverlayService::class.java, intent)
            .create()
            .startCommand(1, 1)
        val service = controller.get()

        assertTrue(service.isAttachedForTest)
    }

    @Test
    fun `collapsing and expanding does not throw and keeps a window attached`() {
        grantOverlayPermission()

        val controller = Robolectric.buildService(FloatingOverlayService::class.java).create()
        val service = controller.get()
        assertTrue(service.isAttachedForTest)

        // The path that was most likely to be the crash: a tap on the player.
        service.collapseForTest()
        assertTrue("collapsed", service.isCollapsedForTest)
        assertTrue("still attached while collapsed", service.isAttachedForTest)

        service.expandForTest()
        assertTrue("expanded", !service.isCollapsedForTest)
        assertTrue("still attached after expanding", service.isAttachedForTest)
    }

    @Test
    fun `the notification and its close action are wired`() {
        grantOverlayPermission()

        val controller = Robolectric.buildService(FloatingOverlayService::class.java).create()
        val service = controller.get()

        // The founder saw the notification appear and then vanish. If the close
        // action were being fired on start, that is exactly what it would look like.
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications
        assertTrue("a foreground notification should be posted", posted.isNotEmpty())
        assertNotNull(
            "the notification must carry a close action",
            posted.first().actions?.firstOrNull(),
        )

        // The close action stops the service, which is its job — but it must not be
        // what the start path runs.
        assertTrue("the service should still be alive after starting", service.isAttachedForTest)
    }

    @Test
    fun `destroying releases the window without throwing`() {
        grantOverlayPermission()

        val controller = Robolectric.buildService(FloatingOverlayService::class.java).create()
        assertTrue(controller.get().isAttachedForTest)

        controller.destroy()
        assertNull(
            "the view should be released on destroy",
            controller.get().attachedViewForTest,
        )
    }

    private fun grantOverlayPermission() {
        // Settings.canDrawOverlays is served by ShadowSettings, not by the runtime
        // permission store, so granting the manifest permission is not enough.
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
    }
}
