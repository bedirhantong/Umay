package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

        assertEquals(listOf(MapProblem.NoNodes), BuildingMapJson.problems(empty))
    }

    @Test
    fun `an edge pointing at a missing node is reported`() {
        val floor = sampleMap.floors.single()
        val broken = sampleMap.copy(
            floors = listOf(floor.copy(edges = floor.edges + MapEdge("A", "GHOST", accessible = true, distanceMeters = 1.0))),
        )

        assertEquals(listOf(MapProblem.DanglingEdges(1)), BuildingMapJson.problems(broken))
    }

    @Test
    fun `a floor transition whose floor index doesn't match its node is reported`() {
        val broken = sampleMap.copy(
            floorTransitions = listOf(FloorTransition("A", 0, "B", 1, costMeters = 15.0)),
        )

        assertEquals(listOf(MapProblem.DanglingTransitions(1)), BuildingMapJson.problems(broken))
    }

    @Test
    fun `a blank building name is reported`() {
        assertEquals(listOf(MapProblem.BlankName), BuildingMapJson.problems(sampleMap.copy(id = "  ")))
    }

    @Test
    fun `every written map says which format it is, first`() {
        val encoded = BuildingMapJson.encode(sampleMap)

        assertTrue(encoded.trimStart().removePrefix("{").trimStart().startsWith("\"formatVersion\": ${BuildingMapJson.FORMAT_VERSION}"), encoded.take(80))
    }

    @Test
    fun `a map from before versioning still opens`() {
        val legacy = BuildingMapJson.encode(sampleMap).replace(Regex("\"formatVersion\":\\s*\\d+,"), "")
        assertTrue("formatVersion" !in legacy)

        assertEquals(sampleMap, BuildingMapJson.decode(legacy))
    }

    @Test
    fun `a map from a newer Umay is refused rather than half read`() {
        val newer = BuildingMapJson.encode(sampleMap).replaceFirst("\"formatVersion\": ${BuildingMapJson.FORMAT_VERSION}", "\"formatVersion\": 99")

        val error = assertFailsWith<NewerMapFormatException> { BuildingMapJson.decode(newer) }
        assertEquals(99, error.version)
    }
}
