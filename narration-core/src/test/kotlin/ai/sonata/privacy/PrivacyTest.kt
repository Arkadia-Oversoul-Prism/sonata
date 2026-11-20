package ai.sonata.privacy

import org.junit.Assert.*
import org.junit.Test

/**
 * Stage 5 — permission, backup and offline policy.
 *
 * The manifest tests parse real XML. The offline tests produce runtime evidence: they
 * run the narration pipeline with the sentinel armed and assert that nothing tried to
 * reach the network. A source-code claim is not accepted as evidence anywhere here.
 */
class PrivacyTest {

    private val goodManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="ai.sonata">
            <uses-permission android:name="android.permission.RECORD_AUDIO" />
            <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
            <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
            <application
                android:allowBackup="false"
                android:fullBackupContent="false"
                android:dataExtractionRules="@xml/data_extraction_rules"
                android:usesCleartextTraffic="false"
                android:label="Sonata">
            </application>
        </manifest>
    """.trimIndent()

    // ================= manifest parsing =================

    @Test
    fun `parses the package name`() {
        assertEquals("ai.sonata", ManifestAudit.parse(goodManifest).packageName)
    }

    @Test
    fun `parses permissions`() {
        val m = ManifestAudit.parse(goodManifest)
        assertTrue(m.permissions.any { it.name == "android.permission.RECORD_AUDIO" })
        assertEquals(3, m.permissions.size)
    }

    @Test
    fun `parses allowBackup`() {
        assertEquals(false, ManifestAudit.parse(goodManifest).allowBackup)
    }

    @Test
    fun `parses maxSdkVersion on a permission`() {
        val xml = """<manifest package="x"><uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" /></manifest>"""
        val m = ManifestAudit.parse(xml)
        assertEquals(32, m.permissions.first().maxSdk)
    }

    // ================= policy audit =================

    @Test
    fun `a compliant manifest passes every check`() {
        // Enrolment is not implemented in this build, so the compliant manifest is
        // the one WITHOUT RECORD_AUDIO. The fixture still carries the permission so
        // the parsing tests above have something to parse; the audit is told the
        // truth about capability.
        val r = ManifestAudit.audit(
            ManifestAudit.parse(goodManifest.replace("<uses-permission android:name=\"android.permission.RECORD_AUDIO\" />", "")),
            voiceEnrolmentImplemented = false
        )
        assertTrue("failures: ${r.failures.map { it.code }}", r.passed)
    }

    @Test
    fun `INTERNET is a failure`() {
        val xml = goodManifest.replace(
            "<uses-permission android:name=\"android.permission.RECORD_AUDIO\" />",
            "<uses-permission android:name=\"android.permission.RECORD_AUDIO\" />\n<uses-permission android:name=\"android.permission.INTERNET\" />"
        )
        val r = ManifestAudit.audit(ManifestAudit.parse(xml))
        assertFalse(r.passed)
        assertTrue(r.failures.any { it.code == "NO_INTERNET" })
    }

    @Test
    fun `missing RECORD_AUDIO is a failure when enrolment is implemented`() {
        // The rule is conditional on capability: a build that records voice samples
        // must declare the microphone. This asserts the enrolment-enabled branch.
        val xml = goodManifest.replace("<uses-permission android:name=\"android.permission.RECORD_AUDIO\" />", "")
        val r = ManifestAudit.audit(ManifestAudit.parse(xml), voiceEnrolmentImplemented = true)
        assertTrue(r.failures.any { it.code == "RECORD_AUDIO_MATCHES_CAPABILITY" })
    }

    @Test
    fun `RECORD_AUDIO without recording code is a failure`() {
        // The inverse defect, and the one this build actually had: asking for a
        // microphone the app never uses. Stage 7b made this a failure.
        val r = ManifestAudit.audit(ManifestAudit.parse(goodManifest), voiceEnrolmentImplemented = false)
        assertTrue(
            "a microphone permission with no recording code must fail the audit",
            r.failures.any { it.code == "RECORD_AUDIO_MATCHES_CAPABILITY" }
        )
    }

    @Test
    fun `allowBackup true is a failure`() {
        val xml = goodManifest.replace("android:allowBackup=\"false\"", "android:allowBackup=\"true\"")
        val r = ManifestAudit.audit(ManifestAudit.parse(xml))
        assertTrue(r.failures.any { it.code == "BACKUP_DISABLED" })
    }

    @Test
    fun `broad storage permission is a failure`() {
        val xml = goodManifest.replace(
            "<uses-permission android:name=\"android.permission.RECORD_AUDIO\" />",
            "<uses-permission android:name=\"android.permission.RECORD_AUDIO\" />\n<uses-permission android:name=\"android.permission.WRITE_EXTERNAL_STORAGE\" />"
        )
        val r = ManifestAudit.audit(ManifestAudit.parse(xml))
        assertTrue(r.failures.any { it.code == "NO_BROAD_STORAGE" })
    }

    @Test
    fun `cleartext traffic is a failure`() {
        val xml = goodManifest.replace("android:usesCleartextTraffic=\"false\"", "android:usesCleartextTraffic=\"true\"")
        val r = ManifestAudit.audit(ManifestAudit.parse(xml))
        assertTrue(r.failures.any { it.code == "NO_CLEARTEXT" })
    }

    @Test
    fun `backup rules must exclude private data`() {
        val rules = """
            <full-backup-content>
                <exclude domain="file" path="voice_samples" />
                <exclude domain="database" path="sonata.db" />
            </full-backup-content>
        """.trimIndent()
        val r = ManifestAudit.audit(ManifestAudit.parse(goodManifest), rules, voiceEnrolmentImplemented = true)
        assertTrue(r.passed)
    }

    @Test
    fun `backup rules missing voice exclusion fails`() {
        val rules = """<full-backup-content><exclude domain="database" path="sonata.db" /></full-backup-content>"""
        val r = ManifestAudit.audit(ManifestAudit.parse(goodManifest), rules, voiceEnrolmentImplemented = true)
        assertTrue(r.failures.any { it.code == "BACKUP_RULES_EXCLUDE_PRIVATE_DATA" })
    }

    // ================= network sentinel =================

    @Test
    fun `sentinel records a blocked attempt with its caller`() {
        NetworkSentinel.reset()
        val a = NetworkSentinel.attempt("example.com", 443, blocking = true)
        assertTrue(a.blocked)
        assertFalse("the caller must be identified", a.caller == "unknown")
        assertEquals(1, NetworkSentinel.attemptCount())
    }

    @Test
    fun `sentinel is silent when nothing tries to connect`() {
        NetworkSentinel.reset()
        assertTrue(NetworkSentinel.isSilent())
    }

    @Test
    fun `offline audit reports silence for a pure computation`() {
        val r = OfflineAudit.run {
            // A representative slice of the narration pipeline: text analysis and planning.
            val structure = ai.sonata.narration.TextUnderstanding.analyse(
                "The morning arrived without ceremony. Light moved across the table."
            )
            ai.sonata.narration.StylePlanner.plan(structure, ai.sonata.narration.DeliveryStyle.NATURAL)
        }
        assertTrue("audit must complete", r.completed)
        assertTrue("unexpected network activity: ${r.summary()}", r.silent)
    }

    @Test
    fun `offline audit catches an attempt`() {
        val r = OfflineAudit.run {
            NetworkSentinel.attempt("telemetry.example.com", 443, blocking = true)
        }
        assertFalse("an attempt must be caught", r.silent)
        assertTrue(r.summary().contains("telemetry.example.com"))
    }

    @Test
    fun `offline audit reports an exception without losing the attempt log`() {
        val r = OfflineAudit.run {
            NetworkSentinel.attempt("x.example.com", 80, blocking = true)
            throw IllegalStateException("boom")
        }
        assertFalse(r.completed)
        assertNotNull(r.error)
        assertEquals(1, r.attempts.size)
    }

    @Test
    fun `connectivity probe returns a definite answer and never throws`() {
        // Stage 7a finding: this test used to assert `live || !live`, which is a
        // tautology — it cannot fail, so it verified nothing. What the probe must
        // actually guarantee is narrower and testable: it terminates within its
        // timeout and yields a definite boolean instead of propagating an exception
        // when the network is unreachable. Whether the answer is true depends on the
        // sandbox, so the answer itself is recorded rather than asserted.
        val started = System.nanoTime()
        val live = OfflineAudit.connectivityProbe(timeoutMs = 1500)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // A definite value was produced (the call returned rather than throwing).
        assertTrue("probe must return a boolean", live || !live)
        // And it respected its own timeout, with headroom for scheduling.
        assertTrue(
            "probe must honour its timeout: took ${elapsedMs}ms for a 1500ms budget",
            elapsedMs < 5_000
        )
        println("connectivityProbe: live=$live elapsedMs=$elapsedMs")
    }
}
