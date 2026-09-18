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
}
