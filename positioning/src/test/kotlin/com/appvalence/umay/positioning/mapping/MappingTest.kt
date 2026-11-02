package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.OccupancyRaster
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.sin
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

    @Test
    fun `tracking that comes back turned is turned back by the gyro's word`() {
        val aligner = ArPoseAligner()
        aligner.onTracked(pose(0.0, 0.0, 0), gyroHeadingDeg = 200.0) // facing plan north
        aligner.onTrackingLost()
        // Walked 3 m on, turned right, walked 2 m: the gyro saw the turn.
        repeat(3) { aligner.onStep(1.0, gyroHeadingDeg = 200.0) }
        repeat(2) { aligner.onStep(1.0, gyroHeadingDeg = 290.0) }

        // ARCore comes back in a frame turned 40°: it says the walker faces 130°, at a point far off.
        val resumed = aligner.onTracked(pose(20.0, -20.0, 9_000, heading = 130.0), gyroHeadingDeg = 290.0)
        // Walking on east one metre, in ARCore's turned frame (heading 130° there).
        val r = Math.toRadians(130.0)
        val next = aligner.onTracked(pose(20.0 + sin(r), -20.0 - cos(r), 9_500, heading = 130.0), gyroHeadingDeg = 290.0)

        assertEquals(90.0, resumed.headingDeg, 1e-6)
        assertEquals(2.0, resumed.x, 1e-6)
        assertEquals(3.0, resumed.y, 1e-6)
        assertEquals(3.0, next.x, 1e-6)
        assertEquals(3.0, next.y, 1e-6)
    }

    @Test
    fun `small turns while lost are ARCore's to correct, not turned back`() {
        val aligner = ArPoseAligner()
        aligner.onTracked(pose(0.0, 0.0, 0), gyroHeadingDeg = 10.0)
        aligner.onTrackingLost()
        repeat(2) { aligner.onStep(0.7, gyroHeadingDeg = 12.0) }

        val resumed = aligner.onTracked(pose(0.0, -1.4, 3_000, heading = 5.0), gyroHeadingDeg = 12.0)

        assertEquals(5.0, resumed.headingDeg, 1e-6)
    }

    @Test
    fun `a turn just before tracking is lost is no reason to turn the plan`() {
        val aligner = ArPoseAligner()
        aligner.onTracked(pose(0.0, 0.0, 0), gyroHeadingDeg = 50.0) // facing plan north, at a dead end
        // Turned back, and the camera lost track on the very step that turned.
        aligner.onTrackingLost()
        repeat(3) { aligner.onStep(1.0, gyroHeadingDeg = 230.0) }

        // ARCore resumes in the same frame, rightly facing south, 3 m back.
        val resumed = aligner.onTracked(pose(0.0, 3.0, 3_000, heading = 180.0), gyroHeadingDeg = 230.0)

        assertEquals(180.0, resumed.headingDeg, 1e-6)
        assertEquals(0.0, resumed.x, 1e-6)
        assertEquals(-3.0, resumed.y, 1e-6)
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

class WallMapBudgetTest {

    /** A noisy floor (no long runs), so it encodes large. */
    private fun noisyFloor(size: Int, cell: Double = 0.1) = OccupancyRaster.of(
        cell, 0.0, 0.0, size, size,
        ByteArray(size * size) { if ((it * 7919) % 3 == 0) OccupancyRaster.OCCUPIED else OccupancyRaster.FREE },
    )

    @Test
    fun `maps already within budget are left alone`() {
        val small = mapOf(0 to noisyFloor(20))

        assertEquals(small, WallMapBudget.fit(small, maxEncodedChars = 100_000))
    }

    @Test
    fun `the largest floor is coarsened until everything fits, walls kept`() {
        val maps = mapOf(0 to noisyFloor(400), 1 to noisyFloor(40))

        val fitted = WallMapBudget.fit(maps, maxEncodedChars = 60_000)

        assertTrue(fitted.values.sumOf { it.cells.length } <= 60_000)
        assertTrue(fitted.getValue(0).cellSizeMeters > 0.1)
        assertEquals(0.1, fitted.getValue(1).cellSizeMeters, 1e-9)
        assertTrue(fitted.getValue(0).decodeCells()!!.any { it == OccupancyRaster.OCCUPIED })
    }

    @Test
    fun `a floor that can't fit even coarsened is dropped rather than blurred`() {
        // Stripes four cells wide: still stripes at 40 cm, so they never collapse to nothing.
        val striped = OccupancyRaster.of(0.1, 0.0, 0.0, 400, 400, ByteArray(400 * 400) {
            if ((it / 400 / 4) % 2 == 0) OccupancyRaster.OCCUPIED else OccupancyRaster.FREE
        })

        val fitted = WallMapBudget.fit(mapOf(0 to striped), maxEncodedChars = 10)

        assertTrue(fitted.isEmpty())
    }
}

class ResumeAcrossSessionsTest {

    @Test
    fun `a walk continued in a new AR session starts at its last point, facing its last way`() {
        val aligner = ArPoseAligner(startX = 5.0, startY = 7.0, startHeadingDeg = 90.0)

        // The new session's frame is arbitrary: here the camera faces its −Z (heading 0).
        val first = aligner.onTracked(ArPose(3.0, 1.4, -2.0, 0.0, 1_000))
        // One metre "forward" in the new frame…
        val next = aligner.onTracked(ArPose(3.0, 1.4, -3.0, 0.0, 1_500))

        assertEquals(5.0, first.x, 1e-9)
        assertEquals(7.0, first.y, 1e-9)
        assertEquals(90.0, first.headingDeg, 1e-9)
        // …is one metre east on the plan, the way the walk was heading.
        assertEquals(6.0, next.x, 1e-9)
        assertEquals(7.0, next.y, 1e-9)
    }

    @Test
    fun `a seeded grid shows the saved map and still yields to new measurements`() {
        val saved = OccupancyRaster.of(0.1, 1.0, 2.0, 2, 1, byteArrayOf(OccupancyRaster.FREE, OccupancyRaster.OCCUPIED))
        val grid = OccupancyGrid(0.1)

        grid.seed(saved)

        assertEquals(saved.decodeCells()!!.toList(), grid.snapshot()!!.decodeCells()!!.toList())
        assertEquals(1.0, grid.snapshot()!!.originX, 1e-9)
        // Rays that keep seeing through the "wall" clear it.
        repeat(7) { grid.addRay(1.15, 1.5, 1.15, 2.95, hit = false) }
        assertEquals(OccupancyRaster.FREE, OccupancyGrid.classify(grid.logOdds(1.15, 2.05)))
    }
}
