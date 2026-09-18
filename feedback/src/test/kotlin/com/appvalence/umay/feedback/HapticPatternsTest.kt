package com.appvalence.umay.feedback

import com.appvalence.umay.coremodel.GuidanceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HapticPatternsTest {

    private val cued = GuidanceCue.entries - GuidanceCue.NONE

    @Test
    fun `a cue-less alert never vibrates`() {
        assertNull(HapticPatterns.forCue(GuidanceCue.NONE))
    }

    @Test
    fun `every cue has its own distinguishable pattern`() {
        val signatures = cued.map { cue -> HapticPatterns.forCue(cue)!!.let { it.timingsMs.toList() to it.amplitudes.toList() } }

        assertEquals(cued.size, signatures.toSet().size)
    }

    @Test
    fun `left and right turns differ in rhythm, not just length`() {
        val right = HapticPatterns.forCue(GuidanceCue.TURN_RIGHT)!!.timingsMs.filterIndexed { i, _ -> i % 2 == 0 }.drop(1).toSet()
        val left = HapticPatterns.forCue(GuidanceCue.TURN_LEFT)!!.timingsMs.filterIndexed { i, _ -> i % 2 == 0 }.drop(1).toSet()

        assertEquals(1, right.size, "right turn: evenly spaced taps")
        assertEquals(2, left.size, "left turn: short gaps inside a pair, long gaps between pairs")
    }

    @Test
    fun `no pattern runs long enough to overlap the next alert`() {
        cued.forEach { cue -> assertTrue(HapticPatterns.forCue(cue)!!.totalDurationMs <= 1_500, "$cue is too long") }
    }

    @Test
    fun `a critical obstacle is the strongest pattern there is`() {
        val critical = HapticPatterns.forCue(GuidanceCue.OBSTACLE_CRITICAL)!!.amplitudes.max()

        assertEquals(255, critical)
        assertTrue(HapticPatterns.forCue(GuidanceCue.CONTINUE)!!.amplitudes.max() < critical)
    }
}
