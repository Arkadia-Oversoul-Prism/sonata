package ai.sonata.validation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The service must attribute a terminal event to what is actually in flight.
 *
 * A correct policy that nothing calls is the defect one level down: every unit test
 * of [com.arkadia.sonata.UtteranceAttributionPolicy] passes while the service keeps
 * deciding from a boolean, and a voice sample that superseded a read is still
 * recorded as the passage's failure.
 *
 * This lives in the narration-core module rather than beside the policy for the same
 * reason as `PlaybackPhaseWiringTest`: the app module's JVM job derives its scope from
 * class references and excludes any file that names an Android-bound class — including
 * inside a string — so a guard that names the service cannot run there. This module is
 * compiled in full, and its working directory is the module root, which is why the
 * candidates below reach up into `app/`.
 */
class UtteranceAttributionWiringTest {

    @Test
    fun `the service attributes a terminal event to what is in flight`() {
        assertEquals(emptyList<String>(), wiringDefects(serviceSource()))
    }

    @Test
    fun `the wiring guard detects the pre-repair service`() {
        // Negative control on the real file: restore the two shapes this change
        // removed — a bare "a read is in progress" flag, and terminal records decided
        // from it — and the same predicate must report every one of them.
        val repaired = serviceSource()
        val stripped = repaired
            .replace("private var inFlight: InFlightUtterance = InFlightUtterance.NONE", "private var reading = false")
            .replace("inFlight = InFlightUtterance.READ", "reading = true")
            .replace("inFlight = InFlightUtterance.PREVIEW", "")
            .replace(
                "UtteranceAttributionPolicy.onComplete(inFlight) == TerminalRecord.COMPLETION",
                "reading",
            )
            .replace(
                "UtteranceAttributionPolicy.onTerminalError(inFlight) == TerminalRecord.FAILURE",
                "reading",
            )

        // The control is only meaningful if it actually rewrote something.
        require(stripped != repaired) {
            "the negative control did not match the service source; the guard would " +
                "silently stop discriminating"
        }

        assertEquals(
            listOf(
                "a dispatched passage is not recorded as the read in flight",
                "a dispatched sample is not recorded as the sample in flight",
                "completion is not decided by UtteranceAttributionPolicy.onComplete",
                "a terminal error is not decided by UtteranceAttributionPolicy.onTerminalError",
                "the service still holds a bare 'reading' flag",
            ),
            wiringDefects(stripped),
        )
    }

    private fun wiringDefects(source: String): List<String> = buildList {
        if (!source.contains("inFlight = InFlightUtterance.READ")) {
            add("a dispatched passage is not recorded as the read in flight")
        }
        if (!source.contains("inFlight = InFlightUtterance.PREVIEW")) {
            add("a dispatched sample is not recorded as the sample in flight")
        }
        // Not merely called: the result must be compared against the record that
        // permits the write, or the call is a verdict nothing acts on.
        if (!source.contains(
                "UtteranceAttributionPolicy.onComplete(inFlight) == TerminalRecord.COMPLETION"
            )
        ) {
            add("completion is not decided by UtteranceAttributionPolicy.onComplete")
        }
        if (!source.contains(
                "UtteranceAttributionPolicy.onTerminalError(inFlight) == TerminalRecord.FAILURE"
            )
        ) {
            add("a terminal error is not decided by UtteranceAttributionPolicy.onTerminalError")
        }
        if (source.contains("private var reading")) {
            add("the service still holds a bare 'reading' flag")
        }
    }

    private fun serviceSource(): String {
        val file = SERVICE_CANDIDATES.map { File(it) }.firstOrNull { it.isFile }
        requireNotNull(file) {
            "the speech service source must be readable from the test working " +
                "directory; searched: ${SERVICE_CANDIDATES.joinToString()}"
        }
        return file.readText()
    }

    private companion object {
        /**
         * Every path the service can legitimately be at, relative to the module
         * directory the test runs from. Listed explicitly so a move is a visible
         * test failure rather than a silent skip.
         */
        val SERVICE_CANDIDATES = listOf(
            "../app/src/main/kotlin/com/arkadia/sonata/SpeechService.kt",
            "app/src/main/kotlin/com/arkadia/sonata/SpeechService.kt",
            "src/main/kotlin/com/arkadia/sonata/SpeechService.kt",
        )
    }
}
