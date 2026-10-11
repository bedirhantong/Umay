package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.PlaceMarkers
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/** A printed place marker on a wall: its centre, which way it faces (a map bearing) and its printed size. */
internal data class PlacedMarker(
    val nodeId: String,
    val x: Double,
    val y: Double,
    val facingDeg: Double,
    val centerHeightMeters: Double = 1.4,
    val qrSideMeters: Double = PlaceMarkers.PRINTED_SIDE_METERS,
)

/**
 * A phone camera in the virtual building: a pinhole camera held upright at
 * chest height, rendering the grayscale frames the app's marker reader would
 * get — walls, floor and ceiling as plain surfaces with a little texture, and
 * the printed QR markers in perspective — with the blur and sensor noise of a
 * real frame. The optics ([horizontalFovDeg]) are the simulated phone's
 * *true* ones, which the app only assumes.
 */
internal class VirtualCamera(
    private val building: VirtualBuilding,
    private val markers: List<PlacedMarker>,
    val width: Int = 480,
    val height: Int = 640,
    val horizontalFovDeg: Double = 50.0,
    private val cameraHeightMeters: Double = 1.3,
    private val pitchDeg: Double = 0.0,
    private val blurRadiusPx: Int = 1,
    private val noiseSigma: Double = 3.0,
    private val random: Random = Random(1),
) {
    private val focalPx = width / (2 * tan(Math.toRadians(horizontalFovDeg / 2)))
    private val codes: Map<String, BitMatrix> = markers.associate { marker ->
        marker.nodeId to QRCodeWriter().encode(
            PlaceMarkers.contentFor(marker.nodeId), BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0),
        )
    }

    /** Markers that could be in view from ([x], [y]) facing [headingDeg]: close, in front, facing back, not behind a wall. */
    fun markersInView(x: Double, y: Double, headingDeg: Double, maxMeters: Double = 8.0): List<PlacedMarker> = markers.filter { m ->
        val distance = hypot(m.x - x, m.y - y)
        val toMarker = Math.toDegrees(atan2(m.x - x, m.y - y))
        distance <= maxMeters &&
            abs(angle(toMarker - headingDeg)) <= horizontalFovDeg / 2 + 5 &&
            abs(angle(m.facingDeg - (toMarker + 180))) < 85 &&
            // A point just in front of the sheet: its own wall mustn't count as in the way.
            building.lineOfSight(x, y, m.x + 0.05 * sin(Math.toRadians(m.facingDeg)), m.y + 0.05 * cos(Math.toRadians(m.facingDeg)))
    }

    /** The frame seen from ([x], [y]) facing [headingDeg]: one luminance byte per pixel, row by row. */
    fun render(x: Double, y: Double, headingDeg: Double): ByteArray {
        val yaw = Math.toRadians(headingDeg)
        val pitch = Math.toRadians(pitchDeg)
        // Camera axes in the world (x east, y north, z up).
        val fx = sin(yaw) * cos(pitch)
        val fy = cos(yaw) * cos(pitch)
        val fz = sin(pitch)
        val rx = cos(yaw)
        val ry = -sin(yaw)
        val ux = -sin(yaw) * sin(pitch)
        val uy = -cos(yaw) * sin(pitch)
        val uz = cos(pitch)
        val image = DoubleArray(width * height)
        val samples = SUPERSAMPLE * SUPERSAMPLE
        for (row in 0 until height) {
            for (column in 0 until width) {
                var sum = 0.0
                for (sy in 0 until SUPERSAMPLE) for (sx in 0 until SUPERSAMPLE) {
                    val u = (column + (sx + 0.5) / SUPERSAMPLE - width / 2.0) / focalPx
                    val v = (height / 2.0 - row - (sy + 0.5) / SUPERSAMPLE) / focalPx
                    sum += shade(x, y, cameraHeightMeters, fx + u * rx + v * ux, fy + u * ry + v * uy, fz + v * uz)
                }
                image[row * width + column] = sum / samples
            }
        }
        val blurred = boxBlur(image)
        return ByteArray(width * height) { i -> (blurred[i] + random.gaussian() * noiseSigma).roundToInt().coerceIn(0, 255).toByte() }
    }

    /** Brightness where the ray from (ox, oy, oz) along (dx, dy, dz) first hits something. */
    private fun shade(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double {
        var nearest = Double.MAX_VALUE
        var value = 128.0
        // Floor and ceiling.
        if (dz < 0) {
            val t = -oz / dz
            if (t < nearest) {
                nearest = t
                value = FLOOR + texture(ox + t * dx, oy + t * dy)
            }
        } else if (dz > 0) {
            val t = (CEILING_METERS - oz) / dz
            if (t < nearest) {
                nearest = t
                value = CEILING
            }
        }
        for (wall in building.walls) {
            val t = hitVertical(ox, oy, dx, dy, wall.x1, wall.y1, wall.x2, wall.y2) ?: continue
            val z = oz + t * dz
            if (t < nearest && z in 0.0..CEILING_METERS) {
                nearest = t
                val px = ox + t * dx
                val py = oy + t * dy
                value = WALL + texture(px + py, z)
            }
        }
        for (marker in markers) {
            val facing = Math.toRadians(marker.facingDeg)
            val nx = sin(facing)
            val ny = cos(facing)
            val denominator = dx * nx + dy * ny
            if (denominator >= 0) continue // seen from behind, or edge-on
            // The sheet sits 5 mm proud of the wall.
            val t = ((marker.x - ox) * nx + (marker.y - oy) * ny) / denominator
            if (t <= 0 || t >= nearest) continue
            val px = ox + t * dx
            val py = oy + t * dy
            val pz = oz + t * dz
            // Across the sheet (to the right when facing it) and up it.
            val across = (px - marker.x) * -ny + (py - marker.y) * nx
            val up = pz - marker.centerHeightMeters
            val code = codes.getValue(marker.nodeId)
            val module = marker.qrSideMeters / code.width
            val paperHalf = marker.qrSideMeters / 2 + PAPER_MARGIN_MODULES * module
            if (abs(across) > paperHalf || abs(up) > paperHalf) continue
            nearest = t
            val column = ((across + marker.qrSideMeters / 2) / module).toInt()
            val row = ((marker.qrSideMeters / 2 - up) / module).toInt()
            val dark = column in 0 until code.width && row in 0 until code.height &&
                across > -marker.qrSideMeters / 2 && up < marker.qrSideMeters / 2 && code[column, row]
            value = if (dark) INK else PAPER
        }
        // A little falloff with distance, like a corridor's lighting.
        return value * (1.0 - minOf(nearest, 12.0) * 0.02)
    }

    private fun hitVertical(ox: Double, oy: Double, dx: Double, dy: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double? {
        val ex = x2 - x1
        val ey = y2 - y1
        val denominator = dx * ey - dy * ex
        if (denominator == 0.0) return null
        val t = ((x1 - ox) * ey - (y1 - oy) * ex) / denominator
        val s = ((x1 - ox) * dy - (y1 - oy) * dx) / denominator
        return if (t > 1e-6 && s in 0.0..1.0) t else null
    }

    private fun texture(a: Double, b: Double): Double = 6.0 * sin(a * 7.3 + cos(b * 5.1)) + 4.0 * sin(b * 13.7)

    private fun boxBlur(image: DoubleArray): DoubleArray {
        if (blurRadiusPx <= 0) return image
        val out = DoubleArray(image.size)
        for (row in 0 until height) for (column in 0 until width) {
            var sum = 0.0
            var count = 0
            for (dy in -blurRadiusPx..blurRadiusPx) for (dx in -blurRadiusPx..blurRadiusPx) {
                val r = row + dy
                val c = column + dx
                if (r in 0 until height && c in 0 until width) {
                    sum += image[r * width + c]
                    count++
                }
            }
            out[row * width + column] = sum / count
        }
        return out
    }

    private fun angle(deg: Double) = ((deg % 360) + 540) % 360 - 180

    companion object {
        private const val SUPERSAMPLE = 2
        private const val CEILING_METERS = 2.7
        private const val FLOOR = 95.0
        private const val CEILING = 215.0
        private const val WALL = 175.0
        private const val PAPER = 235.0
        private const val INK = 35.0
        private const val PAPER_MARGIN_MODULES = 6

        /**
         * A printed marker for each of [building]'s marker places, as an installer following
         * KURULUM.md would hang it: on the flat face of the nearest wall within [maxMeters]
         * (not on the end of one, beside a doorway), at least a sheet's width from its
         * end, facing the place. Places with no wall that close get none.
         */
        fun placeMarkers(building: VirtualBuilding, maxMeters: Double = 2.5): List<PlacedMarker> = building.markerNodeIds.mapNotNull { id ->
            val node = building.nodesById.getValue(id)
            var best: Triple<Double, Pair<Double, Double>, Pair<Double, Double>>? = null
            for (wall in building.walls) {
                val ex = wall.x2 - wall.x1
                val ey = wall.y2 - wall.y1
                val length = hypot(ex, ey)
                if (length < 2 * SHEET_CLEARANCE_METERS) continue
                val inset = SHEET_CLEARANCE_METERS / length
                val t = (((node.x - wall.x1) * ex + (node.y - wall.y1) * ey) / (length * length)).coerceIn(inset, 1 - inset)
                val px = wall.x1 + t * ex
                val py = wall.y1 + t * ey
                // The face toward the place: the wall's normal on the place's side, or for a
                // place in a doorway (on the wall's line), the side its corridors lead from.
                var nx = -ey / length
                var ny = ex / length
                var side = (node.x - px) * nx + (node.y - py) * ny
                if (abs(side) < ON_WALL_LINE_METERS) {
                    val ways = building.edges.mapNotNull { (a, b) -> if (a == id) b else if (b == id) a else null }.map(building.nodesById::getValue)
                    side = ways.sumOf { (it.x - px) * nx + (it.y - py) * ny }
                }
                if (side < 0) {
                    nx = -nx
                    ny = -ny
                }
                val d = hypot(node.x - px, node.y - py)
                if (d <= maxMeters && (best == null || d < best.first)) best = Triple(d, px to py, nx to ny)
            }
            val (_, point, normal) = best ?: return@mapNotNull null
            PlacedMarker(id, point.first + normal.first * 0.005, point.second + normal.second * 0.005, Math.toDegrees(atan2(normal.first, normal.second)))
        }

        private const val SHEET_CLEARANCE_METERS = 0.2
        private const val ON_WALL_LINE_METERS = 0.2
    }
}
