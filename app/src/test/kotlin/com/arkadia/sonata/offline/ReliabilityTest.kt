package com.arkadia.sonata.offline

import com.arkadia.sonata.data.Narration
import com.arkadia.sonata.data.NarrationStatus
import com.arkadia.sonata.export.AudioFormat
import com.arkadia.sonata.export.CompositeEncoder
import com.arkadia.sonata.export.DirectoryFileStore
import com.arkadia.sonata.export.FileAudioExporter
import com.arkadia.sonata.export.WavEncoder
import com.arkadia.sonata.export.WavWriter
import com.arkadia.sonata.reliability.BatchExporter
import com.arkadia.sonata.reliability.BatchItemOutcome
import com.arkadia.sonata.reliability.CrashRecovery
import com.arkadia.sonata.reliability.DeviceValidationHarness
import com.arkadia.sonata.reliability.StorageAccountant
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 — export and reliability. */
class ReliabilityTest {

    private fun tempDir(): File = Files.createTempDirectory("sonata-rel").toFile()

    private fun narration(
        id: Long,
        status: NarrationStatus = NarrationStatus.COMPLETE,
        audioPath: String? = "/tmp/$id.wav",
        reason: String? = null,
    ) = Narration(
        id = id,
        documentId = null,
        title = "Narration $id",
        audioPath = audioPath,
        voiceProfileId = null,
        modelVersion = "test@1.0",
        durationMs = 1000,
        createdAt = 0,
        playbackPositionMs = 0,
        status = status,
        errorReason = reason,
        favorite = false,
    )

    // ── Batch export ─────────────────────────────────────────────────────────

    @Test
    fun `a batch exports every playable narration`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val out = tempDir()
        val exporter = FileAudioExporter(store, CompositeEncoder(listOf(WavEncoder())), out)
        val batch = BatchExporter(exporter)
        val narrations = (1L..3L).map { id ->
            store.write(id, WavWriter.wrap(ByteArray(4800), 24_000))
            narration(id, audioPath = store.canonicalPath(id).absolutePath)
        }

        val report = batch.exportAll(narrations, AudioFormat.WAV)
        assertEquals(3, report.exported.size)
        assertTrue(report.allSucceeded)
        assertTrue(report.totalBytes > 0)
    }

    @Test
    fun `a failure on one item does not abandon the rest`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val out = tempDir()
        val exporter = FileAudioExporter(store, CompositeEncoder(listOf(WavEncoder())), out)
        val batch = BatchExporter(exporter)
        // The middle one has no audio on disk, so it fails; the others must not.
        store.write(1L, WavWriter.wrap(ByteArray(4800), 24_000))
        store.write(3L, WavWriter.wrap(ByteArray(4800), 24_000))
        val narrations = listOf(
            narration(1L, audioPath = store.canonicalPath(1L).absolutePath),
            narration(2L, audioPath = store.canonicalPath(2L).absolutePath),
            narration(3L, audioPath = store.canonicalPath(3L).absolutePath),
        )

        val report = batch.exportAll(narrations, AudioFormat.WAV)
        assertEquals(2, report.exported.size)
        assertEquals(1, report.failed.size)
        assertFalse(report.allSucceeded)
    }

    @Test
    fun `an unfinished narration is skipped, not attempted`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val exporter = FileAudioExporter(store, CompositeEncoder(listOf(WavEncoder())), tempDir())
        val batch = BatchExporter(exporter)
        val report = batch.exportAll(
            listOf(narration(1L, status = NarrationStatus.GENERATING)),
            AudioFormat.WAV,
        )
        assertEquals(1, report.skipped.size)
        assertTrue(report.skipped.first().reason.contains("Not finished"))
    }

    @Test
    fun `the batch summary counts each outcome`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val exporter = FileAudioExporter(store, CompositeEncoder(listOf(WavEncoder())), tempDir())
        val batch = BatchExporter(exporter)
        store.write(1L, WavWriter.wrap(ByteArray(4800), 24_000))
        val report = batch.exportAll(
            listOf(
                narration(1L, audioPath = store.canonicalPath(1L).absolutePath),
                narration(2L, status = NarrationStatus.FAILED),
            ),
            AudioFormat.WAV,
        )
        assertTrue(report.summary().contains("1 exported"))
        assertTrue(report.summary().contains("1 skipped"))
    }

    // ── Storage accounting ───────────────────────────────────────────────────

    @Test
    fun `storage is accounted per narration`() {
        val store = DirectoryFileStore(tempDir())
        val accountant = StorageAccountant(store)
        store.write(1L, ByteArray(1000))
        store.write(2L, ByteArray(2000))
        val report = accountant.report(
            listOf(narration(1L), narration(2L)),
            knownIds = setOf(1L, 2L),
        )
        assertEquals(3000L, report.totalBytes)
        assertEquals(2, report.narrationCount)
        assertEquals(2000L, report.largestBytes)
        assertEquals(0, report.orphanCount)
    }

    @Test
    fun `an orphaned file is found`() {
        // Audio on disk with no record: the residue of a crash between the two
        // deletions the coordinator performs together.
        val store = DirectoryFileStore(tempDir())
        val accountant = StorageAccountant(store)
        store.write(1L, ByteArray(1000))
        store.write(9L, ByteArray(500)) // no record for 9
        val orphans = accountant.findOrphans(knownIds = setOf(1L))
        assertEquals(1, orphans.size)
        assertEquals(9L, orphans.first().first)
    }

    @Test
    fun `cleaning orphans reclaims the space`() {
        val store = DirectoryFileStore(tempDir())
        val accountant = StorageAccountant(store)
        store.write(1L, ByteArray(1000))
        store.write(9L, ByteArray(500))
        val result = accountant.cleanOrphans(knownIds = setOf(1L))
        assertEquals(1, result.removed)
        assertEquals(500L, result.bytesReclaimed)
        assertTrue(store.exists(1L))
        assertFalse(store.exists(9L))
    }

    @Test
    fun `a clean library reports nothing to clean`() {
        val store = DirectoryFileStore(tempDir())
        val accountant = StorageAccountant(store)
        store.write(1L, ByteArray(1000))
        val result = accountant.cleanOrphans(knownIds = setOf(1L))
        assertEquals(0, result.removed)
        assertTrue(result.summary().contains("Nothing"))
    }

    @Test
    fun `byte counts are human readable`() {
        assertEquals("1.0 KB", StorageAccountant.humanBytes(1024))
        assertEquals("1.0 MB", StorageAccountant.humanBytes(1024L * 1024))
        assertEquals("1.0 GB", StorageAccountant.humanBytes(1024L * 1024 * 1024))
    }

    // ── Crash recovery ───────────────────────────────────────────────────────

    @Test
    fun `an interrupted narration needs attention`() {
        assertTrue(CrashRecovery.needsAttention(narration(1L, NarrationStatus.INTERRUPTED)))
        assertTrue(CrashRecovery.needsAttention(narration(1L, NarrationStatus.FAILED)))
        assertFalse(CrashRecovery.needsAttention(narration(1L, NarrationStatus.COMPLETE)))
    }

    @Test
    fun `an interruption is described as an interruption, not a failure`() {
        val message = CrashRecovery.message(narration(1L, NarrationStatus.INTERRUPTED))!!
        assertTrue(message.contains("interrupted"))
        assertFalse(message.contains("failed"))
    }

    @Test
    fun `a failure shows its reason`() {
        val message = CrashRecovery.message(
            narration(1L, NarrationStatus.FAILED, reason = "out of memory"),
        )!!
        assertTrue(message.contains("out of memory"))
    }

    @Test
    fun `both retry and discard are offered`() {
        val actions = CrashRecovery.actions(narration(1L, NarrationStatus.INTERRUPTED))
        assertTrue(CrashRecovery.Action.RETRY in actions)
        assertTrue(CrashRecovery.Action.DISCARD in actions)
    }

    @Test
    fun `a complete narration offers no actions`() {
        assertTrue(CrashRecovery.actions(narration(1L, NarrationStatus.COMPLETE)).isEmpty())
    }

    @Test
    fun `a complete narration has no message`() {
        assertNull(CrashRecovery.message(narration(1L, NarrationStatus.COMPLETE)))
    }

    // ── The device validation gate ───────────────────────────────────────────

    @Test
    fun `a device that passes every gate is validated`() {
        val measurement = DeviceValidationHarness.Measurement(
            realTimeFactor = 0.3f,
            peakRssBytes = 500L * 1024 * 1024,
            onDiskBytes = 100L * 1024 * 1024,
            firstChunkMs = 200,
            peakTemperatureCelsius = 40f,
            batteryPercentPerMinute = 0.5f,
            adapterTrainingMs = 5 * 60_000L,
            adapterPeakMemoryBytes = 400L * 1024 * 1024,
            trainingCompleted = true,
        )
        val gate = DeviceValidationHarness.evaluate(measurement)
        assertTrue(gate.passed)
        assertTrue(gate.failures().isEmpty())
    }

    @Test
    fun `a real-time factor above the limit fails`() {
        val measurement = passing().copy(realTimeFactor = 0.8f)
        val gate = DeviceValidationHarness.evaluate(measurement)
        assertFalse(gate.rtfPass)
        assertFalse(gate.passed)
        assertTrue(gate.failures().any { it.contains("Real-time factor") })
    }

    @Test
    fun `peak memory above the limit fails`() {
        val measurement = passing().copy(peakRssBytes = 900L * 1024 * 1024)
        assertFalse(DeviceValidationHarness.evaluate(measurement).rssPass)
    }

    @Test
    fun `training over ten minutes fails`() {
        val measurement = passing().copy(adapterTrainingMs = 12 * 60_000L)
        assertFalse(DeviceValidationHarness.evaluate(measurement).trainingPass)
    }

    @Test
    fun `a thermal abort fails`() {
        val measurement = passing().copy(trainingCompleted = false)
        assertFalse(DeviceValidationHarness.evaluate(measurement).thermalPass)
    }

    @Test
    fun `an unmeasured device fails every gate`() {
        // The important rule: "we did not measure it" is not a pass.
        val measurement = DeviceValidationHarness.Measurement(
            realTimeFactor = null,
            peakRssBytes = null,
            onDiskBytes = null,
            firstChunkMs = null,
            peakTemperatureCelsius = null,
            batteryPercentPerMinute = null,
            adapterTrainingMs = null,
            adapterPeakMemoryBytes = null,
            trainingCompleted = false,
        )
        val gate = DeviceValidationHarness.evaluate(measurement)
        assertFalse(gate.passed)
        assertEquals(4, gate.failures().size)
    }

    @Test
    fun `a passing measurement yields a thermally validated profile`() {
        val profile = DeviceValidationHarness.profileFrom(
            passing(),
            ramBytes = 4L * 1024 * 1024 * 1024,
            freeStorageBytes = 2L * 1024 * 1024 * 1024,
            label = "mid-tier",
        )
        assertTrue(profile.thermalValidated)
        assertEquals("mid-tier", profile.label)
    }

    @Test
    fun `a device that trained but overheated is not validated`() {
        val profile = DeviceValidationHarness.profileFrom(
            passing().copy(trainingCompleted = false),
            ramBytes = 4L * 1024 * 1024 * 1024,
            freeStorageBytes = 2L * 1024 * 1024 * 1024,
            label = "hot",
        )
        assertFalse(profile.thermalValidated)
    }

    @Test
    fun `the report lists the measured numbers`() {
        val report = DeviceValidationHarness.DeviceReport(
            deviceLabel = "mid-tier",
            model = testModel(),
            measurement = passing(),
            gate = DeviceValidationHarness.evaluate(passing()),
        )
        assertTrue(report.verdict().contains("PASS"))
        assertEquals(8, report.numbers().size)
        assertTrue(report.numbers().all { it.second != "not measured" })
    }

    @Test
    fun `the gate thresholds are the specification's`() {
        assertEquals(0.5f, DeviceValidationHarness.RTF_LIMIT, 0.0001f)
        assertEquals(700L, DeviceValidationHarness.RSS_LIMIT_MB)
        assertEquals(10L, DeviceValidationHarness.TRAINING_LIMIT_MIN)
    }

    private fun passing() = DeviceValidationHarness.Measurement(
        realTimeFactor = 0.3f,
        peakRssBytes = 500L * 1024 * 1024,
        onDiskBytes = 100L * 1024 * 1024,
        firstChunkMs = 200,
        peakTemperatureCelsius = 40f,
        batteryPercentPerMinute = 0.5f,
        adapterTrainingMs = 5 * 60_000L,
        adapterPeakMemoryBytes = 400L * 1024 * 1024,
        trainingCompleted = true,
    )
}
