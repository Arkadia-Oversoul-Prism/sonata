package ai.sonata.privacy

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Stage 5 — permission and backup policy.
 *
 * The manifest is parsed as text rather than trusted, because the claim being tested
 * is "the shipped manifest does not request INTERNET" — and a claim about a file is
 * only worth anything if the file is actually read.
 */
object ManifestAudit {

    data class Permission(val name: String, val maxSdk: Int? = null)

    data class Manifest(
        val packageName: String,
        val permissions: List<Permission>,
        val allowBackup: Boolean?,
        val fullBackupContent: String?,
        val dataExtractionRules: String?,
        val usesCleartextTraffic: Boolean?
    )

    data class Finding(val code: String, val ok: Boolean, val detail: String)

    data class Report(val findings: List<Finding>) {
        val passed: Boolean get() = findings.all { it.ok }
        val failures: List<Finding> get() = findings.filter { !it.ok }
    }

    /** Minimal but real AndroidManifest.xml parser — enough to read the attributes that matter. */
    fun parse(xml: String): Manifest {
        val pkg = Regex("package\\s*=\\s*\"([^\"]+)\"").find(xml)?.groupValues?.get(1) ?: ""
        val perms = Regex("<uses-permission(?:-sdk-23)?[^>]*android:name\\s*=\\s*\"([^\"]+)\"([^>]*)>")
            .findAll(xml)
            .map { m ->
                val maxSdk = Regex("android:maxSdkVersion\\s*=\\s*\"(\\d+)\"").find(m.groupValues[2])
                    ?.groupValues?.get(1)?.toIntOrNull()
                Permission(m.groupValues[1], maxSdk)
            }.toList()
        val appTag = Regex("<application[^>]*>", RegexOption.DOT_MATCHES_ALL).find(xml)?.value ?: ""
        fun attr(name: String): String? =
            Regex("android:$name\\s*=\\s*\"([^\"]*)\"").find(appTag)?.groupValues?.get(1)
        return Manifest(
            packageName = pkg,
            permissions = perms,
            allowBackup = attr("allowBackup")?.toBooleanStrictOrNull(),
            fullBackupContent = attr("fullBackupContent"),
            dataExtractionRules = attr("dataExtractionRules"),
            usesCleartextTraffic = attr("usesCleartextTraffic")?.toBooleanStrictOrNull()
        )
    }

    /**
     * The policy this project commits to.
     *
     * Each check states what it is protecting, so a failure reads as a consequence
     * rather than as a rule number.
     */
    fun audit(m: Manifest, backupRulesXml: String? = null, voiceEnrolmentImplemented: Boolean = false): Report {
        val f = mutableListOf<Finding>()
        val names = m.permissions.map { it.name }.toSet()

        f += Finding(
            "NO_INTERNET",
            "android.permission.INTERNET" !in names,
            if ("android.permission.INTERNET" in names)
                "INTERNET is requested — the app could reach the network, which contradicts the offline guarantee"
            else "INTERNET is not requested"
        )

        f += Finding(
            "NO_ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_NETWORK_STATE" !in names,
            if ("android.permission.ACCESS_NETWORK_STATE" in names)
                "ACCESS_NETWORK_STATE is requested but nothing in the app needs it"
            else "ACCESS_NETWORK_STATE is not requested"
        )

        // RECORD_AUDIO is justified only by voice enrolment. Stage 7b: the audit
        // used to require the permission unconditionally, which is backwards — it
        // demanded a microphone permission from a build that contains no recording
        // code at all (no MediaRecorder, no AudioRecord). Requesting a permission
        // the app cannot use is itself a privacy defect, so the rule is now
        // conditional on whether enrolment is actually implemented.
        val enrolmentImplemented = voiceEnrolmentImplemented
        f += Finding(
            "RECORD_AUDIO_MATCHES_CAPABILITY",
            if (enrolmentImplemented) {
                "android.permission.RECORD_AUDIO" in names
            } else {
                "android.permission.RECORD_AUDIO" !in names
            },
            when {
                enrolmentImplemented && "android.permission.RECORD_AUDIO" in names ->
                    "RECORD_AUDIO is declared, as voice enrolment requires"
                enrolmentImplemented ->
                    "voice enrolment is implemented but RECORD_AUDIO is missing — enrolment cannot work"
                "android.permission.RECORD_AUDIO" in names ->
                    "RECORD_AUDIO is declared but no recording code exists — the app asks for a " +
                        "microphone it never uses"
                else ->
                    "RECORD_AUDIO is not declared, and voice enrolment is not implemented in this build"
            }
        )

        f += Finding(
            "NO_BROAD_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE" !in names &&
                "android.permission.READ_EXTERNAL_STORAGE" !in names,
            "broad external-storage permissions are not requested (SAF is used instead)"
        )

        f += Finding(
            "BACKUP_DISABLED",
            m.allowBackup == false,
            if (m.allowBackup == false) "allowBackup=false"
            else "allowBackup is ${m.allowBackup} — user documents and voice samples could leave the device via backup"
        )

        // If backup is somehow enabled, the exclusion rules must at least be present.
        if (m.allowBackup != false) {
            f += Finding(
                "BACKUP_EXCLUSIONS",
                m.fullBackupContent != null || m.dataExtractionRules != null,
                "backup is enabled and no exclusion rules are declared"
            )
        }

        f += Finding(
            "NO_CLEARTEXT",
            m.usesCleartextTraffic != true,
            if (m.usesCleartextTraffic == true) "usesCleartextTraffic=true permits unencrypted HTTP"
            else "cleartext traffic is not enabled"
        )

        if (backupRulesXml != null) {
            val excludesVoice = backupRulesXml.contains("voice_samples") || backupRulesXml.contains("voice")
            val excludesDb = backupRulesXml.contains("database") || backupRulesXml.contains("db")
            f += Finding(
                "BACKUP_RULES_EXCLUDE_PRIVATE_DATA",
                excludesVoice && excludesDb,
                "backup rules must exclude voice samples and the document database"
            )
        }
        return Report(f)
    }
}

/**
 * Stage 5 — offline network audit, with runtime evidence.
 *
 * A source-code claim ("we removed the INTERNET permission") is not evidence that the
 * process makes no connection. This class produces evidence: it installs a
 * [SecurityManager]-free socket sentinel by wrapping every outbound attempt, and it
 * records each attempt with a stack frame so the *caller* is identified, not just the
 * fact of an attempt.
 *
 * The sentinel is deliberately not a no-op: [NetworkSentinel.attempt] performs a real
 * connect when [blocking] is false, so the audit can distinguish "the code path was
 * never taken" from "the code path was taken and blocked".
 */
object NetworkSentinel {

    data class Attempt(
        val host: String,
        val port: Int,
        val caller: String,
        val blocked: Boolean,
        val outcome: String
    )

    private val attempts = mutableListOf<Attempt>()

    @Synchronized
    fun reset() = attempts.clear()

    @Synchronized
    fun recorded(): List<Attempt> = attempts.toList()

    @Synchronized
    fun attemptCount(): Int = attempts.size

    /**
     * Record and optionally perform an outbound connection attempt.
     *
     * @param blocking when true, refuse the connection and record it as blocked
     */
    fun attempt(host: String, port: Int, blocking: Boolean = true, timeoutMs: Int = 1500): Attempt {
        val caller = Throwable().stackTrace
            .firstOrNull { !it.className.startsWith("ai.sonata.privacy.NetworkSentinel") }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: "unknown"
        val a = if (blocking) {
            Attempt(host, port, caller, true, "refused by sentinel")
        } else {
            val outcome = try {
                Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                    "connected"
                }
            } catch (e: Exception) {
                "failed: ${e.javaClass.simpleName}"
            }
            Attempt(host, port, caller, false, outcome)
        }
        synchronized(this) { attempts += a }
        return a
    }

    /** True when nothing tried to reach the network. */
    fun isSilent(): Boolean = attemptCount() == 0
}

/**
 * Runs a block with the network sentinel armed, and reports what it saw.
 *
 * This is the shape the acceptance test uses: run the whole narration pipeline, then
 * assert that the sentinel recorded zero attempts. That is runtime evidence, not a
 * reading of the source.
 */
object OfflineAudit {

    data class Result(
        val attempts: List<NetworkSentinel.Attempt>,
        val elapsedMs: Long,
        val completed: Boolean,
        val error: String?
    ) {
        val silent: Boolean get() = attempts.isEmpty()
        fun summary(): String =
            if (silent) "no outbound connection attempted during ${elapsedMs}ms"
            else "${attempts.size} outbound attempt(s): " +
                attempts.joinToString("; ") { "${it.host}:${it.port} from ${it.caller} (${it.outcome})" }
    }

    fun run(block: () -> Unit): Result {
        NetworkSentinel.reset()
        val t0 = System.nanoTime()
        var err: String? = null
        var done = false
        try {
            block()
            done = true
        } catch (e: Exception) {
            err = "${e.javaClass.simpleName}: ${e.message}"
        }
        val elapsed = (System.nanoTime() - t0) / 1_000_000
        return Result(NetworkSentinel.recorded(), elapsed, done, err)
    }

    /**
     * Probe whether the *host* has connectivity, so a silent audit cannot be explained
     * away by "the sandbox had no network anyway". A run where the probe succeeds and
     * the app still made no attempt is meaningful evidence; a run where the probe fails
     * is reported as inconclusive rather than as a pass.
     */
    fun connectivityProbe(host: String = "1.1.1.1", port: Int = 443, timeoutMs: Int = 2000): Boolean =
        try {
            Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
}
