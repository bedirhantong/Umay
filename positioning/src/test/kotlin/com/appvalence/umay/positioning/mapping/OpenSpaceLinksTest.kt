package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.OccupancyRaster
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenSpaceLinksTest {

    // A 12 m square lobby. The survey walked Danışma → (corner) → Çıkış along two sides.
    private val nodes = listOf(
        MapNode("d", 1.0, 1.0, NodeType.WAYPOINT, "Danışma"),
        MapNode("k", 1.0, 11.0, NodeType.CORRIDOR),
        MapNode("c", 11.0, 11.0, NodeType.WAYPOINT, "Çıkış"),
    )
    private val edges = listOf(MapEdge("d", "k", true, 10.0), MapEdge("k", "c", true, 10.0))

    private fun lobby(cell: (x: Double, y: Double) -> Byte): OccupancyRaster {
        val n = 120
        return OccupancyRaster.of(0.1, 0.0, 0.0, n, n, ByteArray(n * n) { i -> cell((i % n + 0.5) * 0.1, (i / n + 0.5) * 0.1) })
    }

    @Test
    fun `two places across an open lobby are joined straight`() {
        val floor = OpenSpaceLinks.add(FloorMap(0, nodes, edges, occupancy = lobby { _, _ -> OccupancyRaster.FREE }))

        val shortcut = floor.edges.single { setOf(it.fromId, it.toId) == setOf("d", "c") }
        assertEquals(Math.hypot(10.0, 10.0), shortcut.distanceMeters, 1e-9)
    }

    @Test
    fun `not through a wall, nor through space the camera never saw`() {
        val walled = lobby { x, y -> if (kotlin.math.abs(x - y) < 0.3 && x in 4.0..8.0) OccupancyRaster.OCCUPIED else OccupancyRaster.FREE }
        val unseen = lobby { x, y -> if (kotlin.math.abs(x - y) < 1.0 && x in 5.0..6.0) OccupancyRaster.UNKNOWN else OccupancyRaster.FREE }

        assertEquals(2, OpenSpaceLinks.add(FloorMap(0, nodes, edges, occupancy = walled)).edges.size)
        assertEquals(2, OpenSpaceLinks.add(FloorMap(0, nodes, edges, occupancy = unseen)).edges.size)
    }

    @Test
    fun `no shortcut where the walked way is about as short, or without a wall map`() {
        val straightWalk = listOf(MapEdge("d", "c", true, 14.2))

        assertEquals(1, OpenSpaceLinks.add(FloorMap(0, nodes, straightWalk, occupancy = lobby { _, _ -> OccupancyRaster.FREE })).edges.size)
        assertEquals(2, OpenSpaceLinks.add(FloorMap(0, nodes, edges)).edges.size)
    }
}
