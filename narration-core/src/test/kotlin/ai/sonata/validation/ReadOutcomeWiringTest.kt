package ai.sonata.validation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The terminal read outcome must actually be written by the service and adopted by
 * the screen.
 *
 * [com.arkadia.sonata.ReadOutcomePolicy] is correct on its own, and every unit test
 * of it passes, while a read that finishes with the screen away still returns as
 * "Ready" — because nothing wrote the record and nothing read it. That is the defect
 * one level down, so it is judged here on the real sources.
 *
 * This lives in narration-core for the reason [PlaybackPhaseWiringTest] gives: the
 * app module's JVM job excludes any file that names an Android-bound class, and a
 * guard that must name the service and the screen cannot run there.
 */
class ReadOutcomeWiringTest {

    @Test
    fun `the service records the outcome and the screen adopts it`() {
        assertEquals(emptyList<String>(), serviceDefects(serviceSource()))
        assertEquals(emptyList<String>(), screenDefects(screenSource()))
    }

    @Test
    fun `the guard detects a service that never records an outcome`() {
        val stripped = serviceSource()
            .replace("ReadOutcome.Status.COMPLETED", "Status.COMPLETED")
            .replace("ReadOutcome.Status.FAILED", "Status.FAILED")
        assertEquals(
            listOf(
                "the service does not record a completed read",
                "the service does not record a failed read",
            ),
            serviceDefects(stripped),
        )
    }

    @Test
    fun `the guard detects a screen that never adopts an outcome`() {
        val stripped = screenSource()
            .replace("            adoptStoredOutcome()\n", "")
            .replace("ReadOutcomePolicy.adopt(", "(")
            .replace("prefs.consumeOutcome()", "null")
        assertEquals(
            listOf(
                "the screen does not consult ReadOutcomePolicy",
                "the screen does not consume a stored outcome",
                "the screen does not adopt an outcome when it binds",
            ),
            screenDefects(stripped),
        )
    }

    private fun serviceDefects(source: String): List<String> = buildList {
        if (!source.contains("prefs.rememberOutcome(currentText, ReadOutcome.Status.COMPLETED")) {
            add("the service does not record a completed read")
        }
        if (!source.contains("ReadOutcome.Status.FAILED")) {
            add("the service does not record a failed read")
        }
    }

    private fun screenDefects(source: String): List<String> = buildList {
        if (!source.contains("ReadOutcomePolicy.adopt(")) {
            add("the screen does not consult ReadOutcomePolicy")
        }
        if (!source.contains("prefs.consumeOutcome()")) {
            add("the screen does not consume a stored outcome")
        }
        // A definition with no call site is the defect this guard exists for: the
        // screen would hold a correct adoption path it never runs. The call is
        // required on the bind path, which is the only place the outcome can arrive.
        if (!source.contains("addClient(uiClient)\n            adoptStoredOutcome()")) {
            add("the screen does not adopt an outcome when it binds")
        }
    }

    private fun serviceSource(): String = source(
        "the playback service",
        listOf(
            "../app/src/main/kotlin/com/arkadia/sonata/SpeechService.kt",
            "app/src/main/kotlin/com/arkadia/sonata/SpeechService.kt",
        ),
    )

    private fun screenSource(): String = source(
        "the reading screen",
        listOf(
            "../app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
            "app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
        ),
    )

    /**
     * Read a source file, naming the paths searched if none is there.
     *
     * Listed explicitly so a move is a visible failure rather than a silent skip.
     */
    private fun source(what: String, candidates: List<String>): String {
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
        requireNotNull(file) {
            "$what source must be readable from the test working directory; " +
                "searched: ${candidates.joinToString()}"
        }
        return file.readText()
    }
}
