package com.appvalence.umay.coremodel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.hypot

/**
 * One line of a field-test session log: everything needed to replay a real
 * walk offline and calibrate the constants that were guessed rather than
 * measured (step length, fingerprint match thresholds, waypoint arrival
 * radius, auto floor-change step count...). Written as JSON Lines — one
 * [FieldLogEvent] per line, see [FieldLogJson] — so a log truncated by a
 * crash or a killed process is still readable up to its last complete line.
 *
 * [t] is wall-clock milliseconds, the same clock every [Position] and
 * [BleBeaconReading] timestamp uses, so events from different streams line up.
 */
@Serializable
sealed class FieldLogEvent {
    abstract val t: Long
}

/**
 * Always the first line. Embeds the whole [map] and a snapshot of the tuning
 * [constants] in effect, so a log stays interpretable on its own even after
 * the building is re-surveyed or the constants change in a later build.
 */
@Serializable
@SerialName("session_start")
data class SessionStartEvent(
    override val t: Long,
    val schemaVersion: Int = FieldLogJson.SCHEMA_VERSION,
    val appVersion: String,
    val device: String,
    val androidSdk: Int,
    val buildingId: String,
    val startNodeId: String,
    val destinationNodeId: String,
    val constants: Map<String, Double>,
    val map: BuildingMap,
) : FieldLogEvent()

@Serializable
enum class PositionStream {
    /** What the user was actually guided by (PDR + fingerprint corrections). */
    FUSED,

    /** Raw dead-reckoning output — one line per detected footstep. */
    PDR,

    /** A fingerprint match that was confident enough to be used as a correction. */
    FINGERPRINT,
}

@Serializable
@SerialName("position")
data class PositionEvent(
    override val t: Long,
    val stream: PositionStream,
    val x: Double,
    val y: Double,
    val floor: Int,
    val headingDeg: Double,
    val accuracyMeters: Double,
    val source: String,
) : FieldLogEvent() {
    companion object {
        fun of(stream: PositionStream, position: Position) = PositionEvent(
            t = position.timestampMs,
            stream = stream,
            x = position.x,
            y = position.y,
            floor = position.floor,
            headingDeg = position.headingDeg,
            accuracyMeters = position.accuracyMeters,
            source = position.source.name,
        )
    }
}

/** One (Kalman-smoothed, as the matcher sees it) beacon sighting. */
@Serializable
@SerialName("rssi")
data class RssiEvent(
    override val t: Long,
    val beaconId: String,
    val rssi: Int,
) : FieldLogEvent()

/** A spoken instruction or alert, exactly as the user heard it. */
@Serializable
@SerialName("guidance")
data class GuidanceEvent(
    override val t: Long,
    val alertId: String,
    val priority: String,
    val message: String,
) : FieldLogEvent()

/** The top-level status the screen showed changed (searching, on route, arrived, ...). */
@Serializable
@SerialName("status")
data class StatusEvent(
    override val t: Long,
    val status: String,
) : FieldLogEvent()

@Serializable
enum class FloorChangePhase { PROMPTED, CONFIRMED_MANUALLY, CONFIRMED_AUTOMATICALLY }

@Serializable
@SerialName("floor_change")
data class FloorChangeEvent(
    override val t: Long,
    val phase: FloorChangePhase,
    val targetNodeId: String,
    val targetFloor: Int,
) : FieldLogEvent()

/**
 * The tester declared "I'm physically standing at [nodeName] right now". This
 * is the only ground truth a field log has: comparing it against where the
 * app *thought* they were is what turns a walk into a measured position error.
 * [errorMeters] is null when the estimate was on a different floor, since
 * (x, y) on two floors come from independent origins and aren't comparable.
 */
@Serializable
@SerialName("ground_truth")
data class GroundTruthEvent(
    override val t: Long,
    val nodeId: String,
    val nodeName: String,
    val floor: Int,
    val nodeX: Double,
    val nodeY: Double,
    val estimatedX: Double?,
    val estimatedY: Double?,
    val estimatedFloor: Int?,
    val errorMeters: Double?,
) : FieldLogEvent() {
    companion object {
        fun of(t: Long, node: MapNode, nodeFloor: Int, estimate: Position?) = GroundTruthEvent(
            t = t,
            nodeId = node.id,
            nodeName = node.name,
            floor = nodeFloor,
            nodeX = node.x,
            nodeY = node.y,
            estimatedX = estimate?.x,
            estimatedY = estimate?.y,
            estimatedFloor = estimate?.floor,
            errorMeters = estimate
                ?.takeIf { it.floor == nodeFloor }
                ?.let { hypot(it.x - node.x, it.y - node.y) },
        )
    }
}

@Serializable
@SerialName("session_end")
data class SessionEndEvent(
    override val t: Long,
    val reason: String,
) : FieldLogEvent()

object FieldLogJson {
    /** Bump whenever an event's shape changes incompatibly, so offline tooling can tell logs apart. */
    const val SCHEMA_VERSION = 1

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** A single line, no trailing newline. */
    fun encode(event: FieldLogEvent): String = json.encodeToString(FieldLogEvent.serializer(), event)

    fun decode(line: String): FieldLogEvent = json.decodeFromString(FieldLogEvent.serializer(), line)
}
