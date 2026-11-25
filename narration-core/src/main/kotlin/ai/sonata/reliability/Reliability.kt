package ai.sonata.reliability

import java.io.File
import java.security.MessageDigest

/**
 * Stage 5 — database integrity.
 *
 * The database is a set of plain files plus an index, which is what makes it
 * inspectable without a device. [IntegrityChecker] looks for the defects that
 * actually occur after a kill or a partial write, and reports each one separately
 * because they have different repairs:
 *
 *  - **orphan audio** — a chunk file with no row: wasted space, safe to delete.
 *  - **missing audio** — a row claiming COMPLETE with no file: must be re-generated.
 *  - **duplicate offsets** — two chunks claiming the same source range: the coverage
 *    is wrong and playback would repeat text.
 *  - **coverage gap** — a hole in the source range: text would be silently skipped.
 *  - **checksum mismatch** — the file changed under us: must be re-generated.
 *  - **schema version** — an older database that needs migration, not repair.
 */
object IntegrityChecker {

    data class ChunkRecord(
        val narrationId: String,
        val index: Int,
        val sourceStart: Int,
        val sourceEnd: Int,
        val status: String,
        val audioPath: String?,
        val checksum: String?,
        val bytes: Long
    )

    data class NarrationRecord(
        val id: String,
        val documentId: String,
        val sourceLength: Int,
        val expectedChunks: Int,
        val status: String
    )

    enum class Severity { INFO, WARNING, ERROR }

    data class Finding(val code: String, val severity: Severity, val detail: String)

    data class Report(
        val findings: List<Finding>,
        val checkedChunks: Int,
        val checkedNarrations: Int
    ) {
        val errors: List<Finding> get() = findings.filter { it.severity == Severity.ERROR }
        val warnings: List<Finding> get() = findings.filter { it.severity == Severity.WARNING }
        val isHealthy: Boolean get() = errors.isEmpty()
        fun codes(): Set<String> = findings.map { it.code }.toSet()
    }

    const val SCHEMA_VERSION = 3

    /**
     * Check one narration's chunks against its document.
     *
     * @param audioRoot directory holding the chunk files
     * @param verifyChecksums when true, re-hash every file (slow but catches silent corruption)
     */
    fun checkNarration(
        narration: NarrationRecord,
        chunks: List<ChunkRecord>,
        audioRoot: File,
        verifyChecksums: Boolean = true
    ): Report {
        val findings = mutableListOf<Finding>()

        if (chunks.isEmpty() && narration.expectedChunks > 0) {
            findings += Finding("NO_CHUNKS", Severity.ERROR,
                "narration ${narration.id} expects ${narration.expectedChunks} chunks but has none")
        }

        // Duplicate indices.
        chunks.groupBy { it.index }.filter { it.value.size > 1 }.forEach { (idx, dup) ->
            findings += Finding("DUPLICATE_INDEX", Severity.ERROR,
                "narration ${narration.id} has ${dup.size} rows for chunk index $idx")
        }

        // Duplicate / overlapping source ranges.
        val sorted = chunks.sortedBy { it.sourceStart }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            if (cur.sourceStart < prev.sourceEnd) {
                findings += Finding("DUPLICATE_OFFSETS", Severity.ERROR,
                    "narration ${narration.id}: chunk ${cur.index} starts at ${cur.sourceStart} " +
                        "but chunk ${prev.index} already covers up to ${prev.sourceEnd}")
            } else if (cur.sourceStart > prev.sourceEnd) {
                findings += Finding("COVERAGE_GAP", Severity.ERROR,
                    "narration ${narration.id}: ${cur.sourceStart - prev.sourceEnd} character(s) " +
                        "between chunk ${prev.index} and ${cur.index} are not covered")
            }
        }

        // Coverage of the document's full extent.
        if (sorted.isNotEmpty()) {
            val first = sorted.first()
            val last = sorted.last()
            if (first.sourceStart > 0) {
                findings += Finding("COVERAGE_GAP", Severity.ERROR,
                    "narration ${narration.id}: first ${first.sourceStart} character(s) of the document are not covered")
            }
            if (last.sourceEnd < narration.sourceLength) {
                findings += Finding("COVERAGE_GAP", Severity.ERROR,
                    "narration ${narration.id}: last ${narration.sourceLength - last.sourceEnd} " +
                        "character(s) of the document are not covered")
            }
        }

        // Per-chunk file checks.
        for (c in chunks) {
            if (c.status == "COMPLETE") {
                val f = c.audioPath?.let { File(audioRoot, it) }
                if (f == null || !f.exists()) {
                    findings += Finding("MISSING_AUDIO", Severity.ERROR,
                        "narration ${narration.id} chunk ${c.index} is COMPLETE but its file is absent")
                    continue
                }
                if (f.length() <= 44) {
                    findings += Finding("MISSING_AUDIO", Severity.ERROR,
                        "narration ${narration.id} chunk ${c.index} file is ${f.length()} bytes — header only")
                    continue
                }
                if (c.bytes > 0 && f.length() != c.bytes) {
                    findings += Finding("SIZE_MISMATCH", Severity.ERROR,
                        "narration ${narration.id} chunk ${c.index} is ${f.length()} bytes, row says ${c.bytes}")
                }
                if (verifyChecksums && c.checksum != null) {
                    val actual = sha256(f)
                    if (!actual.equals(c.checksum, ignoreCase = true)) {
                        findings += Finding("CHECKSUM_MISMATCH", Severity.ERROR,
                            "narration ${narration.id} chunk ${c.index} checksum does not match the row")
                    }
                }
            } else if (c.status == "GENERATING") {
                findings += Finding("INTERRUPTED_CHUNK", Severity.WARNING,
                    "narration ${narration.id} chunk ${c.index} was left GENERATING — recovery must resume it")
            }
        }

        // Orphan files: present on disk, no row.
        val known = chunks.mapNotNull { it.audioPath }.toSet()
        val narrationDir = File(audioRoot, narration.id)
        if (narrationDir.isDirectory) {
            narrationDir.listFiles()?.forEach { f ->
                val rel = "${narration.id}/${f.name}"
                if (rel !in known && f.name.endsWith(".wav")) {
                    findings += Finding("ORPHAN_AUDIO", Severity.WARNING,
                        "file $rel has no database row")
                }
            }
        }

        return Report(findings, chunks.size, 1)
    }

    /** Check the schema version, which is a migration concern rather than a repair. */
    fun checkSchema(foundVersion: Int): Report {
        val findings = when {
            foundVersion == SCHEMA_VERSION -> emptyList()
            foundVersion < SCHEMA_VERSION -> listOf(
                Finding("SCHEMA_OLD", Severity.WARNING,
                    "database schema v$foundVersion needs migration to v$SCHEMA_VERSION")
            )
            else -> listOf(
                Finding("SCHEMA_NEWER", Severity.ERROR,
                    "database schema v$foundVersion is newer than this build's v$SCHEMA_VERSION — refusing to open")
            )
        }
        return Report(findings, 0, 0)
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Stage 5 — storage limits.
 *
 * The device has a fixed budget. The policy has to answer three questions and be
 * testable on all of them: what counts against the budget, what gets evicted first,
 * and what must never be evicted.
 *
 * The reserve floor is the part that is easy to omit and expensive to omit: if the
 * app fills the volume, the *next* write fails, and the failure lands on the user's
 * document rather than on the audio it was evicting.
 */
object StorageBudget {

    data class Item(
        val id: String,
        val bytes: Long,
        val kind: Kind,
        val lastAccessedEpochMs: Long,
        val pinned: Boolean = false
    )

    enum class Kind(val priority: Int) {
        /** Regenerable narration audio — evicted first. */
        AUDIO(0),
        /** Cached model files — regenerable but expensive. */
        MODEL_CACHE(1),
        /** User's own voice samples — never evicted without consent. */
        VOICE_SAMPLE(2),
        /** The document text itself — never evicted. */
        DOCUMENT(3)
    }

    data class Policy(
        val budgetBytes: Long,
        val reserveBytes: Long
    ) {
        val usableBytes: Long get() = (budgetBytes - reserveBytes).coerceAtLeast(0)
    }

    data class Decision(
        val evict: List<String>,
        val freedBytes: Long,
        val fits: Boolean,
        val reason: String
    )

    /**
     * Decide what to evict to make room for [incomingBytes].
     *
     * Order: lowest kind priority first, then least-recently-accessed, and never a
     * pinned item or a kind at or above [protectFrom]. If the budget cannot be met
     * even after evicting everything evictable, the answer is [Decision.fits] = false
     * with the shortfall stated — not a silent over-commit.
     */
    fun plan(
        items: List<Item>,
        incomingBytes: Long,
        policy: Policy,
        protectFrom: Kind = Kind.VOICE_SAMPLE
    ): Decision {
        val used = items.sumOf { it.bytes }
        val available = policy.usableBytes - used
        if (incomingBytes <= available) {
            return Decision(emptyList(), 0, true, "fits: $available bytes available, $incomingBytes needed")
        }
        var need = incomingBytes - available
        val candidates = items
            .filter { !it.pinned && it.kind.priority < protectFrom.priority }
            .sortedWith(compareBy({ it.kind.priority }, { it.lastAccessedEpochMs }))
        val evict = mutableListOf<String>()
        var freed = 0L
        for (c in candidates) {
            if (need <= 0) break
            evict += c.id
            freed += c.bytes
            need -= c.bytes
        }
        return if (need <= 0) {
            Decision(evict, freed, true, "evicting ${evict.size} item(s) frees $freed bytes")
        } else {
            Decision(evict, freed, false,
                "cannot fit: still short ${-need} bytes after evicting all ${evict.size} eligible item(s)")
        }
    }

    /** True when the volume itself is below the reserve floor — a hard stop, not a warning. */
    fun volumeBelowReserve(freeBytes: Long, policy: Policy): Boolean = freeBytes < policy.reserveBytes
}

/**
 * Stage 5 — model integrity.
 *
 * A model file that is truncated or corrupted must be detected *before* it is loaded,
 * because a partially-loaded model produces plausible-looking but wrong audio, which
 * is far worse than a clear refusal.
 */
object ModelIntegrity {

    data class ManifestEntry(val name: String, val sha256: String, val bytes: Long)

    sealed class Verdict {
        data class Ok(val name: String) : Verdict()
        data class Missing(val name: String) : Verdict()
        data class Truncated(val name: String, val expected: Long, val actual: Long) : Verdict()
        data class Corrupt(val name: String, val expected: String, val actual: String) : Verdict()
        data class Unlisted(val name: String) : Verdict()
    }

    fun verify(entry: ManifestEntry, file: File): Verdict {
        if (!file.exists()) return Verdict.Missing(entry.name)
        val actualLen = file.length()
        if (actualLen < entry.bytes) return Verdict.Truncated(entry.name, entry.bytes, actualLen)
        val actualHash = IntegrityChecker.sha256(file)
        if (!actualHash.equals(entry.sha256, ignoreCase = true)) {
            return Verdict.Corrupt(entry.name, entry.sha256, actualHash)
        }
        return Verdict.Ok(entry.name)
    }

    /** Verify a whole manifest. Any non-Ok verdict blocks loading. */
    fun verifyAll(manifest: List<ManifestEntry>, root: File): List<Verdict> =
        manifest.map { verify(it, File(root, it.name)) }

    fun allOk(verdicts: List<Verdict>): Boolean = verdicts.all { it is Verdict.Ok }

    /** A user-presentable sentence for a failed verdict — never a stack trace. */
    fun describe(v: Verdict): String = when (v) {
        is Verdict.Ok -> "${v.name} verified"
        is Verdict.Missing -> "${v.name} is not installed"
        is Verdict.Truncated -> "${v.name} is incomplete (${v.actual} of ${v.expected} bytes) — reinstall the voice model"
        is Verdict.Corrupt -> "${v.name} failed its integrity check — reinstall the voice model"
        is Verdict.Unlisted -> "${v.name} is not part of the installed model set"
    }
}

/**
 * Stage 5 — thermal safety.
 *
 * Hysteresis with dwell times, because a governor that reacts to a single reading
 * oscillates: it pauses, the device cools by one degree, it resumes, the device heats,
 * it pauses again — and the user hears a stutter instead of a pause.
 */
object ThermalGovernor {

    enum class Level { NOMINAL, WARM, HOT, CRITICAL }

    data class Config(
        val warmC: Double = 38.0,
        val hotC: Double = 42.0,
        val criticalC: Double = 45.0,
        /** Must stay below the threshold for this long before resuming. */
        val resumeDwellMs: Long = 20_000,
        /** Must stay above the threshold for this long before pausing. */
        val pauseDwellMs: Long = 5_000,
        /** Resume only this many degrees below the pause threshold. */
        val hysteresisC: Double = 2.0
    )

    data class State(
        val level: Level = Level.NOMINAL,
        val paused: Boolean = false,
        val sinceMs: Long = 0,
        val lastTempC: Double = 0.0
    )

    data class Action(val state: State, val pause: Boolean, val resume: Boolean, val reason: String)

    fun levelFor(tempC: Double, cfg: Config): Level = when {
        tempC >= cfg.criticalC -> Level.CRITICAL
        tempC >= cfg.hotC -> Level.HOT
        tempC >= cfg.warmC -> Level.WARM
        else -> Level.NOMINAL
    }

    /**
     * Advance the governor with one temperature reading at [nowMs].
     *
     * Pausing requires the reading to stay above the threshold for [Config.pauseDwellMs];
     * resuming requires it to stay below (threshold - hysteresis) for [Config.resumeDwellMs].
     */
    fun step(state: State, tempC: Double, nowMs: Long, cfg: Config = Config()): Action {
        val level = levelFor(tempC, cfg)
        if (!state.paused) {
            val above = tempC >= cfg.hotC
            if (!above) {
                return Action(state.copy(level = level, sinceMs = nowMs, lastTempC = tempC), false, false,
                    "nominal at ${"%.1f".format(tempC)}C")
            }
            val since = if (state.level == level) state.sinceMs else nowMs
            val dwell = nowMs - since
            return if (dwell >= cfg.pauseDwellMs) {
                Action(state.copy(level = level, paused = true, sinceMs = nowMs, lastTempC = tempC), true, false,
                    "pausing: ${"%.1f".format(tempC)}C held for ${dwell}ms")
            } else {
                Action(state.copy(level = level, sinceMs = since, lastTempC = tempC), false, false,
                    "warm but not yet at dwell (${dwell}ms of ${cfg.pauseDwellMs}ms)")
            }
        } else {
            val resumeBelow = cfg.hotC - cfg.hysteresisC
            if (tempC > resumeBelow) {
                return Action(state.copy(level = level, sinceMs = nowMs, lastTempC = tempC), false, false,
                    "still paused: ${"%.1f".format(tempC)}C is above the resume threshold ${"%.1f".format(resumeBelow)}C")
            }
            val since = if (state.level == level) state.sinceMs else nowMs
            val dwell = nowMs - since
            return if (dwell >= cfg.resumeDwellMs) {
                Action(state.copy(level = level, paused = false, sinceMs = nowMs, lastTempC = tempC), false, true,
                    "resuming: ${"%.1f".format(tempC)}C held for ${dwell}ms")
            } else {
                Action(state.copy(level = level, sinceMs = since, lastTempC = tempC), false, false,
                    "cool enough but not yet at dwell (${dwell}ms of ${cfg.resumeDwellMs}ms)")
            }
        }
    }
}

/**
 * Stage 5 — audio integrity.
 *
 * A chunk that is present but silent, truncated, or the wrong duration is worse than
 * a missing chunk, because the player will happily play it and the user hears a gap
 * with no explanation.
 */
object AudioIntegrity {

    enum class Verdict { OK, MISSING, BAD_HEADER, TRUNCATED, SILENT, TOO_SHORT, WRONG_FORMAT }

    data class Result(
        val verdict: Verdict,
        val detail: String,
        val durationMs: Long = 0,
        val rmsDb: Double = Double.NEGATIVE_INFINITY
    ) {
        val ok: Boolean get() = verdict == Verdict.OK
    }

    /**
     * Validate a chunk file.
     *
     * @param expectedMinMs the shortest plausible duration for the text this chunk
     *        covers; a file far shorter than that was cut off
     */
    fun check(file: File, expectedMinMs: Long = 0, silenceFloorDb: Double = -55.0): Result {
        if (!file.exists()) return Result(Verdict.MISSING, "file does not exist")
        val header = ai.sonata.narration.WavIo.readHeader(file)
            ?: return Result(Verdict.BAD_HEADER, "not a readable PCM WAV")
        if (header.bitsPerSample != 16 || header.channels != 1) {
            return Result(Verdict.WRONG_FORMAT,
                "expected 16-bit mono, found ${header.bitsPerSample}-bit ${header.channels}ch")
        }
        val declared = header.dataSize
        val actual = file.length() - header.dataOffset
        if (actual < declared) {
            return Result(Verdict.TRUNCATED,
                "header declares $declared data bytes but only $actual are present", header.durationMs)
        }
        val samples = ai.sonata.narration.WavIo.readSamples(file)
        if (samples.isEmpty()) return Result(Verdict.BAD_HEADER, "no samples decoded")
        var sum = 0.0
        var peak = 0.0
        for (s in samples) {
            sum += s * s
            if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s)
        }
        val rms = kotlin.math.sqrt(sum / samples.size)
        val rmsDb = if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * kotlin.math.log10(rms)
        if (rmsDb < silenceFloorDb) {
            return Result(Verdict.SILENT, "RMS ${"%.1f".format(rmsDb)} dBFS is below the ${silenceFloorDb} dBFS floor",
                header.durationMs, rmsDb)
        }
        if (expectedMinMs > 0 && header.durationMs < expectedMinMs) {
            return Result(Verdict.TOO_SHORT,
                "duration ${header.durationMs}ms is below the expected minimum ${expectedMinMs}ms",
                header.durationMs, rmsDb)
        }
        return Result(Verdict.OK, "ok", header.durationMs, rmsDb)
    }
}
