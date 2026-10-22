package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.*
import kotlin.test.*

class ObservedRoutesTest {
    @Test fun `routes require clearance and unknown blocks a previously walked edge`() {
        val cells = ByteArray(100) { OccupancyRaster.FREE }
        val map = FloorMap(0,listOf(MapNode("a",0.55,0.55,NodeType.WAYPOINT),MapNode("b",1.35,0.55,NodeType.WAYPOINT)),
            listOf(MapEdge("a","b",true,0.8)),OccupancyRaster.of(0.2,0.0,0.0,10,10,cells),reconstruction=SpatialMap(coordinateFrameId="a"))
        assertEquals(1,ObservedRoutes.restrict(map).edges.size)
        cells[2*10+4] = OccupancyRaster.UNKNOWN
        assertTrue(ObservedRoutes.restrict(map.copy(occupancy=OccupancyRaster.of(0.2,0.0,0.0,10,10,cells))).edges.isEmpty())
        assertTrue(ObservedRoutes.restrict(map.copy(occupancy=null)).edges.isEmpty())
    }

    /** Two rooms 6 m x 3 m in all, joined by a doorway [doorMeters] wide in the wall at x = 3.0 m. Cells are 0.1 m. */
    private fun roomsJoinedByDoor(doorMeters: Double): FloorMap {
        val width = 60; val height = 30
        val doorRows = 10 until (10 + Math.round(doorMeters / 0.1).toInt())
        val cells = ByteArray(width * height) { index ->
            val x = index % width; val y = index / width
            when {
                x < 2 || x >= width - 2 || y < 2 || y >= height - 2 -> OccupancyRaster.OCCUPIED
                x == 30 && y !in doorRows -> OccupancyRaster.OCCUPIED
                else -> OccupancyRaster.FREE
            }
        }
        val doorCentre = (doorRows.first + doorRows.last + 1) * 0.05
        return FloorMap(
            0,
            listOf(MapNode("a", 1.2, doorCentre, NodeType.WAYPOINT), MapNode("b", 4.8, doorCentre, NodeType.WAYPOINT)),
            listOf(MapEdge("a", "b", true, 3.6)),
            OccupancyRaster.of(0.1, 0.0, 0.0, width, height, cells),
            reconstruction = SpatialMap(coordinateFrameId = "a"),
        )
    }

    @Test fun `an ordinary doorway stays a route`() {
        for (door in listOf(0.9, 0.8)) {
            assertEquals(1, ObservedRoutes.restrict(roomsJoinedByDoor(door)).edges.size, "a $door m doorway")
        }
    }

    @Test fun `a gap too narrow to walk through is not a route`() {
        assertTrue(ObservedRoutes.restrict(roomsJoinedByDoor(0.5)).edges.isEmpty())
    }
}
