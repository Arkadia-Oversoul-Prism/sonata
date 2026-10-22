package com.arkadia.sonata

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device protocol: the real OkHttp stack against a real HTTP server.
 *
 * The JVM unit tests exercise the engine through a fake transport, so they cannot
 * see anything the network layer does — including the cleartext policy, which is a
 * platform decision made below OkHttp. These tests run the shipped
 * [OkHttpTtsTransport] against `MockWebServer` over loopback.
 *
 * Cleartext to loopback is permitted by the **debug-only** network security config
 * (`app/src/debug/res/xml/network_security_config.xml`). The release build has no
 * such config and refuses cleartext for every host, so this suite is meaningful
 * only against the debug variant — which is the variant instrumented tests run
 * against.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTransportProtocolTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpTtsTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = OkHttpTtsTransport()
    }

    @After
    fun tearDown() {
        transport.close()
        server.shutdown()
    }

    private fun audioResponse(bytes: ByteArray = ByteArray(128) { 7 }): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "audio/mpeg")
            .setHeader("X-TTS-Engine", "edge_tts")
            .setBody(Buffer().write(bytes))

    @Test
    fun p2_1_loopbackCleartextIsPermittedByTheDebugPolicy() = runBlocking {
        server.enqueue(audioResponse())

        val result = transport.fetch("hello", "aetheria", 1.0f, server.url("/").toString())

        // If the debug policy were missing, Android would block this with
        // UnknownServiceException and we would get a Failure naming CLEARTEXT.
        assertTrue(
            "loopback cleartext was blocked; the debug network security config is not in effect: $result",
            result is SynthesisResult.Success,
        )
        assertEquals("edge_tts", (result as SynthesisResult.Success).engineLabel)
        assertEquals(128, result.bytes.size)
    }

    @Test
    fun p2_2_theRequestPayloadMatchesTheBackendContract() = runBlocking {
        server.enqueue(audioResponse())

        transport.fetch("spoken text", "aetheria", 1.25f, server.url("/").toString())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/tts", recorded.path)

        val body = recorded.body.readUtf8()
        // The backend contract: text, voice, speed (speed clamped to [0.5, 2.0]).
        assertTrue("text missing from payload: $body", body.contains("\"text\""))
        assertTrue("voice missing from payload: $body", body.contains("\"voice\""))
        assertTrue("speed missing from payload: $body", body.contains("\"speed\""))
        assertTrue("speed not clamped: $body", body.contains("1.25"))
    }

    @Test
    fun p2_3_anHttpFailureIsReportedWithItsStatusCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("upstream failed"))

        val result = transport.fetch("hello", "aetheria", 1.0f, server.url("/").toString())

        assertTrue(result is SynthesisResult.Failure)
        assertTrue(
            "the reason must name the status: ${(result as SynthesisResult.Failure).reason}",
            result.reason.contains("502"),
        )
    }

    @Test
    fun p2_4_aNonAudioContentTypeIsRejected() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"nope"}"""),
        )

        val result = transport.fetch("hello", "aetheria", 1.0f, server.url("/").toString())

        assertTrue(result is SynthesisResult.Failure)
        assertTrue(
            "the reason must name the content type: ${(result as SynthesisResult.Failure).reason}",
            result.reason.contains("application/json"),
        )
    }

    @Test
    fun p2_5_anEmptyBodyIsRejected() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(""),
        )

        val result = transport.fetch("hello", "aetheria", 1.0f, server.url("/").toString())

        assertTrue(result is SynthesisResult.Failure)
        assertTrue(
            "the reason must say the audio was empty: ${(result as SynthesisResult.Failure).reason}",
            result.reason.contains("empty", ignoreCase = true),
        )
    }

    @Test
    fun p2_6_anUnreachableHostFailsRatherThanThrowing() = runBlocking {
        // Port 1 on loopback: nothing listens there.
        val result = transport.fetch("hello", "aetheria", 1.0f, "http://127.0.0.1:1/")

        assertTrue("an unreachable host must be a Failure, not an exception", result is SynthesisResult.Failure)
    }

    @Test
    fun p2_7_aMalformedUrlFailsRatherThanThrowing() = runBlocking {
        val result = transport.fetch("hello", "aetheria", 1.0f, "not a url")

        assertTrue(result is SynthesisResult.Failure)
    }

    @Test
    fun p2_8_anEmptyBackendUrlFailsWithAConfigurationReason() = runBlocking {
        val result = transport.fetch("hello", "aetheria", 1.0f, "   ")

        assertTrue(result is SynthesisResult.Failure)
        assertTrue(
            "the reason must name the configuration problem: ${(result as SynthesisResult.Failure).reason}",
            result.reason.contains("No backend URL", ignoreCase = true),
        )
    }

    @Test
    fun p2_9_theCleartextBlockIsNamedInTheReason() {
        // The user-facing reason for a cleartext block must be actionable, not an
        // exception class name. Verified against the shipped formatter.
        val blocked = java.net.UnknownServiceException(
            "CLEARTEXT communication to example.invalid not permitted by network security policy",
        )
        val reason = OkHttpTtsTransport.describeFailure(blocked)

        assertTrue("the reason must name the block: $reason", reason.contains("plain HTTP"))
        assertTrue("the reason must say what to do: $reason", reason.contains("https://"))
    }
}
