package com.appvalence.umay.feedback

import com.appvalence.umay.coremodel.GuidanceCue

/**
 * A vibration waveform in Android's convention: [timingsMs] alternates
 * off/on durations starting with an initial delay, and [amplitudes] gives
 * each segment's strength (0 = off, 255 = strongest; ignored on pre-O
 * devices, which only have on/off).
 */
class HapticPattern(val timingsMs: LongArray, val amplitudes: IntArray) {
    init {
        require(timingsMs.size == amplitudes.size) { "each timing needs an amplitude" }
    }

    val totalDurationMs: Long get() = timingsMs.sum()
}

/**
 * One distinct, learnable vibration per [GuidanceCue], so guidance still gets
 * through when speech can't be heard (a noisy corridor, headphones off, the
 * phone in a pocket).
 *
 * The turn patterns follow Apple Watch's turn-by-turn haptics, the
 * convention most blind iPhone/Watch users already know: a *steady series*
 * of taps for right, *pairs* of taps for left — different in rhythm, not just
 * in count, which is far easier to tell apart while walking. Everything that
 * means "stop / danger" is strong and fast; everything informational is
 * softer. All patterns stay under ~1.5 s so one never runs into the next.
 */
object HapticPatterns {
    private const val SOFT = 110
    private const val MEDIUM = 180
    private const val STRONG = 255

    private fun pattern(vararg segments: Pair<Long, Int>): HapticPattern =
        HapticPattern(segments.map { it.first }.toLongArray(), segments.map { it.second }.toIntArray())

    private fun off(ms: Long) = ms to 0
    private fun on(ms: Long, amplitude: Int) = ms to amplitude

    fun forCue(cue: GuidanceCue): HapticPattern? = when (cue) {
        GuidanceCue.NONE -> null
        GuidanceCue.CONTINUE -> pattern(off(0), on(40, SOFT))
        GuidanceCue.TURN_RIGHT -> pattern(
            off(0), on(60, MEDIUM), off(110), on(60, MEDIUM), off(110), on(60, MEDIUM), off(110), on(60, MEDIUM),
        )
        GuidanceCue.TURN_LEFT -> pattern(
            off(0), on(60, MEDIUM), off(70), on(60, MEDIUM),
            off(260), on(60, MEDIUM), off(70), on(60, MEDIUM),
            off(260), on(60, MEDIUM), off(70), on(60, MEDIUM),
        )
        GuidanceCue.HAZARD_AHEAD -> pattern(off(0), on(350, STRONG), off(180), on(350, STRONG))
        GuidanceCue.FLOOR_CHANGE -> pattern(off(0), on(300, MEDIUM), off(150), on(80, MEDIUM), off(150), on(300, MEDIUM))
        GuidanceCue.ARRIVED -> pattern(off(0), on(500, MEDIUM), off(150), on(120, MEDIUM))
        GuidanceCue.OBSTACLE_WARNING -> pattern(off(0), on(140, MEDIUM), off(100), on(140, MEDIUM), off(100), on(140, MEDIUM))
        GuidanceCue.OBSTACLE_CRITICAL -> pattern(
            off(0), on(110, STRONG), off(50), on(110, STRONG), off(50), on(110, STRONG), off(50), on(110, STRONG), off(50), on(110, STRONG),
        )
        GuidanceCue.REROUTED -> pattern(off(0), on(80, SOFT), off(120), on(280, MEDIUM))
        GuidanceCue.NO_ROUTE -> pattern(off(0), on(700, MEDIUM))
    }
}
