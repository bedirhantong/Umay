package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.*
import kotlin.math.*
import kotlin.test.*

class SpatialReconstructorTest {
    private val pose = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 1f,1.35f,-1f,1f)
    private fun frame(t: Long, confidence: ByteArray? = ByteArray(96*72) { 255.toByte() }): DepthFrame {
        val width = 96; val height = 72; val fx = 55f; val cy = 30f
        // Camera sees a floor plus a vertical wall at z=-4, all geometry in tests only.
        val depths = ShortArray(width*height) { i ->
            val down = (i/width-cy)/fx
            val floorDepth = if (down > 0) 1.35/down else Double.POSITIVE_INFINITY
            (min(3.0, floorDepth)*1000).toInt().toShort()
        }
        return DepthFrame(width,height,depths,confidence,fx,fx,48f,cy,pose,t)
    }

    @Test fun `depth uses calibrated camera signs and world translation`() {
        val f = DepthFrame(1,1,shortArrayOf(2000),byteArrayOf(255.toByte()),1f,1f,0f,0f,pose,1)
        val p = MetricDepth.points(f,1).single()
        assertEquals(1.0,p.x,1e-6); assertEquals(1.35,p.height,1e-6); assertEquals(-3.0,p.z,1e-6)
        val transform = ArToSurvey(90.0,10.0,5.0)
        assertEquals(13.0,transform.x(p.x,p.z),1e-6); assertEquals(4.0,transform.y(p.x,p.z),1e-6)
    }

    @Test fun `malformed calibration confidence and nonrigid poses are rejected`() {
        assertTrue(MetricDepth.points(frame(1,null)).isEmpty())
        val invalid = DepthFrame(2,2,ShortArray(4){1000},byteArrayOf(255.toByte()),1f,1f,0f,0f,pose,1)
        assertTrue(MetricDepth.points(invalid).isEmpty())
        val scaled = pose.copyOf().apply { this[0] = 2f }
        assertTrue(MetricDepth.points(DepthFrame(1,1,shortArrayOf(1000),byteArrayOf(255.toByte()),1f,1f,0f,0f,scaled,1),1).isEmpty())
    }

    @Test fun `repeated depth is not new evidence and no assumed floor produces walls`() {
        val engine = SpatialReconstructor("a")
        repeat(10) { engine.integrate(frame(1),ArToSurvey(0.0,0.0,0.0),1.35,listOf(0.0)) }
        assertEquals(1,engine.snapshot().integratedFrames)
        assertTrue(engine.snapshot().walls.isEmpty())
        val noPlanes = SpatialReconstructor("b")
        for (i in 1..8) noPlanes.integrate(frame(i.toLong()),ArToSurvey(0.0,0.0,0.0),1.35,emptyList())
        assertTrue(noPlanes.snapshot().walls.isEmpty())
        assertTrue(ReconstructionIssue.FLOOR_UNRESOLVED in noPlanes.snapshot().issues)
    }

    @Test fun `real depth fixture accumulates broad floor support and vertical segments`() {
        val engine = SpatialReconstructor("a")
        for (i in 1..8) engine.integrate(frame(i.toLong()),ArToSurvey(0.0,0.0,0.0),1.35,listOf(0.0))
        val map = engine.snapshot()
        assertNotNull(map.floor)
        assertTrue(map.walls.isNotEmpty())
        assertTrue(map.walls.all { it.independentFrames >= 3 && it.residualMeters < 0.07 })
        assertEquals(4.0,map.walls.first().start.y,0.08)
        engine.review(0,false)
        assertTrue(engine.snapshot().walls.isEmpty())
        assertEquals(1,engine.snapshot().rejectedWalls.size)
    }

    private fun wall(start: Double, end: Double, angle: Double = 0.37) = buildList {
        var along = start
        while (along < end) {
            for (h in 0..8) add(SurfaceVoxel(along*cos(angle),along*sin(angle),0.3+h*0.15,4,4))
            along += 0.1
        }
    }

    @Test fun `plane fit preserves oblique wall orientation and does not bridge unobserved opening`() {
        val walls = StructuralWalls.extract(wall(0.0,2.0)+wall(3.0,5.0))
        assertEquals(2,walls.size)
        for (w in walls) {
            val angle = atan2(w.end.y-w.start.y,w.end.x-w.start.x)
            assertEquals(0.37,angle,0.01)
            assertTrue(hypot(w.end.x-w.start.x,w.end.y-w.start.y) < 2.1)
        }
    }

    @Test fun `short furniture and isolated points do not make architectural segments`() {
        val desk = wall(0.0,2.0).filter { it.height < 0.6 }
        assertTrue(StructuralWalls.extract(desk).isEmpty())
        assertTrue(StructuralWalls.extract(wall(0.0,2.0).map { it.copy(frames=1) }).isEmpty())
    }

    @Test fun `session restart and impossible jump latch alignment requirement`() {
        val guard = MappingFrameGuard()
        assertNotNull(guard.accept("a",ArPose(0.0,1.35,0.0,0.0,1)))
        assertNull(guard.accept("b",ArPose(0.0,1.35,0.0,0.0,2)))
        assertTrue(guard.alignmentRequired)
        assertNull(guard.accept("a",ArPose(0.0,1.35,0.0,0.0,3)))
        val jumping = MappingFrameGuard()
        jumping.accept("a",ArPose(0.0,1.35,0.0,0.0,1))
        assertNull(jumping.accept("a",ArPose(10.0,1.35,0.0,0.0,100)))
    }

    @Test fun `a long tracking loss in one session is not a jump`() {
        val guard = MappingFrameGuard()
        guard.accept("a", ArPose(0.0, 1.35, 0.0, 0.0, 1_000))
        // Ten seconds covered, about twelve metres walked: ordinary walking, not a restart.
        assertNotNull(guard.accept("a", ArPose(12.0, 1.35, 0.0, 0.0, 11_000)))
        assertFalse(guard.alignmentRequired)
    }

    @Test fun `poses stamped on different clocks are compared on one`() {
        val guard = MappingFrameGuard()
        guard.accept("a", ArPose(0.0, 1.35, 0.0, 0.0, 1_000, timestampNs = 1_000_000_000L))
        // No nanosecond stamp on the second: their milliseconds decide, and a step forward is fine.
        assertNotNull(guard.accept("a", ArPose(0.5, 1.35, 0.0, 0.0, 1_500)))
    }

    @Test fun `a floor that drifts a few centimetres is the same floor, not new evidence from scratch`() {
        val engine = SpatialReconstructor("a")
        val identity = ArToSurvey(0.0, 0.0, 0.0)
        for (i in 1..5) assertTrue(engine.integrate(frame(i.toLong()), identity, 1.35, listOf(0.0)) || i < 3)
        // ARCore settles its estimate of the ground 10 cm higher (between the 8 cm "same" and 12 cm "different" limits).
        val shifted = pose.copyOf().apply { this[13] = 1.45f }
        val drifted = DepthFrame(96, 72, frame(0).depthMm, frame(0).confidence, 55f, 55f, 48f, 30f, shifted, 6)
        assertTrue(engine.integrate(drifted, identity, 1.45, listOf(0.1)), "the first frame after the drift is still used")
        assertEquals(6, engine.snapshot().floor!!.independentFrames, "the five earlier frames still count, plus this one")
        assertFalse(ReconstructionIssue.FLOOR_UNRESOLVED in engine.snapshot().issues)
    }

    @Test fun `endpoint observations do not certify a ray over an unseen obstacle as free`() {
        val grid = OccupancyGrid()
        repeat(5) { grid.addSurfaceFrame(listOf(0.05 to 3.05),listOf(0.05 to 4.05)) }
        assertEquals(OccupancyRaster.UNKNOWN,OccupancyGrid.classify(grid.logOdds(0.05,1.05)))
        assertEquals(OccupancyRaster.FREE,OccupancyGrid.classify(grid.logOdds(0.05,3.05)))
    }

    @Test fun `floor transform round trips and a changed frame never merges evidence`() {
        for (angle in listOf(0.0,37.0,90.0,181.0)) {
            val transform = ArToSurvey(angle,3.0,-8.0)
            val back = transform.toAr(transform.x(-2.0,5.0),transform.y(-2.0,5.0))
            assertEquals(-2.0,back.first,1e-9); assertEquals(5.0,back.second,1e-9)
        }
        val engine = SpatialReconstructor("a")
        engine.integrate(frame(1),ArToSurvey(0.0,0.0,0.0),1.35,listOf(0.0))
        assertFalse(engine.integrate(frame(2),ArToSurvey(0.0,1.0,0.0),1.35,listOf(0.0)))
        assertTrue(ReconstructionIssue.FRAME_ALIGNMENT_REQUIRED in engine.snapshot().issues)
        assertEquals(MetricFrameTransform(0.0,0.0,0.0),engine.snapshot().worldToFloor)
    }

    @Test fun `supported corners intersect but unrelated endpoints are never joined`() {
        fun segment(a: MetricPoint,b: MetricPoint) = WallSegment(a,b,0.3,1.8,30,4,0.01)
        val wall = segment(MetricPoint(0.0,0.0),MetricPoint(2.0,0.0))
        val corner = segment(MetricPoint(2.0,0.0),MetricPoint(2.0,2.0))
        assertEquals(listOf(MetricPoint(2.0,0.0)),StructuralWalls.corners(listOf(wall,corner)))
        assertTrue(StructuralWalls.corners(listOf(wall,corner.copy(start=MetricPoint(2.0,1.0)))).isEmpty())
        val cross = segment(MetricPoint(1.0,-1.0),MetricPoint(1.0,1.0))
        assertTrue(StructuralWalls.conflicts(listOf(wall,cross)))
    }

    @Test fun `pose ordering uses AR timestamps without assuming their epoch matches wall time`() {
        val guard = MappingFrameGuard()
        assertNotNull(guard.accept("a",ArPose(0.0,1.35,0.0,0.0,1000,1_000_000_000)))
        assertNotNull(guard.accept("a",ArPose(0.0,1.35,-0.1,0.0,500,1_100_000_000)))
        assertNull(guard.accept("a",ArPose(0.0,1.35,-0.1,0.0,1500,1_100_000_000)))
        assertFalse(guard.alignmentRequired)
    }


    @Test fun `a diagonal vertical wall slice cannot masquerade as broad floor support`() {
        val c = cos(PI/4).toFloat(); val s = sin(PI/4).toFloat()
        val rotated = floatArrayOf(c,0f,-s,0f, 0f,1f,0f,0f, s,0f,c,0f, 0f,1.35f,0f,1f)
        val engine = SpatialReconstructor("a")
        for (i in 1..8) {
            val depth = DepthFrame(96,72,ShortArray(96*72){3000},ByteArray(96*72){255.toByte()},
                55f,55f,48f,30f,rotated,i.toLong())
            engine.integrate(depth,ArToSurvey(0.0,0.0,0.0),1.35,listOf(0.0))
        }
        assertNull(engine.snapshot().floor)
        assertTrue(engine.snapshot().walls.isEmpty())
        assertTrue(ReconstructionIssue.FLOOR_UNRESOLVED in engine.snapshot().issues)
    }
}
