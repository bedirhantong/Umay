package com.appvalence.umay.positioning.trilateration

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.FloorTransition
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.RadioSample
import com.appvalence.umay.coremodel.TaggedWaypoint
import com.appvalence.umay.positioning.mapping.PathSimplifier
import com.appvalence.umay.positioning.mapping.SurveyLoopClosure
import java.util.Locale
import java.util.UUID
import kotlin.math.hypot

/**
 * Turns solved beacon positions ([BeaconSurveyResult], from [BeaconSurveySolver])
 * into a routable [BuildingMap]: one [MapNode] of type [NodeType.BLE_ANCHOR] per
 * beacon, connected to every other beacon on the same floor within
 * [maxEdgeDistanceMeters] by a [MapEdge] weighted by straight-line distance.
 *
 * This is an approximation, not a substitute for a properly surveyed corridor
 * graph (doors, stairs, accessibility flags): it assumes beacons were placed
 * along walkable routes, so treating nearby beacons as directly connected is
 * a reasonable stand-in until someone authors the real topology by hand. It
 * exists so [AStarPathfinder] has *something* to route over as soon as a
 * beacon survey is done, rather than nothing at all.
 *
 * [buildFromWaypoints] is the preferred path: it uses the real walked order
 * of user-named [TaggedWaypoint]s as the corridor topology instead of
 * guessing edges from beacon proximity, and gives every node a human name and
 * an RSSI fingerprint for [com.appvalence.umay.positioning.fingerprint.FingerprintMatcher].
 */
object BeaconMapBuilder {
    /**
     * The name given to a beacon node nobody named (floor index, 1-based number) —
     * a raw "uuid-major-minor" id means nothing to a person picking a destination.
     * It's stored in the map, so the app passes one worded in the surveyor's language.
     */
    val DEFAULT_ANCHOR_NAME: (Int, Int) -> String = { floor, number -> "Floor $floor, point $number" }

    fun build(
        buildingId: String,
        results: List<BeaconSurveyResult>,
        maxEdgeDistanceMeters: Double = DEFAULT_MAX_EDGE_DISTANCE_METERS,
        anchorName: (floorIndex: Int, number: Int) -> String = DEFAULT_ANCHOR_NAME,
    ): BuildingMap {
        val floors = results
            .groupBy { it.floor }
            .map { (floorIndex, floorResults) -> buildFloor(floorIndex, floorResults, maxEdgeDistanceMeters, anchorName) }
            .sortedBy { it.floorIndex }
        return BuildingMap(id = buildingId, floors = floors)
    }

    /**
     * Per floor: if the user tagged waypoints there, connect them in tagging
     * order (the real walked path) and drop in any solved [BeaconSurveyResult]s
     * as unconnected [NodeType.BLE_ANCHOR] nodes for visualization only. A
     * floor with no tagged waypoints but with beacon results falls back to
     * [build]'s proximity-connect behavior, so the tool stays useful even if
     * the user tags nothing on that floor.
     *
     * Wherever two *consecutive* tags in [taggedWaypoints] land on different
     * floors, that's a stairs/elevator crossing caught mid-walk (tag, change
     * floor via the recording screen's stepper, keep walking, tag again) — it
     * becomes a [FloorTransition] rather than being silently dropped when
     * waypoints get grouped by floor below.
     */
    fun buildFromWaypoints(
        buildingId: String,
        taggedWaypoints: List<TaggedWaypoint>,
        beaconResults: List<BeaconSurveyResult> = emptyList(),
        maxEdgeDistanceMeters: Double = DEFAULT_MAX_EDGE_DISTANCE_METERS,
        anchorName: (floorIndex: Int, number: Int) -> String = DEFAULT_ANCHOR_NAME,
        trajectory: List<Position> = emptyList(),
        radioSamples: Map<Int, List<RadioSample>> = emptyMap(),
    ): BuildingMap {
        // A survey walked by footsteps drifts; the camera's doesn't, much (and its wall map
        // is drawn in the camera's frame, which correcting only the path would break).
        val footsteps = taggedWaypoints.none { it.trajectoryIndex != null }
        val samePlaceMeters = if (footsteps) LOOP_CLOSURE_METERS else SAME_PLACE_METERS

        // A place named again on coming back to it (the junction of a T-shaped corridor,
        // the foot of the stairs on the way back down) is the same place: one node, so
        // the walk's branches join there.
        val canonical = IntArray(taggedWaypoints.size) { it }
        taggedWaypoints.forEachIndexed { i, waypoint ->
            val earlier = (0 until i).firstOrNull { j ->
                val other = taggedWaypoints[j]
                canonical[j] == j && other.floor == waypoint.floor &&
                    normalizedName(other.name) == normalizedName(waypoint.name) &&
                    hypot(other.x - waypoint.x, other.y - waypoint.y) <= samePlaceMeters
            }
            if (earlier != null) canonical[i] = earlier
        }
        if (footsteps) {
            val closed = closeLoops(taggedWaypoints, canonical, trajectory, radioSamples)
            return buildFromWaypointsClosed(buildingId, closed.first, canonical, beaconResults, maxEdgeDistanceMeters, anchorName, trajectory, closed.second)
        }
        return buildFromWaypointsClosed(buildingId, taggedWaypoints, canonical, beaconResults, maxEdgeDistanceMeters, anchorName, trajectory, radioSamples)
    }

    /**
     * A footsteps survey's drift taken out ([SurveyLoopClosure]): every named place where
     * the walk came back to it pulls the walk in between straight, and the radio samples
     * taken along the way move with it.
     */
    private fun closeLoops(
        tags: List<TaggedWaypoint>,
        canonical: IntArray,
        trajectory: List<Position>,
        radioSamples: Map<Int, List<RadioSample>>,
    ): Pair<List<TaggedWaypoint>, Map<Int, List<RadioSample>>> {
        val corrections = SurveyLoopClosure.corrections(tags.mapIndexed { i, tag -> SurveyLoopClosure.Stop(tag.x, tag.y, tag.floor, canonical[i]) })
        val corrected = tags.mapIndexed { i, tag -> tag.copy(x = tag.x + corrections[i].first, y = tag.y + corrections[i].second) }
        if (trajectory.isEmpty()) return corrected to radioSamples
        // Each tag and each radio sample was taken at a point of the path, in order: find it.
        fun locate(points: List<Triple<Double, Double, Int>>): List<Int> {
            var from = 0
            return points.map { (x, y, floor) ->
                val at = (from until trajectory.size).firstOrNull { k -> trajectory[k].x == x && trajectory[k].y == y && trajectory[k].floor == floor } ?: -1
                if (at >= 0) from = at
                at
            }
        }
        val pathCorrections = SurveyLoopClosure.spread(trajectory.size, locate(tags.map { Triple(it.x, it.y, it.floor) }), corrections)
        val samples = radioSamples.mapValues { (floor, list) ->
            val at = locate(list.map { Triple(it.x, it.y, floor) })
            list.mapIndexed { i, sample ->
                val c = at[i].takeIf { it >= 0 }?.let(pathCorrections::get) ?: return@mapIndexed sample
                sample.copy(x = sample.x + c.first, y = sample.y + c.second)
            }
        }
        return corrected to samples
    }

    private fun buildFromWaypointsClosed(
        buildingId: String,
        taggedWaypoints: List<TaggedWaypoint>,
        canonical: IntArray,
        beaconResults: List<BeaconSurveyResult>,
        maxEdgeDistanceMeters: Double,
        anchorName: (floorIndex: Int, number: Int) -> String,
        trajectory: List<Position>,
        radioSamples: Map<Int, List<RadioSample>>,
    ): BuildingMap {
        // Assigned once, up front, so the same id can be used both inside each
        // floor's node list and when detecting cross-floor transitions below.
        val nodeIds = taggedWaypoints.indices.associateWith { UUID.randomUUID().toString() }
        fun idOf(i: Int) = nodeIds.getValue(canonical[i])

        val waypointIndicesByFloor = taggedWaypoints.indices.groupBy { taggedWaypoints[it].floor }
        val beaconResultsByFloor = beaconResults.groupBy { it.floor }
        val floorIndices = (waypointIndicesByFloor.keys + beaconResultsByFloor.keys).toSortedSet()

        val floors = floorIndices.map { floorIndex ->
            val indices = waypointIndicesByFloor[floorIndex].orEmpty()
            val floorBeaconResults = beaconResultsByFloor[floorIndex].orEmpty()
            if (indices.isEmpty()) {
                buildFloor(floorIndex, floorBeaconResults, maxEdgeDistanceMeters, anchorName)
            } else {
                buildWaypointFloor(floorIndex, indices, taggedWaypoints, canonical, ::idOf, floorBeaconResults, trajectory)
            }
        }

        val transitions = (1 until taggedWaypoints.size)
            .filter { i -> taggedWaypoints[i - 1].floor != taggedWaypoints[i].floor }
            .map { i ->
                FloorTransition(
                    fromNodeId = idOf(i - 1),
                    fromFloorIndex = taggedWaypoints[i - 1].floor,
                    toNodeId = idOf(i),
                    toFloorIndex = taggedWaypoints[i].floor,
                    costMeters = FLOOR_TRANSITION_COST_METERS,
                )
            }
            // Down the same stairs that were walked up: one crossing, not two.
            .distinctBy { setOf(it.fromNodeId, it.toNodeId) }

        return BuildingMap(
            id = buildingId,
            floors = floors.map { it.copy(radioMap = radioSamples[it.floorIndex].orEmpty()) },
            floorTransitions = transitions,
        )
    }

    private fun buildWaypointFloor(
        floorIndex: Int,
        indices: List<Int>,
        taggedWaypoints: List<TaggedWaypoint>,
        canonical: IntArray,
        idOf: (Int) -> String,
        beaconResults: List<BeaconSurveyResult>,
        trajectory: List<Position>,
    ): FloorMap {
        // One node per place, at the average of every time it was named (dead reckoning
        // puts each visit a little apart), with the average of their fingerprints.
        val visitsByPlace = indices.groupBy { canonical[it] }
        val nodesByPlace = visitsByPlace.mapValues { (place, visits) ->
            val first = taggedWaypoints[place]
            MapNode(
                id = idOf(place),
                x = visits.map { taggedWaypoints[it].x }.average(),
                y = visits.map { taggedWaypoints[it].y }.average(),
                type = first.type,
                name = first.name,
                fingerprint = averageFingerprint(visits.map { taggedWaypoints[it].fingerprint }),
            )
        }

        // Only places named one right after the other on this floor are connected: two
        // places either side of a trip to another floor were never walked between.
        val cornerNodes = mutableListOf<MapNode>()
        val edges = mutableListOf<MapEdge>()
        val connected = mutableSetOf<Set<Int>>()
        for (i in 1 until taggedWaypoints.size) {
            val fromPlace = canonical[i - 1]
            val toPlace = canonical[i]
            if (taggedWaypoints[i - 1].floor != floorIndex || taggedWaypoints[i].floor != floorIndex) continue
            if (fromPlace == toPlace || !connected.add(setOf(fromPlace, toPlace))) continue
            val from = nodesByPlace.getValue(fromPlace)
            val to = nodesByPlace.getValue(toPlace)
            val corners = cornersBetween(taggedWaypoints[i - 1], taggedWaypoints[i], trajectory, floorIndex)
            cornerNodes += corners
            edges += (listOf(from) + corners + to).zipWithNext { a, b ->
                MapEdge(fromId = a.id, toId = b.id, accessible = true, distanceMeters = hypot(a.x - b.x, a.y - b.y))
            }
        }

        val anchorNodes = beaconResults.map { result ->
            MapNode(id = result.beaconId, x = result.x, y = result.y, type = NodeType.BLE_ANCHOR)
        }

        return FloorMap(floorIndex = floorIndex, nodes = nodesByPlace.values.toList() + cornerNodes + anchorNodes, edges = edges)
    }

    private fun normalizedName(name: String) = name.trim().lowercase(Locale.forLanguageTag("tr"))

    /** Per beacon, the mean of the visits that heard it. */
    private fun averageFingerprint(visits: List<List<RadioFingerprint>>): List<RadioFingerprint> {
        if (visits.size == 1) return visits.single()
        return visits.flatten()
            .groupBy { it.beaconId }
            .map { (beaconId, heard) -> RadioFingerprint(beaconId, heard.map { it.rssi }.average()) }
    }

    /**
     * The bends of the walked path between two consecutive tags, as unnamed
     * [NodeType.CORRIDOR] nodes — so an edge follows the corridor round a corner
     * instead of cutting straight through the wall between two tagged places.
     * Only for a trajectory precise enough to trust its shape (the camera survey's);
     * dead reckoning's wobble would invent corners that aren't there.
     */
    private fun cornersBetween(from: TaggedWaypoint, to: TaggedWaypoint, trajectory: List<Position>, floorIndex: Int): List<MapNode> {
        val start = from.trajectoryIndex ?: return emptyList()
        val end = to.trajectoryIndex ?: return emptyList()
        if (start >= end || end > trajectory.size) return emptyList()
        // trajectoryIndex is the point count when tagged, so the tag stood on point index - 1.
        val walked = trajectory.subList((start - 1).coerceAtLeast(0), end)
        if (walked.any { it.floor != floorIndex }) return emptyList()
        val path = listOf(from.x to from.y) + walked.map { it.x to it.y } + (to.x to to.y)
        return PathSimplifier.simplify(path, CORNER_TOLERANCE_METERS)
            .drop(1)
            .dropLast(1)
            .map { (x, y) -> MapNode(id = UUID.randomUUID().toString(), x = x, y = y, type = NodeType.CORRIDOR) }
    }

    private fun buildFloor(
        floorIndex: Int,
        results: List<BeaconSurveyResult>,
        maxEdgeDistanceMeters: Double,
        anchorName: (Int, Int) -> String,
    ): FloorMap {
        // A raw beaconId ("uuid-major-minor") is meaningless to a person picking a
        // destination in the UI — a numbered point on the floor they're standing on isn't
        // pretty either, but at least it's something a human can read and choose.
        val nodes = results.mapIndexed { index, result ->
            MapNode(
                id = result.beaconId,
                x = result.x,
                y = result.y,
                type = NodeType.BLE_ANCHOR,
                name = anchorName(floorIndex, index + 1),
            )
        }

        val edges = mutableListOf<MapEdge>()
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                val distance = hypot(a.x - b.x, a.y - b.y)
                if (distance <= maxEdgeDistanceMeters) {
                    edges += MapEdge(fromId = a.id, toId = b.id, accessible = true, distanceMeters = distance)
                }
            }
        }

        return FloorMap(floorIndex = floorIndex, nodes = nodes, edges = edges)
    }

    const val DEFAULT_MAX_EDGE_DISTANCE_METERS = 20.0

    // Half a corridor's width: a walk that wanders less than this is still "straight".
    const val CORNER_TOLERANCE_METERS = 0.5

    // The camera barely drifts between visits; two places of the same name farther apart
    // than this are different places that happen to share a name.
    const val SAME_PLACE_METERS = 5.0

    // Footsteps drift several metres over a long survey before the walk comes back to a
    // place (simulated surveys: 4–6 junctions of an office stayed apart at 5 m), and
    // loop closure then pulls them together.
    const val LOOP_CLOSURE_METERS = 15.0

    // A fixed penalty, not a measured distance — stairs/elevators cost real time and
    // effort A* should weigh against a same-floor detour, but there's no PDR distance
    // to measure across a floor change (see FloorTransition). Rough starting point:
    // ~15m of walking-equivalent effort, in the ballpark of a flight of stairs
    // (see NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT, similarly a guess).
    // Both are field-tunable once real crossings have actually been timed/counted.
    const val FLOOR_TRANSITION_COST_METERS = 15.0
}
