package com.appvalence.umay.positioning

import com.appvalence.umay.coremodel.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeaconMapBuilderTest {

    @Test
    fun `creates one BLE_ANCHOR node per beacon`() {
        val results = listOf(
            BeaconSurveyResult("b1", floor = 0, x = 0.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("b2", floor = 0, x = 5.0, y = 0.0, observationCount = 5),
        )

        val map = BeaconMapBuilder.build("test-building", results)

        val floor = map.floors.single()
        assertEquals(2, floor.nodes.size)
        assertTrue(floor.nodes.all { it.type == NodeType.BLE_ANCHOR })
    }

    @Test
    fun `connects beacons within range but not beacons that are too far apart`() {
        val results = listOf(
            BeaconSurveyResult("near1", floor = 0, x = 0.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("near2", floor = 0, x = 5.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("far", floor = 0, x = 100.0, y = 0.0, observationCount = 5),
        )

        val map = BeaconMapBuilder.build("test-building", results, maxEdgeDistanceMeters = 10.0)

        val floor = map.floors.single()
        assertEquals(1, floor.edges.size)
        assertEquals(setOf("near1", "near2"), setOf(floor.edges.single().fromId, floor.edges.single().toId))
    }

    @Test
    fun `separates beacons into their own floor's FloorMap`() {
        val results = listOf(
            BeaconSurveyResult("ground", floor = 0, x = 0.0, y = 0.0, observationCount = 5),
            BeaconSurveyResult("upstairs", floor = 1, x = 0.0, y = 0.0, observationCount = 5),
        )

        val map = BeaconMapBuilder.build("test-building", results)

        assertEquals(listOf(0, 1), map.floors.map { it.floorIndex })
        assertEquals(0, map.floors.sumOf { it.edges.size }) // different floors are never connected
    }
}
