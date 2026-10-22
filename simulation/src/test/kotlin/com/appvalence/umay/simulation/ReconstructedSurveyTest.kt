package com.appvalence.umay.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A camera survey through the pipeline the app runs today (see
 * [VirtualCameraSurveyor] with `reconstruct = true`). [CameraSurveyTest] runs the
 * older grid pipeline and never meets ObservedRoutes, which decides which walked
 * edges a camera map keeps.
 *
 * What this pins is safety: a route kept is one through the real building. How
 * many walked edges are kept is NOT pinned, because on this simulated depth it is
 * few (docs/YOL_HARITASI.md 0.20): the floor beside and under the surveyor is
 * rarely seen often enough to count as measured. That wants a real phone's depth
 * and a decision about how much unseen floor a walked edge may cross.
 */
class ReconstructedSurveyTest {
    private val office = VirtualBuildings.openOffice()
    private val steady = ArTrackingModel(jumpOnResume = 0.0)

    @Test
    fun `no edge the survey keeps goes through a real wall`() {
        for (seed in 1..3) {
            val survey = VirtualCameraSurveyor.survey(office, "giris", seed, tracking = steady, reconstruct = true)
            val before = survey.beforeRestriction!!
            val floor = survey.surveyed.map.floors.single()
            assertTrue(before.edges.size >= 10, "seed $seed: only ${before.edges.size} walked edges to begin with")
            assertTrue(floor.edges.size <= before.edges.size, "seed $seed: keeping edges cannot add any")
            val toPlan = survey.surveyed.toPlan()
            val nodes = floor.nodes.associateBy { it.id }
            for (edge in floor.edges) {
                val (ax, ay) = toPlan(nodes.getValue(edge.fromId).x, nodes.getValue(edge.fromId).y)
                val (bx, by) = toPlan(nodes.getValue(edge.toId).x, nodes.getValue(edge.toId).y)
                assertTrue(office.lineOfSight(ax, ay, bx, by), "seed $seed: kept ${edge.fromId}-${edge.toId}, which crosses a wall")
            }
        }
    }

    @Test
    fun `the survey walks every corridor and names its places`() {
        val survey = VirtualCameraSurveyor.survey(office, "giris", 1, tracking = steady, reconstruct = true)
        val floor = survey.surveyed.map.floors.single()
        assertEquals(20, floor.nodes.size)
        assertTrue(floor.reconstruction!!.walls.size >= 8, "walls found: ${floor.reconstruction!!.walls.size}")
        assertTrue(survey.wallPrecision >= 0.8, "wall precision ${survey.wallPrecision}")
    }
}
