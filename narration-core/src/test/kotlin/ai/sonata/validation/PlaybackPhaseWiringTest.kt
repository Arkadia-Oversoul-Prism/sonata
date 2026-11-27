package ai.sonata.validation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The reading screen must actually use the playback-phase contract.
 *
 * A correct decision object that nothing calls is the defect one level down: every
 * unit test of the policy passes while the screen keeps showing "Ready" for a read
 * that has finished.
 *
 * This lives in the narration-core module rather than beside the policy on purpose.
 * The app module's JVM job derives its scope from class references and excludes any
 * file that names an Android-bound class — including inside a string — so a guard
 * that has to name the screen cannot run there. This module is compiled in full, and
 * its working directory is the module root, which is why the candidates below reach
 * up into `app/`.
 */
class PlaybackPhaseWiringTest {

    @Test
    fun `the reading screen decides its phase with the policy`() {
        val source = readingScreenSource()
        assertEquals(emptyList<String>(), wiringDefects(source))
    }

    @Test
    fun `the wiring guard detects the pre-repair screen`() {
        // Negative control on the real file: strip the wiring back to the shape the
        // screen had before this change, and the same predicate must report it.
        val stripped = readingScreenSource()
            .replace("PlaybackPhasePolicy.decide(", "when {")
            .replace("PlaybackPhase.COMPLETED ->", "")
            .replace("PlaybackPhase.FAILED ->", "")
        assertEquals(
            listOf(
                "the screen does not consult PlaybackPhasePolicy",
                "the screen does not render PlaybackPhase.COMPLETED",
                "the screen does not render PlaybackPhase.FAILED",
            ),
            wiringDefects(stripped),
        )
    }

    private fun wiringDefects(source: String): List<String> = buildList {
        if (!source.contains("PlaybackPhasePolicy.decide(")) {
            add("the screen does not consult PlaybackPhasePolicy")
        }
        if (!source.contains("PlaybackPhase.COMPLETED")) {
            add("the screen does not render PlaybackPhase.COMPLETED")
        }
        if (!source.contains("PlaybackPhase.FAILED")) {
            add("the screen does not render PlaybackPhase.FAILED")
        }
    }

    private fun readingScreenSource(): String {
        val file = READING_SCREEN_CANDIDATES.map { File(it) }.firstOrNull { it.isFile }
        requireNotNull(file) {
            "the reading screen source must be readable from the test working " +
                "directory; searched: ${READING_SCREEN_CANDIDATES.joinToString()}"
        }
        return file.readText()
    }

    private companion object {
        /**
         * Every path the screen can legitimately be at, relative to the module
         * directory the test runs from. Listed explicitly so a move is a visible
         * test failure rather than a silent skip.
         */
        val READING_SCREEN_CANDIDATES = listOf(
            "../app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
            "app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
            "src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
        )
    }
}
