package ai.sonata.validation

import ai.sonata.narration.AcousticFeatures
import ai.sonata.narration.WavIo
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Stage 6 — acoustic measurement.
 *
 * Everything here is measured from the rendered samples. Nothing is read back from the
 * plan, because the whole point of the exercise is to check whether the plan's intent
 * survived the engine. If this module read the plan, every style would trivially
 * "match its intent".
 *
 * The methods are deliberately simple and documented, so a reader can judge whether
 * the numbers mean what the report says they mean:
 *
 *  - **Framing**: 25 ms frames, 10 ms hop for energy and voicing decisions.
 *  - **Voicing**: a frame is voiced when the normalised cross-correlation peak in the
 *    60–400 Hz lag range exceeds 0.35 and its energy is above the noise floor. This is
 *    a classic pitch-detection gate, not a neural VAD.
 *  - **F0**: estimated over a 60 ms window (long enough to hold several periods of a
 *    low voice), taking the first strong peak rather than the global maximum to avoid
 *    octave errors, then median-filtered over five frames.
 *  - **Pause**: a run of at least 120 ms of unvoiced, low-energy frames. Shorter gaps
 *    are consonant closures, not pauses.
 *  - **Speech rate**: syllables are not detectable from audio alone, so the rate is
 *    reported as *voiced seconds per second of speech* and separately as the
 *    syllables-per-second implied by the plan's text. The report labels which is which.
 */
object AcousticAnalysis {

    const val FRAME_MS = 25.0
    const val HOP_MS = 10.0
    const val VOICING_THRESHOLD = 0.35
    const val MIN_F0_HZ = 60.0
    const val MAX_F0_HZ = 400.0
    const val PAUSE_MIN_MS = 120.0
    const val SILENCE_DB = -45.0
    /** Window used for pitch estimation — long enough to hold several periods at 60 Hz. */
    const val PITCH_WINDOW_MS = 60.0
    /** A peak must reach this fraction of the best peak to be accepted as the period. */
    const val FIRST_PEAK_RATIO = 0.90

    data class Frame(val index: Int, val startMs: Double, val rmsDb: Double, val f0: Double?, val voiced: Boolean)

    data class Detailed(
        val features: AcousticFeatures,
        val frames: List<Frame>,
        val pauses: List<Pause>,
        val durationMs: Long,
        val sampleRate: Int
    )

    data class Pause(val startMs: Double, val endMs: Double) {
        val durationMs: Double get() = endMs - startMs
    }

    fun analyse(file: File, expectedSyllables: Int = 0): Detailed? {
        val header = WavIo.readHeader(file) ?: return null
        val samples = WavIo.readSamples(file)
        if (samples.isEmpty()) return null
        val sr = header.sampleRate.toDouble()
        val frameLen = (FRAME_MS / 1000.0 * sr).toInt()
        val hopLen = (HOP_MS / 1000.0 * sr).toInt()
        if (frameLen < 2 || hopLen < 1) return null

        val frames = mutableListOf<Frame>()
        val pitchLen = (PITCH_WINDOW_MS / 1000.0 * sr).toInt()
        var i = 0
        var idx = 0
        while (i + frameLen <= samples.size) {
            val frame = DoubleArray(frameLen) { samples[i + it] }
            val rms = sqrt(frame.sumOf { it * it } / frameLen)
            val db = if (rms <= 0.0) -120.0 else 20.0 * log10(rms)
            // Pitch is estimated over a longer window centred on this frame, because a
            // 25 ms window cannot hold several periods of a low voice.
            val f0 = if (db > SILENCE_DB) {
                val pStart = (i - (pitchLen - frameLen) / 2).coerceAtLeast(0)
                val pEnd = (pStart + pitchLen).coerceAtMost(samples.size)
                if (pEnd - pStart >= 64) pitchOf(samples.copyOfRange(pStart, pEnd), sr) else null
            } else null
            frames += Frame(idx, i / sr * 1000.0, db, f0, f0 != null)
            i += hopLen
            idx++
        }
        if (frames.isEmpty()) return null

        // Median-filter F0 over five frames to reject octave jumps.
        val smoothed = medianFilter(frames.map { it.f0 }, 5)
        val voicedF0 = smoothed.filterNotNull().sorted()
        val f0Mean = if (voicedF0.isEmpty()) 0.0 else voicedF0[voicedF0.size / 2]
        val f0Range = if (voicedF0.size < 4) 0.0 else {
            val p5 = percentile(voicedF0, 0.05)
            val p95 = percentile(voicedF0, 0.95)
            if (p5 <= 0.0) 0.0 else 12.0 * log10(p95 / p5) / log10(2.0)
        }

        val voicedFrames = frames.count { it.voiced }
        val voicedRatio = voicedFrames.toDouble() / frames.size
        val energyMean = frames.map { it.rmsDb }.filter { it > -100 }.average().let { if (it.isNaN()) -120.0 else it }

        val pauses = findPauses(frames)
        val pauseMean = if (pauses.isEmpty()) 0.0 else pauses.map { it.durationMs }.average()

        // Register spread: the spread of per-sentence median F0. Sentences are located
        // by the pauses between them, so this measures what a listener hears as the
        // voice moving between sentences — not the within-sentence contour.
        val registerSpread = registerSpreadOf(frames, smoothed, pauses)

        val durationMs = (samples.size / sr * 1000.0).toLong()
        // Speech rate in syllables per second of *speech* (pauses excluded).
        //
        // The first version reported voiced-fraction, which is a voicing proxy: it barely
        // moves with the engine's rate parameter because espeak compresses pauses rather
        // than phonemes. That made the rate claims untestable. This version divides the
        // known syllable count of the text by the measured speech time, which is the
        // quantity a listener actually perceives as speed.
        val pauseTotalMs = pauses.sumOf { it.durationMs }
        val speechMs = (durationMs - pauseTotalMs).coerceAtLeast(1.0)
        val speechRate = if (expectedSyllables > 0) expectedSyllables / (speechMs / 1000.0) else 0.0

        return Detailed(
            features = AcousticFeatures(
                speechRate = speechRate,
                f0Mean = f0Mean,
                f0Range = f0Range,
                registerSpread = registerSpread,
                pauseMean = pauseMean,
                energyMean = energyMean,
                voicedRatio = voicedRatio
            ),
            frames = frames,
            pauses = pauses,
            durationMs = durationMs,
            sampleRate = header.sampleRate
        )
    }

    /**
     * Normalised cross-correlation pitch estimate for one frame.
     *
     * Two details matter and both were wrong in the first version:
     *
     *  1. **First strong peak, not global maximum.** A periodic signal correlates
     *     almost as well at twice its period as at its period, so taking the global
     *     maximum systematically reports an octave too low (or, with a short window,
     *     an octave too high). The standard fix is to take the *smallest* lag whose
     *     peak reaches [FIRST_PEAK_RATIO] of the best peak.
     *
     *  2. **A window long enough to hold several periods.** At 60 Hz a period is
     *     367 samples at 22.05 kHz; a 25 ms window holds barely one. Pitch is
     *     therefore estimated over [PITCH_WINDOW_MS], independently of the energy
     *     framing.
     *
     * Returns null when the frame is not periodic enough to have a pitch — the correct
     * answer for fricatives and silence, and why [voicedRatio] is meaningful.
     */
    fun pitchOf(frame: DoubleArray, sampleRate: Double): Double? {
        val n = frame.size
        if (n < 64) return null
        val mean = frame.average()
        val centred = DoubleArray(n) { frame[it] - mean }
        var energy = 0.0
        for (v in centred) energy += v * v
        if (energy <= 1e-9) return null

        val minLag = (sampleRate / MAX_F0_HZ).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / MIN_F0_HZ).toInt().coerceAtMost(n / 2)
        if (maxLag <= minLag + 2) return null

        // NCCF at every lag, normalised by the overlapping energy so a decaying
        // window does not bias the result toward short lags.
        val nccf = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var corr = 0.0
            var e0 = 0.0
            var e1 = 0.0
            for (j in 0 until n - lag) {
                val a = centred[j]
                val b = centred[j + lag]
                corr += a * b
                e0 += a * a
                e1 += b * b
            }
            val denom = sqrt(e0 * e1)
            nccf[lag] = if (denom <= 1e-9) 0.0 else corr / denom
        }

        // Local maxima only, so a shoulder on a peak is not mistaken for a peak.
        val peaks = mutableListOf<Int>()
        for (lag in minLag + 1 until maxLag) {
            if (nccf[lag] >= nccf[lag - 1] && nccf[lag] >= nccf[lag + 1] && nccf[lag] > 0.0) peaks += lag
        }
        if (peaks.isEmpty()) return null
        val best = peaks.maxOf { nccf[it] }
        if (best < VOICING_THRESHOLD) return null
        val chosen = peaks.first { nccf[it] >= best * FIRST_PEAK_RATIO }

        // Parabolic interpolation around the chosen lag for sub-sample accuracy.
        val y0 = nccf[(chosen - 1).coerceAtLeast(minLag)]
        val y1 = nccf[chosen]
        val y2 = nccf[(chosen + 1).coerceAtMost(maxLag)]
        val denom = (y0 - 2 * y1 + y2)
        val shift = if (abs(denom) > 1e-12) 0.5 * (y0 - y2) / denom else 0.0
        val refined = chosen + shift.coerceIn(-0.5, 0.5)
        if (refined <= 0.0) return null
        return sampleRate / refined
    }

    /** Runs of unvoiced, low-energy frames at least [PAUSE_MIN_MS] long. */
    fun findPauses(frames: List<Frame>): List<Pause> {
        val out = mutableListOf<Pause>()
        var runStart: Double? = null
        for (f in frames) {
            val silent = !f.voiced && f.rmsDb < SILENCE_DB
            if (silent) {
                if (runStart == null) runStart = f.startMs
            } else {
                val s = runStart
                if (s != null) {
                    val dur = f.startMs - s
                    if (dur >= PAUSE_MIN_MS) out += Pause(s, f.startMs)
                    runStart = null
                }
            }
        }
        runStart?.let { s ->
            val end = frames.last().startMs + FRAME_MS
            if (end - s >= PAUSE_MIN_MS) out += Pause(s, end)
        }
        return out
    }

    /**
     * Register movement, in semitones: the p10–p90 spread of median F0 over 1.5 s windows.
     *
     * The first version delimited sentences by detected pauses, which made the metric
     * depend on pause detection: a style with short pauses merged sentences and scored
     * *lower* movement than a style with long pauses, even when its plan asked for more.
     * That is a measurement artefact, not a property of the audio.
     *
     * Fixed windows remove the dependency. A 1.5 s window is long enough to contain a
     * phrase and short enough that a register change between sentences lands in
     * different windows. p10–p90 rather than min–max so one stray octave error cannot
     * define the result.
     */
    fun registerSpreadOf(frames: List<Frame>, smoothed: List<Double?>, pauses: List<Pause>): Double {
        if (frames.isEmpty()) return 0.0
        val windowMs = 1500.0
        val hopMs = 500.0
        val end = frames.last().startMs + FRAME_MS
        val medians = mutableListOf<Double>()
        var t = 0.0
        while (t + windowMs <= end) {
            val vals = frames.indices
                .filter { frames[it].startMs >= t && frames[it].startMs < t + windowMs }
                .mapNotNull { smoothed[it] }
                .sorted()
            // A window needs enough voiced material to have a meaningful median.
            if (vals.size >= 20) medians += vals[vals.size / 2]
            t += hopMs
        }
        if (medians.size < 3) return 0.0
        val sorted = medians.sorted()
        val lo = percentile(sorted, 0.10)
        val hi = percentile(sorted, 0.90)
        if (lo <= 0.0) return 0.0
        return 12.0 * log10(hi / lo) / log10(2.0)
    }

    private fun medianFilter(values: List<Double?>, window: Int): List<Double?> {
        val half = window / 2
        return values.indices.map { i ->
            val slice = (i - half..i + half)
                .filter { it in values.indices }
                .mapNotNull { values[it] }
                .sorted()
            if (slice.isEmpty()) null else slice[slice.size / 2]
        }
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /**
     * Pause placement accuracy: how many of the plan's declared pauses appear in the
     * audio, within tolerance.
     *
     * This is the check that distinguishes "the plan said 620 ms" from "620 ms of
     * silence is actually there". A style whose pauses are planned but not rendered
     * fails here even though its plan is correct.
     */
    data class PauseMatch(val planned: Int, val found: Int, val matched: Int, val toleranceMs: Double) {
        val recall: Double get() = if (planned == 0) 1.0 else matched.toDouble() / planned
        val precision: Double get() = if (found == 0) 1.0 else matched.toDouble() / found
    }

    fun matchPauses(planned: List<Double>, found: List<Pause>, toleranceMs: Double = 120.0): PauseMatch {
        val used = BooleanArray(found.size)
        var matched = 0
        for (p in planned) {
            val hit = found.indices.firstOrNull { !used[it] && abs(found[it].durationMs - p) <= toleranceMs }
            if (hit != null) {
                used[hit] = true
                matched++
            }
        }
        return PauseMatch(planned.size, found.size, matched, toleranceMs)
    }

    /**
     * Match pauses by *placement*: a planned boundary at time t is satisfied when a
     * detected pause overlaps t.
     *
     * This is the check that matters. Duration matching fails for a reason that has
     * nothing to do with the planner: the engine emits its own trailing silence inside
     * each segment, so a detected pause straddles the boundary and its measured duration
     * is longer than the silence the planner inserted. Placement is unaffected by that,
     * because the boundary is still inside the pause.
     *
     * @param plannedBoundariesMs times, in the rendered audio, where a pause was planned
     */
    fun matchPausesByPlacement(
        plannedBoundariesMs: List<Double>,
        found: List<Pause>,
        toleranceMs: Double = 150.0
    ): PauseMatch {
        val used = BooleanArray(found.size)
        var matched = 0
        for (t in plannedBoundariesMs) {
            val hit = found.indices.firstOrNull { i ->
                !used[i] && t >= found[i].startMs - toleranceMs && t <= found[i].endMs + toleranceMs
            }
            if (hit != null) {
                used[hit] = true
                matched++
            }
        }
        return PauseMatch(plannedBoundariesMs.size, found.size, matched, toleranceMs)
    }
}
