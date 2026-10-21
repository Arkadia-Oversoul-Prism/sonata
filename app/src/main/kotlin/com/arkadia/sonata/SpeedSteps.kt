package com.arkadia.sonata

/**
 * Speed stepping shared by the overlay and the settings UI.
 *
 * The overlay previously computed the next index as
 * `(index + 1 % speeds.size)`, where `%` binds tighter than `+`, so the index
 * was `index + 1` unclamped: at the maximum speed the index equalled the size
 * and threw `IndexOutOfBoundsException`. Stepping from an unrecognised speed
 * also had no defined landing point.
 */
object SpeedSteps {

    /** Selectable speeds, ascending. Every value is inside the backend's [0.5, 2.0] clamp. */
    val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    /**
     * The next speed after [current], wrapping to the slowest after the fastest.
     * An unrecognised [current] steps to [DEFAULT_SPEED].
     */
    fun next(current: Float): Float {
        val index = SPEEDS.indexOfFirst { it == current }
        if (index < 0) return DEFAULT_SPEED
        return SPEEDS[(index + 1) % SPEEDS.size]
    }

    /** Index of [speed] in [SPEEDS], or the index of [DEFAULT_SPEED] if unrecognised. */
    fun indexOf(speed: Float): Int =
        SPEEDS.indexOfFirst { it == speed }.takeIf { it >= 0 } ?: SPEEDS.indexOf(DEFAULT_SPEED)

    /** Clamp an arbitrary speed into the range the backend accepts. */
    fun clamp(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)

    const val DEFAULT_SPEED = 1.0f
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2.0f
}
