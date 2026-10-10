package com.appvalence.umay.positioning.mapping

import kotlin.math.hypot

/**
 * Ramer–Douglas–Peucker: the fewest of [points] that keep a path within
 * [toleranceMeters] of its original shape. A camera-tracked walk down an
 * L-shaped corridor comes out as three points — start, the corner, end —
 * which is exactly the graph a router and a particle filter want.
 * The first and last points are always kept.
 */
object PathSimplifier {
    fun simplify(points: List<Pair<Double, Double>>, toleranceMeters: Double): List<Pair<Double, Double>> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        // Iterative rather than recursive: a long walk is thousands of points.
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var farthest = -1
            var farthestDistance = toleranceMeters
            for (i in first + 1 until last) {
                val distance = distanceToSegment(points[i], points[first], points[last])
                if (distance > farthestDistance) {
                    farthest = i
                    farthestDistance = distance
                }
            }
            if (farthest != -1) {
                keep[farthest] = true
                stack.addLast(first to farthest)
                stack.addLast(farthest to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToSegment(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(p.first - a.first, p.second - a.second)
        val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }
}
