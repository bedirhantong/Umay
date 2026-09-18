package com.appvalence.umay.positioning.trilateration

import kotlin.math.abs

/**
 * Least-squares multilateration: used by the BLE on-site survey, where the
 * *operator* stands at known measured positions and records their distance
 * to an unidentified beacon, solving for the beacon's (x, y).
 *
 * This is N circles of known center and radius, solved for the one unknown
 * point common to all of them — [AnchorReading] just means "a known point
 * that measured this distance to the unknown point," regardless of which
 * side is physically the anchor, so the same solver would equally serve a
 * ranging-hardware-based positioning source (e.g. UWB) if one is ever added.
 *
 * Method: each circle equation (x-xi)^2+(y-yi)^2=di^2 is subtracted from one
 * reference circle to eliminate the quadratic terms, leaving a linear
 * equation per remaining reading. With 3+ readings this is overdetermined,
 * so it's solved as ordinary least squares via the 2x2 normal equations
 * (only x and y are unknown, regardless of how many readings are supplied).
 */
object Trilateration {
    data class AnchorReading(val x: Double, val y: Double, val distanceMeters: Double)
    data class Solution(val x: Double, val y: Double)

    fun solve(readings: List<AnchorReading>): Solution? {
        if (readings.size < 3) return null

        val ref = readings.last()
        val others = readings.dropLast(1)

        val rows = others.map { reading ->
            val a = 2 * (ref.x - reading.x)
            val b = 2 * (ref.y - reading.y)
            val c = (reading.distanceMeters * reading.distanceMeters) - (ref.distanceMeters * ref.distanceMeters) -
                (reading.x * reading.x) + (ref.x * ref.x) -
                (reading.y * reading.y) + (ref.y * ref.y)
            Triple(a, b, c)
        }

        var sumAA = 0.0
        var sumAB = 0.0
        var sumBB = 0.0
        var sumAC = 0.0
        var sumBC = 0.0
        for ((a, b, c) in rows) {
            sumAA += a * a
            sumAB += a * b
            sumBB += b * b
            sumAC += a * c
            sumBC += b * c
        }

        val determinant = sumAA * sumBB - sumAB * sumAB
        if (abs(determinant) < DEGENERATE_DETERMINANT_THRESHOLD) return null // collinear/coincident reference points

        val x = (sumAC * sumBB - sumBC * sumAB) / determinant
        val y = (sumAA * sumBC - sumAB * sumAC) / determinant
        return Solution(x, y)
    }

    private const val DEGENERATE_DETERMINANT_THRESHOLD = 1e-9
}
