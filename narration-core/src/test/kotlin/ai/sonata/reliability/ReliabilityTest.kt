package ai.sonata.reliability

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Stage 5 — recovery, integrity, storage, thermal, audio.
 *
 * Every test here constructs the failure it is testing. A recovery test that never
 * kills anything proves nothing, so the journal tests truncate and corrupt real files.
 */
class ReliabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ================= process-death recovery =================

    @Test
    fun `journal round-trips records`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.BEGIN_CHUNK, "0")
        RecoveryJournal.append(j, 2, RecoveryJournal.Op.CHUNK_WRITTEN, "0")
        val r = RecoveryJournal.replay(j)
        assertEquals(2, r.records.size)
        assertEquals(RecoveryJournal.Op.BEGIN_CHUNK, r.records[0].op)
        assertEquals("0", r.records[0].payload)
        assertTrue(r.isClean)
    }

    @Test
    fun `journal preserves non-ascii payloads`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.BEGIN_CHUNK, "chunk-é-ü-中文")
        val r = RecoveryJournal.replay(j)
        assertEquals("chunk-é-ü-中文", r.records[0].payload)
    }

    @Test
    fun `torn tail is detected and discarded`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.BEGIN_CHUNK, "0")
        RecoveryJournal.append(j, 2, RecoveryJournal.Op.CHUNK_WRITTEN, "0")
        val full = j.readBytes()
        // Simulate a kill mid-write: keep only part of the last record.
        j.writeBytes(full.copyOfRange(0, full.size - 5))
        val r = RecoveryJournal.replay(j)
        assertEquals("the complete record must survive", 1, r.records.size)
        assertTrue("the torn tail must be reported", r.tornTailBytes > 0)
        assertFalse(r.isClean)
    }

    @Test
    fun `corrupt payload stops replay`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.BEGIN_CHUNK, "0")
        RecoveryJournal.append(j, 2, RecoveryJournal.Op.CHUNK_WRITTEN, "0")
        val bytes = j.readBytes()
        // Record layout: 16-byte header (magic, seq, len, crc) then the body. The first
        // record's body starts at byte 16, so byte 17 is its payload — covered by the
        // CRC. (The first version of this test flipped byte 20, which is inside the
        // SECOND record's magic, so it tested a different thing than it claimed.)
        bytes[17] = (bytes[17].toInt() xor 0xFF).toByte()
        j.writeBytes(bytes)
        val r = RecoveryJournal.replay(j)
        assertEquals("replay must stop at the corrupt record", 0, r.records.size)
        assertTrue(r.corruptRecords > 0)
    }

    @Test
    fun `corrupt magic stops replay`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.BEGIN_CHUNK, "0")
        RecoveryJournal.append(j, 2, RecoveryJournal.Op.CHUNK_WRITTEN, "0")
        val bytes = j.readBytes()
        // Byte 20 is inside the second record's magic field.
        bytes[20] = (bytes[20].toInt() xor 0xFF).toByte()
        j.writeBytes(bytes)
        val r = RecoveryJournal.replay(j)
        assertEquals("the first record must survive", 1, r.records.size)
        assertTrue(r.corruptRecords > 0)
    }

    @Test
    fun `missing journal replays empty`() {
        val r = RecoveryJournal.replay(File(tmp.root, "absent.log"))
        assertTrue(r.records.isEmpty())
        assertTrue(r.isClean)
    }

    @Test
    fun `crc32 matches a known value`() {
        // CRC-32 of "123456789" is 0xCBF43926.
        assertEquals(0xCBF43926.toInt(), RecoveryJournal.crc32("123456789".toByteArray()))
    }

    // ---------- recovery decisions ----------

    private fun rows(vararg pairs: Pair<Int, String>) =
        pairs.map { Recovery.ChunkRow(it.first, it.second, 1000, null) }

    @Test
    fun `resumes at a chunk journalled as written but missing from the database`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.BEGIN_CHUNK, "0")
        RecoveryJournal.append(j, 2, RecoveryJournal.Op.CHUNK_WRITTEN, "0")
        RecoveryJournal.append(j, 3, RecoveryJournal.Op.BEGIN_CHUNK, "1")
        RecoveryJournal.append(j, 4, RecoveryJournal.Op.CHUNK_WRITTEN, "1")
        val d = Recovery.decide(RecoveryJournal.replay(j), rows(0 to "COMPLETE"), 3)
        assertTrue(d is Recovery.Decision.Resume)
        assertEquals(1, (d as Recovery.Decision.Resume).fromChunk)
    }

    @Test
    fun `resumes at a chunk left in flight`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.BEGIN_CHUNK, "0")
        RecoveryJournal.append(j, 2, RecoveryJournal.Op.CHUNK_WRITTEN, "0")
        RecoveryJournal.append(j, 3, RecoveryJournal.Op.BEGIN_CHUNK, "1")
        val d = Recovery.decide(RecoveryJournal.replay(j), rows(0 to "COMPLETE"), 3)
        assertTrue(d is Recovery.Decision.Resume)
        assertEquals(1, (d as Recovery.Decision.Resume).fromChunk)
    }

    @Test
    fun `reports complete when journal and database agree`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.CHUNK_WRITTEN, "0")
        RecoveryJournal.append(j, 2, RecoveryJournal.Op.CHUNK_WRITTEN, "1")
        RecoveryJournal.append(j, 3, RecoveryJournal.Op.NARRATION_DONE, "")
        val d = Recovery.decide(RecoveryJournal.replay(j), rows(0 to "COMPLETE", 1 to "COMPLETE"), 2)
        assertTrue(d is Recovery.Decision.Complete)
    }

    @Test
    fun `restarts when the journal says done but the database lost rows`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.NARRATION_DONE, "")
        val d = Recovery.decide(RecoveryJournal.replay(j), rows(0 to "COMPLETE"), 2)
        assertTrue("a lost row must not be reported as complete", d is Recovery.Decision.Restart)
    }

    @Test
    fun `abandons when the journal records an abort`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.NARRATION_ABORTED, "")
        val d = Recovery.decide(RecoveryJournal.replay(j), emptyList(), 3)
        assertTrue(d is Recovery.Decision.Abandon)
    }

    @Test
    fun `resumes at the first incomplete chunk when nothing is in flight`() {
        val d = Recovery.decide(
            RecoveryJournal.Replay(emptyList(), 0, 0, 0),
            rows(0 to "COMPLETE", 1 to "COMPLETE", 2 to "PENDING"), 4
        )
        assertTrue(d is Recovery.Decision.Resume)
        assertEquals(2, (d as Recovery.Decision.Resume).fromChunk)
    }

    @Test
    fun `a torn tail alone does not force a restart`() {
        val j = File(tmp.root, "j.log")
        RecoveryJournal.append(j, 1, RecoveryJournal.Op.CHUNK_WRITTEN, "0")
        val full = j.readBytes()
        j.writeBytes(full.copyOfRange(0, full.size - 3))
        val d = Recovery.decide(RecoveryJournal.replay(j), rows(0 to "COMPLETE", 1 to "PENDING"), 2)
        assertTrue("a torn tail must not discard good work", d is Recovery.Decision.Resume)
        assertEquals(1, (d as Recovery.Decision.Resume).fromChunk)
    }

    // ================= database integrity =================

    private fun narration(len: Int = 100, chunks: Int = 2) =
        IntegrityChecker.NarrationRecord("n1", "d1", len, chunks, "COMPLETE")

    @Test
    fun `healthy narration has no findings`() {
        val root = tmp.newFolder("audio")
        val dir = File(root, "n1").apply { mkdirs() }
        val f0 = File(dir, "0.wav").apply { writeBytes(ByteArray(100)) }
        val f1 = File(dir, "1.wav").apply { writeBytes(ByteArray(100)) }
        val chunks = listOf(
            IntegrityChecker.ChunkRecord("n1", 0, 0, 50, "COMPLETE", "n1/0.wav", null, 100),
            IntegrityChecker.ChunkRecord("n1", 1, 50, 100, "COMPLETE", "n1/1.wav", null, 100)
        )
        val r = IntegrityChecker.checkNarration(narration(), chunks, root, verifyChecksums = false)
        assertTrue("unexpected findings: ${r.findings}", r.isHealthy)
    }

    @Test
    fun `detects a coverage gap`() {
        val root = tmp.newFolder("audio")
        val chunks = listOf(
            IntegrityChecker.ChunkRecord("n1", 0, 0, 40, "COMPLETE", null, null, 0),
            IntegrityChecker.ChunkRecord("n1", 1, 60, 100, "COMPLETE", null, null, 0)
        )
        val r = IntegrityChecker.checkNarration(narration(), chunks, root, verifyChecksums = false)
        assertTrue(r.codes().contains("COVERAGE_GAP"))
    }

    @Test
    fun `detects duplicate offsets`() {
        val root = tmp.newFolder("audio")
        val chunks = listOf(
            IntegrityChecker.ChunkRecord("n1", 0, 0, 60, "COMPLETE", null, null, 0),
            IntegrityChecker.ChunkRecord("n1", 1, 40, 100, "COMPLETE", null, null, 0)
        )
        val r = IntegrityChecker.checkNarration(narration(), chunks, root, verifyChecksums = false)
        assertTrue(r.codes().contains("DUPLICATE_OFFSETS"))
    }

    @Test
    fun `detects duplicate chunk index`() {
        val root = tmp.newFolder("audio")
        val chunks = listOf(
            IntegrityChecker.ChunkRecord("n1", 0, 0, 50, "COMPLETE", null, null, 0),
            IntegrityChecker.ChunkRecord("n1", 0, 50, 100, "COMPLETE", null, null, 0)
        )
        val r = IntegrityChecker.checkNarration(narration(), chunks, root, verifyChecksums = false)
        assertTrue(r.codes().contains("DUPLICATE_INDEX"))
    }

    @Test
    fun `detects missing audio for a complete row`() {
        val root = tmp.newFolder("audio")
        val chunks = listOf(
            IntegrityChecker.ChunkRecord("n1", 0, 0, 100, "COMPLETE", "n1/0.wav", null, 100)
        )
        val r = IntegrityChecker.checkNarration(narration(), chunks, root, verifyChecksums = false)
        assertTrue(r.codes().contains("MISSING_AUDIO"))
    }

    @Test
    fun `detects a checksum mismatch`() {
        val root = tmp.newFolder("audio")
        val dir = File(root, "n1").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(100) { 7 })
        val chunks = listOf(
            IntegrityChecker.ChunkRecord("n1", 0, 0, 100, "COMPLETE", "n1/0.wav", "deadbeef", 100)
        )
        val r = IntegrityChecker.checkNarration(narration(), chunks, root, verifyChecksums = true)
        assertTrue(r.codes().contains("CHECKSUM_MISMATCH"))
    }

    @Test
    fun `detects orphan audio`() {
        val root = tmp.newFolder("audio")
        val dir = File(root, "n1").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(100))
        File(dir, "99.wav").writeBytes(ByteArray(100))
        val chunks = listOf(
            IntegrityChecker.ChunkRecord("n1", 0, 0, 100, "COMPLETE", "n1/0.wav", null, 100)
        )
        val r = IntegrityChecker.checkNarration(narration(), chunks, root, verifyChecksums = false)
        assertTrue(r.codes().contains("ORPHAN_AUDIO"))
    }

    @Test
    fun `detects an interrupted chunk`() {
        val root = tmp.newFolder("audio")
        val chunks = listOf(
            IntegrityChecker.ChunkRecord("n1", 0, 0, 100, "GENERATING", null, null, 0)
        )
        val r = IntegrityChecker.checkNarration(narration(), chunks, root, verifyChecksums = false)
        assertTrue(r.codes().contains("INTERRUPTED_CHUNK"))
    }

    @Test
    fun `schema version older is a warning not an error`() {
        val r = IntegrityChecker.checkSchema(IntegrityChecker.SCHEMA_VERSION - 1)
        assertTrue(r.isHealthy)
        assertTrue(r.codes().contains("SCHEMA_OLD"))
    }

    @Test
    fun `schema version newer is an error`() {
        val r = IntegrityChecker.checkSchema(IntegrityChecker.SCHEMA_VERSION + 1)
        assertFalse(r.isHealthy)
        assertTrue(r.codes().contains("SCHEMA_NEWER"))
    }

    // ================= storage budget =================

    private fun item(id: String, mb: Long, kind: StorageBudget.Kind, age: Long, pinned: Boolean = false) =
        StorageBudget.Item(id, mb * 1024 * 1024, kind, age, pinned)

    @Test
    fun `fits when there is room`() {
        val policy = StorageBudget.Policy(1000L * 1024 * 1024, 100L * 1024 * 1024)
        val d = StorageBudget.plan(listOf(item("a", 100, StorageBudget.Kind.AUDIO, 1)), 50L * 1024 * 1024, policy)
        assertTrue(d.fits)
        assertTrue(d.evict.isEmpty())
    }

    @Test
    fun `evicts audio before voice samples`() {
        val policy = StorageBudget.Policy(1000L * 1024 * 1024, 0)
        val items = listOf(
            item("voice", 400, StorageBudget.Kind.VOICE_SAMPLE, 1),
            item("audio", 400, StorageBudget.Kind.AUDIO, 2)
        )
        val d = StorageBudget.plan(items, 400L * 1024 * 1024, policy)
        assertTrue(d.fits)
        assertEquals(listOf("audio"), d.evict)
    }

    @Test
    fun `evicts least recently used first within a kind`() {
        val policy = StorageBudget.Policy(1000L * 1024 * 1024, 0)
        val items = listOf(
            item("new", 300, StorageBudget.Kind.AUDIO, 100),
            item("old", 300, StorageBudget.Kind.AUDIO, 1)
        )
        val d = StorageBudget.plan(items, 500L * 1024 * 1024, policy)
        assertEquals("old", d.evict.first())
    }

    @Test
    fun `never evicts a pinned item`() {
        val policy = StorageBudget.Policy(1000L * 1024 * 1024, 0)
        val items = listOf(item("keep", 900, StorageBudget.Kind.AUDIO, 1, pinned = true))
        val d = StorageBudget.plan(items, 500L * 1024 * 1024, policy)
        assertFalse(d.fits)
        assertTrue(d.evict.isEmpty())
    }

    @Test
    fun `never evicts documents`() {
        val policy = StorageBudget.Policy(1000L * 1024 * 1024, 0)
        val items = listOf(item("doc", 900, StorageBudget.Kind.DOCUMENT, 1))
        val d = StorageBudget.plan(items, 500L * 1024 * 1024, policy)
        assertFalse(d.fits)
        assertTrue(d.evict.isEmpty())
    }

    @Test
    fun `reports the shortfall when it cannot fit`() {
        val policy = StorageBudget.Policy(1000L * 1024 * 1024, 0)
        val items = listOf(item("audio", 100, StorageBudget.Kind.AUDIO, 1))
        val d = StorageBudget.plan(items, 2000L * 1024 * 1024, policy)
        assertFalse(d.fits)
        assertTrue(d.reason.contains("short"))
    }

    @Test
    fun `reserve floor reduces usable space`() {
        val policy = StorageBudget.Policy(1000L * 1024 * 1024, 200L * 1024 * 1024)
        assertEquals(800L * 1024 * 1024, policy.usableBytes)
    }

    @Test
    fun `volume below reserve is a hard stop`() {
        val policy = StorageBudget.Policy(1000L * 1024 * 1024, 200L * 1024 * 1024)
        assertTrue(StorageBudget.volumeBelowReserve(100L * 1024 * 1024, policy))
        assertFalse(StorageBudget.volumeBelowReserve(300L * 1024 * 1024, policy))
    }

    // ================= model integrity =================

    @Test
    fun `verifies an intact model`() {
        val f = File(tmp.root, "m.bin").apply { writeBytes(ByteArray(1000) { 3 }) }
        val entry = ModelIntegrity.ManifestEntry("m.bin", IntegrityChecker.sha256(f), 1000)
        assertTrue(ModelIntegrity.verify(entry, f) is ModelIntegrity.Verdict.Ok)
    }

    @Test
    fun `detects a missing model`() {
        val entry = ModelIntegrity.ManifestEntry("absent.bin", "abc", 1000)
        assertTrue(ModelIntegrity.verify(entry, File(tmp.root, "absent.bin")) is ModelIntegrity.Verdict.Missing)
    }

    @Test
    fun `detects a truncated model`() {
        val f = File(tmp.root, "m.bin").apply { writeBytes(ByteArray(500)) }
        val entry = ModelIntegrity.ManifestEntry("m.bin", "abc", 1000)
        val v = ModelIntegrity.verify(entry, f)
        assertTrue(v is ModelIntegrity.Verdict.Truncated)
        assertEquals(500L, (v as ModelIntegrity.Verdict.Truncated).actual)
    }

    @Test
    fun `detects a corrupted model of the right length`() {
        val f = File(tmp.root, "m.bin").apply { writeBytes(ByteArray(1000) { 1 }) }
        val entry = ModelIntegrity.ManifestEntry("m.bin", "0".repeat(64), 1000)
        assertTrue(ModelIntegrity.verify(entry, f) is ModelIntegrity.Verdict.Corrupt)
    }

    @Test
    fun `a failed verdict is described in user terms`() {
        val msg = ModelIntegrity.describe(ModelIntegrity.Verdict.Truncated("voice.bin", 1000, 500))
        assertTrue(msg.contains("incomplete"))
        assertFalse("must not leak a stack trace", msg.contains("Exception"))
    }

    @Test
    fun `allOk is false when any verdict fails`() {
        val verdicts = listOf(
            ModelIntegrity.Verdict.Ok("a"),
            ModelIntegrity.Verdict.Missing("b")
        )
        assertFalse(ModelIntegrity.allOk(verdicts))
    }

    // ================= thermal governor =================

    private val cfg = ThermalGovernor.Config()

    @Test
    fun `nominal temperature does not pause`() {
        val a = ThermalGovernor.step(ThermalGovernor.State(), 30.0, 0, cfg)
        assertFalse(a.pause)
        assertEquals(ThermalGovernor.Level.NOMINAL, a.state.level)
    }

    @Test
    fun `hot temperature pauses only after the dwell time`() {
        var s = ThermalGovernor.State()
        val first = ThermalGovernor.step(s, 43.0, 0, cfg)
        assertFalse("must not pause on the first hot reading", first.pause)
        s = first.state
        val second = ThermalGovernor.step(s, 43.0, cfg.pauseDwellMs + 1, cfg)
        assertTrue("must pause after the dwell time", second.pause)
    }

    @Test
    fun `a single hot reading does not pause`() {
        val a = ThermalGovernor.step(ThermalGovernor.State(), 50.0, 0, cfg)
        assertFalse("one reading must not trigger a pause", a.pause)
    }

    @Test
    fun `resume requires the hysteresis margin`() {
        val paused = ThermalGovernor.State(paused = true, level = ThermalGovernor.Level.HOT, sinceMs = 0)
        // Just below the pause threshold but above the resume threshold.
        val a = ThermalGovernor.step(paused, cfg.hotC - 0.5, 100_000, cfg)
        assertFalse("must not resume inside the hysteresis band", a.resume)
    }

    @Test
    fun `resume requires the dwell time`() {
        val paused = ThermalGovernor.State(paused = true, level = ThermalGovernor.Level.HOT, sinceMs = 0)
        val cool = cfg.hotC - cfg.hysteresisC - 1.0
        val early = ThermalGovernor.step(paused, cool, 1000, cfg)
        assertFalse("must not resume before the dwell", early.resume)
        val late = ThermalGovernor.step(early.state, cool, cfg.resumeDwellMs + 2000, cfg)
        assertTrue("must resume after the dwell", late.resume)
    }

    @Test
    fun `governor does not oscillate on a borderline temperature`() {
        // Hold a temperature just above the pause threshold and confirm it pauses once
        // and stays paused rather than flapping.
        var s = ThermalGovernor.State()
        var pauses = 0
        var t = 0L
        repeat(20) {
            val a = ThermalGovernor.step(s, cfg.hotC + 0.1, t, cfg)
            if (a.pause) pauses++
            s = a.state
            t += 1000
        }
        assertEquals("must pause exactly once", 1, pauses)
        assertTrue(s.paused)
    }

    @Test
    fun `critical temperature is classified`() {
        assertEquals(ThermalGovernor.Level.CRITICAL, ThermalGovernor.levelFor(46.0, cfg))
    }

    // ================= audio integrity =================

    private fun writeWav(name: String, samples: DoubleArray, sr: Int = 22050): File {
        val f = File(tmp.root, name)
        ai.sonata.narration.WavIo.write(f, samples, sr)
        return f
    }

    @Test
    fun `valid audio passes`() {
        val tone = DoubleArray(22050) { kotlin.math.sin(2 * Math.PI * 200 * it / 22050.0) * 0.3 }
        val r = AudioIntegrity.check(writeWav("ok.wav", tone))
        assertTrue("expected OK, got ${r.verdict}: ${r.detail}", r.ok)
    }

    @Test
    fun `missing file is reported`() {
        assertEquals(AudioIntegrity.Verdict.MISSING, AudioIntegrity.check(File(tmp.root, "nope.wav")).verdict)
    }

    @Test
    fun `silent audio is rejected`() {
        val r = AudioIntegrity.check(writeWav("silent.wav", DoubleArray(22050)))
        assertEquals(AudioIntegrity.Verdict.SILENT, r.verdict)
    }

    @Test
    fun `truncated audio is rejected`() {
        val tone = DoubleArray(22050) { kotlin.math.sin(2 * Math.PI * 200 * it / 22050.0) * 0.3 }
        val f = writeWav("trunc.wav", tone)
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOfRange(0, bytes.size - 5000))
        assertEquals(AudioIntegrity.Verdict.TRUNCATED, AudioIntegrity.check(f).verdict)
    }

    @Test
    fun `too-short audio is rejected against an expected minimum`() {
        val tone = DoubleArray(2205) { kotlin.math.sin(2 * Math.PI * 200 * it / 22050.0) * 0.3 }
        val r = AudioIntegrity.check(writeWav("short.wav", tone), expectedMinMs = 5000)
        assertEquals(AudioIntegrity.Verdict.TOO_SHORT, r.verdict)
    }

    @Test
    fun `non-wav data is rejected`() {
        val f = File(tmp.root, "junk.wav").apply { writeBytes(ByteArray(200) { 9 }) }
        assertEquals(AudioIntegrity.Verdict.BAD_HEADER, AudioIntegrity.check(f).verdict)
    }
}
