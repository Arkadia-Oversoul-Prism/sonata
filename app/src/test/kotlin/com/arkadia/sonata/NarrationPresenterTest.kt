package com.arkadia.sonata

import com.arkadia.sonata.engine.NarrationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationPresenterTest {

    @Test
    fun success_names_the_title_and_duration_and_claims_saved() {
        val report = NarrationPresenter.report(
            NarrationOutcome.Success(
                narrationId = 7L,
                audioPath = "/data/user/0/com.arkadia.sonata/files/7.wav",
                durationMs = 65_000L,
            ),
            title = "The NASA story",
        )

        assertTrue(report.saved)
        assertEquals(7L, report.narrationId)
        assertTrue(report.message.contains("The NASA story"))
        assertTrue(report.message.contains("1:05"))
    }

    @Test
    fun failure_carries_the_reason_and_never_claims_saved() {
        val report = NarrationPresenter.report(
            NarrationOutcome.Failure("No speech engine is available on this device"),
            title = "Anything",
        )

        assertFalse(report.saved)
        assertNull(report.narrationId)
        assertEquals("No speech engine is available on this device", report.message)
    }

    // Negative control: the engine reported Success, but no file was named. A
    // saved-audio claim here would offer the user a row the library cannot play.
    @Test
    fun a_success_with_no_path_is_not_reported_as_saved() {
        val report = NarrationPresenter.report(
            NarrationOutcome.Success(narrationId = 9L, audioPath = "  ", durationMs = 1_000L),
            title = "Anything",
        )

        assertFalse(report.saved)
        assertNull(report.narrationId)
        assertEquals(NarrationPresenter.NOT_WRITTEN, report.message)
    }

    @Test
    fun duration_is_minutes_with_padded_seconds() {
        assertEquals("0:00", NarrationPresenter.formatDuration(0L))
        assertEquals("0:00", NarrationPresenter.formatDuration(-5L))
        assertEquals("0:07", NarrationPresenter.formatDuration(7_400L))
        assertEquals("1:05", NarrationPresenter.formatDuration(65_000L))
        assertEquals("10:00", NarrationPresenter.formatDuration(600_000L))
    }
}
