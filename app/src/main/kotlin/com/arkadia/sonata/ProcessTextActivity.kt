package com.arkadia.sonata

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.arkadia.sonata.imports.DocumentImport
import java.io.IOException

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
 *
 * ## Shared files
 *
 * Three routes in, not two. PROCESS_TEXT and SEND-with-`EXTRA_TEXT` carry *text*; a
 * file shared from a file manager carries `EXTRA_STREAM` and no text at all, so the
 * handoff used to answer "No text selected" for a perfectly good `.txt`. The stream
 * is now read under a byte ceiling and decoded by [DocumentImport], which owns every
 * decision about what is acceptable and why — this activity only opens the stream
 * and reports the outcome.
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
        if (text.isNotEmpty()) {
            hand(text)
            return
        }

        // No text, but possibly a file. A share carrying neither is the empty handoff.
        val file = sharedFile(intent)
        if (file == null) {
            Toast.makeText(this, "No text selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        importFile(file)
    }

    /**
     * The file the user shared, if any.
     *
     * `EXTRA_STREAM` is the documented extra, but not every sender uses it: some
     * attach the file as clip data instead. Both are checked, because a route that
     * silently drops the file is the defect this exists to close.
     */
    private fun sharedFile(intent: Intent?): Uri? {
        if (intent == null) return null
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let { return it }
        return intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private fun importFile(uri: Uri) {
        val read = try {
            contentResolver.openInputStream(uri)?.use { DocumentImport.readBounded(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        if (read == null) {
            refuse("That file could not be opened")
            return
        }
        if (read == DocumentImport.StreamRead.OverCeiling) {
            refuse("That file is too large to read")
            return
        }

        val bytes = (read as DocumentImport.StreamRead.Ok).bytes
        when (val outcome = DocumentImport.import(displayName(uri), bytes)) {
            is DocumentImport.ImportOutcome.Accepted -> {
                // Say which file was taken in. An import that opens a screen with no
                // word about what happened is the silent handover this app already
                // had once.
                Toast.makeText(this, "Reading ${outcome.title}", Toast.LENGTH_SHORT).show()
                hand(outcome.text)
            }

            is DocumentImport.ImportOutcome.Rejected -> when (outcome.reason) {
                DocumentImport.Reason.TOO_LARGE -> refuse("That file is too large to read")
                DocumentImport.Reason.BINARY -> refuse("That file is not text")
            }

            DocumentImport.ImportOutcome.Empty -> refuse("That file has no text to read")
        }
    }

    /**
     * The name the file has wherever it came from.
     *
     * Queried rather than taken from the URI, because a `content://` last segment is
     * usually an opaque document id — the title would be a hex string.
     */
    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
    } catch (_: SecurityException) {
        null
    }

    private fun refuse(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun hand(text: String) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        finish()
    }
}
