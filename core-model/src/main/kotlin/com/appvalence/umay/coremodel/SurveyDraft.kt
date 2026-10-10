package com.appvalence.umay.coremodel

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Everything a survey walk has recorded so far except its BLE sightings, as the
 * app keeps it on disk while recording: if Android ends the app mid-walk (it was
 * in the background, the battery ran low), the walk comes back as an unfinished
 * survey instead of being lost. The sightings, often tens of thousands, are
 * appended to their own file as they arrive (see [SurveyDraftJson.encodeReadings]).
 *
 * [mode] is the app's survey mode by name, so this model needn't know the app's enum.
 */
@Serializable
data class SurveyDraft(
    val schemaVersion: Int = SurveyDraftJson.SCHEMA_VERSION,
    val mode: String,
    val currentFloor: Int,
    val stepLengthMeters: Double? = null,
    val trajectory: List<Position> = emptyList(),
    val taggedWaypoints: List<TaggedWaypoint> = emptyList(),
    val wallMaps: Map<Int, OccupancyRaster> = emptyMap(),
    val radioSamples: Map<Int, List<RadioSample>> = emptyMap(),
    val savedAtMs: Long,
)

object SurveyDraftJson {
    /** Bump on an incompatible change; a draft of another version is discarded rather than misread. */
    const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

    fun encode(draft: SurveyDraft): String = json.encodeToString(SurveyDraft.serializer(), draft)

    /** Null for anything that isn't a draft this version can read. */
    fun decode(text: String): SurveyDraft? =
        runCatching { json.decodeFromString(SurveyDraft.serializer(), text) }.getOrNull()?.takeIf { it.schemaVersion == SCHEMA_VERSION }

    /** One line per batch of sightings, so a write cut short by the app being killed loses at most that batch. */
    fun encodeReadings(readings: List<BleBeaconReading>): String = json.encodeToString(ListSerializer(BleBeaconReading.serializer()), readings)

    /** Every complete line; a torn last line is skipped. */
    fun decodeReadings(lines: Sequence<String>): List<BleBeaconReading> = lines
        .filter { it.isNotBlank() }
        .mapNotNull { line -> runCatching { json.decodeFromString(ListSerializer(BleBeaconReading.serializer()), line) }.getOrNull() }
        .flatten()
        .toList()
}
