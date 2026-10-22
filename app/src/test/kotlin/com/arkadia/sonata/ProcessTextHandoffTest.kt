package com.arkadia.sonata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PROCESS_TEXT handoff must land on the one screen, with the text.
 *
 * A user reported that selecting Sonata opened a blank page. The cause was
 * structural: the selection was delivered to a service while a separate player
 * activity was opened without it, so the screen the user saw never received the
 * text. These assertions pin the wiring that fixes it, because a source-level
 * regression here is invisible to every engine test.
 */
class ProcessTextHandoffTest {

    private val root = File("src/main")

    private fun read(path: String): String {
        val f = File(root, path)
        assertTrue("missing $path", f.exists())
        return f.readText()
    }

    @Test
    fun `the handoff forwards the selected text to the single screen`() {
        val src = read("kotlin/com/arkadia/sonata/ProcessTextActivity.kt")
        assertTrue(
            "ProcessTextActivity must forward the selection to MainActivity",
            src.contains("MainActivity::class.java"),
        )
        assertTrue(
            "and must carry the text with it",
            src.contains("Intent.EXTRA_PROCESS_TEXT"),
        )
    }

    @Test
    fun `the screen reads the forwarded text on both entry paths`() {
        val src = read("kotlin/com/arkadia/sonata/MainActivity.kt")
        assertTrue("MainActivity must read EXTRA_PROCESS_TEXT", src.contains("EXTRA_PROCESS_TEXT"))
        assertTrue(
            "and must handle onNewIntent, or a second selection reuses the activity " +
                "without re-running onCreate and the input stays stale",
            src.contains("onNewIntent"),
        )
    }

    @Test
    fun `the screen is declared singleTop so onNewIntent is reachable`() {
        val manifest = read("AndroidManifest.xml")
        assertTrue(
            "MainActivity needs launchMode=singleTop for onNewIntent to be delivered",
            manifest.contains("android:launchMode=\"singleTop\""),
        )
    }

    /**
     * Negative control for the two-screen defect: the separate player must not come
     * back, because that is what made the handover look blank.
     */
    @Test
    fun `there is no second player screen`() {
        assertTrue(
            "PlayerActivity was removed; reintroducing it restores the blank handover",
            !File(root, "kotlin/com/arkadia/sonata/PlayerActivity.kt").exists(),
        )
        assertTrue(
            "SettingsActivity was removed; settings live in the one screen's More panel",
            !File(root, "kotlin/com/arkadia/sonata/SettingsActivity.kt").exists(),
        )
    }

    @Test
    fun `optional controls live in the collapsible panel, not a second screen`() {
        val layout = read("res/layout/activity_main.xml")
        assertTrue("the More panel must exist", layout.contains("panelMore"))
        assertTrue("and start hidden", layout.contains("android:visibility=\"gone\""))
    }
}
