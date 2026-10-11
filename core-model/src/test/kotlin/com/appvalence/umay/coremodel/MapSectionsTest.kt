package com.appvalence.umay.coremodel

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapSectionsTest {
    private fun place(id: String, x: Double, y: Double, name: String = id, fingerprint: List<RadioFingerprint> = emptyList()) =
        MapNode(id, x, y, NodeType.WAYPOINT, name = name, fingerprint = fingerprint)

    private fun bend(id: String, x: Double, y: Double) = MapNode(id, x, y, NodeType.CORRIDOR)

    private fun edge(a: MapNode, b: MapNode) = MapEdge(a.id, b.id, true, hypot(b.x - a.x, b.y - a.y))

    // Lobi (0,0) — bend (0,10) — Mutfak (10,10); and Lobi — Ofis (−10, 0).
    private val lobi = place("lobi", 0.0, 0.0, "Lobi")
    private val oldBend = bend("b-old", 0.0, 10.0)
    private val mutfak = place("mutfak", 10.0, 10.0, "Mutfak")
    private val ofis = place("ofis", -10.0, 0.0, "Ofis")
    private val map = BuildingMap(
        id = "Bina",
        floors = listOf(FloorMap(0, listOf(lobi, oldBend, mutfak, ofis), listOf(edge(lobi, oldBend), edge(oldBend, mutfak), edge(lobi, ofis)))),
    )

    /**
     * A re-walk from Lobi to Mutfak by a new diagonal way, past a new place "Yazıcı",
     * recorded in its own frame: turned 30°, shifted, and 10% too big (a long stride).
     */
    private fun sectionWalk(): BuildingMap {
        fun survey(x: Double, y: Double): Pair<Double, Double> {
            val a = Math.toRadians(30.0)
            return (100 + 1.1 * (cos(a) * x - sin(a) * y)) to (50 + 1.1 * (sin(a) * x + cos(a) * y))
        }
        val (lx, ly) = survey(0.0, 0.0)
        val (yx, yy) = survey(5.0, 5.0)
        val (mx, my) = survey(10.0, 10.0)
        val sLobi = place("s1", lx, ly, " lobi ", listOf(RadioFingerprint("b1", -61.0)))
        val yazici = place("s2", yx, yy, "Yazıcı")
        val sMutfak = place("s3", mx, my, "Mutfak")
        return BuildingMap(id = "x", floors = listOf(FloorMap(0, listOf(sLobi, yazici, sMutfak), listOf(edge(sLobi, yazici), edge(yazici, sMutfak)))))
    }

    @Test
    fun `a re-walked way replaces the old one, new places land where they are`() {
        val result = assertIs<MapSections.Result.Merged>(MapSections.merge(map, sectionWalk()))

        val floor = result.map.floors.single()
        assertEquals(2, result.sharedPlaces)
        assertEquals(1, result.newPlaces)
        assertEquals(1, result.replacedWays)
        val yazici = floor.nodes.single { it.name == "Yazıcı" }
        assertTrue(hypot(yazici.x - 5.0, yazici.y - 5.0) < 0.01, "Yazıcı at (${yazici.x}, ${yazici.y})")
        // The old way's bend is gone; Lobi–Ofis, not re-walked, stays.
        assertTrue(floor.nodes.none { it.id == "b-old" })
        assertTrue(floor.edges.any { setOf(it.fromId, it.toId) == setOf("lobi", "ofis") })
        // Shared places keep the map's ids and positions, and take the walk's fingerprint.
        val newLobi = floor.nodes.single { it.id == "lobi" }
        assertEquals(0.0, newLobi.x, 1e-9)
        assertEquals(listOf(RadioFingerprint("b1", -61.0)), newLobi.fingerprint)
        assertEquals(setOf(setOf("lobi", yazici.id), setOf(yazici.id, "mutfak")), floor.edges.filter { "ofis" !in setOf(it.fromId, it.toId) }.map { setOf(it.fromId, it.toId) }.toSet())
    }

    @Test
    fun `asked to keep the old way, both stay`() {
        val result = assertIs<MapSections.Result.Merged>(MapSections.merge(map, sectionWalk(), replaceOld = false))
        assertEquals(0, result.replacedWays)
        assertTrue(result.map.floors.single().nodes.any { it.id == "b-old" })
    }

    @Test
    fun `a walk sharing fewer than two places with the map can't be placed`() {
        val lonely = BuildingMap(id = "x", floors = listOf(FloorMap(0, listOf(place("s1", 0.0, 0.0, "Lobi"), place("s2", 5.0, 0.0, "Yeni")), emptyList())))
        assertEquals(MapSections.Result.TooFewKnownPlaces(0, 1), MapSections.merge(map, lonely))
        assertEquals(MapSections.Result.UnknownFloor(3), MapSections.merge(map, lonely.copy(floors = lonely.floors.map { it.copy(floorIndex = 3) })))
    }

    @Test
    fun `a walk that doesn't fit the map's distances is refused`() {
        val wrong = BuildingMap(id = "x", floors = listOf(FloorMap(0, listOf(place("s1", 0.0, 0.0, "Lobi"), place("s3", 40.0, 40.0, "Mutfak")), emptyList())))
        assertIs<MapSections.Result.DoesNotFit>(MapSections.merge(map, wrong))
    }

    @Test
    fun `the walls the walk saw replace the old ones, the rest stay`() {
        // Old: a 10 × 10 m wall map at 0.5 m, all free. The walk saw a wall at x = 4.
        val oldCells = ByteArray(20 * 20) { OccupancyRaster.FREE }
        val oldWalls = OccupancyRaster.of(0.5, -5.0, -5.0, 20, 20, oldCells)
        val withWalls = map.copy(floors = map.floors.map { it.copy(occupancy = oldWalls) })
        val sectionCells = ByteArray(4 * 4) { i -> if (i % 4 == 2) OccupancyRaster.OCCUPIED else OccupancyRaster.FREE }
        val walk = BuildingMap(
            id = "x",
            floors = listOf(
                FloorMap(
                    0,
                    listOf(place("s1", 0.0, 0.0, "Lobi"), place("s3", 10.0, 10.0, "Mutfak")),
                    emptyList(),
                    occupancy = OccupancyRaster.of(0.5, 3.0, 0.0, 4, 4, sectionCells),
                ),
            ),
        )

        val walls = assertIs<MapSections.Result.Merged>(MapSections.merge(withWalls, walk)).map.floors.single().occupancy!!
        val cells = walls.decodeCells()!!
        fun at(x: Double, y: Double) = cells[((y - walls.originY) / walls.cellSizeMeters).toInt() * walls.width + ((x - walls.originX) / walls.cellSizeMeters).toInt()]
        assertEquals(OccupancyRaster.OCCUPIED, at(4.2, 1.0))
        assertEquals(OccupancyRaster.FREE, at(-3.0, -3.0))
        assertEquals(0.5, walls.cellSizeMeters)
    }

    @Test
    fun `a map without walls takes the walk's`() {
        val walk = BuildingMap(
            id = "x",
            floors = listOf(
                FloorMap(0, listOf(place("s1", 0.0, 0.0, "Lobi"), place("s3", 10.0, 10.0, "Mutfak")), emptyList(), occupancy = OccupancyRaster.of(0.5, 0.0, 0.0, 2, 2, ByteArray(4) { OccupancyRaster.FREE })),
            ),
        )
        assertNull(map.floors.single().occupancy)
        val merged = assertIs<MapSections.Result.Merged>(MapSections.merge(map, walk)).map
        assertTrue(merged.floors.single().occupancy != null)
    }
}
