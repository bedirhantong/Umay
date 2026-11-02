package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.GeometryEvidence
import com.appvalence.umay.coremodel.MetricPoint
import com.appvalence.umay.coremodel.OpeningClearanceWitness
import com.appvalence.umay.coremodel.SurfaceVoxel
import com.appvalence.umay.coremodel.WallSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpeningDetectorTest {
    private fun wall(from: Double, to: Double) = WallSegment(
        MetricPoint(from, 0.0), MetricPoint(to, 0.0), 0.2, 1.85,
        supportingVoxels = 40, independentFrames = 4, residualMeters = 0.02,
        evidence = GeometryEvidence.MULTI_FRAME,
    )

    private val jambs = listOf(wall(-2.0, 0.0), wall(0.9, 2.0))
    private val lintel = listOf(0.2, 0.45, 0.7).map {
        SurfaceVoxel(it, 0.0, 2.05, frames = 4, lastFrame = 4)
    }
    private val witnesses = buildList {
        for (fraction in listOf(0.25, 0.5, 0.75)) for (height in listOf(0.6, 1.2, 1.6)) {
            add(OpeningClearanceWitness(MetricPoint(0.9 * fraction, 0.0), height, frames = 3, lastFrame = 4))
        }
    }

    @Test fun `missing wall is not classified without lintel and free volume evidence`() {
        assertTrue(OpeningDetector.extract(jambs, emptyList(), emptyList()).isEmpty())
        assertTrue(OpeningDetector.extract(jambs, lintel, emptyList()).isEmpty())
    }

    @Test fun `jamb lintel and repeated clearance rays produce candidate opening`() {
        val openings = OpeningDetector.extract(jambs, lintel, witnesses)
        assertEquals(1, openings.size)
        assertEquals(0.9, openings.single().end.x - openings.single().start.x, 1e-6)
        assertEquals(3, openings.single().independentFrames)
    }

    @Test fun `observed obstacle inside gap rejects opening`() {
        val obstruction = SurfaceVoxel(0.45, 0.0, 1.0, frames = 2, lastFrame = 4)
        assertTrue(OpeningDetector.extract(jambs, lintel + obstruction, witnesses).isEmpty())
    }

    @Test fun `clearance evidence counts each source frame once`() {
        val evidence = OpeningRayEvidence()
        val probe = OpeningDetector.probes(jambs).single()
        val origin = OpeningRayEvidence.Point(0.45, -1.0, 1.2)
        val hits = List(5) { OpeningRayEvidence.Point(0.45, 1.0, 1.2) }
        evidence.observe(origin, hits, listOf(probe), frame = 10)
        evidence.observe(origin, hits, listOf(probe), frame = 10)
        assertTrue(evidence.snapshot().all { it.frames == 1 })
        evidence.observe(origin, hits, listOf(probe), frame = 11)
        assertTrue(evidence.snapshot().all { it.frames == 2 })
    }

    @Test fun `lintel-only evidence cannot bridge wall extraction`() {
        val body = buildList {
            for (x in listOf(-1.0, -0.9, -0.8, -0.7, -0.6, -0.5, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)) {
                for (height in listOf(0.3, 0.6, 0.9, 1.2, 1.5, 1.8)) {
                    add(SurfaceVoxel(x, 0.0, height, frames = 3, lastFrame = 3))
                }
            }
        }
        val overhead = (-4..4).map { SurfaceVoxel(it / 10.0, 0.0, 2.05, frames = 3, lastFrame = 3) }
        val walls = StructuralWalls.extract(body + overhead)
        assertTrue(walls.none { it.start.x < -0.4 && it.end.x > 0.4 })
    }
}
