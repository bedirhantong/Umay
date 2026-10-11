package com.appvalence.umay.coremodel

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Re-surveying part of a building instead of all of it (docs/YOL_HARITASI.md
 * 2.5): a short walk, recorded like any survey, that starts and ends at places
 * the map already has (named the same), merged into the map.
 *
 * The places the walk shares with the map pin it down: the turn, shift and —
 * for a walk counted in footsteps — scale that lay them best over the map's
 * places carry everything else the walk recorded onto the map. Then:
 * - places new to the map are added; shared ones keep the map's position;
 * - with [merge]'s replaceOld, a way the walk re-walked between two shared
 *   places replaces the map's old drawing of it;
 * - wall-map cells the walk saw replace the old ones, the rest stay;
 * - radio samples along the re-walked way are replaced by the walk's.
 */
object MapSections {

    sealed interface Result {
        data class Merged(val map: BuildingMap, val sharedPlaces: Int, val newPlaces: Int, val replacedWays: Int) : Result

        /** Fewer than two of the walk's places are on the map's floor: nothing to pin the walk down with. */
        data class TooFewKnownPlaces(val floorIndex: Int, val found: Int) : Result

        /** The walk's floor isn't on the map. */
        data class UnknownFloor(val floorIndex: Int) : Result

        /** The walk doesn't fit the map: its shared places are much farther apart, or closer, than on the map. */
        data class DoesNotFit(val floorIndex: Int, val scale: Double) : Result
    }

    fun merge(map: BuildingMap, section: BuildingMap, replaceOld: Boolean = true): Result {
        var merged = map
        var shared = 0
        var added = 0
        var replaced = 0
        for (sectionFloor in section.floors) {
            if (sectionFloor.nodes.isEmpty()) continue
            val floor = merged.floors.firstOrNull { it.floorIndex == sectionFloor.floorIndex } ?: return Result.UnknownFloor(sectionFloor.floorIndex)
            when (val result = mergeFloor(merged, floor, sectionFloor, replaceOld)) {
                is FloorMerge.Done -> {
                    merged = result.map
                    shared += result.shared
                    added += result.added
                    replaced += result.replaced
                }
                is FloorMerge.Failed -> return result.reason
            }
        }
        return Result.Merged(merged, shared, added, replaced)
    }

    private sealed interface FloorMerge {
        data class Done(val map: BuildingMap, val shared: Int, val added: Int, val replaced: Int) : FloorMerge
        data class Failed(val reason: Result) : FloorMerge
    }

    private fun mergeFloor(map: BuildingMap, floor: FloorMap, section: FloorMap, replaceOld: Boolean): FloorMerge {
        val placesByName = floor.nodes.filter { it.isPlace }.groupBy { normalized(it.name) }
        // Section node id -> map node id, for the places both have (named the same, once on the map).
        val shared = section.nodes.filter { it.isPlace }.mapNotNull { node ->
            placesByName[normalized(node.name)]?.singleOrNull()?.let { node.id to it }
        }.toMap()
        if (shared.size < 2) return FloorMerge.Failed(Result.TooFewKnownPlaces(floor.floorIndex, shared.size))

        val fit = Similarity.fit(shared.map { (sectionId, mapNode) -> section.nodes.first { it.id == sectionId }.let { it.x to it.y } to (mapNode.x to mapNode.y) })
        if (fit.scale !in MIN_SCALE..MAX_SCALE) return FloorMerge.Failed(Result.DoesNotFit(floor.floorIndex, fit.scale))

        fun id(sectionId: String) = shared[sectionId]?.id ?: sectionId
        val newNodes = section.nodes.filter { it.id !in shared }.map { node ->
            val (x, y) = fit.apply(node.x, node.y)
            node.copy(x = x, y = y)
        }
        // A shared place keeps where the map has it, and takes the walk's fresher fingerprint.
        val freshFingerprints = shared.mapNotNull { (sectionId, mapNode) ->
            section.nodes.first { it.id == sectionId }.fingerprint.takeIf { it.isNotEmpty() }?.let { mapNode.id to it }
        }.toMap()
        val allNodes = (floor.nodes.map { node -> freshFingerprints[node.id]?.let { node.copy(fingerprint = it) } ?: node } + newNodes).associateBy { it.id }
        val newEdges = section.edges.map { edge ->
            val from = allNodes.getValue(id(edge.fromId))
            val to = allNodes.getValue(id(edge.toId))
            edge.copy(fromId = from.id, toId = to.id, distanceMeters = hypot(to.x - from.x, to.y - from.y))
        }

        var updated = map.copy(floors = map.floors.map { if (it === floor) it.copy(nodes = allNodes.values.toList()) else it })
        var replaced = 0
        if (replaceOld) {
            val rewalked = rewalkedPairs(section, shared.keys).map { (a, b) -> setOf(id(a), id(b)) }.toSet()
            val oldFloor = updated.floors.first { it.floorIndex == floor.floorIndex }
            for (link in MapEdits.placeLinks(oldFloor)) {
                if (setOf(link.fromId, link.toId) in rewalked) {
                    updated = MapEdits.disconnect(updated, link)
                    replaced++
                }
            }
        }

        val sectionPath = newEdges.map { edge -> allNodes.getValue(edge.fromId).let { it.x to it.y } to allNodes.getValue(edge.toId).let { it.x to it.y } }
        val merged = updated.copy(
            floors = updated.floors.map { current ->
                if (current.floorIndex != floor.floorIndex) return@map current
                current.copy(
                    edges = current.edges + newEdges.filter { e -> current.edges.none { it.fromId == e.fromId && it.toId == e.toId || it.fromId == e.toId && it.toId == e.fromId } },
                    occupancy = mergeWalls(current.occupancy, section.occupancy, fit),
                    radioMap = current.radioMap.filter { sample -> sectionPath.none { (a, b) -> distanceToSegment(sample.x, sample.y, a, b) <= RADIO_REPLACE_METERS } } +
                        section.radioMap.map { sample -> fit.apply(sample.x, sample.y).let { (x, y) -> sample.copy(x = x, y = y) } },
                    doors = current.doors + section.doors.filter { door -> door.nodeId !in shared },
                )
            },
        )
        return FloorMerge.Done(merged, shared.size, newNodes.count { it.isPlace }, replaced)
    }

    /** The pairs of shared places the walk went between directly (through only places new to the map, or bends). */
    private fun rewalkedPairs(section: FloorMap, sharedIds: Set<String>): Set<Pair<String, String>> {
        val neighbors = section.edges.flatMap { listOf(it.fromId to it.toId, it.toId to it.fromId) }.groupBy({ it.first }, { it.second })
        val pairs = mutableSetOf<Pair<String, String>>()
        for (start in sharedIds) {
            val seen = mutableSetOf(start)
            val queue = ArrayDeque(neighbors[start].orEmpty())
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                if (!seen.add(at)) continue
                if (at in sharedIds) {
                    if (start < at) pairs += start to at else pairs += at to start
                    continue
                }
                queue += neighbors[at].orEmpty()
            }
        }
        return pairs
    }

    /**
     * The map's wall map, with every cell the walk saw replaced by what it saw there,
     * grown to take in all of the walk's. At the map's resolution, or the walk's when
     * the map has none.
     */
    private fun mergeWalls(old: OccupancyRaster?, section: OccupancyRaster?, fit: Similarity): OccupancyRaster? {
        section ?: return old
        val sectionCells = section.decodeCells() ?: return old
        val oldCells = old?.decodeCells()
        val cell = old?.cellSizeMeters ?: section.cellSizeMeters
        // The walk's raster corners, on the map.
        val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0).map { (u, v) ->
            fit.apply(section.originX + u * section.width * section.cellSizeMeters, section.originY + v * section.height * section.cellSizeMeters)
        }
        var minX = corners.minOf { it.first }
        var minY = corners.minOf { it.second }
        var maxX = corners.maxOf { it.first }
        var maxY = corners.maxOf { it.second }
        if (old != null && oldCells != null) {
            minX = min(minX, old.originX)
            minY = min(minY, old.originY)
            maxX = max(maxX, old.originX + old.width * old.cellSizeMeters)
            maxY = max(maxY, old.originY + old.height * old.cellSizeMeters)
        }
        // Keep the old grid's alignment, so its cells carry over exactly.
        val originX = if (old != null) old.originX - ceil((old.originX - minX) / cell) * cell else minX
        val originY = if (old != null) old.originY - ceil((old.originY - minY) / cell) * cell else minY
        val width = ceil((maxX - originX) / cell).toInt()
        val height = ceil((maxY - originY) / cell).toInt()
        if (width.toLong() * height > OccupancyRaster.MAX_CELLS) return old
        val out = ByteArray(width * height)
        val inverse = fit.inverse()
        for (row in 0 until height) {
            val y = originY + (row + 0.5) * cell
            for (column in 0 until width) {
                val x = originX + (column + 0.5) * cell
                val (sx, sy) = inverse.apply(x, y)
                val sc = floor((sx - section.originX) / section.cellSizeMeters).toInt()
                val sr = floor((sy - section.originY) / section.cellSizeMeters).toInt()
                val seen = if (sc in 0 until section.width && sr in 0 until section.height) sectionCells[sr * section.width + sc] else OccupancyRaster.UNKNOWN
                out[row * width + column] = if (seen != OccupancyRaster.UNKNOWN) {
                    seen
                } else if (old != null && oldCells != null) {
                    val oc = floor((x - old.originX) / old.cellSizeMeters).toInt()
                    val or = floor((y - old.originY) / old.cellSizeMeters).toInt()
                    if (oc in 0 until old.width && or in 0 until old.height) oldCells[or * old.width + oc] else OccupancyRaster.UNKNOWN
                } else {
                    OccupancyRaster.UNKNOWN
                }
            }
        }
        return OccupancyRaster.of(cell, originX, originY, width, height, out)
    }

    /** x' = scale·R(θ)·(x − c) + c', fitted to point pairs by least squares. */
    internal class Similarity(val scale: Double, val angleRad: Double, private val from: Pair<Double, Double>, private val to: Pair<Double, Double>) {
        fun apply(x: Double, y: Double): Pair<Double, Double> {
            val dx = x - from.first
            val dy = y - from.second
            val c = cos(angleRad)
            val s = sin(angleRad)
            return (to.first + scale * (c * dx - s * dy)) to (to.second + scale * (s * dx + c * dy))
        }

        fun inverse() = Similarity(1 / scale, -angleRad, to, from)

        companion object {
            fun fit(pairs: List<Pair<Pair<Double, Double>, Pair<Double, Double>>>): Similarity {
                val from = pairs.map { it.first.first }.average() to pairs.map { it.first.second }.average()
                val to = pairs.map { it.second.first }.average() to pairs.map { it.second.second }.average()
                var dot = 0.0
                var cross = 0.0
                var fromSpread = 0.0
                for ((p, q) in pairs) {
                    val px = p.first - from.first
                    val py = p.second - from.second
                    val qx = q.first - to.first
                    val qy = q.second - to.second
                    dot += px * qx + py * qy
                    cross += px * qy - py * qx
                    fromSpread += px * px + py * py
                }
                val angle = atan2(cross, dot)
                val scale = if (fromSpread == 0.0) 1.0 else sqrt(dot * dot + cross * cross) / fromSpread
                return Similarity(scale, angle, from, to)
            }
        }
    }

    private fun distanceToSegment(x: Double, y: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(x - a.first, y - a.second)
        val t = (((x - a.first) * dx + (y - a.second) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(x - (a.first + t * dx), y - (a.second + t * dy))
    }

    private fun normalized(name: String) = name.trim().lowercase(Locale.forLanguageTag("tr"))

    // A stride off by a quarter is already a badly calibrated one; beyond, it's the wrong building or floor.
    private const val MIN_SCALE = 0.75
    private const val MAX_SCALE = 1.33

    // Radio samples this close to the re-walked way were taken where the walk went again.
    private const val RADIO_REPLACE_METERS = 2.0
}
