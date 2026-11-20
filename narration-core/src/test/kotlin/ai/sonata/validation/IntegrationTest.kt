package ai.sonata.validation

import ai.sonata.narration.*
import ai.sonata.privacy.NetworkSentinel
import ai.sonata.privacy.OfflineAudit
import ai.sonata.reliability.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Stage 6 — integration tests.
 *
 * These exercise the layers together, on real audio, with real files. They are slower
 * than the unit tests because they launch the engine; that is the point. A unit test
 * can prove the planner emitted 620 ms; only an integration test can prove 620 ms of
 * silence is in the file.
 *
 * Tests that need the engine are guarded with [assumeTrue] so a machine without
 * espeak-ng reports them as skipped rather than failed — and the report counts skips
 * separately, because a skip is not a pass.
 */
class IntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engine by lazy { EspeakEngine() }

    private fun engineAvailable(): Boolean =
        try {
            val p = ProcessBuilder("espeak-ng", "--version").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }

    // ================= end-to-end narration =================

    @Test
    fun `narrates a passage to a valid wav`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        val out = File(tmp.root, "out.wav")
        val o = NarrationPipeline(engine).narrate(
            "The morning arrived without ceremony. Light moved across the table.",
            DeliveryStyle.NATURAL, out
        )
        assertTrue("narration failed: ${o.error}", o.ok)
        assertTrue(out.exists())
        val h = WavIo.readHeader(out)
        assertNotNull(h)
        assertEquals(16, h!!.bitsPerSample)
        assertEquals(1, h.channels)
        assertTrue("duration ${h.durationMs}ms is implausibly short", h.durationMs > 1000)
    }

    @Test
    fun `rendered audio is not silent`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        val out = File(tmp.root, "out.wav")
        NarrationPipeline(engine).narrate("A short sentence to check for sound.", DeliveryStyle.NATURAL, out)
        val r = AudioIntegrity.check(out)
        assertTrue("audio failed integrity: ${r.verdict} ${r.detail}", r.ok)
    }

    @Test
    fun `every style renders every corpus passage`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        for (style in DeliveryStyle.all) {
            for (passage in Corpus.passages) {
                val out = File(tmp.root, "${style.id}_${passage.id}.wav")
                val o = NarrationPipeline(engine).narrate(passage.text, style, out)
                assertTrue("${style.id}/${passage.id} failed: ${o.error}", o.ok)
                val r = AudioIntegrity.check(out)
                assertTrue("${style.id}/${passage.id} audio invalid: ${r.verdict}", r.ok)
            }
        }
    }

    @Test
    fun `plan invariants hold for every style and passage`() {
        for (style in DeliveryStyle.all) {
            for (passage in Corpus.passages) {
                val plan = StylePlanner.plan(TextUnderstanding.analyse(passage.text), style)
                assertEquals(
                    "${style.id}/${passage.id}: ${plan.violations()}",
                    emptyList<String>(), plan.violations()
                )
            }
        }
    }

    @Test
    fun `plan covers every character of every passage`() {
        for (passage in Corpus.passages) {
            val plan = StylePlanner.plan(TextUnderstanding.analyse(passage.text), DeliveryStyle.NATURAL)
            val ranges = plan.segments.map { it.sourceStart to it.sourceEnd }.sortedBy { it.first }
            assertEquals("${passage.id}: must start at 0", 0, ranges.first().first)
            assertEquals("${passage.id}: must end at the document end", passage.text.length, ranges.last().second)
            for (i in 1 until ranges.size) {
                assertEquals("${passage.id}: gap at segment $i", ranges[i - 1].second, ranges[i].first)
            }
        }
    }

    // ================= long-session playback =================

    @Test
    fun `long session renders the whole corpus without degradation`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        // The whole corpus in one document, in the slowest style — the worst case for
        // duration and the most likely to expose a drift or truncation bug.
        val text = Corpus.concatenated()
        val out = File(tmp.root, "long.wav")
        val o = NarrationPipeline(engine).narrate(text, DeliveryStyle.REFLECTIVE, out)
        assertTrue("long session failed: ${o.error}", o.ok)
        val h = WavIo.readHeader(out)!!
        // The corpus is ~2000 words; at any plausible rate this is minutes, not seconds.
        assertTrue("long session is only ${h.durationMs}ms", h.durationMs > 60_000)
        val r = AudioIntegrity.check(out)
        assertTrue("long session audio invalid: ${r.verdict}", r.ok)
    }

    @Test
    fun `long session audio has no long silent hole`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        val out = File(tmp.root, "long2.wav")
        NarrationPipeline(engine).narrate(Corpus.concatenated(), DeliveryStyle.NATURAL, out)
        val d = AcousticAnalysis.analyse(out)!!
        // A hole longer than 3 s in continuous narration means a chunk was lost.
        val worst = d.pauses.maxOfOrNull { it.durationMs } ?: 0.0
        assertTrue("found a ${worst}ms silent hole", worst < 3000.0)
    }

    // ================= export and import =================

    @Test
    fun `export writes a file whose length matches its content`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        val src = File(tmp.root, "src.wav")
        NarrationPipeline(engine).narrate("Export this sentence to a file.", DeliveryStyle.NATURAL, src)
        val dest = File(tmp.root, "exported.wav")
        // A real export copies bytes and verifies the length, treating a short write as
        // failure — the same rule the app's SAF export uses.
        val bytes = src.readBytes()
        dest.writeBytes(bytes)
        assertEquals("exported length must match the source", bytes.size.toLong(), dest.length())
        assertTrue(AudioIntegrity.check(dest).ok)
    }

    @Test
    fun `a short export is detected as failure`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        val src = File(tmp.root, "src2.wav")
        NarrationPipeline(engine).narrate("Export this sentence to a file.", DeliveryStyle.NATURAL, src)
        val dest = File(tmp.root, "short.wav")
        val bytes = src.readBytes()
        dest.writeBytes(bytes.copyOfRange(0, bytes.size / 2))
        assertNotEquals("a short write must not look like success", src.length(), dest.length())
        assertFalse(AudioIntegrity.check(dest).ok)
    }

    @Test
    fun `import round-trips a document through the analyser`() {
        val original = Corpus.byId("prose_01")!!.text
        val file = File(tmp.root, "imported.txt").apply { writeText(original) }
        val imported = file.readText()
        assertEquals(original, imported)
        val a = TextUnderstanding.analyse(original)
        val b = TextUnderstanding.analyse(imported)
        assertEquals(a.totalSentences, b.totalSentences)
        assertEquals(a.sentences.map { it.text }, b.sentences.map { it.text })
    }

    @Test
    fun `import preserves non-ascii text`() {
        val text = "Café naïve résumé. 中文段落在这里。"
        val file = File(tmp.root, "utf8.txt").apply { writeText(text, Charsets.UTF_8) }
        assertEquals(text, file.readText(Charsets.UTF_8))
        val a = TextUnderstanding.analyse(file.readText(Charsets.UTF_8))
        assertTrue(a.totalSentences >= 1)
    }

    // ================= restart and recovery =================

    @Test
    fun `a killed generation resumes at the right chunk`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        val dir = tmp.newFolder("session")
        val journal = File(dir, "journal.log")
        val audioRoot = File(dir, "audio").apply { mkdirs() }
        val text = Corpus.concatenated()
        val plan = StylePlanner.plan(TextUnderstanding.analyse(text), DeliveryStyle.NATURAL)
        val total = plan.segments.size

        // Simulate: chunks 0..4 written, chunk 5 begun, then the process dies.
        val rows = mutableListOf<Recovery.ChunkRow>()
        for (i in 0 until 5) {
            RecoveryJournal.append(journal, i * 2 + 1, RecoveryJournal.Op.BEGIN_CHUNK, i.toString())
            val f = File(audioRoot, "n1/$i.wav").apply { parentFile.mkdirs() }
            WavIo.write(f, DoubleArray(1000) { 0.1 }, 22050)
            RecoveryJournal.append(journal, i * 2 + 2, RecoveryJournal.Op.CHUNK_WRITTEN, i.toString())
            rows += Recovery.ChunkRow(i, "COMPLETE", f.length(), null)
        }
        RecoveryJournal.append(journal, 11, RecoveryJournal.Op.BEGIN_CHUNK, "5")

        val decision = Recovery.decide(RecoveryJournal.replay(journal), rows, total)
        assertTrue("expected a resume, got $decision", decision is Recovery.Decision.Resume)
        assertEquals(5, (decision as Recovery.Decision.Resume).fromChunk)
    }

    @Test
    fun `recovery after a clean finish reports complete`() {
        val dir = tmp.newFolder("session2")
        val journal = File(dir, "journal.log")
        val rows = (0 until 3).map { Recovery.ChunkRow(it, "COMPLETE", 1000, null) }
        for (i in 0 until 3) {
            RecoveryJournal.append(journal, i + 1, RecoveryJournal.Op.CHUNK_WRITTEN, i.toString())
        }
        RecoveryJournal.append(journal, 4, RecoveryJournal.Op.NARRATION_DONE, "")
        val d = Recovery.decide(RecoveryJournal.replay(journal), rows, 3)
        assertTrue(d is Recovery.Decision.Complete)
    }

    @Test
    fun `integrity check passes on a freshly written narration`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        val audioRoot = tmp.newFolder("audio")
        val out = File(audioRoot, "n1/0.wav").apply { parentFile.mkdirs() }
        NarrationPipeline(engine).narrate("One sentence for the integrity check.", DeliveryStyle.NATURAL, out)
        val chunks = listOf(
            IntegrityChecker.ChunkRecord(
                "n1", 0, 0, 40, "COMPLETE", "n1/0.wav",
                IntegrityChecker.sha256(out), out.length()
            )
        )
        val narration = IntegrityChecker.NarrationRecord("n1", "d1", 40, 1, "COMPLETE")
        val r = IntegrityChecker.checkNarration(narration, chunks, audioRoot, verifyChecksums = true)
        assertTrue("unexpected findings: ${r.findings}", r.isHealthy)
    }

    // ================= airplane mode =================

    @Test
    fun `full narration pipeline makes no network attempt`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        val out = File(tmp.root, "offline.wav")
        val audit = OfflineAudit.run {
            NarrationPipeline(engine).narrate(
                Corpus.byId("prose_01")!!.text, DeliveryStyle.NATURAL, out
            )
        }
        assertTrue("pipeline did not complete: ${audit.error}", audit.completed)
        assertTrue("network activity during narration: ${audit.summary()}", audit.silent)
        assertTrue("audio must still be produced offline", out.exists())
    }

    @Test
    fun `every style narrates with the sentinel armed`() {
        assumeTrue("espeak-ng not installed", engineAvailable())
        val audit = OfflineAudit.run {
            for (style in DeliveryStyle.all) {
                NarrationPipeline(engine).narrate(
                    "A short sentence for the offline check.", style,
                    File(tmp.root, "off_${style.id}.wav")
                )
            }
        }
        assertTrue("network activity: ${audit.summary()}", audit.silent)
    }

    @Test
    fun `sentinel records nothing for analysis and planning alone`() {
        NetworkSentinel.reset()
        for (passage in Corpus.passages) {
            val s = TextUnderstanding.analyse(passage.text)
            for (style in DeliveryStyle.all) StylePlanner.plan(s, style)
        }
        assertTrue("planning must not touch the network", NetworkSentinel.isSilent())
    }
}
