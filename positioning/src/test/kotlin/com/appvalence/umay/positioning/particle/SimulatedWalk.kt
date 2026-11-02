package com.appvalence.umay.positioning.particle

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.StepEvent
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot

internal fun node(id: String, x: Double, y: Double, fingerprint: List<RadioFingerprint> = emptyList()) =
    MapNode(id = id, x = x, y = y, type = NodeType.WAYPOINT, fingerprint = fingerprint)

/** A one-floor map whose edges join [nodes] in order, like a survey walk. */
internal fun corridorMap(vararg nodes: MapNode, floorIndex: Int = 0): BuildingMap =
    BuildingMap(id = "test", floors = listOf(floor(floorIndex, *nodes)))

internal fun floor(floorIndex: Int, vararg nodes: MapNode) = FloorMap(
    floorIndex = floorIndex,
    nodes = nodes.toList(),
    edges = nodes.toList().zipWithNext { a, b -> MapEdge(a.id, b.id, accessible = true, distanceMeters = hypot(b.x - a.x, b.y - a.y)) },
)

/**
 * Footsteps walking through [waypoints] in order, as the phone would report them: the
 * gyro frame is rotated [gyroOffsetDeg] away from the map frame, and drifts a further
 * [driftPerStepDeg] every step.
 */
internal fun stepsAlong(
    waypoints: List<MapNode>,
    stride: Double = 0.7,
    gyroOffsetDeg: Double = 0.0,
    driftPerStepDeg: Double = 0.0,
    startMs: Long = 1_000L,
): List<StepEvent> {
    val steps = mutableListOf<StepEvent>()
    var gyroHeading = 0.0
    var t = startMs
    for ((from, to) in waypoints.zipWithNext()) {
        val mapBearing = Math.toDegrees(atan2(to.x - from.x, to.y - from.y))
        val count = ceil(hypot(to.x - from.x, to.y - from.y) / stride).toInt()
        repeat(count) {
            val drift = driftPerStepDeg * steps.size
            val target = mapBearing - gyroOffsetDeg + drift
            val delta = ((target - gyroHeading + 540.0) % 360.0) - 180.0
            gyroHeading += delta
            t += 550
            steps += StepEvent(stepLengthMeters = stride, headingDeltaDeg = delta, timestampMs = t)
        }
    }
    return steps
}
