package ai.sonata.validation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A terminal event must reach the line it belongs to, not only the record it belongs to.
 *
 * `UtteranceAttributionWiringTest` proves the *stored* outcome is attributed to what is
 * in flight. Correcting that alone left the *visible* outcome wrong: every terminal
 * event still went to `Client.onError` / `Client.onComplete`, so auditioning a voice
 * painted the read's line as "the read stopped" (or, on success, as finished) for a
 * passage the user had merely interrupted.
 *
 * Two independent defects are judged here, because a correct policy and a correct
 * service are still not a correct screen:
 *
 *  1. the service must deliver by the policy's surface, and
 *  2. both bound clients must implement the sample's line, and must not fold it back
 *     into the read's facts.
 *
 * Lives in narration-core for the same reason as its sibling: the app module's JVM job
 * excludes any source that names an Android-bound class, so a guard that names the
 * service or the screens cannot run there.
 */
class LiveLineAttributionWiringTest {

    @Test
    fun `the service delivers a terminal event to the surface the policy names`() {
        assertEquals(emptyList<String>(), serviceDefects(serviceSource()))
    }

    @Test
    fun `the wiring guard detects the pre-repair service`() {
        // Negative control on the real file: restore the two shapes this change
        // removed — every terminal event delivered to every client regardless of what
        // was in flight — and the same predicate must report each of them.
        val repaired = serviceSource()
        val stripped = repaired
            .replace(
                "surface == LiveSurface.PREVIEW -> it.onPreviewEnd(message)",
                "surface == LiveSurface.PREVIEW -> it.onError(message ?: \"\")",
            )
            .replace(
                "val surface = UtteranceAttributionPolicy.liveSurface(inFlight)",
                "val surface = LiveSurface.READ",
            )
            .replace(
                "notifyTerminal(surface, msg)",
                "clients.forEach { it.onError(msg) }",
            )
            .replace(
                "notifyTerminal(surface, message = null)",
                "clients.forEach { it.onComplete() }",
            )

        require(stripped != repaired) {
            "the negative control did not match the service source; the guard would " +
                "silently stop discriminating"
        }

        assertEquals(
            listOf(
                "a sample's end is not delivered to the sample's line",
                "the surface is not decided by UtteranceAttributionPolicy.liveSurface",
                "the read's completion is not delivered by the attribution rule",
                "a failure is not delivered by the attribution rule",
                "the service still delivers every failure to the read's line",
                "the service still delivers every completion to the read's line",
            ).sorted(),
            serviceDefects(stripped).sorted(),
        )
    }

    @Test
    fun `both bound clients implement the sample's line`() {
        assertEquals(emptyList<String>(), screenDefects(screenSource()))
        assertEquals(emptyList<String>(), overlayDefects(overlaySource()))
    }

    @Test
    fun `the wiring guard detects a client that folds the sample back into the read`() {
        // Negative control: the two shapes a screen falls back into — no sample line at
        // all, and one that paints the read's terminal facts anyway.
        val repaired = screenSource()
        val absent = repaired.replace(SAMPLE_LINE_SIGNATURE, "override fun onPreviewStart(voiceId: String) {")
        require(absent != repaired) { "the negative control did not match the screen source" }
        assertEquals(
            listOf("the screen does not implement the sample's line"),
            screenDefects(absent),
        )

        val folded = repaired.replace(
            "$SAMPLE_LINE_SIGNATURE {\n            if (message != null) showNotice(message)",
            "$SAMPLE_LINE_SIGNATURE {\n            readFailure = message\n            readCompleted = false\n            if (message != null) showNotice(message)",
        )
        require(folded != repaired) { "the negative control did not match the screen source" }
        assertEquals(
            listOf(
                "the screen paints the read as failed from the sample's end",
                "the screen paints the read as not-completed from the sample's end",
            ),
            screenDefects(folded),
        )
    }

    private fun serviceDefects(source: String): List<String> = buildList {
        if (!source.contains("surface == LiveSurface.PREVIEW -> it.onPreviewEnd(message)")) {
            add("a sample's end is not delivered to the sample's line")
        }
        // The surface must come from the policy, or the dispatch is a decision nothing
        // makes: a hardcoded surface at a call site is the pre-repair shape wearing the
        // new name.
        if (!source.contains("val surface = UtteranceAttributionPolicy.liveSurface(inFlight)")) {
            add("the surface is not decided by UtteranceAttributionPolicy.liveSurface")
        }
        if (source.contains("notifyTerminal(LiveSurface.")) {
            add("a terminal event is delivered to a hardcoded surface, not the policy's")
        }
        if (!source.contains("notifyTerminal(surface, message = null)")) {
            add("the read's completion is not delivered by the attribution rule")
        }
        if (!source.contains("notifyTerminal(surface, msg)")) {
            add("a failure is not delivered by the attribution rule")
        }
        // The pre-repair delivery shape, asserted absent rather than merely unreferenced.
        if (source.contains("clients.forEach { it.onError(msg) }")) {
            add("the service still delivers every failure to the read's line")
        }
        if (source.contains("clients.forEach { it.onComplete() }")) {
            add("the service still delivers every completion to the read's line")
        }
    }

    private fun screenDefects(source: String): List<String> = buildList {
        val body = methodBody(source, SAMPLE_LINE_SIGNATURE)
        if (body == null) {
            add("the screen does not implement the sample's line")
            return@buildList
        }
        if (body.contains("readFailure")) {
            add("the screen paints the read as failed from the sample's end")
        }
        if (body.contains("readCompleted")) {
            add("the screen paints the read as not-completed from the sample's end")
        }
    }

    private fun overlayDefects(source: String): List<String> = buildList {
        val body = methodBody(source, SAMPLE_LINE_SIGNATURE)
        if (body == null) {
            add("the floating player does not implement the sample's line")
            return@buildList
        }
        // The window represents the passage, so a sample ending must not dismiss it.
        if (body.contains("stopSelf")) {
            add("the floating player dismisses itself when a sample ends")
        }
    }

    /** The text between a method's braces, or null when the method is absent. */
    private fun methodBody(source: String, signature: String): String? {
        val start = source.indexOf(signature)
        if (start < 0) return null
        val open = source.indexOf('{', start + signature.length)
        if (open < 0) return null
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        return null
    }

    private fun serviceSource(): String = readFirst(SERVICE_CANDIDATES, "speech service")
    private fun screenSource(): String = readFirst(SCREEN_CANDIDATES, "main screen")
    private fun overlaySource(): String = readFirst(OVERLAY_CANDIDATES, "floating player")

    private fun readFirst(candidates: List<String>, what: String): String {
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
        requireNotNull(file) {
            "the $what source must be readable from the test working directory; " +
                "searched: ${candidates.joinToString()}"
        }
        return file.readText()
    }

    private companion object {
        const val SAMPLE_LINE_SIGNATURE = "override fun onPreviewEnd(message: String?)"

        /**
         * Every path each source can legitimately be at, relative to the module
         * directory the test runs from. Listed explicitly so a move is a visible test
         * failure rather than a silent skip.
         */
        val SERVICE_CANDIDATES = listOf(
            "../app/src/main/kotlin/com/arkadia/sonata/SpeechService.kt",
            "app/src/main/kotlin/com/arkadia/sonata/SpeechService.kt",
            "src/main/kotlin/com/arkadia/sonata/SpeechService.kt",
        )
        val SCREEN_CANDIDATES = listOf(
            "../app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
            "app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
            "src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
        )
        val OVERLAY_CANDIDATES = listOf(
            "../app/src/main/kotlin/com/arkadia/sonata/FloatingOverlayService.kt",
            "app/src/main/kotlin/com/arkadia/sonata/FloatingOverlayService.kt",
            "src/main/kotlin/com/arkadia/sonata/FloatingOverlayService.kt",
        )
    }
}
