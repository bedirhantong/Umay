package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.positioning.ble.BleBeaconScanner
import com.appvalence.umay.positioning.ble.KalmanFilter1D
import com.appvalence.umay.positioning.fingerprint.RadioSampler
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** A beacon on the wall at ([x], [y]). */
internal data class VirtualBeacon(val id: String, val x: Double, val y: Double)

/** A straight piece of wall; door openings are simply gaps between pieces. */
internal data class Wall(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    fun distanceTo(x: Double, y: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(x - x1, y - y1)
        val t = (((x - x1) * dx + (y - y1) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
    }

    /** Whether the straight line from (ax, ay) to (bx, by) passes through this wall. */
    fun blocks(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        fun cross(ox: Double, oy: Double, px: Double, py: Double, qx: Double, qy: Double) = (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = cross(x1, y1, x2, y2, ax, ay)
        val d2 = cross(x1, y1, x2, y2, bx, by)
        val d3 = cross(ax, ay, bx, by, x1, y1)
        val d4 = cross(ax, ay, bx, by, x2, y2)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0)) && d1 != 0.0 && d2 != 0.0 && d3 != 0.0 && d4 != 0.0
    }
}

/** Furniture that stands in the way (desks, a counter): the survey camera maps it as occupied. */
internal data class Block(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun contains(x: Double, y: Double) = x in minX..maxX && y in minY..maxY
}

/**
 * How the radio behaves: log-distance path loss with Gaussian fading, a loss
 * for every wall in the way, and scans that sometimes miss a beacon
 * altogether. The defaults are typical indoor BLE.
 */
internal data class RadioModel(
    val rssiAtOneMeter: Double = -59.0,
    val pathLossExponent: Double = 2.2,
    val fadingSigmaDb: Double = 4.0,
    val dropoutProbability: Double = 0.1,
    val hearingFloorDbm: Double = -95.0,
    val wallLossDb: Double = 4.0,
) {
    fun meanRssi(distanceMeters: Double, wallsCrossed: Int = 0): Double =
        rssiAtOneMeter - 10.0 * pathLossExponent * log10(max(distanceMeters, 0.5)) - wallLossDb * wallsCrossed
}

/**
 * One floor of a building as the simulation knows it: the true corridors (the
 * map's graph), its walls and furniture, the beacons on its walls and how they
 * sound, and the places with a printed marker. [surveyedMap] is what a
 * surveyor would have recorded walking every corridor once.
 */
internal class VirtualBuilding(
    val name: String,
    val nodes: List<MapNode>,
    val edges: List<Pair<String, String>>,
    val beacons: List<VirtualBeacon>,
    val radio: RadioModel = RadioModel(),
    val walls: List<Wall> = emptyList(),
    val blocks: List<Block> = emptyList(),
    val markerNodeIds: Set<String> = emptySet(),
) {
    val nodesById: Map<String, MapNode> = nodes.associateBy { it.id }

    /** Whether nothing but air lies between the two points (walls only; furniture is lower than a marker). */
    fun lineOfSight(ax: Double, ay: Double, bx: Double, by: Double): Boolean = walls.none { it.blocks(ax, ay, bx, by) }

    /** The beacons as the viewer marks them. */
    fun beaconMarks(): List<BeaconMark> = beacons.map { BeaconMark(it.id, 0, it.x, it.y) }

    /** RSSI each beacon would read at ([x], [y]) in one scan; beacons not heard are left out. */
    fun scan(x: Double, y: Double, timestampMs: Long, random: Random, radio: RadioModel = this.radio): List<BleBeaconReading> =
        beacons.mapNotNull { beacon ->
            if (random.nextDouble() < radio.dropoutProbability) return@mapNotNull null
            val wallsCrossed = walls.count { it.blocks(beacon.x, beacon.y, x, y) }
            val rssi = radio.meanRssi(hypot(beacon.x - x, beacon.y - y), wallsCrossed) + random.gaussian() * radio.fadingSigmaDb
            if (rssi < radio.hearingFloorDbm) return@mapNotNull null
            BleBeaconReading(beacon.id, "", null, "sim", 1, 1, rssi.toInt(), radio.rssiAtOneMeter.toInt(), 1.0, timestampMs)
        }

    /**
     * The map a survey of this building yields: the corridor graph, each place's
     * fingerprint (a few seconds standing there) and radio samples along every
     * corridor, recorded through the phone's own smoothing while walking it and
     * kept the way the survey screen keeps them ([RadioSampler]).
     */
    fun surveyedMap(random: Random, withRadio: Boolean = true, phone: PhoneRadio = PhoneRadio(), placeFingerprints: Boolean = withRadio): BuildingMap {
        fun standing(x: Double, y: Double): List<RadioFingerprint> {
            val scanner = VirtualScanner(this, random, phone)
            var heard = emptyList<BleBeaconReading>()
            repeat(STANDING_STEPS) { heard = scanner.hear(x, y, it * 550L) }
            return heard.map { RadioFingerprint(it.beaconId, it.rssi.toDouble()) }
        }

        val mapNodes = nodes.map { if (placeFingerprints) it.copy(fingerprint = standing(it.x, it.y)) else it }
        val mapEdges = edges.map { (a, b) ->
            val from = nodesById.getValue(a)
            val to = nodesById.getValue(b)
            MapEdge(a, b, accessible = true, distanceMeters = hypot(to.x - from.x, to.y - from.y))
        }
        val samples = if (!withRadio) {
            emptyList()
        } else {
            // Walked corridor by corridor, sampled the way the survey screen samples (RadioSampler).
            edges.flatMap { (a, b) ->
                val from = nodesById.getValue(a)
                val to = nodesById.getValue(b)
                val scanner = VirtualScanner(this, random, phone)
                val sampler = RadioSampler()
                var t = 0L
                // The surveyor arrives at the corridor's start already hearing it.
                repeat(STANDING_STEPS) { scanner.hear(from.x, from.y, t); t += 550L }
                val count = ceil(hypot(to.x - from.x, to.y - from.y) / SURVEY_STRIDE_METERS).toInt()
                (0..count).mapNotNull { i ->
                    val f = i.toDouble() / count
                    val x = from.x + (to.x - from.x) * f
                    val y = from.y + (to.y - from.y) * f
                    t += 550L
                    sampler.offer(Position(x, y, 0, 0.0, 1.0, PositionSource.PEDESTRIAN_DEAD_RECKONING, t), scanner.hear(x, y, t))
                }
            }
        }
        val floor = FloorMap(floorIndex = 0, nodes = mapNodes, edges = mapEdges, occupancy = occupancy(), radioMap = samples)
        return BuildingMap(id = name, floors = listOf(floor))
    }

    /**
     * The wall map a camera survey would have made: walls and furniture occupied,
     * the floor inside the outer walls free, outside unknown. Null without walls.
     */
    fun occupancy(cellSizeMeters: Double = 0.1): OccupancyRaster? {
        if (walls.isEmpty()) return null
        val insideMinX = walls.minOf { minOf(it.x1, it.x2) }
        val insideMaxX = walls.maxOf { maxOf(it.x1, it.x2) }
        val insideMinY = walls.minOf { minOf(it.y1, it.y2) }
        val insideMaxY = walls.maxOf { maxOf(it.y1, it.y2) }
        val originX = insideMinX - RASTER_MARGIN_METERS
        val originY = insideMinY - RASTER_MARGIN_METERS
        val width = ceil((insideMaxX - insideMinX + 2 * RASTER_MARGIN_METERS) / cellSizeMeters).toInt()
        val height = ceil((insideMaxY - insideMinY + 2 * RASTER_MARGIN_METERS) / cellSizeMeters).toInt()
        val cells = ByteArray(width * height)
        for (row in 0 until height) {
            val y = originY + (row + 0.5) * cellSizeMeters
            for (column in 0 until width) {
                val x = originX + (column + 0.5) * cellSizeMeters
                cells[row * width + column] = when {
                    walls.any { it.distanceTo(x, y) <= WALL_HALF_THICKNESS_METERS } || blocks.any { it.contains(x, y) } -> OccupancyRaster.OCCUPIED
                    x > insideMinX && x < insideMaxX && y > insideMinY && y < insideMaxY -> OccupancyRaster.FREE
                    else -> OccupancyRaster.UNKNOWN
                }
            }
        }
        return OccupancyRaster.of(cellSizeMeters, originX, originY, width, height, cells)
    }

    companion object {
        private const val STANDING_STEPS = 8
        private const val SURVEY_STRIDE_METERS = 0.7
        private const val RASTER_MARGIN_METERS = 0.3
        private const val WALL_HALF_THICKNESS_METERS = 0.08
    }
}

/**
 * How the phone hears beacons: [advertsPerStep] advertisements per beacon reach
 * it every step (beacons sending every 100–300 ms, as KURULUM.md recommends,
 * some lost), and each is smoothed per beacon with this [processNoise].
 */
internal data class PhoneRadio(
    val advertsPerStep: Int = 2,
    val processNoise: Double = KalmanFilter1D.DEFAULT_PROCESS_NOISE,
)

/**
 * The phone's side of the radio, as BleBeaconScanner does it: every advertisement
 * heard is smoothed per beacon (KalmanFilter1D) and kept as that beacon's latest
 * sighting until [BleBeaconScanner.STALE_THRESHOLD_MS] passes without another.
 * What the filter gets is that whole list, so a beacon not heard for a while
 * still shows its old value.
 */
internal class VirtualScanner(private val building: VirtualBuilding, private val random: Random, private val phone: PhoneRadio = PhoneRadio()) {
    private val smoothers = mutableMapOf<String, KalmanFilter1D>()
    private val sightings = mutableMapOf<String, BleBeaconReading>()

    /** A step's worth of advertisements heard at ([x], [y]); returns the sightings list after them. */
    fun hear(x: Double, y: Double, timestampMs: Long): List<BleBeaconReading> {
        repeat(phone.advertsPerStep) {
            for (reading in building.scan(x, y, timestampMs, random)) {
                val smoothed = smoothers.getOrPut(reading.beaconId) { KalmanFilter1D(processNoise = phone.processNoise) }.update(reading.rssi.toDouble())
                sightings[reading.beaconId] = reading.copy(rssi = smoothed.roundToInt())
            }
        }
        sightings.values.removeAll { timestampMs - it.timestampMs > BleBeaconScanner.STALE_THRESHOLD_MS }
        return sightings.values.sortedByDescending { it.rssi }
    }
}

internal fun Random.gaussian(): Double {
    // Box–Muller; 1 - nextDouble() keeps the log away from zero.
    val u1 = 1.0 - nextDouble()
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}
