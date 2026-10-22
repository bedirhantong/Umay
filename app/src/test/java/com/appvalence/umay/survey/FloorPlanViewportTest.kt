package com.appvalence.umay.survey

import com.appvalence.umay.common.FloorPlanBounds
import com.appvalence.umay.common.FloorPlanViewport
import com.appvalence.umay.common.floorPlanBounds
import com.appvalence.umay.common.nearestWall
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MetricPoint
import com.appvalence.umay.coremodel.ObservedRegion
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.OpeningCandidate
import com.appvalence.umay.coremodel.SpatialMap
import com.appvalence.umay.coremodel.WallSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class FloorPlanViewportTest {
    @Test fun `pan zoom and y inversion preserve metric round trip`() {
        val viewport = FloorPlanViewport(MetricPoint(-8.0,12.0),400.0,300.0,25.0,71.0,-31.0)
        val point = MetricPoint(5.2,-9.3)
        val screen = viewport.toScreen(point)
        val result = viewport.toWorld(screen.first,screen.second)
        assertEquals(point.x,result.x,1e-9)
        assertEquals(point.y,result.y,1e-9)
        assertEquals(25.0,viewport.toScreen(point.copy(x=point.x+1)).first-screen.first,1e-9)
        assertEquals(-25.0,viewport.toScreen(point.copy(y=point.y+1)).second-screen.second,1e-9)
    }

    @Test fun `rotated and translated cameras preserve metric round trips`() {
        for (heading in listOf(0.0, 33.0, 90.0, 180.0, 270.0, -127.0)) {
            val viewport = FloorPlanViewport(MetricPoint(-8.0, 12.0), 640.0, 480.0, 37.0, 51.0, -26.0, heading)
            for (point in listOf(MetricPoint(5.2, -9.3), MetricPoint(-8.0, 12.0), MetricPoint(-21.6, 30.2))) {
                val (x, y) = viewport.toScreen(point)
                assertPoint(point, viewport.toWorld(x, y))
            }
        }
    }

    @Test fun `heading rotates map axes consistently with the screen y direction`() {
        val viewport = FloorPlanViewport(MetricPoint(0.0, 0.0), 400.0, 300.0, 20.0, headingDeg = 90.0)
        val east = viewport.toScreen(MetricPoint(1.0, 0.0))
        val north = viewport.toScreen(MetricPoint(0.0, 1.0))
        assertEquals(200.0, east.first, 1e-9)
        assertEquals(130.0, east.second, 1e-9)
        assertEquals(180.0, north.first, 1e-9)
        assertEquals(150.0, north.second, 1e-9)
    }

    @Test fun `pinch retains the touched metric point beneath the moving centroid`() {
        for (heading in listOf(0.0, 37.0, 95.0, 320.0)) {
            val before = FloorPlanViewport(MetricPoint(7.0, -4.0), 640.0, 480.0, 40.0, 32.0, -19.0, heading)
            val touched = before.toWorld(113.0, 321.0)
            val after = before.transformed(113.0, 321.0, 2.3, 12.0, -8.0)
            assertPoint(touched, after.toWorld(125.0, 313.0))
            assertEquals(92.0, after.pixelsPerMeter, 1e-9)
            assertEquals(heading, after.headingDeg, 1e-9)
        }
    }

    @Test fun `pinch still retains its focus when zoom reaches a scale limit`() {
        val before = FloorPlanViewport(MetricPoint(-3.0, 4.0), 640.0, 480.0, 25.0, headingDeg = 43.0)
        val touched = before.toWorld(500.0, 119.0)
        val after = before.transformed(500.0, 119.0, 1e9, -27.0, 16.0)
        assertEquals(10_000.0, after.pixelsPerMeter, 1e-9)
        assertPoint(touched, after.toWorld(473.0, 135.0))
    }

    @Test fun `geometry without waypoints or raster can be fitted without clipping its features`() {
        val wall = wall(MetricPoint(-4.0, -2.0), MetricPoint(7.0, -2.0))
        val corner = MetricPoint(-6.0, 4.0)
        val opening = OpeningCandidate(MetricPoint(7.0, 5.0), MetricPoint(8.0, 5.0), 2.1, 1.8, 4)
        val region = ObservedRegion(
            outline = listOf(MetricPoint(-2.0, -3.0), MetricPoint(3.0, -3.0), MetricPoint(3.0, 6.0), MetricPoint(-2.0, 6.0)),
            holes = listOf(listOf(MetricPoint(0.0, 0.0), MetricPoint(0.0, 1.0), MetricPoint(1.0, 1.0), MetricPoint(1.0, 0.0))),
            areaSquareMeters = 44.0,
            boundaryComplete = false,
        )
        val floor = FloorMap(2, emptyList(), emptyList(), reconstruction = SpatialMap(
            coordinateFrameId = "measured-floor", walls = listOf(wall), corners = listOf(corner),
            openings = listOf(opening), observedRegions = listOf(region),
        ))
        val bounds = floorPlanBounds(floor)
        assertEquals(FloorPlanBounds(-6.0, -3.0, 8.0, 6.0), bounds)
        assertNotNull(bounds)
        val viewport = FloorPlanViewport.fit(bounds!!, 640.0, 480.0, padding = 20.0)
        val points = listOf(wall.start, wall.end, corner, opening.start, opening.end) + region.outline + region.holes.flatten()
        points.forEach { point ->
            val (x, y) = viewport.toScreen(point)
            assertTrue("Clipped x=$x for $point", x >= 20.0 - 1e-9 && x <= 620.0 + 1e-9)
            assertTrue("Clipped y=$y for $point", y >= 20.0 - 1e-9 && y <= 460.0 + 1e-9)
        }
    }

    @Test fun `raster fit covers physical outer cell edges with their metric origin`() {
        val raster = OccupancyRaster.of(0.5, -7.0, 12.0, 8, 6, ByteArray(48))
        val floor = FloorMap(0, emptyList(), emptyList(), occupancy = raster)
        val bounds = floorPlanBounds(floor)
        assertEquals(FloorPlanBounds(-7.0, 12.0, -3.0, 15.0), bounds)
        val viewport = FloorPlanViewport.fit(bounds!!, 480.0, 360.0, padding = 20.0)
        val upperLeft = viewport.toScreen(MetricPoint(-7.0, 15.0))
        val lowerRight = viewport.toScreen(MetricPoint(-3.0, 12.0))
        assertEquals(26.6666666667, upperLeft.first, 1e-7)
        assertEquals(20.0, upperLeft.second, 1e-9)
        assertEquals(453.3333333333, lowerRight.first, 1e-7)
        assertEquals(340.0, lowerRight.second, 1e-9)
    }

    @Test fun `wall selection tolerance remains in pixels across zoom and camera rotation`() {
        val walls = listOf(wall(MetricPoint(0.0, 0.0), MetricPoint(4.0, 0.0)),
            wall(MetricPoint(0.0, 1.5), MetricPoint(4.0, 1.5)))
        for (scale in listOf(10.0, 80.0, 700.0)) for (heading in listOf(0.0, 37.0, 90.0, -122.0)) {
            val viewport = FloorPlanViewport(MetricPoint(2.0, 0.75), 500.0, 400.0, scale, 16.0, -21.0, heading)
            val (ax, ay) = viewport.toScreen(walls[0].start)
            val (bx, by) = viewport.toScreen(walls[0].end)
            val length = hypot(bx - ax, by - ay)
            // Offset towards the side opposite the other wall, so only the tested
            // segment can be selected within this screen-space tolerance.
            val normalX = -(by - ay) / length
            val normalY = (bx - ax) / length
            val (x, y) = viewport.toScreen(MetricPoint(2.0, 0.0))
            assertEquals(0, nearestWall(walls, viewport, x + 5.0 * normalX, y + 5.0 * normalY, 8.0))
            assertNull(nearestWall(walls, viewport, x + 9.0 * normalX, y + 9.0 * normalY, 8.0))
            val second = viewport.toScreen(MetricPoint(2.0, 1.5))
            assertEquals(1, nearestWall(walls, viewport, second.first, second.second, 8.0))
        }
    }

    @Test fun `wall selection uses the finite segment and picks the closest surface`() {
        val walls = listOf(wall(MetricPoint(0.0, 0.0), MetricPoint(4.0, 0.0)),
            wall(MetricPoint(0.0, 0.15), MetricPoint(4.0, 0.15)))
        val viewport = FloorPlanViewport(MetricPoint(2.0, 0.0), 500.0, 400.0, 20.0)
        val closeToSecond = viewport.toScreen(MetricPoint(2.0, 0.13))
        assertEquals(1, nearestWall(walls, viewport, closeToSecond.first, closeToSecond.second, 8.0))
        val beyondEndpoint = viewport.toScreen(MetricPoint(4.5, 0.0))
        assertNull(nearestWall(walls, viewport, beyondEndpoint.first, beyondEndpoint.second, 8.0))
    }

    private fun assertPoint(expected: MetricPoint, actual: MetricPoint) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }

    private fun wall(a: MetricPoint, b: MetricPoint) = WallSegment(a, b, 0.3, 2.0, 20, 4, 0.02)
}
