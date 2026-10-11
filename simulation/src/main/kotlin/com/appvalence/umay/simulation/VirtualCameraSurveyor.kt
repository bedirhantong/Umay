package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.MapDoors
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.RadioSample
import com.appvalence.umay.coremodel.TaggedWaypoint
import com.appvalence.umay.positioning.fingerprint.RadioSampler
import com.appvalence.umay.positioning.mapping.ArPose
import com.appvalence.umay.positioning.mapping.ArPoseAligner
import com.appvalence.umay.positioning.mapping.ArToSurvey
import com.appvalence.umay.positioning.mapping.DepthFrame
import com.appvalence.umay.positioning.mapping.DepthProjector
import com.appvalence.umay.positioning.mapping.FloorHeightEstimator
import com.appvalence.umay.positioning.mapping.OccupancyGrid
import com.appvalence.umay.positioning.mapping.OpenSpaceLinks
import com.appvalence.umay.positioning.mapping.SurveyPose
import com.appvalence.umay.positioning.mapping.WallMapBudget
import com.appvalence.umay.positioning.trilateration.BeaconMapBuilder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** How ARCore's tracking behaves during a camera survey; the defaults are an ordinary phone in a lit office. */
internal data class ArTrackingModel(
    /** The pose's heading wanders this much per metre walked (degrees, random walk). */
    val yawDriftDegPerMeter: Double = 0.15,
    /** And its distances are this much off (a fraction, fixed for the session). */
    val scaleError: Double = 0.01,
    /** Centimetre-level jitter of each pose. */
    val positionNoiseMeters: Double = 0.02,
    /** Chance per step that tracking is lost (a blank wall, a hand over the lens), for [lossSteps] steps. */
    val lossPerStep: Double = 0.01,
    val lossSteps: Int = 6,
    /** Chance that tracking comes back in a different frame (ARCore relocalised wrongly). */
    val jumpOnResume: Double = 0.3,
    /** Relative depth error of each depth pixel. */
    val depthNoiseFraction: Double = 0.02,
)

/**
 * A sighted surveyor making the map with the camera, as KURULUM.md describes:
 * every corridor walked once (down each branch and back), each place named on
 * reaching it, the phone held at chest height facing ahead. ARCore's poses
 * drift ([ArTrackingModel]), lose track now and then and sometimes come back
 * somewhere else; depth images are cast from the building's real walls and
 * furniture but placed with ARCore's (drifted) pose. Everything after that is
 * the app's own code, exactly as SurveyRecorder and SurveyViewModel run it:
 * ArPoseAligner, DepthProjector into an OccupancyGrid, BeaconMapBuilder,
 * WallMapBudget, MapDoors, OpenSpaceLinks.
 */
internal object VirtualCameraSurveyor {

    data class Survey(
        val surveyed: SurveyedMap,
        /** Per wall-map cell called a wall: whether a real wall or furniture is within [WALL_MATCH_METERS]. */
        val wallPrecision: Double,
        /** Per point along the real walls the survey passed near: whether the map has a wall cell close by. */
        val wallRecall: Double,
        val trackingLosses: Int,
        val frameJumps: Int,
    )

    fun survey(
        building: VirtualBuilding,
        startNodeId: String,
        seed: Int,
        walker: WalkerModel = WalkerModel(),
        tracking: ArTrackingModel = ArTrackingModel(),
        phone: PhoneRadio = PhoneRadio(),
    ): Survey {
        require(building.floors.size == 1) { "the virtual camera surveyor walks one floor; ${building.name} has ${building.floors.size}" }
        val random = Random(seed)
        val scanner = VirtualScanner(building, random, phone)
        val sampler = RadioSampler()
        val aligner = ArPoseAligner()
        val grid = OccupancyGrid()
        val floorHeight = FloorHeightEstimator()
        // Where ARCore's world sits relative to the building's plan: anywhere at all.
        var arFrame = ArToSurvey(random.nextDouble(0.0, 360.0), random.nextDouble(-50.0, 50.0), random.nextDouble(-50.0, 50.0))
        var yawDrift = 0.0
        // Where ARCore's odometry has the walker, on the plan: each true step, turned by the drift so far and scaled.
        var odoX = 0.0
        var odoY = 0.0
        var trueX: Double? = null
        var trueY = 0.0
        var lostFor = 0
        var losses = 0
        var jumps = 0
        var t = 1_000L
        var walked = 0.0
        val trajectory = ArrayList<Position>()
        val tags = ArrayList<TaggedWaypoint>()
        val radio = ArrayList<RadioSample>()
        var live: Position? = null
        // The phone's gyro heading: the true one in its own frame, drifting a little.
        val gyroOffset = random.nextDouble(0.0, 360.0)
        var gyroDrift = 0.0

        // The building's walls and furniture as rays meet them: everything the depth camera can see.
        fun depthFrame(x: Double, y: Double, headingDeg: Double, reported: ArPose): DepthFrame {
            val width = 32
            val height = 24
            val focal = 25f
            val depth = ShortArray(width * height)
            val h = Math.toRadians(headingDeg)
            for (row in 0 until height) for (column in 0 until width) {
                // Camera space: +x right, +y up, looking along −z; then onto the plan.
                val cx = (column + 0.5 - width / 2.0) / focal
                val cy = (height / 2.0 - row - 0.5) / focal
                // Plan direction: forward (sin h, cos h), right (cos h, −sin h).
                val dx = sin(h) + cx * cos(h)
                val dy = cos(h) - cx * sin(h)
                val dz = cy
                val z = castRay(building, x, y, CAMERA_HEIGHT_METERS, dx, dy, dz) ?: continue
                // DepthFrame holds depth along the view axis, which is z itself here.
                depth[row * width + column] = (z * (1 + random.gaussian() * tracking.depthNoiseFraction) * 1000).roundToInt().coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
            }
            // The camera's pose as ARCore reports it, column-major, OpenGL axes (−Z forward).
            val rh = Math.toRadians(reported.headingDeg)
            val pose = floatArrayOf(
                cos(rh).toFloat(), 0f, sin(rh).toFloat(), 0f,
                0f, 1f, 0f, 0f,
                (-sin(rh)).toFloat(), 0f, cos(rh).toFloat(), 0f,
                reported.x.toFloat(), reported.y.toFloat(), reported.z.toFloat(), 1f,
            )
            return DepthFrame(width, height, depth, null, focal, focal, width / 2f, height / 2f, pose)
        }

        // Tracking lost: the footsteps carry the live position and the path, as SurveyRecorder does.
        fun reckon(pose: SurveyPose?) {
            pose ?: return
            val position = Position(pose.x, pose.y, 0, pose.headingDeg, 1.0, PositionSource.PEDESTRIAN_DEAD_RECKONING, t)
            live = position
            val last = trajectory.lastOrNull()
            if (last == null || hypot(last.x - position.x, last.y - position.y) >= TRAJECTORY_SPACING_METERS) trajectory += position
        }

        fun frame(x: Double, y: Double, headingDeg: Double) {
            t += walker.stepIntervalMs
            val fromX = trueX
            if (fromX == null) {
                odoX = x
                odoY = y
            } else {
                val d = Math.toRadians(yawDrift)
                val ex = (x - fromX) * (1 + tracking.scaleError)
                val ey = (y - trueY) * (1 + tracking.scaleError)
                odoX += ex * cos(d) + ey * sin(d)
                odoY += -ex * sin(d) + ey * cos(d)
            }
            trueX = x
            trueY = y
            gyroDrift += walker.gyroDriftPerStepDeg
            val gyro = headingDeg + gyroOffset + gyroDrift + random.gaussian() * GYRO_NOISE_DEG
            if (lostFor > 0) {
                lostFor--
                reckon(aligner.onStep(walker.calibratedStrideMeters, gyro))
                if (lostFor == 0 && random.nextDouble() < tracking.jumpOnResume) {
                    // Back in a different frame: where ARCore thinks the session started has moved.
                    arFrame = ArToSurvey(arFrame.rotationDeg + random.nextDouble(-40.0, 40.0), arFrame.tx + random.nextDouble(-5.0, 5.0), arFrame.ty + random.nextDouble(-5.0, 5.0))
                    jumps++
                }
                return
            }
            if (random.nextDouble() < tracking.lossPerStep) {
                lostFor = tracking.lossSteps
                losses++
                aligner.onTrackingLost()
                reckon(aligner.onStep(walker.calibratedStrideMeters, gyro))
                return
            }
            // The pose ARCore reports: the true one, drifted and jittered, in its own frame.
            val drifted = arPoseOf(arFrame, odoX, odoY, headingDeg + yawDrift, t, random, tracking.positionNoiseMeters)
            val pose = aligner.onTracked(drifted, gyro)
            val position = Position(pose.x, pose.y, 0, pose.headingDeg, 0.1, PositionSource.MAP_MATCHED, t)
            live = position
            val last = trajectory.lastOrNull()
            if (last == null || hypot(last.x - position.x, last.y - position.y) >= TRAJECTORY_SPACING_METERS) trajectory += position
            sampler.offer(position, scanner.hear(x, y, t))?.let(radio::add)
            val toSurvey = aligner.transform ?: return
            val floorY = floorHeight.update(drifted.y, listOf(0.0))
            DepthProjector.integrate(depthFrame(x, y, headingDeg, drifted), floorY, toSurvey, grid)
        }

        fun tag(node: MapNode) {
            var heard = emptyList<RadioFingerprint>()
            repeat(STANDING_STEPS) { heard = scanner.hear(node.x, node.y, t).map { RadioFingerprint(it.beaconId, it.rssi.toDouble()) } }
            val here = live ?: return
            tags += TaggedWaypoint(node.name, here.x, here.y, 0, heard, node.type, trajectoryIndex = trajectory.size)
        }

        fun walk(from: MapNode, to: MapNode) {
            val length = hypot(to.x - from.x, to.y - from.y)
            val bearing = Math.toDegrees(atan2(to.x - from.x, to.y - from.y))
            var along = 0.0
            while (along < length) {
                val step = minOf(walker.trueStrideMeters * (1.0 + random.gaussian() * walker.strideNoiseFraction), length - along)
                along += step
                walked += step
                yawDrift += random.gaussian() * tracking.yawDriftDegPerMeter * kotlin.math.sqrt(step)
                frame(from.x + (to.x - from.x) * along / length, from.y + (to.y - from.y) * along / length, bearing)
            }
        }

        val neighbors = building.edges.flatMap { (a, b) -> listOf(a to b, b to a) }.groupBy({ it.first }, { it.second })
        val walkedEdges = HashSet<Set<String>>()
        fun explore(nodeId: String) {
            val node = building.nodesById.getValue(nodeId)
            for (next in neighbors[nodeId].orEmpty()) {
                if (!walkedEdges.add(setOf(nodeId, next))) continue
                val nextNode = building.nodesById.getValue(next)
                walk(node, nextNode)
                tag(nextNode)
                explore(next)
                walk(nextNode, node)
                tag(node)
            }
        }
        val start = building.nodesById.getValue(startNodeId)
        // Looking about at the start until the camera tracks.
        repeat(3) { frame(start.x, start.y, 0.0) }
        tag(start)
        explore(startNodeId)

        // What SurveyViewModel does with a finished camera walk.
        val built = BeaconMapBuilder.buildFromWaypoints(building.name, tags, trajectory = trajectory, radioSamples = mapOf(0 to radio))
        val walls = grid.snapshot()?.let { WallMapBudget.fit(mapOf(0 to it))[0] }
        val map = built.copy(floors = built.floors.map { OpenSpaceLinks.add(MapDoors.apply(it.copy(occupancy = walls))) })
        val byName = map.floors.flatMap { it.nodes }.groupBy { it.name }
        val ids = building.nodes.mapNotNull { node -> byName[node.name]?.firstOrNull()?.let { node.id to it.id } }.toMap()
        val surveyed = SurveyedMap.fitted(map, building, ids)
        val (precision, recall) = wallAgreement(building, surveyed, map.floors.single().occupancy)
        return Survey(surveyed, precision, recall, losses, jumps)
    }

    /** The pose ARCore would report for a camera at plan ([x], [y]) facing [headingDeg], in [frame]'s world. */
    private fun arPoseOf(frame: ArToSurvey, x: Double, y: Double, headingDeg: Double, t: Long, random: Random, noise: Double): ArPose {
        // Invert ArToSurvey: plan = R·(arX, −arZ) + t.
        val r = Math.toRadians(frame.rotationDeg)
        val px = x - frame.tx
        val py = y - frame.ty
        val u = px * cos(r) - py * sin(r)
        val v = px * sin(r) + py * cos(r)
        return ArPose(u + random.gaussian() * noise, CAMERA_HEIGHT_METERS, -v + random.gaussian() * noise, normalize(headingDeg - frame.rotationDeg), t)
    }

    /** Distance along the ray (unit forward component) to the first wall, furniture top or side, or floor; null past range. */
    private fun castRay(building: VirtualBuilding, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double): Double? {
        var nearest = DepthProjector.MAX_RANGE_METERS + 1
        if (dz < 0) nearest = minOf(nearest, -z / dz)
        if (dz > 0) nearest = minOf(nearest, (CEILING_METERS - z) / dz)
        for (wall in building.walls) {
            val ex = wall.x2 - wall.x1
            val ey = wall.y2 - wall.y1
            val denominator = dx * ey - dy * ex
            if (denominator == 0.0) continue
            val s = ((wall.x1 - x) * ey - (wall.y1 - y) * ex) / denominator
            val u = ((wall.x1 - x) * dy - (wall.y1 - y) * dx) / denominator
            if (s > 1e-6 && u in 0.0..1.0 && z + s * dz in 0.0..CEILING_METERS) nearest = minOf(nearest, s)
        }
        for (block in building.blocks) {
            // Slab test against the box [minX, maxX] × [minY, maxY] × [0, height].
            var near = 0.0
            var far = Double.MAX_VALUE
            for ((origin, direction, low, high) in listOf(
                listOf(x, dx, block.minX, block.maxX),
                listOf(y, dy, block.minY, block.maxY),
                listOf(z, dz, 0.0, block.heightMeters),
            )) {
                if (abs(direction) < 1e-12) {
                    if (origin < low || origin > high) { near = Double.MAX_VALUE; break }
                    continue
                }
                val t1 = (low - origin) / direction
                val t2 = (high - origin) / direction
                near = maxOf(near, minOf(t1, t2))
                far = minOf(far, maxOf(t1, t2))
            }
            if (near <= far && near > 1e-6) nearest = minOf(nearest, near)
        }
        return nearest.takeIf { it <= DepthProjector.MAX_RANGE_METERS }
    }

    /**
     * How well the wall map matches the building, laid over its plan: of the cells it
     * calls walls, how many have a real wall or furniture close by; and of the points
     * along the real walls within sight of the walked corridors, how many it found.
     */
    private fun wallAgreement(building: VirtualBuilding, surveyed: SurveyedMap, raster: OccupancyRaster?): Pair<Double, Double> {
        raster ?: return 0.0 to 0.0
        val cells = raster.decodeCells() ?: return 0.0 to 0.0
        val toPlan = surveyed.toPlan()
        fun nearSolid(x: Double, y: Double) =
            building.walls.any { it.distanceTo(x, y) <= WALL_MATCH_METERS } ||
                building.blocks.any { x in it.minX - WALL_MATCH_METERS..it.maxX + WALL_MATCH_METERS && y in it.minY - WALL_MATCH_METERS..it.maxY + WALL_MATCH_METERS }
        var occupied = 0
        var matched = 0
        val wallCells = ArrayList<Pair<Double, Double>>()
        for (row in 0 until raster.height) for (column in 0 until raster.width) {
            if (cells[row * raster.width + column] != OccupancyRaster.OCCUPIED) continue
            val (x, y) = toPlan(raster.originX + (column + 0.5) * raster.cellSizeMeters, raster.originY + (row + 0.5) * raster.cellSizeMeters)
            occupied++
            if (nearSolid(x, y)) matched++
            wallCells += x to y
        }
        // Points every 25 cm along the real walls that a corridor passes within depth range of.
        val corridorPoints = building.edges.flatMap { (a, b) ->
            val from = building.nodesById.getValue(a)
            val to = building.nodesById.getValue(b)
            val n = (hypot(to.x - from.x, to.y - from.y) / 0.5).toInt().coerceAtLeast(1)
            (0..n).map { i -> from.x + (to.x - from.x) * i / n to from.y + (to.y - from.y) * i / n }
        }
        var expected = 0
        var found = 0
        for (wall in building.walls) {
            val n = (hypot(wall.x2 - wall.x1, wall.y2 - wall.y1) / 0.25).toInt().coerceAtLeast(1)
            for (i in 0..n) {
                val x = wall.x1 + (wall.x2 - wall.x1) * i / n
                val y = wall.y1 + (wall.y2 - wall.y1) * i / n
                if (corridorPoints.none { (cx, cy) -> hypot(cx - x, cy - y) <= VISIBLE_WALL_METERS && building.lineOfSight(cx, cy, x + (cx - x) * 0.05, y + (cy - y) * 0.05) }) continue
                expected++
                if (wallCells.any { (wx, wy) -> hypot(wx - x, wy - y) <= WALL_MATCH_METERS }) found++
            }
        }
        return (if (occupied == 0) 0.0 else matched.toDouble() / occupied) to (if (expected == 0) 0.0 else found.toDouble() / expected)
    }

    private fun normalize(deg: Double) = ((deg % 360.0) + 360.0) % 360.0

    private const val CAMERA_HEIGHT_METERS = 1.35
    private const val GYRO_NOISE_DEG = 1.0
    private const val CEILING_METERS = 2.7
    private const val STANDING_STEPS = 4
    private const val TRAJECTORY_SPACING_METERS = 0.25
    private const val WALL_MATCH_METERS = 0.3
    private const val VISIBLE_WALL_METERS = 2.5
}
