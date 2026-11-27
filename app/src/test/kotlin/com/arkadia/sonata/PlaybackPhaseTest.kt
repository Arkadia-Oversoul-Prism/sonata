package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The presentation contract: which phase the screen is in, given what the engine
 * and the screen know.
 *
 * The two terminal phases exist because the engine cannot report them. After a
 * read finishes the engine is idle, and after a failure the service stops — so
 * without an outcome the screen showed "Ready" for both, which is the defect this
 * contract exists to remove.
 */
class PlaybackPhaseTest {

    private fun observe(
        bound: Boolean = true,
        playing: Boolean = false,
        paused: Boolean = false,
        bufferingNext: Boolean = false,
        preparing: Boolean = false,
        completed: Boolean = false,
        failure: String? = null,
    ) = PlaybackObservation(
        bound = bound,
        playing = playing,
        paused = paused,
        bufferingNext = bufferingNext,
        preparing = preparing,
        completed = completed,
        failure = failure,
    )

    @Test
    fun `unbound with nothing in flight is starting`() {
        assertEquals(PlaybackPhase.STARTING, PlaybackPhasePolicy.decide(observe(bound = false)))
    }

    @Test
    fun `bound and idle is ready`() {
        assertEquals(PlaybackPhase.READY, PlaybackPhasePolicy.decide(observe()))
    }

    @Test
    fun `a dispatched read with no audio yet is preparing`() {
        val phase = PlaybackPhasePolicy.decide(observe(preparing = true))
        assertEquals(PlaybackPhase.PREPARING, phase)
    }

    @Test
    fun `audio playing is playing`() {
        assertEquals(PlaybackPhase.PLAYING, PlaybackPhasePolicy.decide(observe(playing = true)))
    }

    @Test
    fun `playing while the next piece is fetched is buffering`() {
        val phase = PlaybackPhasePolicy.decide(observe(playing = true, bufferingNext = true))
        assertEquals(PlaybackPhase.BUFFERING, phase)
    }

    @Test
    fun `paused is paused`() {
        assertEquals(PlaybackPhase.PAUSED, PlaybackPhasePolicy.decide(observe(paused = true)))
    }

    @Test
    fun `a read that reached the end is completed, not ready`() {
        // The pre-repair screen reported READY here: the engine is idle and nothing
        // carried the fact that the passage had been read to the end.
        assertEquals(PlaybackPhase.COMPLETED, PlaybackPhasePolicy.decide(observe(completed = true)))
    }

    @Test
    fun `a read that stopped with an error is failed, not ready`() {
        val phase = PlaybackPhasePolicy.decide(observe(failure = "No voice installed"))
        assertEquals(PlaybackPhase.FAILED, phase)
    }

    @Test
    fun `completion survives the service going away`() {
        // onComplete is delivered before stopSelf, and the disconnect that follows
        // clears `bound`. A completion that needed a bound service would be lost
        // exactly when it happened.
        val phase = PlaybackPhasePolicy.decide(observe(bound = false, completed = true))
        assertEquals(PlaybackPhase.COMPLETED, phase)
    }

    @Test
    fun `a stale completed flag cannot hide a read that is playing`() {
        val phase = PlaybackPhasePolicy.decide(observe(playing = true, completed = true))
        assertEquals(PlaybackPhase.PLAYING, phase)
    }

    @Test
    fun `a stale failure cannot hide a read that is playing`() {
        // The offline fallback reports an error and then keeps reading. The message
        // belongs in a notice; it must not turn a live read into a stopped screen.
        val phase = PlaybackPhasePolicy.decide(
            observe(playing = true, failure = "Network unreachable"),
        )
        assertEquals(PlaybackPhase.PLAYING, phase)
    }

    @Test
    fun `pausing outranks a fallback failure`() {
        val phase = PlaybackPhasePolicy.decide(
            observe(paused = true, failure = "Network unreachable"),
        )
        assertEquals(PlaybackPhase.PAUSED, phase)
    }

    @Test
    fun `failure outranks completion when both linger`() {
        // A read cannot both finish and fail. If both flags survive a wiring slip,
        // the error is the later fact and reporting "Finished" would be a lie.
        val phase = PlaybackPhasePolicy.decide(observe(completed = true, failure = "Stopped"))
        assertEquals(PlaybackPhase.FAILED, phase)
    }

    @Test
    fun `a failed preparation is failed, not preparing`() {
        // preparing and failure can be set by the same read: dispatch sets the wait,
        // then the engine refuses. The refusal is the truth.
        val phase = PlaybackPhasePolicy.decide(observe(preparing = true, failure = "No voice"))
        assertEquals(PlaybackPhase.FAILED, phase)
    }
}
