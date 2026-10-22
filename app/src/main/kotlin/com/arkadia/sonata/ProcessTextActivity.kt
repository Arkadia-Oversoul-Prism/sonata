package com.arkadia.sonata

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Receives text from Android's text-selection menu and hands it to the one screen.
 *
 * This activity has no UI. It previously opened a separate player, which is how the
 * app ended up with two screens and a blank-looking handover: the player showed only
 * a preview and the real text sat in a service the user could not see. Forwarding to
 * [MainActivity] means the selection lands in the input the user would have typed
 * into, with the read starting there.
 *
 * `noHistory` is deliberately not set: a second selection while an instance is alive
 * arrives through [onNewIntent], and both entry points forward the same way.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        forward(intent)
    }

    private fun forward(intent: Intent?) {
        // Some apps send the read-only variant; accept either, plus a plain share.
        val raw = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)

        val text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            // Nothing to read: say so rather than opening an empty screen.
            Toast.makeText(this, "No text selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        finish()
    }
}
