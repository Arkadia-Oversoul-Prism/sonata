package com.arkadia.sonata

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * Handles android.intent.action.PROCESS_TEXT — appears as
 * "Speak with Sonata" in the native text-selection pop-up across all apps.
 *
 * No UI is shown (transparent theme). The activity starts SpeechService
 * (foreground) and optionally FloatingOverlayService, then finishes.
 *
 * The activity is `noHistory`, but a re-delivery while an instance is alive
 * arrives through [onNewIntent]; both entry points share [handle] so a second
 * selection does not depend on a fresh activity.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        // Some apps send the read-only variant; accept either.
        val raw = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)

        when (val validation = TextLimits.validate(raw)) {
            is TextValidation.Empty -> {
                // Nothing to read: do not start a foreground service for it.
                finish()
                return
            }

            is TextValidation.Ready -> dispatch(validation.text)

            is TextValidation.Truncated -> {
                Toast.makeText(
                    this,
                    "Reading the first $MAX_TTS_CHARS characters",
                    Toast.LENGTH_SHORT
                ).show()
                dispatch(validation.text)
            }
        }

        finish()
    }

    private fun dispatch(text: String) {
        val speechIntent = Intent(this, SpeechService::class.java).apply {
            action = SpeechService.ACTION_SPEAK
            putExtra(SpeechService.EXTRA_TEXT,  text)
            putExtra(SpeechService.EXTRA_LABEL, "SONATA")
        }
        startForegroundService(speechIntent)

        // Show the floating overlay if permitted; otherwise point at the setting.
        if (Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(this, FloatingOverlayService::class.java).apply {
                putExtra(FloatingOverlayService.EXTRA_LABEL,   "ORACLE TRANSMISSION")
                putExtra(FloatingOverlayService.EXTRA_PREVIEW, text)
            }
            startService(overlayIntent)
        } else {
            Toast.makeText(
                this,
                "Grant 'Display over other apps' in Settings for the floating player.",
                Toast.LENGTH_LONG
            ).show()
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}
