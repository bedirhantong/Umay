package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.positioning.mapping.SurveyLoopClosure.Stop
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurveyLoopClosureTest {
    @Test
    fun `a walk that never comes back to a place is left as it is`() {
        val stops = listOf(Stop(0.0, 0.0, 0, 0), Stop(0.0, 10.0, 0, 1), Stop(10.0, 10.0, 0, 2))
        assertTrue(SurveyLoopClosure.corrections(stops).all { it == 0.0 to 0.0 })
    }

    @Test
    fun `coming back to the start pulls the two visits together and spreads the gap along the walk`() {
        // A square whose end missed its start by 4 m east.
        val stops = listOf(Stop(0.0, 0.0, 0, 0), Stop(0.0, 10.0, 0, 1), Stop(10.0, 10.0, 0, 2), Stop(10.0, 0.0, 0, 3), Stop(4.0, 0.0, 0, 0))
        val corrections = SurveyLoopClosure.corrections(stops)
        val corrected = stops.mapIndexed { i, s -> (s.x + corrections[i].first) to (s.y + corrections[i].second) }

        assertTrue(hypot(corrected[0].first - corrected[4].first, corrected[0].second - corrected[4].second) < 0.05, "$corrected")
        // Each stretch takes its share: the stops in between move west progressively.
        assertTrue(corrections[1].first > corrections[2].first && corrections[2].first > corrections[3].first, "$corrections")
    }

    @Test
    fun `stops on another floor are kept out of this floor's loops`() {
        val stops = listOf(Stop(0.0, 0.0, 0, 0), Stop(5.0, 5.0, 1, 1), Stop(3.0, 0.0, 0, 0))
        val corrections = SurveyLoopClosure.corrections(stops)
        assertEquals(0.0, corrections[1].first, 1e-6)
    }

    @Test
    fun `corrections reach every point of the path in proportion`() {
        val spread = SurveyLoopClosure.spread(5, listOf(0, 4), listOf(0.0 to 0.0, -4.0 to 0.0))
        assertEquals(listOf(0.0, -1.0, -2.0, -3.0, -4.0), spread.map { it.first })
    }
}
