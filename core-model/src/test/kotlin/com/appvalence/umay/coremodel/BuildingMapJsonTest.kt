package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildingMapJsonTest {

    private val sampleMap = BuildingMap(
        id = "test-building",
        floors = listOf(
            FloorMap(
                floorIndex = 0,
                nodes = listOf(
                    MapNode("A", 0.0, 0.0, NodeType.CORRIDOR),
                    MapNode("B", 5.0, 0.0, NodeType.DESTINATION),
                ),
                edges = listOf(
                    MapEdge("A", "B", accessible = true, distanceMeters = 5.0),
                ),
            ),
        ),
    )

    @Test
    fun `a building map survives an encode-decode round trip unchanged`() {
        val encoded = BuildingMapJson.encode(sampleMap)
        val decoded = BuildingMapJson.decode(encoded)

        assertEquals(sampleMap, decoded)
    }

    @Test
    fun `decoding ignores unknown fields instead of failing`() {
        val encoded = BuildingMapJson.encode(sampleMap)
        val withExtraField = encoded.replaceFirst("{", "{\"futureField\": 123, ")

        val decoded = BuildingMapJson.decode(withExtraField)

        assertEquals(sampleMap, decoded)
    }

    @Test
    fun `a well-formed map has no problems`() {
        assertEquals(emptyList(), BuildingMapJson.problems(sampleMap))
    }

    @Test
    fun `a map without any nodes is rejected`() {
        val empty = BuildingMap(id = "x", floors = listOf(FloorMap(0, emptyList(), emptyList())))

        assertEquals(listOf("Haritada hiç nokta yok"), BuildingMapJson.problems(empty))
    }

    @Test
    fun `an edge pointing at a missing node is reported`() {
        val floor = sampleMap.floors.single()
        val broken = sampleMap.copy(
            floors = listOf(floor.copy(edges = floor.edges + MapEdge("A", "GHOST", accessible = true, distanceMeters = 1.0))),
        )

        assertEquals(listOf("1 bağlantı var olmayan bir noktayı gösteriyor"), BuildingMapJson.problems(broken))
    }

    @Test
    fun `a floor transition whose floor index doesn't match its node is reported`() {
        val broken = sampleMap.copy(
            floorTransitions = listOf(FloorTransition("A", 0, "B", 1, costMeters = 15.0)),
        )

        assertEquals(listOf("1 kat geçişi var olmayan bir noktayı gösteriyor"), BuildingMapJson.problems(broken))
    }

    @Test
    fun `a blank building name is reported`() {
        assertEquals(listOf("Binanın adı yok"), BuildingMapJson.problems(sampleMap.copy(id = "  ")))
    }
}
