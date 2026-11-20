package ai.sonata.narration

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Stage 4, layer 3 — acoustic synthesis.
 *
 * The only layer that touches audio. It receives a [ProsodyPlan] and produces a file.
 * It has no knowledge of [DeliveryStyle] and no knowledge of text structure: if the
 * planner is replaced, this layer is unchanged, and if the engine is replaced, the
 * planner is unchanged. [SpeechEngine] is the seam.
 */
interface SpeechEngine {
    val id: String
    val version: String
    /** Render a plan to a 16-bit PCM mono WAV at [sampleRate]. */
    fun synthesize(plan: ProsodyPlan, outFile: File, sampleRate: Int = 22050): SynthesisResult
}

data class SynthesisResult(
    val ok: Boolean,
    val outFile: File?,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val error: String? = null,
    /** How many engine invocations were made — one per segment in the per-segment engine. */
    val invocations: Int = 0,
    /**
     * Start time of each segment in the rendered audio, in milliseconds.
     *
     * Needed to check pause *placement* rather than pause *duration*: without knowing
     * where a segment begins, a detected pause cannot be attributed to the boundary it
     * was planned for.
     */
    val segmentStartMs: List<Long> = emptyList()
)

/**
 * Renders one [PlannedSegment] to SSML.
 *
 * Kept separate from the engine so the mapping from plan to markup can be asserted as
 * a string, with no process launch and no audio. That is what makes "did the planner's
 * intent reach the engine?" a testable question.
 *
 * Note what is deliberately absent: no `range` attribute. Measurement showed it
 * collapses the pitch contour on this engine (see Prosody.kt), so emitting it would
 * silently destroy the prosody the plan asked for.
 */
object SsmlRenderer {

    /** Render a whole plan as one document. Used only for engines that honour per-segment pitch. */
    fun render(plan: ProsodyPlan): String {
        val sb = StringBuilder("<speak>")
        plan.segments.forEachIndexed { i, seg ->
            if (i > 0 && seg.pauseBeforeMs > 0) sb.append("<break time=\"${seg.pauseBeforeMs}ms\"/>")
            sb.append(segment(seg))
            if (seg.pauseAfterMs > 0) sb.append("<break time=\"${seg.pauseAfterMs}ms\"/>")
        }
        return sb.append("</speak>").toString()
    }

    /** Render a single segment. */
    fun segment(seg: PlannedSegment): String =
        "<prosody rate=\"${fmt(seg.rate)}\" volume=\"${seg.volume}\">${escape(seg.text)}</prosody>"

    /** XML-escape text so a document containing & or < cannot break the markup. */
    fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    private fun fmt(d: Double): String = String.format(java.util.Locale.US, "%.2f", d)
}

/**
 * 16-bit PCM WAV reading and writing, with header validation.
 *
 * Written by hand rather than pulled from a library because the integrity checks in
 * Stage 5 need to inspect the header fields directly — a library that silently
 * repairs a truncated file would hide exactly the defect being tested.
 */
object WavIo {

    data class Header(
        val riffSize: Long,
        val format: Int,
        val channels: Int,
        val sampleRate: Int,
        val byteRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Long
    ) {
        val durationMs: Long get() = if (byteRate > 0) dataSize * 1000L / byteRate else 0L
        val frameCount: Long get() = if (blockAlign > 0) dataSize / blockAlign else 0L
    }

    /** Parse a WAV header. Returns null when the file is not a readable PCM WAV. */
    fun readHeader(file: File): Header? {
        if (!file.exists() || file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val riffSize = le32(head, 4)
            var pos = 12L
            var fmt: ByteArray? = null
            var dataOffset = -1L
            var dataSize = -1L
            while (pos + 8 <= file.length()) {
                raf.seek(pos)
                val chunkHead = ByteArray(8)
                raf.readFully(chunkHead)
                val id = String(chunkHead, 0, 4, Charsets.US_ASCII)
                val size = le32(chunkHead, 4)
                when (id) {
                    "fmt " -> {
                        val b = ByteArray(size.toInt().coerceAtMost(64))
                        raf.readFully(b)
                        fmt = b
                    }
                    "data" -> {
                        dataOffset = pos + 8
                        dataSize = size
                    }
                }
                pos += 8 + size + (size % 2)
            }
            val f = fmt ?: return null
            if (f.size < 16) return null
            return Header(
                riffSize = riffSize,
                format = le16(f, 0),
                channels = le16(f, 2),
                sampleRate = le32(f, 4).toInt(),
                byteRate = le32(f, 8).toInt(),
                blockAlign = le16(f, 12),
                bitsPerSample = le16(f, 14),
                dataOffset = dataOffset.toInt(),
                dataSize = dataSize
            )
        }
    }

    /** Read PCM samples as doubles in [-1, 1]. */
    fun readSamples(file: File): DoubleArray {
        val h = readHeader(file) ?: return DoubleArray(0)
        if (h.dataOffset < 0 || h.bitsPerSample != 16) return DoubleArray(0)
        val n = (h.dataSize / 2).toInt()
        val bytes = ByteArray(n * 2)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(h.dataOffset.toLong())
            raf.readFully(bytes)
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(n) { bb.short.toDouble() / 32768.0 }
    }

    /** Write 16-bit PCM mono. */
    fun write(file: File, samples: DoubleArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)                       // PCM
        bb.putShort(1)                       // mono
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)            // byte rate
        bb.putShort(2)                       // block align
        bb.putShort(16)                      // bits
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize)
        for (s in samples) {
            val v = (s.coerceIn(-1.0, 1.0) * 32767.0).toInt()
            bb.putShort(v.toShort())
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bb.array())
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24))
}

/**
 * espeak-ng as a [SpeechEngine], rendering **one invocation per segment**.
 *
 * This is not an implementation detail — it is the only way to get real register
 * movement out of this engine. Two measured facts forced the design:
 *
 *  1. SSML `pitch` is a *global* offset. Setting it per segment inside one document
 *     shifts the whole utterance uniformly; the sentence-to-sentence spread stays at
 *     ~0.7 semitones regardless of the offsets requested.
 *  2. SSML `range` is destructive. Any value collapses the contour to ~0.5 semitones.
 *
 * Rendering each segment separately with its own `-p` value, then concatenating with
 * exact silence, produces the register movement the plan asked for. The cost is one
 * process launch per segment, which is acceptable for a validation harness and is
 * stated in the report.
 *
 * It is NOT the app's production engine: the app's engine is the on-device model
 * behind [SpeechEngine], and the same [ProsodyPlan] drives both.
 */
class EspeakEngine(
    private val binary: String = "espeak-ng",
    private val voice: String = "en-us",
    /** Engine base pitch, 0..99. 50 is the default. */
    private val basePitch: Int = 50,
    private val baseSpeed: Int = 165,
    private val baseAmplitude: Int = 175
) : SpeechEngine {

    override val id: String = "espeak-ng"
    override val version: String by lazy {
        runCatching {
            val p = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }.getOrDefault("unknown")
    }

    override fun synthesize(plan: ProsodyPlan, outFile: File, sampleRate: Int): SynthesisResult {
        val work = File.createTempFile("sonata-seg", "")
        work.delete()
        work.mkdirs()
        return try {
            val pieces = mutableListOf<DoubleArray>()
            val starts = mutableListOf<Long>()
            var invocations = 0
            var elapsedSamples = 0L
            for ((i, seg) in plan.segments.withIndex()) {
                if (seg.pauseBeforeMs > 0 && i > 0) {
                    val sil = silence(seg.pauseBeforeMs, sampleRate)
                    pieces += sil
                    elapsedSamples += sil.size
                }
                starts += elapsedSamples * 1000L / sampleRate
                val segFile = File(work, "seg_$i.wav")
                val err = renderSegment(seg, segFile)
                if (err != null) {
                    return SynthesisResult(false, null, 0, sampleRate, 1, 16, "segment $i: $err", invocations)
                }
                invocations++
                val samples = WavIo.readSamples(segFile)
                if (samples.isEmpty()) {
                    return SynthesisResult(false, null, 0, sampleRate, 1, 16, "segment $i produced no samples", invocations)
                }
                pieces += samples
                elapsedSamples += samples.size
                if (seg.pauseAfterMs > 0) {
                    val sil = silence(seg.pauseAfterMs, sampleRate)
                    pieces += sil
                    elapsedSamples += sil.size
                }
            }
            val total = pieces.sumOf { it.size }
            val merged = DoubleArray(total)
            var at = 0
            for (p in pieces) {
                System.arraycopy(p, 0, merged, at, p.size)
                at += p.size
            }
            WavIo.write(outFile, merged, sampleRate)
            val h = WavIo.readHeader(outFile)
                ?: return SynthesisResult(false, null, 0, sampleRate, 1, 16, "merged file unreadable", invocations)
            SynthesisResult(true, outFile, h.durationMs, h.sampleRate, h.channels, h.bitsPerSample, null, invocations, starts)
        } catch (e: Exception) {
            SynthesisResult(false, null, 0, sampleRate, 1, 16, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            work.deleteRecursively()
        }
    }

    /** Render one segment. Returns an error string, or null on success. */
    private fun renderSegment(seg: PlannedSegment, out: File): String? {
        val ssml = "<speak>${SsmlRenderer.segment(seg)}</speak>"
        val ssmlFile = File(out.parentFile, out.nameWithoutExtension + ".ssml")
        ssmlFile.writeText(ssml)
        val pitch = (basePitch + semitonesToPitchSteps(seg.pitchSt)).coerceIn(0, 99)
        val speed = (baseSpeed * seg.rate).toInt().coerceIn(80, 450)
        val amp = (seg.volume.coerceIn(60, 200))
        val cmd = listOf(
            binary, "-v", voice,
            "-s", speed.toString(),
            "-p", pitch.toString(),
            "-a", amp.toString(),
            "-m", "-f", ssmlFile.absolutePath,
            "-w", out.absolutePath
        )
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return if (code != 0) "espeak-ng exit $code: ${log.take(200)}" else null
    }

    /**
     * Convert semitones to espeak's 0..99 pitch scale.
     *
     * espeak's pitch parameter is roughly linear in log-frequency over its usable
     * range; empirically one step is about 0.25 semitones near the default. The
     * conversion is calibrated by measurement (see the calibration test), not assumed.
     */
    fun semitonesToPitchSteps(semitones: Double): Int = (semitones / SEMITONES_PER_PITCH_STEP).toInt()

    companion object {
        /**
         * Measured on espeak-ng 1.51, en-us, s=165: p=20 gives 79.5 Hz and p=80 gives
         * 139.6 Hz, i.e. 9.75 semitones over 60 steps = 0.1625 semitones per step.
         * The first version of this file assumed 0.25 and was wrong by 54%; the value
         * is now fitted from measurement (see Calibrate.kt) rather than guessed.
         */
        const val SEMITONES_PER_PITCH_STEP = 0.1625
    }

    private fun silence(ms: Int, sampleRate: Int): DoubleArray =
        DoubleArray((ms / 1000.0 * sampleRate).toInt())
}

/**
 * The pipeline: structure -> plan -> audio.
 *
 * Deliberately thin. All the decisions live in the three layers; this only sequences
 * them and refuses to synthesise a plan that violates its own invariants.
 */
class NarrationPipeline(private val engine: SpeechEngine) {

    data class Outcome(
        val ok: Boolean,
        val plan: ProsodyPlan,
        val synthesis: SynthesisResult?,
        val error: String?
    )

    fun narrate(text: String, style: DeliveryStyle, outFile: File): Outcome {
        val structure = TextUnderstanding.analyse(text)
        if (structure.totalSentences == 0) {
            return Outcome(false, ProsodyPlan(style, emptyList(), 0, 0), null, "no speakable text")
        }
        val plan = StylePlanner.plan(structure, style)
        val violations = plan.violations()
        if (violations.isNotEmpty()) {
            return Outcome(false, plan, null, "plan invariant violated: ${violations.first()}")
        }
        val result = engine.synthesize(plan, outFile)
        return Outcome(result.ok, plan, result, result.error)
    }
}
