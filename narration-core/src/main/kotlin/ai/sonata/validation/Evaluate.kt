package ai.sonata.validation

import ai.sonata.narration.*
import java.io.File

/**
 * Stage 6 — the style evaluation matrix.
 *
 * Renders every approved style over every corpus passage, measures the resulting audio,
 * and checks the style's declared intent against what was actually produced.
 *
 * The output is a TSV plus a summary, both written to disk so the report can quote them
 * verbatim. Nothing here is allowed to "pass" by reading the plan: every number comes
 * from [AcousticAnalysis], which only sees samples.
 */
object Evaluate {

    data class Cell(
        val style: DeliveryStyle,
        val passage: Corpus.Passage,
        val ok: Boolean,
        val error: String?,
        val features: AcousticFeatures?,
        val durationMs: Long,
        val plannedPauses: List<Double>,
        val foundPauses: Int,
        val pauseMatch: AcousticAnalysis.PauseMatch?,
        val invocations: Int
    )

    data class ClaimResult(val claim: OrderingClaim, val greaterValue: Double, val lesserValue: Double, val holds: Boolean)

    data class ExpectationResult(val style: DeliveryStyle, val expectation: Expectation, val value: Double, val holds: Boolean)

    data class Summary(
        val cells: List<Cell>,
        val claims: List<ClaimResult>,
        val expectations: List<ExpectationResult>,
        val rendered: Int,
        val failed: Int
    ) {
        val claimsHeld: Int get() = claims.count { it.holds }
        val expectationsHeld: Int get() = expectations.count { it.holds }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrElse(0) { "build/eval" })
        outDir.mkdirs()
        val engine = EspeakEngine()
        println("engine=${engine.id}")
        println("engine_version=${engine.version}")
        println("corpus_passages=${Corpus.passages.size}")
        println("styles=${DeliveryStyle.all.size}")
        println()

        val cells = mutableListOf<Cell>()
        for (style in DeliveryStyle.all) {
            for (passage in Corpus.passages) {
                val file = File(outDir, "${style.id}__${passage.id}.wav")
                val outcome = NarrationPipeline(engine).narrate(passage.text, style, file)
                if (!outcome.ok) {
                    cells += Cell(style, passage, false, outcome.error, null, 0, emptyList(), 0, null, 0)
                    println("FAIL ${style.id}/${passage.id}: ${outcome.error}")
                    continue
                }
                val d = AcousticAnalysis.analyse(file, expectedSyllables = passage.syllables)
                if (d == null) {
                    cells += Cell(style, passage, false, "analysis returned null", null, 0, emptyList(), 0, null, 0)
                    continue
                }
                // Planned pause boundaries: the start of each segment after the first is
                // where a pause was inserted.
                val starts = outcome.synthesis?.segmentStartMs ?: emptyList()
                val boundaries = starts.drop(1).map { it.toDouble() }
                val match = AcousticAnalysis.matchPausesByPlacement(boundaries, d.pauses)
                cells += Cell(
                    style, passage, true, null, d.features, d.durationMs,
                    boundaries, d.pauses.size, match, outcome.synthesis?.invocations ?: 0
                )
            }
        }

        // ---- per-style aggregate features (median across passages) ----
        val byStyle = DeliveryStyle.all.associateWith { style ->
            val fs = cells.filter { it.style == style && it.ok }.mapNotNull { it.features }
            Aggregate(
                speechRate = median(fs.map { it.speechRate }),
                f0Mean = median(fs.map { it.f0Mean }),
                f0Range = median(fs.map { it.f0Range }),
                registerSpread = median(fs.map { it.registerSpread }),
                pauseMean = median(fs.map { it.pauseMean }),
                energyMean = median(fs.map { it.energyMean }),
                voicedRatio = median(fs.map { it.voicedRatio })
            )
        }

        // ---- absolute expectations ----
        val expectations = mutableListOf<ExpectationResult>()
        for (style in DeliveryStyle.all) {
            val agg = byStyle[style] ?: continue
            for (e in style.expectations) {
                val v = agg.value(e.feature)
                expectations += ExpectationResult(style, e, v, e.accepts(v))
            }
        }

        // ---- relative ordering claims ----
        val claims = StyleClaims.ordering.map { c ->
            val g = byStyle[c.greater]?.value(c.feature) ?: 0.0
            val l = byStyle[c.lesser]?.value(c.feature) ?: 0.0
            ClaimResult(c, g, l, c.holds(g, l))
        }

        val summary = Summary(cells, claims, expectations, cells.count { it.ok }, cells.count { !it.ok })

        // ---- write the raw matrix ----
        val tsv = File(outDir, "style_matrix.tsv")
        tsv.writeText(buildString {
            appendLine("style\tpassage\tcategory\tok\tduration_ms\tinvocations\tspeech_rate\tf0_mean\tf0_range\tregister_spread\tpause_mean\tenergy_mean\tvoiced_ratio\tplanned_pauses\tfound_pauses\tpause_recall\tpause_precision")
            for (c in cells) {
                val f = c.features
                appendLine(
                    listOf(
                        c.style.id, c.passage.id, c.passage.category.id, c.ok,
                        c.durationMs, c.invocations,
                        f?.speechRate?.fmt() ?: "", f?.f0Mean?.fmt() ?: "", f?.f0Range?.fmt() ?: "",
                        f?.registerSpread?.fmt() ?: "", f?.pauseMean?.fmt() ?: "",
                        f?.energyMean?.fmt() ?: "", f?.voicedRatio?.fmt() ?: "",
                        c.plannedPauses.size, c.foundPauses,
                        c.pauseMatch?.recall?.fmt() ?: "", c.pauseMatch?.precision?.fmt() ?: ""
                    ).joinToString("\t")
                )
            }
        })

        // ---- write the aggregate ----
        val aggFile = File(outDir, "style_aggregate.tsv")
        aggFile.writeText(buildString {
            appendLine("style\tspeech_rate\tf0_mean\tf0_range\tregister_spread\tpause_mean\tenergy_mean\tvoiced_ratio")
            for (style in DeliveryStyle.all) {
                val a = byStyle[style] ?: continue
                appendLine(
                    listOf(
                        style.id, a.speechRate.fmt(), a.f0Mean.fmt(), a.f0Range.fmt(),
                        a.registerSpread.fmt(), a.pauseMean.fmt(), a.energyMean.fmt(), a.voicedRatio.fmt()
                    ).joinToString("\t")
                )
            }
        })

        // ---- write the claim results ----
        val claimFile = File(outDir, "claims.tsv")
        claimFile.writeText(buildString {
            appendLine("kind\tfeature\tgreater\tlesser\tmin_ratio\tgreater_value\tlesser_value\tholds\tnote")
            for (c in claims) {
                appendLine(
                    listOf(
                        "ordering", c.claim.feature.id, c.claim.greater.id, c.claim.lesser.id,
                        c.claim.minRatio, c.greaterValue.fmt(), c.lesserValue.fmt(), c.holds, c.claim.note
                    ).joinToString("\t")
                )
            }
            for (e in expectations) {
                appendLine(
                    listOf(
                        "expectation", e.expectation.feature.id, e.style.id, "-",
                        e.expectation.describe(), e.value.fmt(), "-", e.holds, e.expectation.note
                    ).joinToString("\t")
                )
            }
        })

        // ---- console summary ----
        println("rendered=${summary.rendered} failed=${summary.failed}")
        println()
        println("style aggregate (median across ${Corpus.passages.size} passages):")
        println("style            rate   f0mean  f0range  regspread  pause   energy  voiced")
        for (style in DeliveryStyle.all) {
            val a = byStyle[style] ?: continue
            println(
                "%-15s %6.3f %7.1f %8.2f %10.2f %7.1f %7.1f %7.3f".format(
                    style.id, a.speechRate, a.f0Mean, a.f0Range, a.registerSpread, a.pauseMean, a.energyMean, a.voicedRatio
                )
            )
        }
        println()
        println("ordering claims: ${summary.claimsHeld}/${claims.size} hold")
        for (c in claims) {
            println(
                "  [%s] %-16s %-14s vs %-14s  %.3f vs %.3f  (need ratio >= %.2f)".format(
                    if (c.holds) "PASS" else "FAIL", c.claim.feature.id, c.claim.greater.id,
                    c.claim.lesser.id, c.greaterValue, c.lesserValue, c.claim.minRatio
                )
            )
        }
        println()
        println("absolute expectations: ${summary.expectationsHeld}/${expectations.size} hold")
        for (e in expectations) {
            println(
                "  [%s] %-15s %-14s value=%.3f  %s".format(
                    if (e.holds) "PASS" else "FAIL", e.style.id, e.expectation.feature.id, e.value, e.expectation.describe()
                )
            )
        }
        println()
        val recalls = cells.filter { it.ok }.mapNotNull { it.pauseMatch?.recall }
        val precisions = cells.filter { it.ok }.mapNotNull { it.pauseMatch?.precision }
        println("pause placement: mean recall=%.3f mean precision=%.3f over %d cells".format(
            recalls.average(), precisions.average(), recalls.size
        ))
        println()
        println("wrote ${tsv.path}, ${aggFile.path}, ${claimFile.path}")
    }

    data class Aggregate(
        val speechRate: Double, val f0Mean: Double, val f0Range: Double,
        val registerSpread: Double, val pauseMean: Double, val energyMean: Double, val voicedRatio: Double
    ) {
        fun value(f: Feature): Double = when (f) {
            Feature.SPEECH_RATE -> speechRate
            Feature.F0_MEAN -> f0Mean
            Feature.F0_RANGE -> f0Range
            Feature.REGISTER_SPREAD -> registerSpread
            Feature.PAUSE_MEAN -> pauseMean
            Feature.ENERGY_MEAN -> energyMean
            Feature.VOICED_RATIO -> voicedRatio
        }
    }

    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[s.size / 2]
    }

    private fun Double.fmt(): String = String.format(java.util.Locale.US, "%.4f", this)
}
