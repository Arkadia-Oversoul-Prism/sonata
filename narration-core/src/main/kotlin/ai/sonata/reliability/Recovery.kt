package ai.sonata.reliability

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Stage 5 — process-death recovery.
 *
 * The failure this exists for: the app is killed mid-generation. On restart it must
 * know which chunks were actually written, which were being written, and which were
 * never started — and it must never present a half-written chunk as complete.
 *
 * Design: an append-only journal of fixed-size records, each carrying a length, a
 * CRC32 of its payload, and a monotonically increasing sequence number. A record is
 * only trusted if its CRC matches. A process killed mid-write leaves a torn tail,
 * which fails its CRC and is discarded — and because the record is length-prefixed,
 * a torn record cannot be mistaken for a shorter valid one.
 *
 * The journal is not the database. It is the *intent* log; the database is the
 * *result*. Recovery reconciles the two, and the reconciliation is what gets tested.
 */
object RecoveryJournal {

    private const val MAGIC = 0x534F4E41 // "SONA"
    private const val RECORD_HEADER = 16 // magic(4) + seq(4) + len(4) + crc(4)

    enum class Op(val code: Int) {
        BEGIN_CHUNK(1),
        CHUNK_WRITTEN(2),
        CHUNK_FAILED(3),
        NARRATION_DONE(4),
        NARRATION_ABORTED(5);

        companion object {
            fun of(code: Int): Op? = entries.firstOrNull { it.code == code }
        }
    }

    data class Record(val seq: Int, val op: Op, val payload: String, val crcOk: Boolean)

    data class Replay(
        val records: List<Record>,
        val tornTailBytes: Int,
        val corruptRecords: Int,
        val lastGoodSeq: Int
    ) {
        val isClean: Boolean get() = tornTailBytes == 0 && corruptRecords == 0
    }

    /** Append one record. Flushed and fsynced so a kill cannot lose an acknowledged write. */
    fun append(journal: File, seq: Int, op: Op, text: String) {
        journal.parentFile?.mkdirs()
        val body = encodeBody(op, text)
        val crc = crc32(body)
        val buf = ByteBuffer.allocate(RECORD_HEADER + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putInt(seq)
        buf.putInt(body.size)
        buf.putInt(crc)
        buf.put(body)
        FileOutputStream(journal, true).use { out ->
            out.write(buf.array())
            out.flush()
            out.fd.sync()
        }
    }

    /** Body layout: one op byte, then the payload as raw UTF-8 bytes. */
    private fun encodeBody(op: Op, text: String): ByteArray {
        val t = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + t.size)
        out[0] = op.code.toByte()
        System.arraycopy(t, 0, out, 1, t.size)
        return out
    }

    /**
     * Replay the journal, stopping at the first record that fails validation.
     *
     * Stopping rather than skipping is deliberate: a corrupt record in the middle means
     * the file's structure is no longer trustworthy, and continuing past it could
     * resurrect a chunk that was later invalidated.
     */
    fun replay(journal: File): Replay {
        if (!journal.exists()) return Replay(emptyList(), 0, 0, 0)
        val bytes = journal.readBytes()
        val records = mutableListOf<Record>()
        var pos = 0
        var torn = 0
        var corrupt = 0
        var lastSeq = 0
        while (pos + RECORD_HEADER <= bytes.size) {
            val bb = ByteBuffer.wrap(bytes, pos, RECORD_HEADER).order(ByteOrder.LITTLE_ENDIAN)
            val magic = bb.int
            val seq = bb.int
            val len = bb.int
            val crc = bb.int
            if (magic != MAGIC || len < 0 || len > 1 shl 20) {
                corrupt++
                break
            }
            if (pos + RECORD_HEADER + len > bytes.size) {
                torn = bytes.size - pos
                break
            }
            val body = bytes.copyOfRange(pos + RECORD_HEADER, pos + RECORD_HEADER + len)
            val actualCrc = crc32(body)
            if (actualCrc != crc) {
                corrupt++
                break
            }
            // The op code is the first byte of the payload.
            val opByte = if (body.isNotEmpty()) body[0].toInt() and 0xFF else 0
            val resolved = Op.of(opByte)
            if (resolved == null) {
                corrupt++
                break
            }
            val text = if (body.size > 1) String(body, 1, body.size - 1, Charsets.UTF_8) else ""
            records += Record(seq, resolved, text, true)
            lastSeq = seq
            pos += RECORD_HEADER + len
        }
        if (pos < bytes.size && torn == 0 && corrupt == 0) torn = bytes.size - pos
        return Replay(records, torn, corrupt, lastSeq)
    }

    fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc.inv()
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
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
 * Reconciles a replayed journal against the database's chunk rows.
 *
 * The output is a decision, not a boolean: [Resume] with an exact chunk index,
 * [Restart] with a reason, or [Complete]. Every branch is reachable in tests.
 */
object Recovery {

    data class ChunkRow(val index: Int, val status: String, val bytes: Long, val checksum: String?)

    sealed class Decision {
        data class Resume(val fromChunk: Int, val reason: String) : Decision()
        data class Restart(val reason: String) : Decision()
        data class Complete(val reason: String) : Decision()
        data class Abandon(val reason: String) : Decision()
    }

    /**
     * Decide what to do for one narration.
     *
     * Rules, in order:
     *  1. A journal that ends in NARRATION_DONE means the work finished; verify the
     *     database agrees, otherwise restart (the DB lost rows the journal saw).
     *  2. A journal that ends in NARRATION_ABORTED means the user cancelled; abandon.
     *  3. A chunk marked WRITTEN in the journal but missing or short in the database
     *     means the write was lost; resume from that chunk, not past it.
     *  4. A chunk marked BEGIN but never WRITTEN was in flight; resume from it.
     *  5. A torn journal tail is not itself a reason to restart — the last good
     *     record is still authoritative — but it is reported.
     */
    fun decide(replay: RecoveryJournal.Replay, rows: List<ChunkRow>, expectedChunks: Int): Decision {
        val written = mutableSetOf<Int>()
        val begun = mutableSetOf<Int>()
        var done = false
        var aborted = false
        for (r in replay.records) {
            when (r.op) {
                RecoveryJournal.Op.BEGIN_CHUNK -> r.payload.toIntOrNull()?.let { begun += it }
                RecoveryJournal.Op.CHUNK_WRITTEN -> r.payload.toIntOrNull()?.let { written += it }
                RecoveryJournal.Op.NARRATION_DONE -> done = true
                RecoveryJournal.Op.NARRATION_ABORTED -> aborted = true
                else -> {}
            }
        }
        if (aborted) return Decision.Abandon("journal records an explicit abort")

        val byIndex = rows.associateBy { it.index }
        if (done) {
            val missing = (0 until expectedChunks).filter { byIndex[it]?.status != "COMPLETE" }
            return if (missing.isEmpty()) Decision.Complete("journal and database agree on all $expectedChunks chunks")
            else Decision.Restart("journal says done but ${missing.size} chunk(s) are not COMPLETE in the database")
        }

        // A chunk the journal claims to have written but the database does not hold.
        val lost = written.filter { idx ->
            val row = byIndex[idx]
            row == null || row.status != "COMPLETE" || row.bytes <= 0
        }.sorted()
        if (lost.isNotEmpty()) {
            return Decision.Resume(lost.first(), "chunk ${lost.first()} was journalled as written but is not intact in the database")
        }

        // A chunk that was begun and never finished.
        val inFlight = (begun - written).sorted()
        if (inFlight.isNotEmpty()) {
            return Decision.Resume(inFlight.first(), "chunk ${inFlight.first()} was in flight when the process died")
        }

        // Nothing in flight: resume after the highest contiguous complete chunk.
        var next = 0
        while (next < expectedChunks && byIndex[next]?.status == "COMPLETE") next++
        return if (next >= expectedChunks) Decision.Complete("all $expectedChunks chunks already complete")
        else Decision.Resume(next, "resuming at the first incomplete chunk")
    }
}
