package com.arkadia.sonata

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SonataApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val playback = NotificationChannel(
            CHANNEL_ID,
            "Sonata Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ARKANA voice playback controls"
            setShowBadge(false)
        }

        // The floating player needs its own ongoing notification, because a
        // foreground service must post one. It is a *separate*, silent channel at
        // the lowest importance so it stays out of the way: it exists to satisfy the
        // foreground-service contract and to give the user a way to close the
        // overlay, not to announce anything.
        val overlay = NotificationChannel(
            OVERLAY_CHANNEL_ID,
            "Floating player",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps the floating player on screen over other apps"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(playback)
            createNotificationChannel(overlay)
        }
    }

    companion object {
        const val CHANNEL_ID = "sonata_playback"
        const val NOTIF_ID   = 1001

        const val OVERLAY_CHANNEL_ID = "sonata_overlay"
        const val OVERLAY_NOTIF_ID   = 1002
    }
}
