package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.CompassCalibration
import com.appvalence.umay.coremodel.CompassSums
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.RadioSample
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.compass.CompassHint
import com.appvalence.umay.positioning.compass.HeadingReading
import com.appvalence.umay.positioning.particle.GraphParticleFilter
import com.appvalence.umay.positioning.pdr.PdrIntegrator
import com.appvalence.umay.positioning.pdr.RotationVectors
import com.appvalence.umay.positioning.pdr.StepDetection
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * One real walk from Microsoft's Indoor Location Competition 2.0 data (MIT
 * licence, github.com/location-competition/indoor-location-competition-20):
 * a phone held in front of the body in a shopping mall, recording its
 * accelerometer, gyroscope and rotation vector, iBeacon and Wi-Fi scans, and
 * the surveyor's ground-truth points. It lets the app's own step detection
 * and positioning run on real sensor data without a phone
 * (docs/YOL_HARITASI.md 0.10).
 */
internal class IlcPath(
    val name: String,
    val accelerometer: List<Sample>,
    val gyroscope: List<Sample>,
    val rotation: List<Sample>,
    val waypoints: List<Waypoint>,
    val beacons: List<Scan>,
    val wifi: List<Scan>,
) {
    data class Sample(val t: Long, val x: Float, val y: Float, val z: Float)
    data class Waypoint(val t: Long, val x: Double, val y: Double)
    data class Scan(val t: Long, val id: String, val rssi: Int)

    /** From the first ground-truth point's time to the last's: when the true position is known. */
    val truthSpan: LongRange get() = waypoints.first().t..waypoints.last().t

    val truthLengthMeters: Double get() = waypoints.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()

    /** Where the surveyor was at [t], on the straight line between the two marked points around it; null outside them. */
    fun truthAt(t: Long): Point? {
        if (waypoints.isEmpty() || t < waypoints.first().t || t > waypoints.last().t) return null
        val after = waypoints.indexOfFirst { it.t >= t }
        if (after <= 0) return Point(waypoints[0].x, waypoints[0].y)
        val a = waypoints[after - 1]
        val b = waypoints[after]
        val f = if (b.t == a.t) 1.0 else (t - a.t).toDouble() / (b.t - a.t)
        return Point(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
    }

    /**
     * The phone's heading over time from the gyroscope alone, turned about the
     * vertical that the rotation vector says is up — what the app's
     * TYPE_GAME_ROTATION_VECTOR would have reported (the data has no game
     * rotation vector, and the plain one leans on the magnetometer, which the app
     * deliberately avoids). Degrees clockwise; the starting value is arbitrary.
     */
    fun gyroHeadings(): List<Pair<Long, Double>> {
        if (gyroscope.isEmpty() || rotation.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Long, Double>>(gyroscope.size)
        var r = 0
        var azimuth = RotationVectors.headingDeg(floatArrayOf(rotation[0].x, rotation[0].y, rotation[0].z))
        var previousT = gyroscope.first().t
        for (sample in gyroscope) {
            while (r + 1 < rotation.size && rotation[r + 1].t <= sample.t) r++
            val m = RotationVectors.rotationMatrix(floatArrayOf(rotation[r].x, rotation[r].y, rotation[r].z))
            // The world's vertical in device coordinates is the matrix's bottom row; turning
            // anticlockwise about it (seen from above) lowers the azimuth.
            val upRate = m[6] * sample.x + m[7] * sample.y + m[8] * sample.z
            azimuth -= Math.toDegrees(upRate) * (sample.t - previousT) / 1000.0
            previousT = sample.t
            out += sample.t to azimuth
        }
        return out
    }

    /** The compass's heading (rotation vector, magnetometer included) at each sample from [from] to [to], as the app reads it. */
    fun magneticHeadings(from: Long, to: Long): List<Pair<Long, Double>> =
        rotation.filter { it.t in from..to }.map { it.t to RotationVectors.headingDeg(floatArrayOf(it.x, it.y, it.z)) }

    /** Footsteps as the app's own step detection ([StepDetection]) finds them in this walk's accelerometer. */
    fun steps(strideMeters: Double): List<StepEvent> {
        val detection = StepDetection(strideMeters)
        val headings = gyroHeadings()
        var h = 0
        val steps = ArrayList<StepEvent>()
        for (sample in accelerometer) {
            while (h < headings.size && headings[h].first <= sample.t) {
                detection.onHeading(((headings[h].second % 360.0) + 360.0) % 360.0)
                h++
            }
            detection.onAcceleration(sample.x, sample.y, sample.z, sample.t * 1_000_000, sample.t)?.let(steps::add)
        }
        return steps
    }

    companion object {
        /** Reads one path file; lines it can't read (the data has a few run-together ones) are skipped. */
        fun parse(file: File): IlcPath = parse(file.nameWithoutExtension, file.readLines())

        fun parse(name: String, lines: List<String>): IlcPath {
            val accelerometer = ArrayList<Sample>()
            val gyroscope = ArrayList<Sample>()
            val rotation = ArrayList<Sample>()
            val waypoints = ArrayList<Waypoint>()
            val beacons = ArrayList<Scan>()
            val wifi = ArrayList<Scan>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                runCatching {
                    val t = p[0].toLong()
                    fun sample() = Sample(t, p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
                    when (p[1]) {
                        "TYPE_ACCELEROMETER" -> accelerometer += sample()
                        "TYPE_GYROSCOPE" -> gyroscope += sample()
                        "TYPE_ROTATION_VECTOR" -> rotation += sample()
                        "TYPE_WAYPOINT" -> waypoints += Waypoint(t, p[2].toDouble(), p[3].toDouble())
                        "TYPE_BEACON" -> beacons += Scan(t, "${p[2]}-${p[3]}-${p[4]}", p[6].toInt())
                        "TYPE_WIFI" -> wifi += Scan(t, p[3], p[4].toInt())
                    }
                }
            }
            return IlcPath(name, accelerometer, gyroscope, rotation, waypoints.sortedBy { it.t }, beacons, wifi)
        }
    }
}

/** Which radio the survey and the walk use. */
internal enum class IlcRadio {
    /** Footsteps and the map alone. */
    NONE,

    /** The iBeacons the walks heard, as the app would use them (few, in this data). */
    BEACONS,

    /**
     * The most-heard Wi-Fi access points standing in for beacons. The app doesn't
     * scan Wi-Fi (Android allows four scans in two minutes); this only tests the
     * radio-map method on real signal strengths.
     */
    WIFI,
}

/** One floor's walks; each is replayed against a map surveyed from the others. */
internal class IlcFloor(val name: String, val paths: List<IlcPath>) {

    /**
     * The corridor graph a survey of this floor would give, from every walk's
     * ground-truth points but [excluded]'s (so a walk is never tested against a
     * map drawn from itself): nearby points merge into one place, consecutive
     * points become corridors, crossing corridors meet at a new place, and a
     * place lying on a corridor splits it. With [radio], the radio map those
     * walks recorded: each scan at the true position of its moment.
     */
    fun surveyMap(excluded: IlcPath?, radio: IlcRadio): BuildingMap {
        val sources = paths.filter { it !== excluded }
        val graph = CorridorGraph()
        for (path in sources) {
            for ((a, b) in path.waypoints.zipWithNext()) {
                val length = hypot(b.x - a.x, b.y - a.y)
                if (length < MIN_SEGMENT_METERS || length > MAX_SEGMENT_METERS) continue
                graph.addCorridor(a.x, a.y, b.x, b.y)
            }
        }
        graph.joinCrossings()
        val radioSamples = when (radio) {
            IlcRadio.NONE -> emptyList()
            IlcRadio.BEACONS -> radioSamples(sources) { it.beacons }
            IlcRadio.WIFI -> {
                val common = commonAccessPoints()
                radioSamples(sources) { path -> path.wifi.filter { it.id in common } }
            }
        }
        return BuildingMap(id = name, floors = listOf(FloorMap(floorIndex = 0, nodes = graph.nodes(), edges = graph.edges(), radioMap = radioSamples)))
    }

    /**
     * How the compass lines up with this floor's map, made from every walk but [excluded]
     * the way the app's survey makes it ([CompassSums]): the map heading against the
     * compass all along the walk — here, the direction walked along each straight stretch
     * (the phone held facing ahead), against the compass over its middle.
     */
    fun compassCalibration(excluded: IlcPath?): CompassCalibration? {
        var sums = CompassSums()
        for (path in paths) {
            if (path === excluded) continue
            for ((a, b) in path.waypoints.zipWithNext()) {
                if (hypot(b.x - a.x, b.y - a.y) < STRAIGHT_STRETCH_METERS) continue
                val walked = Math.toDegrees(atan2(b.x - a.x, b.y - a.y))
                for ((_, magnetic) in path.magneticHeadings(a.t + (b.t - a.t) / 5, b.t - (b.t - a.t) / 5)) sums = sums.plus(walked, magnetic)
            }
        }
        return sums.calibration()
    }

    /** The access points heard most often on this floor, as many as a building would have beacons. */
    fun commonAccessPoints(): Set<String> =
        paths.flatMap { it.wifi }.groupingBy { it.id }.eachCount().entries.sortedByDescending { it.value }.take(WIFI_AS_BEACONS).map { it.key }.toSet()

    private fun radioSamples(sources: List<IlcPath>, scansOf: (IlcPath) -> List<IlcPath.Scan>): List<RadioSample> =
        sources.flatMap { path ->
            scansOf(path).groupBy { it.t }.mapNotNull { (t, scans) ->
                val at = path.truthAt(t) ?: return@mapNotNull null
                val strongest = scans.sortedByDescending { it.rssi }.distinctBy { it.id }.take(MAX_IDS_PER_SAMPLE)
                if (strongest.size < 2) null else RadioSample(at.x, at.y, strongest.map { RadioFingerprint(it.id, it.rssi.toDouble()) })
            }
        }

    companion object {
        private const val MIN_SEGMENT_METERS = 0.3
        private const val MAX_SEGMENT_METERS = 25.0
        private const val WIFI_AS_BEACONS = 40
        private const val MAX_IDS_PER_SAMPLE = 8

        /** A floor folder of the dataset: the one with path_data_files in it. */
        fun load(folder: File): IlcFloor {
            val files = File(folder, "path_data_files").listFiles { file -> file.extension == "txt" }.orEmpty().sortedBy { it.name }
            val paths = files.map(IlcPath::parse).filter { it.waypoints.size >= 2 && it.accelerometer.isNotEmpty() }
            return IlcFloor("${folder.parentFile?.name}/${folder.name}", paths)
        }

        /** Every floor folder under [root] (or [root] itself). */
        fun loadAll(root: File): List<IlcFloor> =
            root.walkTopDown().filter { File(it, "path_data_files").isDirectory }.sortedBy { it.path }.map(::load).toList()
    }
}

/**
 * Corridors as straight lines between places, places merged when closer than
 * [mergeMeters]. Built up from survey points, then joined where lines cross.
 */
private class CorridorGraph(private val mergeMeters: Double = 1.5) {
    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val grid = HashMap<Long, MutableList<Int>>()
    private var links = HashSet<Pair<Int, Int>>()

    private fun cell(v: Double) = floor(v / mergeMeters).toLong()
    private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)

    fun place(x: Double, y: Double, within: Double = mergeMeters): Int {
        var best = -1
        var bestDistance = within
        for (dx in -1L..1L) for (dy in -1L..1L) {
            for (i in grid[key(cell(x) + dx, cell(y) + dy)].orEmpty()) {
                val d = hypot(xs[i] - x, ys[i] - y)
                if (d <= bestDistance) {
                    best = i
                    bestDistance = d
                }
            }
        }
        if (best >= 0) return best
        xs += x
        ys += y
        val index = xs.size - 1
        grid.getOrPut(key(cell(x), cell(y))) { mutableListOf() } += index
        return index
    }

    fun addCorridor(ax: Double, ay: Double, bx: Double, by: Double) {
        val a = place(ax, ay)
        val b = place(bx, by)
        if (a != b) links += minOf(a, b) to maxOf(a, b)
    }

    /** Where two corridors cross, or a place sits on a corridor, they get a shared place. */
    fun joinCrossings() {
        val list = links.toList()
        val cuts = HashMap<Int, MutableList<Pair<Double, Int>>>()
        for (i in list.indices) {
            val (a, b) = list[i]
            for (j in i + 1 until list.size) {
                val (c, d) = list[j]
                if (a == c || a == d || b == c || b == d) continue
                val hit = crossing(xs[a], ys[a], xs[b], ys[b], xs[c], ys[c], xs[d], ys[d]) ?: continue
                val p = place(xs[a] + (xs[b] - xs[a]) * hit.first, ys[a] + (ys[b] - ys[a]) * hit.first, within = mergeMeters / 2)
                cuts.getOrPut(i) { mutableListOf() } += hit.first to p
                cuts.getOrPut(j) { mutableListOf() } += hit.second to p
            }
            for (p in xs.indices) {
                if (p == a || p == b) continue
                val t = projection(xs[a], ys[a], xs[b], ys[b], xs[p], ys[p]) ?: continue
                val px = xs[a] + (xs[b] - xs[a]) * t
                val py = ys[a] + (ys[b] - ys[a]) * t
                if (hypot(px - xs[p], py - ys[p]) <= ON_CORRIDOR_METERS) cuts.getOrPut(i) { mutableListOf() } += t to p
            }
        }
        val joined = HashSet<Pair<Int, Int>>()
        list.forEachIndexed { i, (a, b) ->
            val chain = listOf(a) + cuts[i].orEmpty().sortedBy { it.first }.map { it.second } + b
            chain.zipWithNext().forEach { (u, v) -> if (u != v) joined += minOf(u, v) to maxOf(u, v) }
        }
        links = joined
    }

    fun nodes(): List<MapNode> = xs.indices.map { MapNode(id = "n$it", x = xs[it], y = ys[it], type = NodeType.WAYPOINT) }

    fun edges(): List<MapEdge> = links.map { (a, b) -> MapEdge("n$a", "n$b", accessible = true, distanceMeters = hypot(xs[b] - xs[a], ys[b] - ys[a])) }

    private fun crossing(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Pair<Double, Double>? {
        val rX = bx - ax
        val rY = by - ay
        val sX = dx - cx
        val sY = dy - cy
        val denominator = rX * sY - rY * sX
        if (denominator == 0.0) return null
        val t = ((cx - ax) * sY - (cy - ay) * sX) / denominator
        val u = ((cx - ax) * rY - (cy - ay) * rX) / denominator
        return if (t in INNER && u in INNER) t to u else null
    }

    private fun projection(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double? {
        val lengthSquared = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
        if (lengthSquared == 0.0) return null
        val t = ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / lengthSquared
        return t.takeIf { it in INNER }
    }

    private companion object {
        val INNER = 0.02..0.98
        const val ON_CORRIDOR_METERS = 1.0
    }
}

/** How one real walk went when replayed ([replayIlcWalk]). */
internal data class IlcResult(
    val path: String,
    val steps: Int,
    val truthMeters: Double,
    val strideMeters: Double,
    /** Particle filter (the app's positioning) against the truth, at every step. */
    val particleErrors: List<Double>,
    /** Plain dead reckoning, even given the true starting direction, at every step. */
    val pdrErrors: List<Double>,
    val recording: WalkRecording?,
    /** How far the true position was from the nearest surveyed corridor, at every step. */
    val offMapMeters: List<Double> = emptyList(),
    /** How far the compass's starting direction was from the true one, when it gave one. */
    val compassMissDeg: Double? = null,
) {
    /** Share of the walk within [ON_MAP_METERS] of a surveyed corridor. */
    val coverage: Double get() = offMapMeters.count { it <= ON_MAP_METERS }.toDouble() / offMapMeters.size.coerceAtLeast(1)
    val particleMean: Double get() = particleErrors.average()
    val pdrMean: Double get() = pdrErrors.average()
}

/**
 * Replays [path] through the app's own step detection and particle filter on
 * a map surveyed from the floor's other walks, scoring both it and plain dead
 * reckoning against the walk's true position at every step. [strideMeters]
 * null: the walker's stride calibrated (the true length over the steps
 * counted), as the app's calibration would. Null when the walk starts too far
 * from the surveyed corridors to be placed on them.
 */
internal fun replayIlcWalk(
    floor: IlcFloor,
    path: IlcPath,
    radio: IlcRadio = IlcRadio.NONE,
    strideMeters: Double? = null,
    seed: Int = 1,
    config: GraphParticleFilter.Config = GraphParticleFilter.Config(),
    record: Boolean = false,
    surveyIncludesWalk: Boolean = false,
    /** Tell the filter the walk's true starting direction, give or take this many degrees (an experiment: the app has no such hint). */
    startHeadingHintSigmaDeg: Double? = null,
    /** Non-null: give the filter the compass's starting direction, as the app does (CompassHint, which sets its own trust). */
    compassHintSigmaDeg: Double? = null,
): IlcResult? {
    val map = floor.surveyMap(excluded = if (surveyIncludesWalk) null else path, radio = radio)
    val nodes = map.floors.single().nodes
    val first = path.waypoints.first()
    val start = nodes.minByOrNull { hypot(it.x - first.x, it.y - first.y) } ?: return null
    if (hypot(start.x - first.x, start.y - first.y) > MAX_START_OFFSET_METERS) return null

    val counted = path.steps(StepDetectionStride).filter { it.timestampMs in path.truthSpan }
    if (counted.size < MIN_STEPS) return null
    val stride = strideMeters ?: (path.truthLengthMeters / counted.size)
    val steps = path.steps(stride)
    val inSpan = steps.filter { it.timestampMs in path.truthSpan }

    val pdr = oraclePdr(path, steps, inSpan)
    // The filter's gyro frame starts at zero before the first step it sees; the dead
    // reckoner's starting heading is already in that frame's terms.
    val filter = GraphParticleFilter(
        map, start.id, config, Random(seed),
        startHeadingOffsetDeg = startHeadingHintSigmaDeg?.let { pdr.startHeadingDeg() },
        startHeadingSigmaDeg = startHeadingHintSigmaDeg ?: 30.0,
    )
    val compass = if (compassHintSigmaDeg != null) compassStartHeading(floor, path, steps, inSpan) else null
    compass?.let { (offset, sigma) -> filter.observeHeadingOffset(offset, sigma) }
    val compassMissDeg = compass?.let { (offset, _) -> kotlin.math.abs(((offset - pdr.startHeadingDeg()) % 360.0 + 540.0) % 360.0 - 180.0) }

    val scans: List<Pair<Long, List<BleBeaconReading>>> = when (radio) {
        IlcRadio.NONE -> emptyList()
        IlcRadio.BEACONS -> path.beacons.groupBy { it.t }.map { (t, s) -> t to s.map { reading(it) } }
        IlcRadio.WIFI -> {
            val common = floor.commonAccessPoints()
            path.wifi.filter { it.id in common }.groupBy { it.t }.map { (t, s) -> t to s.map { reading(it) } }
        }
    }.filter { it.first in path.truthSpan }.sortedBy { it.first }

    val particleErrors = ArrayList<Double>()
    val pdrErrors = ArrayList<Double>()
    val offMap = ArrayList<Double>()
    val frames = ArrayList<WalkFrame>()
    var scan = 0
    for (step in inSpan) {
        val notes = ArrayList<String>()
        while (scan < scans.size && scans[scan].first <= step.timestampMs) {
            val (t, readings) = scans[scan++]
            filter.onBeaconReadings(readings, t)?.let { notes += "Parmak izi eşleşmesi: ${it.node.id}" }
        }
        val estimate = filter.onStep(step)
        val dead = pdr.integrate(step)
        val truth = path.truthAt(step.timestampMs) ?: continue
        particleErrors += hypot(estimate.x - truth.x, estimate.y - truth.y)
        pdrErrors += hypot(dead.x - truth.x, dead.y - truth.y)
        offMap += distanceToCorridors(map, truth)
        if (record) {
            frames += WalkFrame(
                tMs = step.timestampMs,
                floor = 0,
                truth = truth,
                estimate = Point(estimate.x, estimate.y),
                accuracyMeters = estimate.accuracyMeters,
                headingDeg = estimate.headingDeg,
                secondEstimate = Point(dead.x, dead.y),
                particles = filter.particlePositions(60),
                notes = notes,
            )
        }
    }
    if (particleErrors.isEmpty()) return null

    val recording = if (!record) {
        null
    } else {
        WalkRecording(
            title = "${floor.name} · ${path.name.take(8)}",
            subtitle = "Gerçek yürüyüş (Indoor Location Competition 2.0, MIT). Adımlar ve yön uygulamanın kendi koduyla bulundu; harita katın diğer yürüyüşlerinden çıkarıldı." +
                if (radio == IlcRadio.NONE) "" else " Radyo: ${radio.name.lowercase()}.",
            map = map,
            frames = frames,
            summary = listOf(
                SummaryItem("Uygulamanın hatası", "%.2f m".format(particleErrors.average())),
                SummaryItem("Yalnız adım sayma", "%.2f m".format(pdrErrors.average())),
                SummaryItem("Adım", inSpan.size.toString()),
                SummaryItem("Yürünen", "%.0f m".format(path.truthLengthMeters)),
                SummaryItem("Adım boyu", "%.2f m".format(stride)),
            ),
            estimateLabel = "Uygulamanın tahmini",
            secondEstimateLabel = "Yalnız adım sayma (başlangıç yönü bilinerek)",
        )
    }
    return IlcResult(path.name, inSpan.size, path.truthLengthMeters, stride, particleErrors, pdrErrors, recording, offMap, compassMissDeg)
}

private const val MAX_START_OFFSET_METERS = 3.0
internal const val ON_MAP_METERS = 2.0

private fun distanceToCorridors(map: BuildingMap, point: Point): Double {
    val floor = map.floors.single()
    val byId = floor.nodes.associateBy { it.id }
    return floor.edges.minOfOrNull { edge ->
        val a = byId.getValue(edge.fromId)
        val b = byId.getValue(edge.toId)
        Wall(a.x, a.y, b.x, b.y).distanceTo(point.x, point.y)
    } ?: Double.MAX_VALUE
}
private const val MIN_STEPS = 10

// Only the count matters when calibrating the stride; any length does.
private const val StepDetectionStride = 0.7

/**
 * Dead reckoning given what the app never has: the true starting direction (the
 * gyro's heading turned to match the first few metres of ground truth).
 */
private class OraclePdr(private val startHeading: Double, private val integrator: PdrIntegrator) {
    fun startHeadingDeg() = startHeading
    fun integrate(step: StepEvent) = integrator.integrate(step)
}

private fun oraclePdr(path: IlcPath, allSteps: List<StepEvent>, inSpan: List<StepEvent>): OraclePdr {
    val first = path.waypoints.first()
    // The gyro's own heading at each step is the running sum of the turns.
    var heading = 0.0
    val headingAt = HashMap<Long, Double>()
    for (step in allSteps) {
        heading += step.headingDeltaDeg
        headingAt[step.timestampMs] = heading
    }
    val target = path.waypoints.firstOrNull { hypot(it.x - first.x, it.y - first.y) >= ORACLE_METERS } ?: path.waypoints.last()
    val trueBearing = Math.toDegrees(atan2(target.x - first.x, target.y - first.y))
    val early = inSpan.filter { it.timestampMs <= target.t }.ifEmpty { inSpan.take(1) }
    val sinSum = early.sumOf { sin(Math.toRadians(headingAt.getValue(it.timestampMs))) }
    val cosSum = early.sumOf { cos(Math.toRadians(headingAt.getValue(it.timestampMs))) }
    val gyroBearing = Math.toDegrees(atan2(sinSum, cosSum))
    val beforeFirst = inSpan.firstOrNull()?.let { headingAt.getValue(it.timestampMs) - it.headingDeltaDeg } ?: 0.0
    val startHeading = beforeFirst + (trueBearing - gyroBearing)
    return OraclePdr(startHeading, PdrIntegrator(startX = first.x, startY = first.y, startHeadingDeg = startHeading))
}

private const val ORACLE_METERS = 4.0

/**
 * The filter's starting heading offset as the app gets it (CompassHint): the compass and
 * the gyro read together for the first moments of the walk, turned onto the map with the
 * floor's calibration. The gyro reading is in the filter's own frame: the turns of the
 * steps it was given, from zero. Null when the compass or the floor can't say.
 */
private fun compassStartHeading(floor: IlcFloor, path: IlcPath, allSteps: List<StepEvent>, inSpan: List<StepEvent>): Pair<Double, Double>? {
    val calibration = floor.compassCalibration(excluded = path) ?: return null
    val first = inSpan.firstOrNull() ?: return null
    // Each step carries the turn since the one before; the very first, the gyro's whole heading.
    val beforeFirst = allSteps.takeWhile { it.timestampMs < first.timestampMs }.sumOf { it.headingDeltaDeg }
    val gyro = path.gyroHeadings()
    val hint = CompassHint(calibration)
    var g = 0
    // As the app does: about 5 readings a second, from when the walk starts, until enough
    // or CompassHint.WAIT_MS has gone by.
    val from = first.timestampMs - COMPASS_LOOK_BACK_MS
    for ((t, magnetic) in path.magneticHeadings(from, from + CompassHint.WAIT_MS).filterIndexed { i, _ -> i % 10 == 0 }) {
        while (g + 1 < gyro.size && gyro[g + 1].first <= t) g++
        val gyroHeading = gyro.getOrNull(g)?.second ?: continue
        hint.add(HeadingReading(t, gyroHeading - beforeFirst, magnetic, magneticReliable = true))
        if (hint.isReady) break
    }
    return hint.offset()
}

private const val COMPASS_LOOK_BACK_MS = 1_000L
private const val STRAIGHT_STRETCH_METERS = 4.0

private fun reading(scan: IlcPath.Scan) = BleBeaconReading(
    beaconId = scan.id,
    macAddress = "",
    deviceName = null,
    uuid = "ilc",
    major = null,
    minor = null,
    rssi = scan.rssi,
    txPowerAtOneMeter = null,
    estimatedDistanceMeters = 0.0,
    timestampMs = scan.t,
)
