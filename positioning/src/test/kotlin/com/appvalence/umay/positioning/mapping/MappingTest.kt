package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.OccupancyRaster
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OccupancyGridTest {

    @Test
    fun `a wall seen twice becomes a wall, and the floor in front of it free`() {
        val grid = OccupancyGrid()
        repeat(2) { grid.addRay(0.05, 0.05, 0.05, 3.05, hit = true) }
        repeat(3) { grid.addRay(0.05, 0.05, 0.05, 3.05, hit = true) }

        assertEquals(OccupancyRaster.OCCUPIED, OccupancyGrid.classify(grid.logOdds(0.05, 3.05)))
        assertEquals(OccupancyRaster.FREE, OccupancyGrid.classify(grid.logOdds(0.05, 1.5)))
    }

    @Test
    fun `someone walking past once is cleared by later views of the floor behind them`() {
        val grid = OccupancyGrid()
        grid.addRay(0.05, 0.05, 0.05, 1.55, hit = true) // a person at 1.5 m
        repeat(6) { grid.addRay(0.05, 0.05, 0.05, 3.05, hit = true) } // the wall behind, seen through them

        assertEquals(OccupancyRaster.FREE, OccupancyGrid.classify(grid.logOdds(0.05, 1.55)))
    }

    @Test
    fun `a snapshot covers exactly what was seen, in any direction from the origin`() {
        val grid = OccupancyGrid()
        assertNull(grid.snapshot())
        repeat(3) {
            grid.addRay(0.05, 0.05, -2.05, 0.05, hit = true)
            grid.addRay(0.05, 0.05, 0.05, -1.05, hit = true)
        }

        val raster = assertNotNull(grid.snapshot())
        val cells = assertNotNull(raster.decodeCells())

        assertEquals(-2.1, raster.originX, 1e-9)
        assertEquals(-1.1, raster.originY, 1e-9)
        fun at(x: Double, y: Double) = cells[((y - raster.originY) / 0.1).toInt() * raster.width + ((x - raster.originX) / 0.1).toInt()]
        assertEquals(OccupancyRaster.OCCUPIED, at(-2.05, 0.05))
        assertEquals(OccupancyRaster.OCCUPIED, at(0.05, -1.05))
        assertEquals(OccupancyRaster.FREE, at(-1.0, 0.05))
    }

    @Test
    fun `a corrupt measurement is ignored instead of looping or drawing at the origin`() {
        val grid = OccupancyGrid()
        grid.addRay(0.0, 0.0, Double.NaN, 1.0, hit = true)
        grid.addRay(0.0, 0.0, 1e12, 1.0, hit = true)

        assertTrue(grid.isEmpty)
    }
}

class DepthProjectorTest {

    private val cameraHeight = 1.35

    /** A camera standing at the AR origin, looking along −Z (level, upright). */
    private fun levelPose(x: Float = 0f, z: Float = 0f) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        x, cameraHeight.toFloat(), z, 1f,
    )

    private val width = 64
    private val height = 48
    private val fx = 50f
    private val cx = 32f
    private val cy = 24f

    private fun frame(depthAt: (column: Int, row: Int) -> Double, pose: FloatArray = levelPose()) = DepthFrame(
        width = width,
        height = height,
        depthMm = ShortArray(width * height) { (depthAt(it % width, it / width) * 1000).toInt().toShort() },
        confidence = null,
        fx = fx, fy = fx, cx = cx, cy = cy,
        cameraPose = pose,
    )

    @Test
    fun `a wall 2 m ahead lands 2 m ahead on the plan, with free floor before it`() {
        val grid = OccupancyGrid()
        val wall = frame({ _, _ -> 2.0 })
        val identity = ArToSurvey(0.0, 0.0, 0.0)

        repeat(3) { DepthProjector.integrate(wall, floorY = 0.0, toSurvey = identity, grid = grid) }

        // AR −Z is plan +y.
        assertEquals(OccupancyRaster.OCCUPIED, OccupancyGrid.classify(grid.logOdds(0.0, 2.0)))
        assertEquals(OccupancyRaster.FREE, OccupancyGrid.classify(grid.logOdds(0.0, 1.0)))
        assertEquals(OccupancyRaster.UNKNOWN, OccupancyGrid.classify(grid.logOdds(0.0, -1.0)))
    }

    @Test
    fun `the plan transform places the wall wherever the survey says the camera is`() {
        val grid = OccupancyGrid()
        // Surveying started facing what the plan calls east, 10 m along x.
        val toSurvey = ArToSurvey(90.0, 10.0, 0.0)

        repeat(3) { DepthProjector.integrate(frame({ _, _ -> 2.0 }), floorY = 0.0, toSurvey = toSurvey, grid = grid) }

        assertEquals(OccupancyRaster.OCCUPIED, OccupancyGrid.classify(grid.logOdds(12.0, 0.0)))
        assertEquals(OccupancyRaster.FREE, OccupancyGrid.classify(grid.logOdds(11.0, 0.0)))
    }

    @Test
    fun `the floor seen ahead is open space, and the ceiling is ignored`() {
        val grid = OccupancyGrid()
        // Lower half of the image sees the floor; upper half sees a ceiling 1.5 m above the camera.
        val view = frame({ _, row ->
            val down = (row - cy) / fx
            when {
                down > 0.4 -> cameraHeight / down // ray meets the floor at this z-depth
                down < -0.2 -> 1.5 / -down // ceiling
                else -> 0.0 // nothing measured
            }
        })

        val used = DepthProjector.integrate(view, floorY = 0.0, toSurvey = ArToSurvey(0.0, 0.0, 0.0), grid = grid)
        repeat(3) { DepthProjector.integrate(view, floorY = 0.0, toSurvey = ArToSurvey(0.0, 0.0, 0.0), grid = grid) }

        assertTrue(used > 0)
        val snapshot = assertNotNull(grid.snapshot())
        val cells = snapshot.decodeCells()!!
        assertTrue(cells.none { it == OccupancyRaster.OCCUPIED }, "the ceiling or floor was drawn as a wall")
        assertTrue(cells.count { it == OccupancyRaster.FREE } > 50, "too little open floor")
        // The floor here is seen between ~2.9 m and ~3.4 m ahead.
        assertTrue(snapshot.originY >= 0.0 && snapshot.originY + snapshot.height * snapshot.cellSizeMeters <= 3.6)
    }

    @Test
    fun `low-confidence and out-of-range pixels are skipped`() {
        val grid = OccupancyGrid()
        val farAway = frame({ _, _ -> 9.0 })
        val doubtful = DepthFrame(width, height, ShortArray(width * height) { 2000 }, ByteArray(width * height) { 40 }, fx, fx, cx, cy, levelPose())

        assertEquals(0, DepthProjector.integrate(farAway, 0.0, ArToSurvey(0.0, 0.0, 0.0), grid))
        assertEquals(0, DepthProjector.integrate(doubtful, 0.0, ArToSurvey(0.0, 0.0, 0.0), grid))
        assertTrue(grid.isEmpty)
    }
}

class ArPoseAlignerTest {

    private fun pose(x: Double, z: Double, t: Long, heading: Double = 0.0) = ArPose(x, 1.4, z, heading, t)

    @Test
    fun `the first pose is the survey's start, and walking forward is plan north`() {
        val aligner = ArPoseAligner(startX = 5.0, startY = 7.0)

        val start = aligner.onTracked(pose(1.0, -2.0, 0))
        val later = aligner.onTracked(pose(1.0, -4.0, 2_000))

        assertEquals(5.0, start.x, 1e-9)
        assertEquals(7.0, start.y, 1e-9)
        assertEquals(5.0, later.x, 1e-9)
        assertEquals(9.0, later.y, 1e-9)
    }

    @Test
    fun `small corrections ARCore makes while tracking are kept`() {
        val aligner = ArPoseAligner()
        aligner.onTracked(pose(0.0, 0.0, 0))

        val corrected = aligner.onTracked(pose(0.4, 0.0, 33))

        assertEquals(0.4, corrected.x, 1e-9)
    }

    @Test
    fun `an implausible jump after a loss is re-anchored where the walk left off`() {
        val aligner = ArPoseAligner()
        aligner.onTracked(pose(0.0, 0.0, 0))
        aligner.onTracked(pose(0.0, -3.0, 3_000))
        aligner.onTrackingLost()

        // Back after 10 s of standing still, but 25 m away: not something ARCore could correct to.
        val resumed = aligner.onTracked(pose(20.0, 15.0, 13_000))
        val next = aligner.onTracked(pose(20.0, 14.0, 14_000))

        assertEquals(0.0, resumed.x, 1e-9)
        assertEquals(3.0, resumed.y, 1e-9)
        assertEquals(4.0, next.y, 1e-9, "walking on continues from the re-anchored point")
    }

    @Test
    fun `a resumed pose within reach of the footsteps taken meanwhile is believed`() {
        val aligner = ArPoseAligner()
        aligner.onTracked(pose(0.0, 0.0, 0))
        aligner.onTrackingLost()
        repeat(7) { aligner.onStep(0.7) } // ~5 m walked blind

        val resumed = aligner.onTracked(pose(0.0, -5.2, 6_000))

        assertEquals(5.2, resumed.y, 1e-9)
    }

    @Test
    fun `when a resumed pose can't be believed, the footsteps taken meanwhile still count`() {
        val aligner = ArPoseAligner()
        aligner.onTracked(pose(0.0, 0.0, 0, heading = 90.0)) // facing plan east
        aligner.onTrackingLost()
        repeat(3) { aligner.onStep(1.0) }

        val resumed = aligner.onTracked(pose(-30.0, 30.0, 9_000, heading = 90.0))

        assertEquals(3.0, resumed.x, 1e-9)
        assertEquals(0.0, resumed.y, 1e-9)
    }
}

class FloorHeightEstimatorTest {

    @Test
    fun `assumes chest height until the floor has been seen`() {
        assertEquals(0.05, FloorHeightEstimator().update(cameraY = 1.4, upwardPlaneHeights = emptyList()), 1e-9)
    }

    @Test
    fun `takes the floor, not a table, when both are planes`() {
        val estimator = FloorHeightEstimator()

        val floor = estimator.update(cameraY = 1.4, upwardPlaneHeights = listOf(0.75, 0.02, 2.6))

        assertEquals(0.02, floor, 1e-9)
    }

    @Test
    fun `follows the walker up a flight of stairs`() {
        val estimator = FloorHeightEstimator()
        estimator.update(cameraY = 1.4, upwardPlaneHeights = listOf(0.0))

        // Upstairs, 3.2 m higher: the old floor is out of reach and the new one not yet seen.
        val upstairs = estimator.update(cameraY = 4.6, upwardPlaneHeights = listOf(0.0))
        val seen = estimator.update(cameraY = 4.6, upwardPlaneHeights = listOf(0.0, 3.2))

        assertEquals(4.6 - FloorHeightEstimator.DEFAULT_CAMERA_HEIGHT_METERS, upstairs, 1e-9)
        // Smoothed toward the seen floor from the assumed one, already within a few cm.
        assertEquals(3.2, seen, 0.06)
    }
}

class PathSimplifierTest {

    @Test
    fun `an L-shaped walk with sway keeps only its corner`() {
        val leg1 = (0..20).map { (if (it % 2 == 0) 0.1 else -0.1) to it * 0.5 }
        val leg2 = (1..20).map { it * 0.5 to 10.0 + if (it % 2 == 0) 0.1 else -0.1 }

        val simplified = PathSimplifier.simplify(leg1 + leg2, toleranceMeters = 0.5)

        assertEquals(3, simplified.size)
        val corner = simplified[1]
        assertTrue(hypot(corner.first - 0.0, corner.second - 10.0) < 0.3, "corner at $corner")
    }

    @Test
    fun `a straight walk keeps just its ends`() {
        val walk = (0..50).map { 0.0 to it * 0.2 }

        assertEquals(listOf(walk.first(), walk.last()), PathSimplifier.simplify(walk, 0.5))
    }
}
