package ai.sonata.validation

import ai.sonata.narration.*
import ai.sonata.privacy.ManifestAudit
import ai.sonata.reliability.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Stage 6 — regression tests for previously fixed defects.
 *
 * Each test names the defect it guards, because a regression test whose purpose is
 * forgotten gets deleted the first time it is inconvenient. These are the defects that
 * were actually found during Stages 2–4, including the ones this stage found in its own
 * first draft — a defect found and fixed is only fixed if something stops it returning.
 */
class RegressionTest {

    // ---- R1: the library had no route to the canvas (Stage 3 reachability gate) ----
    //
    // The gate caught that a user could open a document but never reach the read-along
    // canvas. The structural guarantee is that every analysed sentence carries a source
    // range the canvas can highlight, so a document with sentences always has something
    // to draw. If offsets were ever dropped, the canvas would silently show nothing.

    @Test
    fun `R1 every sentence carries a highlightable source range`() {
        for (passage in Corpus.passages) {
            val d = TextUnderstanding.analyse(passage.text)
            assertTrue("${passage.id} produced no sentences", d.totalSentences > 0)
            for (s in d.sentences) {
                assertTrue("${passage.id}: empty range", s.sourceEnd > s.sourceStart)
                assertEquals(
                    "${passage.id}: range does not point at the sentence",
                    s.text, passage.text.substring(s.sourceStart, s.sourceEnd)
                )
            }
        }
    }

    // ---- R2: SSML `range` destroyed intonation ----
    //
    // The first renderer emitted a `range` attribute for every segment. Measurement
    // showed it collapses the pitch contour to ~0.5 semitones. The regression guard is
    // that the renderer never emits `range` again.

    @Test
    fun `R2 renderer never emits the destructive range attribute`() {
        val plan = StylePlanner.plan(
            TextUnderstanding.analyse(Corpus.byId("prose_01")!!.text),
            DeliveryStyle.DRAMATIC
        )
        val ssml = SsmlRenderer.render(plan)
        assertFalse("the range attribute must never be emitted", ssml.contains("range="))
        assertTrue(ssml.contains("<prosody"))
    }

    // ---- R3: SSML pitch is global, so per-segment pitch did nothing ----
    //
    // The plan carries a per-segment register offset. The engine must render each
    // segment separately for that offset to have any effect; a single-document render
    // would silently flatten every style to the same contour.

    @Test
    fun `R3 engine renders one invocation per segment`() {
        val plan = StylePlanner.plan(
            TextUnderstanding.analyse("One. Two. Three. Four."),
            DeliveryStyle.STORYTELLING
        )
        // The plan has four segments; the engine's contract is one invocation each.
        assertEquals(4, plan.segments.size)

        // Assert the contract by OBSERVING the engine, not by constructing it.
        // Stage 7a finding: the previous version of this test only did
        // `assertNotNull(NarrationPipeline(EspeakEngine()))`, which passes even if
        // the engine renders the whole plan as a single document and every
        // per-segment register offset is silently flattened. A recording engine
        // makes the invocation count an observed fact.
        val recorder = RecordingEngine()
        val outcome = NarrationPipeline(recorder).narrate(
            "One. Two. Three. Four.",
            DeliveryStyle.STORYTELLING,
            File.createTempFile("r3-invocations", ".wav").also { it.deleteOnExit() }
        )
        assertTrue("pipeline must succeed: ${outcome.error}", outcome.ok)
        assertEquals(
            "the engine must be invoked once per planned segment",
            plan.segments.size,
            recorder.plansSeen.size
        )
        // And each invocation must carry exactly one segment, which is what makes a
        // per-segment register offset meaningful.
        recorder.plansSeen.forEach { seen ->
            assertEquals("each invocation renders one segment", 1, seen.segments.size)
        }
        assertEquals(
            "the reported invocation count must match what the engine saw",
            recorder.plansSeen.size,
            outcome.synthesis?.invocations
        )
    }

    /** Records every plan handed to the engine. No audio, no process launch. */
    private class RecordingEngine : SpeechEngine {
        val plansSeen = mutableListOf<ProsodyPlan>()
        override val id: String = "recording"
        override val version: String = "1"
        override fun synthesize(plan: ProsodyPlan, outFile: File, sampleRate: Int): SynthesisResult {
            // Mirror the per-segment contract of the real engine: one call per segment.
            plan.segments.forEach { seg ->
                plansSeen += ProsodyPlan(plan.style, listOf(seg), plan.totalPauseMs, plan.estimatedDurationMs)
            }
            return SynthesisResult(
                ok = true,
                outFile = outFile,
                durationMs = 0,
                sampleRate = sampleRate,
                channels = 1,
                bitsPerSample = 16,
                invocations = plan.segments.size
            )
        }
    }

    // ---- R4: sentence splitter cut at initials and abbreviations ----
    //
    // `substringAfterLast(' ', "")` returned empty for a single-token sentence, so
    // every initial looked like a sentence end and "J. R. R. Tolkien" became four
    // sentences.

    @Test
    fun `R4 initials and abbreviations do not split sentences`() {
        val cases = listOf(
            "J. R. R. Tolkien wrote books. He was patient." to 2,
            "Dr. Smith arrived at 9 a.m. and waited. Nobody came." to 2,
            "Mr. and Mrs. Jones left. They were late." to 2,
            "The U.S. edition sold well. It was reprinted." to 2
        )
        for ((text, expected) in cases) {
            assertEquals("wrong split for: $text", expected, TextUnderstanding.splitSentences(text).size)
        }
    }

    // ---- R5: a decimal point split a sentence ----
    //
    // "3.5" was treated as a sentence boundary because the next character is a digit.

    @Test
    fun `R5 decimals do not split sentences`() {
        assertEquals(1, TextUnderstanding.splitSentences("The value was 3.5 units in total.").size)
        assertEquals(1, TextUnderstanding.splitSentences("It rose 12.4 percent last year.").size)
    }

    // ---- R6: the space between sentences belonged to no segment ----
    //
    // The coverage check found a one-character gap between every pair of sentences,
    // which would leave that character permanently unhighlighted in the canvas.

    @Test
    fun `R6 plan tiles the document with no gaps`() {
        for (passage in Corpus.passages) {
            val plan = StylePlanner.plan(TextUnderstanding.analyse(passage.text), DeliveryStyle.NATURAL)
            val ranges = plan.segments.map { it.sourceStart to it.sourceEnd }.sortedBy { it.first }
            assertEquals("${passage.id}: must start at 0", 0, ranges.first().first)
            assertEquals("${passage.id}: must end at the document end", passage.text.length, ranges.last().second)
            for (i in 1 until ranges.size) {
                assertEquals("${passage.id}: gap at $i", ranges[i - 1].second, ranges[i].first)
            }
        }
    }

    // ---- R7: the pitch tracker reported octave errors ----
    //
    // Taking the global autocorrelation maximum reported 2x the true period, giving
    // implausible 14–26 semitone ranges. The guard is that a synthetic tone of known
    // frequency is measured correctly.

    @Test
    fun `R7 pitch tracker measures a known tone correctly`() {
        val sr = 22050.0
        for (hz in listOf(100.0, 150.0, 220.0)) {
            val frame = DoubleArray((0.06 * sr).toInt()) {
                kotlin.math.sin(2 * Math.PI * hz * it / sr) * 0.5
            }
            val measured = AcousticAnalysis.pitchOf(frame, sr)
            assertNotNull("no pitch found for $hz Hz", measured)
            assertEquals("wrong pitch for $hz Hz", hz, measured!!, hz * 0.05)
        }
    }

    @Test
    fun `R7 pitch tracker rejects noise`() {
        val sr = 22050.0
        val rnd = java.util.Random(42)
        val frame = DoubleArray((0.06 * sr).toInt()) { (rnd.nextDouble() - 0.5) * 0.5 }
        assertNull("noise must not be reported as voiced", AcousticAnalysis.pitchOf(frame, sr))
    }

    // ---- R8: the semitone conversion was guessed, not measured ----
    //
    // The first version assumed 0.25 semitones per espeak pitch step; measurement gave
    // 0.1625. The guard is that the constant matches the calibration.

    @Test
    fun `R8 semitone conversion matches the measured calibration`() {
        assertEquals(0.1625, EspeakEngine.SEMITONES_PER_PITCH_STEP, 0.0001)
        // 6 semitones should be ~37 steps, not 24.
        val steps = EspeakEngine().semitonesToPitchSteps(6.0)
        assertTrue("6 semitones gave $steps steps, expected ~37", steps in 30..45)
    }

    // ---- R9: the rate metric measured voicing, not speed ----
    //
    // Voiced-fraction barely moves with the engine's rate parameter, which made the
    // rate claims untestable. The guard is that the metric is syllables per second of
    // speech, so a faster style must score higher.

    @Test
    fun `R9 rate metric is syllables per second of speech`() {
        val f = File.createTempFile("rate", ".wav")
        try {
            // 2 s of tone with a 0.5 s silent gap: 1.5 s of speech.
            val sr = 22050
            val samples = DoubleArray(sr * 2) { i ->
                val t = i.toDouble() / sr
                if (t in 0.0..0.75 || t in 1.25..2.0) kotlin.math.sin(2 * Math.PI * 200 * t) * 0.3 else 0.0
            }
            WavIo.write(f, samples, sr)
            val d = AcousticAnalysis.analyse(f, expectedSyllables = 9)!!
            // 9 syllables over ~1.5 s of speech is ~6/s, not a voicing fraction near 0.6.
            assertTrue("rate ${d.features.speechRate} should be in syllables/s", d.features.speechRate > 3.0)
        } finally {
            f.delete()
        }
    }

    // ---- R10: pause matching by duration failed for an engine reason ----
    //
    // The engine emits its own trailing silence, so a detected pause is longer than the
    // silence the planner inserted. Matching by placement is unaffected.

    @Test
    fun `R10 pause matching by placement tolerates engine trailing silence`() {
        val found = listOf(
            AcousticAnalysis.Pause(1000.0, 1000.0 + 320.0 + 180.0), // planned 320 + engine 180
            AcousticAnalysis.Pause(3000.0, 3000.0 + 620.0 + 200.0)
        )
        val planned = listOf(1000.0, 3000.0)
        val m = AcousticAnalysis.matchPausesByPlacement(planned, found)
        assertEquals("both boundaries must match", 2, m.matched)
        assertEquals(1.0, m.recall, 0.001)
    }

    // ---- R11: INTERNET permission and backup policy (Stage 3 anchor fix) ----
    //
    // The manifest must not request INTERNET and must disable backup. This is a
    // regression guard on the shipped manifest text.

    @Test
    fun `R11 shipped manifest keeps the privacy policy`() {
        // Stage 7a finding (blocker B4): this test used to fall back to a manifest
        // string literal embedded in the test when the real file was absent. That
        // made it a test of the fixture, not of the product: it passed in exactly
        // the situation it existed to catch. The fallback is gone. If the manifest
        // cannot be found, the test FAILS, because "we could not read the shipped
        // manifest" is not evidence that the shipped manifest is private.
        val manifest = locateShippedManifest()
        assertTrue(
            "the shipped AndroidManifest.xml must be readable from the test working " +
                "directory; searched: ${MANIFEST_CANDIDATES.joinToString()}",
            manifest != null
        )
        val r = ManifestAudit.audit(ManifestAudit.parse(manifest!!.readText()))
        assertTrue("manifest policy failures: ${r.failures.map { it.code }}", r.passed)
    }

    // ---- R14: the offline engine was constructible but no screen invoked it ----
    //
    // EngineFactory could build the production engine, and the engine's own tests
    // proved it saved a narration, but nothing in the app called it — so no user
    // action could produce a stored file. A correct component that nothing
    // constructs is not a feature. This guard is the one that notices if the wiring
    // is removed: the screen must construct the engine, narrate, and route the
    // result through the presenter whose tests pin the "saved" claim.

    @Test
    fun `R14 the screen reaches the offline engine`() {
        val activity = locateSource(MAIN_ACTIVITY_CANDIDATES)
        assertNotNull(
            "MainActivity.kt must be readable from the test working directory; " +
                "searched: ${MAIN_ACTIVITY_CANDIDATES.joinToString()}",
            activity
        )
        val source = activity!!.readText()
        assertTrue(
            "the screen must construct the production engine",
            source.contains("EngineFactory.create(")
        )
        assertTrue("the screen must actually narrate", source.contains(".narrate("))
        assertTrue(
            "the save report must come from the tested presenter",
            source.contains("NarrationPresenter.report(")
        )

        val layout = locateSource(LAYOUT_CANDIDATES)
        assertNotNull(
            "activity_main.xml must be readable from the test working directory; " +
                "searched: ${LAYOUT_CANDIDATES.joinToString()}",
            layout
        )
        assertTrue(
            "the save action must have a control the user can reach",
            layout!!.readText().contains("btnSaveNarration")
        )
    }

    private fun locateSource(candidates: List<String>): File? =
        candidates.map { File(it) }.firstOrNull { it.isFile }

    private fun locateShippedManifest(): File? =
        MANIFEST_CANDIDATES.map { File(it) }.firstOrNull { it.isFile }

    private companion object {
        /**
         * Every path the shipped manifest can legitimately be at, relative to the
         * module directory the test runs from. Listed explicitly so a move is a
         * visible test failure rather than a silent skip.
         */
        val MANIFEST_CANDIDATES = listOf(
            "src/main/AndroidManifest.xml",
            "../app/src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        )

        /** The screen and its layout, relative to the module or the repo root. */
        val MAIN_ACTIVITY_CANDIDATES = listOf(
            "../app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
            "app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt",
        )

        val LAYOUT_CANDIDATES = listOf(
            "../app/src/main/res/layout/activity_main.xml",
            "app/src/main/res/layout/activity_main.xml",
        )
    }

    // ---- R12: a torn journal tail discarded good work ----
    //
    // Recovery must resume from the last good record, not restart, when only the tail
    // is torn.

    @Test
    fun `R12 torn tail does not discard completed chunks`() {
        val dir = File.createTempFile("jrnl", "").let { it.delete(); it.mkdirs(); it }
        try {
            val j = File(dir, "journal.log")
            RecoveryJournal.append(j, 1, RecoveryJournal.Op.CHUNK_WRITTEN, "0")
            RecoveryJournal.append(j, 2, RecoveryJournal.Op.CHUNK_WRITTEN, "1")
            val full = j.readBytes()
            j.writeBytes(full.copyOfRange(0, full.size - 4))
            val rows = listOf(
                Recovery.ChunkRow(0, "COMPLETE", 100, null),
                Recovery.ChunkRow(1, "COMPLETE", 100, null)
            )
            val d = Recovery.decide(RecoveryJournal.replay(j), rows, 3)
            assertTrue("must resume, not restart: $d", d is Recovery.Decision.Resume)
            assertEquals(2, (d as Recovery.Decision.Resume).fromChunk)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- R13: a lost database row was reported as complete ----
    //
    // If the journal says done but the database lost a row, the narration is not
    // complete. Reporting it as complete would ship a gap with no explanation.

    @Test
    fun `R13 a lost row is never reported as complete`() {
        val dir = File.createTempFile("jrnl2", "").let { it.delete(); it.mkdirs(); it }
        try {
            val j = File(dir, "journal.log")
            RecoveryJournal.append(j, 1, RecoveryJournal.Op.NARRATION_DONE, "")
            val d = Recovery.decide(
                RecoveryJournal.replay(j),
                listOf(Recovery.ChunkRow(0, "COMPLETE", 100, null)),
                2
            )
            assertTrue("must restart: $d", d is Recovery.Decision.Restart)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- R14: thermal governor oscillated ----
    //
    // Without hysteresis and dwell, a borderline temperature caused pause/resume
    // flapping. The guard is that a held borderline temperature pauses exactly once.

    @Test
    fun `R14 thermal governor does not flap`() {
        val cfg = ThermalGovernor.Config()
        var s = ThermalGovernor.State()
        var pauses = 0
        var resumes = 0
        var t = 0L
        repeat(30) {
            val a = ThermalGovernor.step(s, cfg.hotC + 0.1, t, cfg)
            if (a.pause) pauses++
            if (a.resume) resumes++
            s = a.state
            t += 1000
        }
        assertEquals("must pause exactly once", 1, pauses)
        assertEquals("must not resume while still hot", 0, resumes)
    }

    // ---- R15: a short export looked like success ----
    //
    // The SAF export must treat a short write as failure.

    @Test
    fun `R15 short audio is rejected by integrity check`() {
        val f = File.createTempFile("short", ".wav")
        try {
            val sr = 22050
            val tone = DoubleArray(sr) { kotlin.math.sin(2 * Math.PI * 200 * it / sr) * 0.3 }
            WavIo.write(f, tone, sr)
            val bytes = f.readBytes()
            f.writeBytes(bytes.copyOfRange(0, bytes.size - 4000))
            assertEquals(AudioIntegrity.Verdict.TRUNCATED, AudioIntegrity.check(f).verdict)
        } finally {
            f.delete()
        }
    }

    // ---- R16: emphasis markers leaked into the spoken text ----
    //
    // "**very**" must be spoken as "very", not read as asterisks.

    @Test
    fun `R16 emphasis markers are stripped from spoken text`() {
        val plan = StylePlanner.plan(
            TextUnderstanding.analyse("This is **very** important and *quite* clear."),
            DeliveryStyle.NATURAL
        )
        val spoken = plan.segments.joinToString(" ") { it.text }
        assertFalse("asterisks leaked into speech: $spoken", spoken.contains("*"))
        assertTrue(spoken.contains("very"))
        assertTrue(spoken.contains("quite"))
    }

    // ---- R17: snake_case identifiers were read as emphasis ----
    //
    // "read_files" must not be treated as italic markup.

    @Test
    fun `R17 snake_case is not emphasis`() {
        val (text, level) = TextUnderstanding.extractEmphasis("Call read_files then write_files.")
        assertEquals(Emphasis.NONE, level)
        assertEquals("Call read_files then write_files.", text)
    }

    // ---- R18: XML special characters broke the SSML ----
    //
    // A document containing & or < must not produce invalid markup.

    @Test
    fun `R18 xml special characters are escaped`() {
        val plan = StylePlanner.plan(
            TextUnderstanding.analyse("Allen & Unwin published it. The tag was <b>bold</b>."),
            DeliveryStyle.NATURAL
        )
        val ssml = SsmlRenderer.render(plan)
        assertTrue(ssml.contains("&amp;"))
        assertFalse("raw ampersand would break the markup", Regex("&(?!amp;|lt;|gt;|quot;|apos;)").containsMatchIn(ssml))
        assertFalse(ssml.contains("<b>"))
    }
}
