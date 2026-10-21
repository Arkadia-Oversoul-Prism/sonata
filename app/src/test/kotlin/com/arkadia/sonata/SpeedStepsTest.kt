package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speed stepping, including the wrap that previously threw. */
class SpeedStepsTest {

    @Test
    fun `stepping from the maximum wraps to the slowest`() {
        // Regression: the overlay computed `index + 1 % size`, i.e. `index + 1`
        // unclamped, so stepping from 2.0x threw IndexOutOfBoundsException.
        assertEquals(SpeedSteps.SPEEDS.first(), SpeedSteps.next(SpeedSteps.SPEEDS.last()))
    }

    @Test
    fun `stepping never throws for any supported speed`() {
        for (speed in SpeedSteps.SPEEDS) {
            val next = SpeedSteps.next(speed)
            assertTrue("$speed -> $next must be a supported speed", next in SpeedSteps.SPEEDS)
        }
    }

    @Test
    fun `stepping advances through the list in order`() {
        assertEquals(0.75f, SpeedSteps.next(0.5f), 0.001f)
        assertEquals(1.0f, SpeedSteps.next(0.75f), 0.001f)
        assertEquals(1.25f, SpeedSteps.next(1.0f), 0.001f)
        assertEquals(1.5f, SpeedSteps.next(1.25f), 0.001f)
        assertEquals(1.75f, SpeedSteps.next(1.5f), 0.001f)
        assertEquals(2.0f, SpeedSteps.next(1.75f), 0.001f)
    }

    @Test
    fun `an unrecognised speed steps to the default`() {
        assertEquals(SpeedSteps.DEFAULT_SPEED, SpeedSteps.next(3.7f), 0.001f)
        assertEquals(SpeedSteps.DEFAULT_SPEED, SpeedSteps.next(0.0f), 0.001f)
    }

    @Test
    fun `every supported speed is inside the backend clamp range`() {
        // The backend clamps speed to [0.5, 2.0]; offering a value outside that
        // range would make the UI disagree with what is actually spoken.
        for (speed in SpeedSteps.SPEEDS) {
            assertEquals(speed, SpeedSteps.clamp(speed), 0.001f)
        }
    }

    @Test
    fun `clamp bounds out-of-range speeds`() {
        assertEquals(2.0f, SpeedSteps.clamp(9.0f), 0.001f)
        assertEquals(0.5f, SpeedSteps.clamp(0.1f), 0.001f)
    }

    @Test
    fun `indexOf falls back to the default for an unrecognised speed`() {
        assertEquals(SpeedSteps.SPEEDS.indexOf(1.0f), SpeedSteps.indexOf(1.0f))
        assertEquals(SpeedSteps.SPEEDS.indexOf(1.0f), SpeedSteps.indexOf(7.5f))
    }
}
