package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurveyDraftJsonTest {

    private val draft = SurveyDraft(
        mode = "CAMERA",
        currentFloor = 1,
        stepLengthMeters = 0.7,
        trajectory = listOf(Position(1.0, 2.0, 1, 90.0, 0.1, PositionSource.MAP_MATCHED, 5L)),
        taggedWaypoints = listOf(TaggedWaypoint("Kapı", 1.0, 2.0, 1, listOf(RadioFingerprint("b1", -60.0)), NodeType.DOOR, trajectoryIndex = 1)),
        wallMaps = mapOf(1 to OccupancyRaster.of(0.1, 0.0, 0.0, 2, 1, byteArrayOf(OccupancyRaster.FREE, OccupancyRaster.OCCUPIED))),
        radioSamples = mapOf(1 to listOf(RadioSample(1.0, 2.0, listOf(RadioFingerprint("b1", -61.0))))),
        savedAtMs = 99L,
    )

    private fun reading(id: String, rssi: Int) = BleBeaconReading(id, "AA", null, "u", 1, 2, rssi, -59, 2.5, 7L)

    @Test
    fun `a draft survives the round trip whole`() {
        assertEquals(draft, SurveyDraftJson.decode(SurveyDraftJson.encode(draft)))
    }

    @Test
    fun `a draft from another schema version, or no draft at all, reads as nothing`() {
        assertNull(SurveyDraftJson.decode(SurveyDraftJson.encode(draft.copy(schemaVersion = 99))))
        assertNull(SurveyDraftJson.decode("{not json"))
    }

    @Test
    fun `sightings come back from every complete line, skipping a torn last one`() {
        val lines = sequenceOf(
            SurveyDraftJson.encodeReadings(listOf(reading("a", -60), reading("b", -70))),
            SurveyDraftJson.encodeReadings(listOf(reading("c", -80))),
            SurveyDraftJson.encodeReadings(listOf(reading("d", -90))).dropLast(5),
        )

        assertEquals(listOf("a", "b", "c"), SurveyDraftJson.decodeReadings(lines).map { it.beaconId })
    }
}
