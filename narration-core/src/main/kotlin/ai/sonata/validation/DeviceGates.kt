package ai.sonata.validation

import java.io.File

/**
 * Stage 6 — device gates and the honesty rules for reporting them.
 *
 * The Pixel 7a G1–G6 gates are physical-device criteria. This sandbox has no Android
 * SDK, no emulator image, and 754 MB of RAM — an emulator needs at least 2 GB of guest
 * memory, so it cannot run here. That is a fact about the environment, not a result.
 *
 * The rule this file enforces: a gate that was not run is reported as NOT TESTED with
 * the exact external dependency that would unblock it. It is never reported as a pass,
 * and it is never quietly omitted from the report.
 */
object DeviceGates {

    enum class Status { PASS, FAIL, NOT_TESTED, BLOCKED }

    data class Gate(
        val id: String,
        val name: String,
        val criterion: String,
        val status: Status,
        val evidence: String,
        val dependency: String?
    )

    data class Environment(
        val hasAdb: Boolean,
        val hasEmulator: Boolean,
        val hasKvm: Boolean,
        val totalRamMb: Long,
        val freeDiskMb: Long,
        val deviceSerial: String?
    ) {
        /** An emulator needs ~2 GB of guest RAM plus host overhead. */
        val canRunEmulator: Boolean
            get() = hasEmulator && hasKvm && totalRamMb >= 3000 && freeDiskMb >= 6000
    }

    /** Probe the environment rather than assuming it. */
    fun probe(): Environment {
        fun which(bin: String): Boolean =
            try {
                ProcessBuilder("which", bin).start().let { it.waitFor() == 0 }
            } catch (e: Exception) {
                false
            }
        val ram = try {
            // Read the CONTAINER limit, not the host's total. /proc/meminfo reports the
            // host (754 GB here), which would make the emulator look feasible when the
            // cgroup actually allows 2 GB. The first version of this probe made exactly
            // that mistake and reported 772949 MB.
            val cgroupV2 = File("/sys/fs/cgroup/memory.max")
            val cgroupV1 = File("/sys/fs/cgroup/memory/memory.limit_in_bytes")
            val limit = when {
                cgroupV2.exists() -> cgroupV2.readText().trim().toLongOrNull()
                cgroupV1.exists() -> cgroupV1.readText().trim().toLongOrNull()
                else -> null
            }
            if (limit != null && limit > 0 && limit < Long.MAX_VALUE / 2) {
                limit / (1024 * 1024)
            } else {
                File("/proc/meminfo").readLines()
                    .firstOrNull { it.startsWith("MemTotal") }
                    ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLong()?.div(1024) } ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
        val disk = try {
            val p = ProcessBuilder("df", "-m", "/workspace").start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.lines().drop(1).firstOrNull()
                ?.split(Regex("\\s+"))?.getOrNull(3)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
        val serial = try {
            val p = ProcessBuilder("adb", "devices").start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.lines().drop(1).firstOrNull { it.contains("\tdevice") }?.split("\t")?.firstOrNull()
        } catch (e: Exception) {
            null
        }
        return Environment(
            hasAdb = which("adb"),
            hasEmulator = which("emulator"),
            hasKvm = File("/dev/kvm").exists(),
            totalRamMb = ram,
            freeDiskMb = disk,
            deviceSerial = serial
        )
    }

    /**
     * The six gates, with their status determined by what was actually possible.
     *
     * G1–G6 are defined here as the physical-device criteria this project commits to.
     * Each carries the dependency that would let it run, so the report tells the reader
     * exactly what to provide rather than just "not tested".
     */
    fun evaluate(env: Environment): List<Gate> {
        val noDevice = "A physical Pixel 7a (or an emulator with >= 2 GB guest RAM) attached via adb"
        val noEmulator = "Android SDK emulator + KVM + >= 3 GB host RAM + >= 6 GB free disk " +
            "(this sandbox: ${env.totalRamMb} MB RAM, ${env.freeDiskMb} MB free, KVM=${env.hasKvm})"

        fun gate(id: String, name: String, criterion: String, dependency: String): Gate =
            if (env.deviceSerial != null) {
                Gate(id, name, criterion, Status.NOT_TESTED,
                    "a device is attached (${env.deviceSerial}) but this harness has no instrumentation APK to install",
                    "A built instrumentation APK (assembleDebugAndroidTest) — no Android SDK in this environment")
            } else {
                Gate(id, name, criterion, Status.NOT_TESTED, "no device attached; adb=${env.hasAdb}", dependency)
            }

        return listOf(
            gate(
                "G1", "Cold start and first narration",
                "App launches from cold, a pasted document narrates end to end, and audio is audible on the device speaker.",
                noDevice
            ),
            gate(
                "G2", "Process death and resume",
                "Killing the app mid-generation and relaunching resumes at the correct chunk with no repeated or skipped text.",
                noDevice
            ),
            gate(
                "G3", "Voice enrolment on device",
                "A 5–15 minute enrolment completes, the adapter validates, and the personal voice activates.",
                noDevice
            ),
            gate(
                "G4", "Thermal behaviour under sustained playback",
                "A 30-minute playback session pauses and resumes on thermal thresholds without audible stutter.",
                noDevice
            ),
            gate(
                "G5", "Airplane mode with runtime network inspection",
                "With the radio off, narration completes and a packet capture shows zero outbound traffic.",
                noDevice
            ),
            gate(
                "G6", "Export and re-import",
                "An exported narration re-imports and plays with identical duration and no truncation.",
                noDevice
            )
        )
    }

    /** Emulator-only gates, reported separately because they are a different dependency. */
    fun emulatorGates(env: Environment): List<Gate> {
        val dep = "Android SDK emulator + KVM + >= 3 GB host RAM + >= 6 GB free disk " +
            "(this sandbox: ${env.totalRamMb} MB RAM, ${env.freeDiskMb} MB free, KVM=${env.hasKvm})"
        return listOf(
            Gate("E1", "Instrumented test suite", "androidTest suite passes on an emulator.",
                if (env.canRunEmulator) Status.NOT_TESTED else Status.BLOCKED,
                if (env.canRunEmulator) "emulator available but no instrumentation APK built"
                else "emulator cannot run in this environment", dep),
            Gate("E2", "Emulator long-session playback", "60-minute playback on an emulator without leak or drift.",
                if (env.canRunEmulator) Status.NOT_TESTED else Status.BLOCKED,
                if (env.canRunEmulator) "emulator available but no APK built" else "emulator cannot run", dep)
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val env = probe()
        println("environment:")
        println("  adb=${env.hasAdb} emulator=${env.hasEmulator} kvm=${env.hasKvm}")
        println("  total_ram_mb=${env.totalRamMb} free_disk_mb=${env.freeDiskMb}")
        println("  device_serial=${env.deviceSerial ?: "none"}")
        println("  can_run_emulator=${env.canRunEmulator}")
        println()
        val gates = evaluate(env) + emulatorGates(env)
        println("gate\tname\tstatus\tdependency")
        for (g in gates) {
            println("${g.id}\t${g.name}\t${g.status}\t${g.dependency ?: "-"}")
        }
        println()
        val passed = gates.count { it.status == Status.PASS }
        val failed = gates.count { it.status == Status.FAIL }
        val notTested = gates.count { it.status == Status.NOT_TESTED }
        val blocked = gates.count { it.status == Status.BLOCKED }
        println("summary: pass=$passed fail=$failed not_tested=$notTested blocked=$blocked")
        println("NOTE: no gate is reported as a pass without device evidence.")
    }
}
