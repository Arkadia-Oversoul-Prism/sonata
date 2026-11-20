package ai.sonata.validation

import ai.sonata.narration.*
import java.io.File

/**
 * Stage 6 — intelligibility, measured by speech recognition.
 *
 * A style that sounds expressive but cannot be understood has failed. This harness
 * renders each passage, transcribes the audio with an offline recogniser, and compares
 * the transcript with the source text using word error rate.
 *
 * Why WER and not "does it sound right": WER is objective, reproducible, and it fails
 * loudly. It is also the metric that catches the specific failure this project is
 * exposed to — a style whose rate or register makes words unrecognisable.
 *
 * The recogniser is a small offline model, so its absolute WER is not a statement about
 * human intelligibility. What it *is* good for is comparison: the same recogniser on the
 * same text across eight styles, where a style that is materially harder to recognise
 * shows up as a higher WER. The report states this limitation explicitly.
 */
object Intelligibility {

    data class Result(
        val style: DeliveryStyle,
        val passage: Corpus.Passage,
        val wer: Double,
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val referenceWords: Int,
        val hypothesis: String,
        val error: String? = null
    )

    /** Levenshtein alignment of two word sequences, returning (subs, dels, ins). */
    fun align(ref: List<String>, hyp: List<String>): Triple<Int, Int, Int> {
        val n = ref.size
        val m = hyp.size
        // dp[i][j] = (cost, subs, dels, ins)
        val cost = Array(n + 1) { IntArray(m + 1) }
        val subs = Array(n + 1) { IntArray(m + 1) }
        val dels = Array(n + 1) { IntArray(m + 1) }
        val inss = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            cost[i][0] = i; dels[i][0] = i
        }
        for (j in 1..m) {
            cost[0][j] = j; inss[0][j] = j
        }
        for (i in 1..n) {
            for (j in 1..m) {
                val subCost = cost[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                val delCost = cost[i - 1][j] + 1
                val insCost = cost[i][j - 1] + 1
                when (minOf(subCost, delCost, insCost)) {
                    subCost -> {
                        cost[i][j] = subCost
                        subs[i][j] = subs[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                        dels[i][j] = dels[i - 1][j - 1]
                        inss[i][j] = inss[i - 1][j - 1]
                    }
                    delCost -> {
                        cost[i][j] = delCost
                        subs[i][j] = subs[i - 1][j]
                        dels[i][j] = dels[i - 1][j] + 1
                        inss[i][j] = inss[i - 1][j]
                    }
                    else -> {
                        cost[i][j] = insCost
                        subs[i][j] = subs[i][j - 1]
                        dels[i][j] = dels[i][j - 1]
                        inss[i][j] = inss[i][j - 1] + 1
                    }
                }
            }
        }
        return Triple(subs[n][m], dels[n][m], inss[n][m])
    }

    /** Normalise text for comparison: lower case, letters and digits only. */
    fun normalise(text: String): List<String> =
        Regex("[a-z0-9']+").findAll(text.lowercase()).map { it.value }.toList()

    fun wer(reference: String, hypothesis: String): Result? {
        val ref = normalise(reference)
        val hyp = normalise(hypothesis)
        if (ref.isEmpty()) return null
        val (s, d, i) = align(ref, hyp)
        return Result(
            DeliveryStyle.NATURAL, Corpus.passages.first(), (s + d + i).toDouble() / ref.size,
            s, d, i, ref.size, hypothesis
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val audioDir = File(args.getOrElse(0) { "build/eval" })
        val outDir = File(args.getOrElse(1) { "build/asr" })
        outDir.mkdirs()
        val modelDir = args.getOrElse(2) { "/opt/asr/vosk-model-small-en-us-0.15" }
        val script = File(outDir, "transcribe.py")
        script.writeText(TRANSCRIBE_PY)

        val rows = mutableListOf<String>()
        rows += "style\tpassage\twer\tsubs\tdels\tins\tref_words\thyp_words"
        val perStyle = mutableMapOf<String, MutableList<Double>>()

        for (style in DeliveryStyle.all) {
            for (passage in Corpus.passages) {
                val wav = File(audioDir, "${style.id}__${passage.id}.wav")
                if (!wav.exists()) {
                    rows += "${style.id}\t${passage.id}\tNA\t0\t0\t0\t0\t0"
                    continue
                }
                val hyp = transcribe(script, wav, modelDir)
                val ref = normalise(passage.text)
                val h = normalise(hyp)
                val (s, d, i) = align(ref, h)
                val w = (s + d + i).toDouble() / ref.size.coerceAtLeast(1)
                perStyle.getOrPut(style.id) { mutableListOf() } += w
                rows += "${style.id}\t${passage.id}\t%.4f\t$s\t$d\t$i\t${ref.size}\t${h.size}".format(w)
                println("%-15s %-14s WER=%.3f  (subs=%d dels=%d ins=%d)".format(style.id, passage.id, w, s, d, i))
            }
        }

        File(outDir, "wer.tsv").writeText(rows.joinToString("\n") + "\n")
        println()
        println("mean WER by style:")
        for (style in DeliveryStyle.all) {
            val xs = perStyle[style.id] ?: continue
            println("  %-15s %.4f  (n=%d)".format(style.id, xs.average(), xs.size))
        }
        val all = perStyle.values.flatten()
        println()
        println("overall mean WER = %.4f over %d renders".format(all.average(), all.size))

        // ---- control: the same recogniser on the same text with NO pipeline ----
        //
        // Without this, a high absolute WER is ambiguous: it could mean the pipeline
        // degrades speech, or it could mean a recogniser trained on natural speech
        // simply struggles with formant synthesis. The control settles it. If the
        // control's WER is close to the pipeline's, the pipeline is not the cause.
        println()
        println("control (raw espeak-ng, no pipeline):")
        val controlRows = mutableListOf<String>()
        controlRows += "passage\twer\tsubs\tdels\tins\tref_words"
        val controlWers = mutableListOf<Double>()
        for (passage in Corpus.passages) {
            val wav = File(outDir, "control__${passage.id}.wav")
            val proc = ProcessBuilder(
                "espeak-ng", "-v", "en-us", "-s", "165", "-p", "50", "-a", "175",
                "-w", wav.absolutePath, passage.text
            ).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val hyp = transcribe(script, wav, modelDir)
            val ref = normalise(passage.text)
            val h = normalise(hyp)
            val (s, d, i) = align(ref, h)
            val w = (s + d + i).toDouble() / ref.size.coerceAtLeast(1)
            controlWers += w
            controlRows += "${passage.id}\t%.4f\t$s\t$d\t$i\t${ref.size}".format(w)
            println("  %-14s WER=%.3f".format(passage.id, w))
        }
        File(outDir, "wer_control.tsv").writeText(controlRows.joinToString("\n") + "\n")
        println("  control mean WER = %.4f".format(controlWers.average()))
        println("  pipeline mean WER = %.4f".format(all.average()))
        println("  delta = %+.4f".format(all.average() - controlWers.average()))
        println("wrote ${File(outDir, "wer.tsv").path} and wer_control.tsv")
    }

    private fun transcribe(script: File, wav: File, modelDir: String): String {
        val p = ProcessBuilder("python3", script.absolutePath, modelDir, wav.absolutePath)
            .redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) return "TRANSCRIBE_FAILED ${err.take(200)}"
        return out.trim()
    }

    private val TRANSCRIBE_PY = """
import sys, json, wave
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
model = Model(sys.argv[1])
wf = wave.open(sys.argv[2], "rb")
rec = KaldiRecognizer(model, wf.getframerate())
rec.SetWords(False)
parts = []
while True:
    data = wf.readframes(4000)
    if len(data) == 0:
        break
    if rec.AcceptWaveform(data):
        parts.append(json.loads(rec.Result()).get("text", ""))
parts.append(json.loads(rec.FinalResult()).get("text", ""))
print(" ".join(p for p in parts if p))
""".trimIndent()
}
