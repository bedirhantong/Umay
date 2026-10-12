package com.appvalence.umay.positioning.mapping

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where the camera is, in the AR session's own world frame: gravity-aligned, Y
 * up, metres, with an arbitrary origin and yaw fixed when the session started.
 * [headingDeg] is the camera's facing in the survey's convention (0 = the
 * session's −Z, clockwise), already flattened onto the floor.
 */
data class ArPose(val x: Double, val y: Double, val z: Double, val headingDeg: Double, val timestampMs: Long)

/** A point on the survey's floor plan: x right, y "north", heading clockwise from +y. */
data class SurveyPose(val x: Double, val y: Double, val headingDeg: Double, val timestampMs: Long)

/**
 * Maps the AR frame onto the survey's floor plan: first AR (x, −z) is taken as
 * plan (x, y), then rotated clockwise by [rotationDeg] and shifted by
 * ([tx], [ty]).
 */
data class ArToSurvey(val rotationDeg: Double, val tx: Double, val ty: Double) {
    private val cosR = cos(Math.toRadians(rotationDeg))
    private val sinR = sin(Math.toRadians(rotationDeg))

    fun x(arX: Double, arZ: Double): Double = arX * cosR + (-arZ) * sinR + tx
    fun y(arX: Double, arZ: Double): Double = -arX * sinR + (-arZ) * cosR + ty
    fun heading(arHeadingDeg: Double): Double = normalizeDegrees(arHeadingDeg + rotationDeg)

    /** The same rotation, shifted so that AR ([arX], [arZ]) lands on plan ([x], [y]). */
    fun anchoredAt(arX: Double, arZ: Double, x: Double, y: Double): ArToSurvey {
        val unshifted = copy(tx = 0.0, ty = 0.0)
        return copy(tx = x - unshifted.x(arX, arZ), ty = y - unshifted.y(arX, arZ))
    }
}

/**
 * Turns AR camera poses into a continuous survey trajectory.
 *
 * While ARCore tracks, its poses are used as they are — including its own small
 * drift corrections, which make the map better. The hard part is what happens
 * around a tracking loss (a hand over the lens, a blank wall, a dark stairwell,
 * leaving the screen): ARCore usually resumes in the same frame, possibly with a
 * legitimate correction, but can also come back somewhere implausible. A resumed
 * pose is believed when it's within reach of where the walk left off — given the
 * footsteps counted meanwhile — and otherwise re-anchored there. Either way the
 * trajectory never teleports.
 *
 * It can come back turned, too: then everything walked afterwards would be
 * drawn at an angle to what came before (simulated camera surveys: a map's
 * places metres out, its walls scrambled). The phone's gyro keeps turning with
 * the walker while the camera is lost: given its heading at the last tracked
 * pose and when tracking came back, a resumed heading that disagrees with the gyro's turn
 * meanwhile by more than [MAX_YAW_JUMP_DEG] has the plan turned back to match,
 * and the resumed pose re-anchored where the footsteps, in the gyro's
 * directions, lead.
 */
class ArPoseAligner(
    private val startX: Double = 0.0,
    private val startY: Double = 0.0,
    /**
     * Null: the plan takes the AR session's own orientation (a new walk). Set when a
     * walk continues in a new AR session (the app was ended mid-survey): the first
     * pose is taken to face this way, as the resume prompt asks the surveyor to.
     */
    private val startHeadingDeg: Double? = null,
    private val jumpMarginMeters: Double = DEFAULT_JUMP_MARGIN_METERS,
) {
    var transform: ArToSurvey? = null
        private set

    var isTracking: Boolean = false
        private set

    private var last: SurveyPose? = null
    private var walkedWhileLostMeters = 0.0

    // The gyro's heading at the last tracked pose; while lost, the footsteps' path since.
    private var gyroAtLastDeg: Double? = null
    private var lostDx = 0.0
    private var lostDy = 0.0

    /** [gyroHeadingDeg]: the phone's gyro heading now (any fixed frame, the same throughout), if known. */
    fun onTracked(pose: ArPose, gyroHeadingDeg: Double? = null): SurveyPose {
        val previous = last
        var current = transform
        if (current == null) {
            val rotation = startHeadingDeg?.let { normalizeDegrees(it - pose.headingDeg) } ?: 0.0
            current = ArToSurvey(rotation, 0.0, 0.0).anchoredAt(pose.x, pose.z, startX, startY)
        } else if (previous != null) {
            val atLast = gyroAtLastDeg
            if (!isTracking && atLast != null && gyroHeadingDeg != null) {
                val expectedHeading = previous.headingDeg + signedDegrees(gyroHeadingDeg - atLast)
                val yawJump = signedDegrees(current.heading(pose.headingDeg) - expectedHeading)
                if (abs(yawJump) > MAX_YAW_JUMP_DEG) {
                    // Back in a turned frame: turn the plan back, and put the walker where the
                    // footsteps taken meanwhile, with their turns, lead.
                    current = ArToSurvey(normalizeDegrees(current.rotationDeg - yawJump), 0.0, 0.0)
                        .anchoredAt(pose.x, pose.z, previous.x + lostDx, previous.y + lostDy)
                }
            }
            val candidateX = current.x(pose.x, pose.z)
            val candidateY = current.y(pose.x, pose.z)
            val elapsedSeconds = (pose.timestampMs - previous.timestampMs).coerceAtLeast(0) / 1000.0
            // With the gyro's turns the footsteps say where the walker is; without, only how far they got.
            val guided = !isTracking && gyroAtLastDeg != null && gyroHeadingDeg != null
            val jump = if (guided) {
                hypot(candidateX - previous.x - lostDx, candidateY - previous.y - lostDy)
            } else {
                hypot(candidateX - previous.x, candidateY - previous.y)
            }
            val reach = jumpMarginMeters + when {
                isTracking -> elapsedSeconds * MAX_WALKING_SPEED_MPS
                guided -> walkedWhileLostMeters * GUIDED_DISTANCE_SLACK
                else -> walkedWhileLostMeters * WALKED_DISTANCE_SLACK
            }
            if (jump > reach) {
                // Not a correction ARCore could have made: put the walker where the steps
                // taken since the last good pose say they are.
                current = current.anchoredAt(pose.x, pose.z, previous.x + lostDx, previous.y + lostDy)
            }
        }
        transform = current
        isTracking = true
        walkedWhileLostMeters = 0.0
        gyroAtLastDeg = gyroHeadingDeg
        lostDx = 0.0
        lostDy = 0.0
        return SurveyPose(current.x(pose.x, pose.z), current.y(pose.x, pose.z), current.heading(pose.headingDeg), pose.timestampMs).also { last = it }
    }

    fun onTrackingLost() {
        isTracking = false
    }

    /**
     * A footstep, with the gyro's heading at it if known: only counts while tracking is
     * lost, to judge where a resumed pose should be. Returns where the footsteps have
     * the walker meanwhile (the last tracked pose's timestamp), so that a place named
     * during a loss isn't put where tracking stopped; null while tracking.
     */
    fun onStep(lengthMeters: Double, gyroHeadingDeg: Double? = null): SurveyPose? {
        if (isTracking) return null
        val from = last ?: return null
        walkedWhileLostMeters += lengthMeters
        val atLast = gyroAtLastDeg
        val turned = if (atLast != null && gyroHeadingDeg != null) signedDegrees(gyroHeadingDeg - atLast) else 0.0
        val heading = normalizeDegrees(from.headingDeg + turned)
        lostDx += lengthMeters * sin(Math.toRadians(heading))
        lostDy += lengthMeters * cos(Math.toRadians(heading))
        return SurveyPose(from.x + lostDx, from.y + lostDy, heading, from.timestampMs)
    }

    private fun signedDegrees(deg: Double): Double = ((deg % 360.0) + 540.0) % 360.0 - 180.0

    companion object {
        const val DEFAULT_JUMP_MARGIN_METERS = 1.0
        const val MAX_WALKING_SPEED_MPS = 2.5
        const val WALKED_DISTANCE_SLACK = 1.5

        /** Footsteps in the gyro's directions: their length a tenth or so out, their turns a few degrees. */
        const val GUIDED_DISTANCE_SLACK = 0.25

        /**
         * A turned frame, not a correction: a gyro drifts well under a degree over a few
         * seconds' loss, and ARCore's own corrections are no bigger; a frame turned more
         * than this skews every metre walked after it (9° is 3 m across a 20 m floor).
         */
        const val MAX_YAW_JUMP_DEG = 5.0
    }
}

/**
 * One depth measurement from the camera: [depthMm] (0 = no reading) and,
 * optionally, its per-pixel [confidence] (0–255), row by row, in the camera
 * sensor's orientation. [fx]…[cy] are the pinhole intrinsics already scaled to
 * this image's size, and [cameraPose] the camera's 4×4 column-major pose in
 * the AR world (OpenGL convention: +X right, +Y up, −Z forward).
 */
class DepthFrame(
    val width: Int,
    val height: Int,
    val depthMm: ShortArray,
    val confidence: ByteArray?,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val cameraPose: FloatArray,
)

/**
 * Projects depth pixels into the AR world and votes them into an
 * [OccupancyGrid] on the survey plan: anything between [OBSTACLE_MIN_HEIGHT]
 * and [OBSTACLE_MAX_HEIGHT] above the floor is a wall (or a pillar, a counter —
 * whatever a walker would bump into); a pixel on the floor itself is open
 * space. Everything else (the ceiling, a lamp overhead) says nothing about
 * where one can walk and is ignored.
 */
object DepthProjector {
    const val MIN_RANGE_METERS = 0.3

    // Depth-from-motion degrades quickly with distance; a wall 4 m away is still
    // placed within a cell or two, anything beyond mostly smears.
    const val MAX_RANGE_METERS = 4.0
    const val MIN_CONFIDENCE = 128
    const val OBSTACLE_MIN_HEIGHT = 0.25
    const val OBSTACLE_MAX_HEIGHT = 1.9
    const val FLOOR_TOLERANCE = 0.12
    const val DEFAULT_PIXEL_STRIDE = 3

    /** Returns how many pixels were used. */
    fun integrate(
        frame: DepthFrame,
        floorY: Double,
        toSurvey: ArToSurvey,
        grid: OccupancyGrid,
        pixelStride: Int = DEFAULT_PIXEL_STRIDE,
    ): Int {
        val m = frame.cameraPose
        if (m.size != 16 || frame.depthMm.size < frame.width * frame.height) return 0
        if (frame.fx <= 0f || frame.fy <= 0f) return 0
        val cameraX = m[12].toDouble()
        val cameraZ = m[14].toDouble()
        if (pixelStride <= 0) return 0
        val originX = toSurvey.x(cameraX, cameraZ)
        val originY = toSurvey.y(cameraX, cameraZ)

        var used = 0
        for (row in pixelStride / 2 until frame.height step pixelStride) {
            for (column in pixelStride / 2 until frame.width step pixelStride) {
                val index = row * frame.width + column
                val confidence = frame.confidence?.let { it[index].toInt() and 0xFF } ?: 255
                if (confidence < MIN_CONFIDENCE) continue
                val depth = (frame.depthMm[index].toInt() and 0xFFFF) / 1000.0
                if (depth < MIN_RANGE_METERS || depth > MAX_RANGE_METERS) continue

                // Pixel → camera space (image rows grow downward, camera +Y is up) → world.
                val px = depth * (column - frame.cx) / frame.fx
                val py = depth * (frame.cy - row) / frame.fy
                val pz = -depth
                val worldX = m[0] * px + m[4] * py + m[8] * pz + m[12]
                val worldY = m[1] * px + m[5] * py + m[9] * pz + m[13]
                val worldZ = m[2] * px + m[6] * py + m[10] * pz + m[14]

                val heightAboveFloor = worldY - floorY
                val hit = when {
                    heightAboveFloor in OBSTACLE_MIN_HEIGHT..OBSTACLE_MAX_HEIGHT -> true
                    abs(heightAboveFloor) <= FLOOR_TOLERANCE -> false
                    else -> continue
                }
                grid.addRay(originX, originY, toSurvey.x(worldX, worldZ), toSurvey.y(worldX, worldZ), hit)
                used++
            }
        }
        return used
    }
}

/**
 * Where the floor is under the camera, in AR world height. ARCore's detected
 * horizontal planes give it directly when the floor has been seen (the lowest
 * plausible one — a table is a horizontal plane too, just higher); until then,
 * or when a staircase has left every known plane out of range, the camera is
 * assumed to be held at chest height.
 */
class FloorHeightEstimator(private val assumedCameraHeight: Double = DEFAULT_CAMERA_HEIGHT_METERS) {
    private var estimate: Double? = null

    fun update(cameraY: Double, upwardPlaneHeights: List<Double>): Double {
        val floorPlane = upwardPlaneHeights
            .filter { cameraY - it in MIN_CAMERA_HEIGHT_METERS..MAX_CAMERA_HEIGHT_METERS }
            .minOrNull()
        val previous = estimate?.takeIf { cameraY - it in MIN_CAMERA_HEIGHT_METERS..MAX_CAMERA_HEIGHT_METERS }
        val next = when {
            floorPlane == null -> previous ?: (cameraY - assumedCameraHeight)
            previous == null || abs(floorPlane - previous) > STEP_CHANGE_METERS -> floorPlane
            else -> previous + (floorPlane - previous) * SMOOTHING
        }
        estimate = next
        return next
    }

    companion object {
        const val DEFAULT_CAMERA_HEIGHT_METERS = 1.35
        const val MIN_CAMERA_HEIGHT_METERS = 0.5
        const val MAX_CAMERA_HEIGHT_METERS = 2.2
        private const val STEP_CHANGE_METERS = 0.3
        private const val SMOOTHING = 0.2
    }
}

internal fun normalizeDegrees(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
