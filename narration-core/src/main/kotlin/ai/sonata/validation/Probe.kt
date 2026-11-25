package ai.sonata.validation

import ai.sonata.narration.*
import java.io.File

/**
 * Diagnostic probe: renders one passage in two styles and prints the measured
 * features. Used to confirm the measurement chain is sane before the full matrix
 * run, and kept in the repo because a future reader will want the same check.
 */
object Probe {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File("build/probe")
        out.mkdirs()
        val engine = EspeakEngine()
        println("engine=${engine.id} version=${engine.version}")
        val passage = Corpus.byId("prose_01")!!
        for (style in listOf(DeliveryStyle.NATURAL, DeliveryStyle.DRAMATIC, DeliveryStyle.CONVERSATIONAL)) {
            val f = File(out, "probe_${style.id}.wav")
            val outcome = NarrationPipeline(engine).narrate(passage.text, style, f)
            if (!outcome.ok) {
                println("${style.id}: FAILED ${outcome.error}")
                continue
            }
            val d = AcousticAnalysis.analyse(f)
            if (d == null) {
                println("${style.id}: analysis returned null")
                continue
            }
            val ft = d.features
            println(
                "%-15s dur=%5dms f0mean=%6.1f f0range=%5.2fst regspread=%5.2fst pauseMean=%6.1fms voiced=%.2f energy=%.1fdB pauses=%d".format(
                    style.id, d.durationMs, ft.f0Mean, ft.f0Range, ft.registerSpread, ft.pauseMean, ft.voicedRatio, ft.energyMean, d.pauses.size
                )
            )
            println("   plan: segments=${outcome.plan.segments.size} totalPause=${outcome.plan.totalPauseMs}ms est=${outcome.plan.estimatedDurationMs}ms")
        }
    }
}
