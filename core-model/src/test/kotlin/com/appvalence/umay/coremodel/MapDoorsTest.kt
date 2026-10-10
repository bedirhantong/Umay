package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapDoorsTest {

    /** A wall across a north-going corridor at y = 5 (one cell thick, 4 m wide), seen by the camera with the door shut. */
    private fun wallAcross(): OccupancyRaster {
        val size = 0.1
        val width = 40 // x 0..4
        val height = 100 // y 0..10
        val cells = ByteArray(width * height) { OccupancyRaster.FREE }
        for (column in 0 until width) cells[50 * width + column] = OccupancyRaster.OCCUPIED
        return OccupancyRaster.of(size, 0.0, 0.0, width, height, cells)
    }

    private fun cellAt(raster: OccupancyRaster, x: Double, y: Double): Byte =
        raster.decodeCells()!![((y - raster.originY) / raster.cellSizeMeters).toInt() * raster.width + ((x - raster.originX) / raster.cellSizeMeters).toInt()]

    private val floor = FloorMap(
        0,
        listOf(
            MapNode("a", 2.0, 1.0, NodeType.WAYPOINT, "Koridor"),
            MapNode("d", 2.0, 5.05, NodeType.DOOR, "Kapı"),
            MapNode("b", 2.0, 9.0, NodeType.WAYPOINT, "Oda"),
        ),
        listOf(MapEdge("a", "d", true, 4.0), MapEdge("d", "b", true, 4.0)),
        occupancy = wallAcross(),
    )

    @Test
    fun `a door walked through becomes an opening across the walking direction`() {
        val opened = MapDoors.apply(floor)

        val door = opened.doors.single()
        assertEquals("d", door.nodeId)
        // North or south: either way the wall runs east–west.
        assertTrue(door.passageBearingDeg < 1.0 || door.passageBearingDeg > 359.0 || kotlin.math.abs(door.passageBearingDeg - 180.0) < 1.0, "${door.passageBearingDeg}")
    }

    @Test
    fun `the wall is opened as wide as the door, and only there`() {
        val walls = MapDoors.apply(floor).occupancy!!

        assertEquals(OccupancyRaster.FREE, cellAt(walls, 2.0, 5.05))
        assertEquals(OccupancyRaster.FREE, cellAt(walls, 2.4, 5.05))
        assertEquals(OccupancyRaster.OCCUPIED, cellAt(walls, 1.0, 5.05))
        assertEquals(OccupancyRaster.OCCUPIED, cellAt(walls, 3.2, 5.05))
    }

    @Test
    fun `applying it twice changes nothing more, and a chosen width is kept`() {
        val once = MapDoors.apply(floor)
        val widened = once.copy(doors = once.doors.map { it.copy(widthMeters = 1.6) })

        assertEquals(once, MapDoors.apply(once))
        assertEquals(1.6, MapDoors.apply(widened).doors.single().widthMeters, 1e-9)
    }

    @Test
    fun `a door nobody walked through, or a floor without walls, is left alone`() {
        val isolated = FloorMap(0, listOf(MapNode("d", 0.0, 0.0, NodeType.DOOR, "Kapı")), emptyList())

        assertTrue(MapDoors.apply(isolated).doors.isEmpty())
        assertEquals(1, MapDoors.apply(floor.copy(occupancy = null)).doors.size)
    }
}
