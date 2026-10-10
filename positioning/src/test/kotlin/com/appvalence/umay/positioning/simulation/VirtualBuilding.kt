package com.appvalence.umay.positioning.simulation

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
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

/**
 * How the radio behaves: log-distance path loss with Gaussian fading, and scans
 * that sometimes miss a beacon altogether. The defaults are typical indoor BLE.
 */
internal data class RadioModel(
    val rssiAtOneMeter: Double = -59.0,
    val pathLossExponent: Double = 2.2,
    val fadingSigmaDb: Double = 4.0,
    val dropoutProbability: Double = 0.1,
    val hearingFloorDbm: Double = -95.0,
) {
    fun meanRssi(distanceMeters: Double): Double = rssiAtOneMeter - 10.0 * pathLossExponent * log10(max(distanceMeters, 0.5))
}

/**
 * One floor of a building as the simulation knows it: the true corridors (the
 * map's graph), the beacons on its walls and how they sound. [surveyedMap]
 * is what a surveyor would have recorded walking every corridor once.
 */
internal class VirtualBuilding(
    val nodes: List<MapNode>,
    val edges: List<Pair<String, String>>,
    val beacons: List<VirtualBeacon>,
    val radio: RadioModel = RadioModel(),
) {
    val nodesById: Map<String, MapNode> = nodes.associateBy { it.id }

    /** RSSI each beacon would read at ([x], [y]) in one scan; beacons not heard are left out. */
    fun scan(x: Double, y: Double, timestampMs: Long, random: Random, radio: RadioModel = this.radio): List<BleBeaconReading> =
        beacons.mapNotNull { beacon ->
            if (random.nextDouble() < radio.dropoutProbability) return@mapNotNull null
            val rssi = radio.meanRssi(hypot(beacon.x - x, beacon.y - y)) + random.gaussian() * radio.fadingSigmaDb
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
        return BuildingMap(id = "virtual", floors = listOf(FloorMap(floorIndex = 0, nodes = mapNodes, edges = mapEdges, radioMap = samples)))
    }

    companion object {
        private const val STANDING_STEPS = 8
        private const val SURVEY_STRIDE_METERS = 0.7

        private fun place(id: String, x: Double, y: Double) = MapNode(id = id, x = x, y = y, type = NodeType.WAYPOINT, name = id)

        /**
         * A typical institution floor: a 40 m main corridor, two side corridors
         * up to a parallel one 15 m away (so there is a loop, and two corridors
         * that look alike to the steps), and a dead end to the south. Beacons
         * about every 10 m, as KURULUM.md recommends.
         *
         * ```
         *  N10 ─── N20 ─── N30
         *   │               │
         *  M0 ─ M10 ─ M20 ─ M30 ─ M40
         *              │
         *             S20
         * ```
         */
        fun institutionFloor(radio: RadioModel = RadioModel()) = VirtualBuilding(
            nodes = listOf(
                place("M0", 0.0, 0.0),
                place("M10", 10.0, 0.0),
                place("M20", 20.0, 0.0),
                place("M30", 30.0, 0.0),
                place("M40", 40.0, 0.0),
                place("N10", 10.0, 15.0),
                place("N20", 20.0, 15.0),
                place("N30", 30.0, 15.0),
                place("S20", 20.0, -8.0),
            ),
            edges = listOf(
                "M0" to "M10", "M10" to "M20", "M20" to "M30", "M30" to "M40",
                "M10" to "N10", "N10" to "N20", "N20" to "N30", "N30" to "M30",
                "M20" to "S20",
            ),
            beacons = listOf(
                VirtualBeacon("b1", 4.0, 1.5),
                VirtualBeacon("b2", 16.0, -1.5),
                VirtualBeacon("b3", 27.0, 1.5),
                VirtualBeacon("b4", 38.0, -1.5),
                VirtualBeacon("b5", 11.5, 8.0),
                VirtualBeacon("b6", 28.5, 8.0),
                VirtualBeacon("b7", 15.0, 16.5),
                VirtualBeacon("b8", 25.0, 13.5),
                VirtualBeacon("b9", 21.5, -6.0),
            ),
            radio = radio,
        )
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
