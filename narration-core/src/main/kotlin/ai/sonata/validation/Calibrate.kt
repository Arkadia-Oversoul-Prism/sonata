package ai.sonata.validation

import ai.sonata.narration.EspeakEngine
import ai.sonata.narration.WavIo
import java.io.File
import kotlin.math.log10

/**
 * Calibration: measures how many semitones one espeak pitch step actually produces.
 *
 * The engine's `-p` parameter is not documented in semitones, and the conversion used
 * by [EspeakEngine] is a claim that must be measured rather than assumed. This renders
 * the same sentence at several pitch settings and reports the median F0 of each, so the
 * mapping can be fitted from data.
 */
object Calibrate {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File("build/calib")
        out.mkdirs()
        val sentence = "The morning arrived without ceremony and the light moved slowly across the table."
        val engine = EspeakEngine()
        val results = mutableListOf<Pair<Int, Double>>()
        for (p in listOf(20, 30, 40, 50, 60, 70, 80)) {
            val f = File(out, "p$p.wav")
            val proc = ProcessBuilder(
                "espeak-ng", "-v", "en-us", "-s", "165", "-p", p.toString(), "-a", "175",
                "-w", f.absolutePath, sentence
            ).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val d = AcousticAnalysis.analyse(f) ?: continue
            results += p to d.features.f0Mean
            println("p=$p  f0_median=%.1f Hz  voiced=%.2f".format(d.features.f0Mean, d.features.voicedRatio))
        }
        if (results.size >= 2) {
            val (p0, f0) = results.first()
            val (p1, f1) = results.last()
            val semis = 12.0 * log10(f1 / f0) / log10(2.0)
            val steps = (p1 - p0).toDouble()
            println()
            println("pitch $p0 -> $p1 : %.1f Hz -> %.1f Hz".format(f0, f1))
            println("total %.2f semitones over %.0f steps".format(semis, steps))
            println("=> %.4f semitones per pitch step".format(semis / steps))
            println("=> %.2f pitch steps per semitone".format(steps / semis))
        }
    }
}
