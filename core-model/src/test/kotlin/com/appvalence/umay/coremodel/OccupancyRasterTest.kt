package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OccupancyRasterTest {

    private fun cells(width: Int, height: Int, at: (column: Int, row: Int) -> Byte) =
        ByteArray(width * height) { at(it % width, it / width) }

    @Test
    fun `cells survive the round trip, including runs longer than one varint byte`() {
        val original = cells(300, 7) { column, row ->
            when {
                row == 3 -> OccupancyRaster.OCCUPIED
                column < 200 -> OccupancyRaster.FREE
                else -> OccupancyRaster.UNKNOWN
            }
        }
        val raster = OccupancyRaster.of(0.1, -2.0, 5.0, 300, 7, original)

        assertContentEquals(original, raster.decodeCells())
    }

    @Test
    fun `a mostly uniform floor encodes far smaller than one byte per cell`() {
        val raster = OccupancyRaster.of(0.1, 0.0, 0.0, 500, 500, cells(500, 500) { column, _ ->
            if (column == 0 || column == 499) OccupancyRaster.OCCUPIED else OccupancyRaster.FREE
        })

        assertTrue(raster.cells.length < 10_000, "encoded to ${raster.cells.length} characters")
    }

    @Test
    fun `survives a JSON round trip as part of a floor`() {
        val raster = OccupancyRaster.of(0.1, 1.5, -3.0, 4, 2, byteArrayOf(0, 1, 2, 2, 1, 1, 0, 0))
        val map = BuildingMap(
            id = "b",
            floors = listOf(FloorMap(0, listOf(MapNode("A", 0.0, 0.0, NodeType.WAYPOINT)), emptyList(), occupancy = raster)),
        )

        val decoded = BuildingMapJson.decode(BuildingMapJson.encode(map))

        assertEquals(raster, decoded.floors.single().occupancy)
        assertEquals(emptyList(), BuildingMapJson.problems(decoded))
    }

    @Test
    fun `maps saved before wall maps existed still load without one`() {
        val legacy = """{"id":"b","floors":[{"floorIndex":0,"nodes":[{"id":"A","x":0.0,"y":0.0,"type":"WAYPOINT"}],"edges":[]}]}"""

        assertNull(BuildingMapJson.decode(legacy).floors.single().occupancy)
    }

    @Test
    fun `a wall map whose cells don't add up is rejected rather than half-drawn`() {
        val good = OccupancyRaster.of(0.1, 0.0, 0.0, 4, 2, ByteArray(8) { 1 })
        val corrupt = listOf(
            good.copy(width = 5),
            good.copy(cells = "not base64!"),
            good.copy(cells = ""),
            OccupancyRaster.of(0.1, 0.0, 0.0, 2, 1, byteArrayOf(1, 7)), // not a cell value
            good.copy(width = 100_000, height = 100_000),
        )
        corrupt.forEach { assertNull(it.decodeCells(), "decoded $it") }

        val map = BuildingMap(id = "b", floors = listOf(FloorMap(0, listOf(MapNode("A", 0.0, 0.0, NodeType.WAYPOINT)), emptyList(), occupancy = corrupt[0])))
        assertEquals(listOf<MapProblem>(MapProblem.InvalidWallMap(1)), BuildingMapJson.problems(map))
    }

    @Test
    fun `downsampling keeps a one-cell wall`() {
        // A wall one cell thin, on an odd column: the coarse cell holding it must stay a wall.
        val raster = OccupancyRaster.of(0.1, 0.0, 0.0, 5, 4, cells(5, 4) { column, _ ->
            if (column == 3) OccupancyRaster.OCCUPIED else OccupancyRaster.FREE
        })

        val coarse = assertNotNull(raster.downsampled())

        assertEquals(0.2, coarse.cellSizeMeters, 1e-9)
        assertEquals(3, coarse.width)
        assertEquals(2, coarse.height)
        val decoded = assertNotNull(coarse.decodeCells())
        for (row in 0 until 2) {
            assertEquals(OccupancyRaster.FREE, decoded[row * 3 + 0])
            assertEquals(OccupancyRaster.OCCUPIED, decoded[row * 3 + 1])
            assertEquals(OccupancyRaster.FREE, decoded[row * 3 + 2])
        }
    }

    @Test
    fun `corridor bends and beacons are not places`() {
        assertTrue(MapNode("a", 0.0, 0.0, NodeType.WAYPOINT).isPlace)
        assertTrue(MapNode("a", 0.0, 0.0, NodeType.STAIRS).isPlace)
        assertTrue(!MapNode("a", 0.0, 0.0, NodeType.CORRIDOR).isPlace)
        assertTrue(MapNode("a", 0.0, 0.0, NodeType.CORRIDOR, name = "Ana koridor").isPlace)
        assertTrue(!MapNode("a", 0.0, 0.0, NodeType.BLE_ANCHOR).isPlace)
    }
}
