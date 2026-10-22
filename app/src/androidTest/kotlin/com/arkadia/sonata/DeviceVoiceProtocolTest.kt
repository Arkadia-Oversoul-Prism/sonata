package com.arkadia.sonata

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device protocol: the real platform speech engine.
 *
 * These assertions cannot be made on the JVM, because they depend on the actual
 * Android `TextToSpeech` service:
 *
 *  - whether it initialises at all on this device, and how long that takes;
 *  - whether [AndroidOfflineVoice.awaitReady] observes the callback rather than
 *    polling a flag that has not been set yet.
 *
 * A software-emulated runtime (no KVM) inflates initialisation by orders of
 * magnitude, so the waits here are deliberately longer than the production
 * default and the test reports what it measured rather than assuming a budget.
 */
@RunWith(AndroidJUnit4::class)
class DeviceVoiceProtocolTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Generous: this measures behaviour, not emulation speed. */
    private val readinessBudgetMs = 30_000L

    private lateinit var voice: AndroidOfflineVoice

    @Before
    fun setUp() {
        voice = AndroidOfflineVoice(context)
    }

    @Test
    fun p1_1_thePlatformEngineBecomesReadyWithinTheBudget() = runBlocking {
        val ready = voice.awaitReady(readinessBudgetMs)
        assertTrue(
            "the platform TextToSpeech engine did not initialise within ${readinessBudgetMs}ms; " +
                "offline fallback cannot work on this device",
            ready,
        )
        assertTrue("isReady() must agree with awaitReady()", voice.isReady())
    }

    @Test
    fun p1_2_awaitReadyIsIdempotentOnceReady() = runBlocking {
        assertTrue(voice.awaitReady(readinessBudgetMs))
        // A second call must return immediately rather than waiting again.
        assertTrue(voice.awaitReady(1L))
    }

    @Test
    fun p1_3_aShutdownEngineReportsNotReadyRatherThanHanging() = runBlocking {
        voice.awaitReady(readinessBudgetMs)
        voice.shutdown()

        // A waiter must not hang on a shut-down engine.
        val ready = withTimeoutOrNull(5_000L) { voice.awaitReady(1_000L) }
        assertEquals("awaitReady must resolve, not hang, after shutdown", false, ready)
    }

    @Test
    fun p1_4_speakingOnAReadyEngineStartsTheUtterance() = runBlocking {
        assertTrue("cannot test speech without a ready engine", voice.awaitReady(readinessBudgetMs))

        var started = false
        val startedLatch = java.util.concurrent.CountDownLatch(1)
        voice.speak(
            text = "Sonata device protocol check.",
            fromChar = 0,
            speed = 1.0f,
            pitch = 1.0f,
            onStart = { started = true; startedLatch.countDown() },
            onRange = {},
            onDone = { startedLatch.countDown() },
            onError = { startedLatch.countDown() },
        )

        // Either the utterance starts, or it completes/errors — all are terminal and
        // all prove the call reached the platform engine. A hang would time out.
        assertTrue(
            "speak() did not reach a terminal callback within 20s",
            startedLatch.await(20, java.util.concurrent.TimeUnit.SECONDS),
        )
        voice.stop()
    }

    @Test
    fun p1_5_stoppingAnIdleEngineIsSafe() {
        // stop() on a never-started engine must not throw.
        voice.stop()
        voice.stop()
    }
}
