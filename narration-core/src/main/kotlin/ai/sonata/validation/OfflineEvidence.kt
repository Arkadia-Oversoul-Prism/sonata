package ai.sonata.validation

import ai.sonata.narration.*
import ai.sonata.privacy.OfflineAudit
import ai.sonata.reliability.AudioIntegrity
import java.io.File

/**
 * Stage 6 — offline acceptance with runtime evidence.
 *
 * Three independent lines of evidence, because any one of them alone is weak:
 *
 *  1. **Sentinel**: the pipeline runs with every outbound attempt recorded. Zero
 *     attempts is evidence that the code path was never taken.
 *  2. **Connectivity control**: the same process proves it *could* reach the network
 *     when asked. Without this, "no attempts" could just mean "no network".
 *  3. **Network namespace**: the whole run is repeated inside `unshare -n`, where the
 *     interface does not exist. If narration still completes and produces valid audio,
 *     the app genuinely does not need the network.
 *
 * The third is the strongest, because it does not depend on the app's cooperation.
 */
object OfflineEvidence {

    data class Line(val name: String, val passed: Boolean, val detail: String)

    @JvmStatic
    fun main(args: Array<String>) {
        // Dispatch first: the namespace run re-enters this class with --inner, and
        // without this check it recursed into the outer routine instead.
        if (args.isNotEmpty() && args[0] == "--inner") {
            inner(args.getOrElse(1) { "build/offline/namespace.wav" })
            return
        }
        val outDir = File(args.getOrElse(0) { "build/offline" })
        outDir.mkdirs()
        val engine = EspeakEngine()
        val lines = mutableListOf<Line>()

        // ---- 1. sentinel ----
        val sentinelOut = File(outDir, "sentinel.wav")
        val audit = OfflineAudit.run {
            for (style in DeliveryStyle.all) {
                NarrationPipeline(engine).narrate(
                    Corpus.byId("prose_01")!!.text, style,
                    File(outDir, "sentinel_${style.id}.wav")
                )
            }
        }
        lines += Line(
            "sentinel",
            audit.silent && audit.completed,
            "completed=${audit.completed} attempts=${audit.attempts.size} :: ${audit.summary()}"
        )

        // ---- 2. connectivity control ----
        val live = OfflineAudit.connectivityProbe()
        lines += Line(
            "connectivity_control",
            live,
            if (live) "the process can reach the network when it tries, so 'no attempts' is meaningful"
            else "the sandbox has no outbound network; the sentinel result is therefore inconclusive on its own"
        )

        // ---- 3. network namespace ----
        val nsOut = File(outDir, "namespace.wav")
        val nsScript = File(outDir, "ns_run.sh")
        nsScript.writeText(
            """
            #!/usr/bin/env bash
            set -euo pipefail
            cd /workspace/sonata
            # Prove the interface is gone before doing anything else.
            if ip addr show 2>/dev/null | grep -q "state UP"; then
              echo "NS_HAS_INTERFACE"
            else
              echo "NS_NO_INTERFACE"
            fi
            ./run.sh ai.sonata.validation.OfflineEvidence --inner "${nsOut.absolutePath}"
            """.trimIndent()
        )
        nsScript.setExecutable(true)
        val proc = ProcessBuilder("sudo", "unshare", "-n", "bash", nsScript.absolutePath)
            .redirectErrorStream(true).start()
        val nsLog = proc.inputStream.bufferedReader().readText()
        val nsCode = proc.waitFor()
        val nsOk = nsCode == 0 && nsOut.exists() && AudioIntegrity.check(nsOut).ok
        lines += Line(
            "network_namespace",
            nsOk,
            "exit=$nsCode audio_ok=${nsOut.exists() && AudioIntegrity.check(nsOut).ok} :: " +
                nsLog.lines().filter { it.isNotBlank() }.takeLast(4).joinToString(" | ")
        )

        // ---- report ----
        println()
        println("offline evidence:")
        for (l in lines) {
            println("  [${if (l.passed) "PASS" else "FAIL"}] ${l.name}: ${l.detail}")
        }
        val allPassed = lines.all { it.passed }
        println()
        println("overall: ${if (allPassed) "PASS" else "INCOMPLETE"}")

        File(outDir, "offline_evidence.tsv").writeText(
            "line\tpassed\tdetail\n" + lines.joinToString("\n") { "${it.name}\t${it.passed}\t${it.detail}" } + "\n"
        )
        println("wrote ${File(outDir, "offline_evidence.tsv").path}")
    }

    /** Inner entry point: runs inside the network namespace. */
    @JvmStatic
    fun inner(path: String) {
        val engine = EspeakEngine()
        val out = File(path)
        val o = NarrationPipeline(engine).narrate(
            Corpus.byId("prose_01")!!.text, DeliveryStyle.NATURAL, out
        )
        println("INNER ok=${o.ok} error=${o.error ?: "none"} exists=${out.exists()}")
        if (!o.ok) kotlin.system.exitProcess(1)
    }
}
