package com.appvalence.umay.positioning.mapping

import kotlin.math.hypot
import kotlin.math.max

/**
 * Takes the drift out of a survey walked by footsteps, the way a robot vacuum
 * closes a loop: naming a place again means "I'm back where I was", so
 * whatever dead reckoning says lies between the two visits is drift, and it's
 * spread back over the walk in between rather than left in one place.
 *
 * The walk is a chain of named stops ([Stop]): consecutive stops on a floor
 * keep the step-counted displacement between them (trusted less the longer
 * the stretch), stops naming the same place are pulled together hard, and the
 * whole is solved by least squares, x and y separately. Rotation drift is
 * only undone as far as moving the stops can undo it.
 */
object SurveyLoopClosure {

    /** A named stop of the walk: where dead reckoning put it, on which floor, and which place it was. */
    data class Stop(val x: Double, val y: Double, val floor: Int, val place: Int)

    /** Where each stop should have been: its correction to add, in order. */
    fun corrections(stops: List<Stop>): List<Pair<Double, Double>> {
        val n = stops.size
        if (n < 2 || stops.indices.none { stops[it].place != it }) return List(n) { 0.0 to 0.0 }
        val a = Array(n) { DoubleArray(n) }
        val bx = DoubleArray(n)
        val by = DoubleArray(n)

        fun relative(i: Int, j: Int, weight: Double, dx: Double, dy: Double) {
            // weight · ((P_j − P_i) − d)²
            a[i][i] += weight
            a[j][j] += weight
            a[i][j] -= weight
            a[j][i] -= weight
            bx[j] += weight * dx
            bx[i] -= weight * dx
            by[j] += weight * dy
            by[i] -= weight * dy
        }

        for (i in 0 until n - 1) {
            val s = stops[i]
            val t = stops[i + 1]
            if (s.floor != t.floor) continue
            val length = hypot(t.x - s.x, t.y - s.y)
            relative(i, i + 1, 1.0 / max(length, MIN_STRETCH_METERS), t.x - s.x, t.y - s.y)
        }
        for (i in 0 until n) {
            val place = stops[i].place
            if (place != i && stops[place].floor == stops[i].floor) relative(place, i, SAME_PLACE_WEIGHT, 0.0, 0.0)
            // A whisper of "stay where you were", so every stretch is pinned somewhere.
            a[i][i] += ANCHOR_WEIGHT
            bx[i] += ANCHOR_WEIGHT * stops[i].x
            by[i] += ANCHOR_WEIGHT * stops[i].y
        }
        val x = solve(a.map { it.copyOf() }.toTypedArray(), bx)
        val y = solve(a, by)
        return List(n) { (x[it] - stops[it].x) to (y[it] - stops[it].y) }
    }

    /**
     * Corrections for every point of a walked path, given the corrections of the stops and
     * where along the path each stop was ([stopIndices], ascending, -1 for one not found):
     * between two stops, in proportion; before the first and after the last, theirs.
     */
    fun spread(pathSize: Int, stopIndices: List<Int>, stopCorrections: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val known = stopIndices.indices.filter { stopIndices[it] in 0 until pathSize }.map { stopIndices[it] to stopCorrections[it] }.sortedBy { it.first }
        if (known.isEmpty()) return List(pathSize) { 0.0 to 0.0 }
        return List(pathSize) { k ->
            val after = known.indexOfFirst { it.first >= k }
            when {
                after == 0 -> known.first().second
                after < 0 -> known.last().second
                else -> {
                    val (i0, c0) = known[after - 1]
                    val (i1, c1) = known[after]
                    val f = if (i1 == i0) 1.0 else (k - i0).toDouble() / (i1 - i0)
                    (c0.first + (c1.first - c0.first) * f) to (c0.second + (c1.second - c0.second) * f)
                }
            }
        }
    }

    /** Gaussian elimination with partial pivoting; [a] is consumed. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val rhs = b.copyOf()
        for (column in 0 until n) {
            var pivot = column
            for (row in column + 1 until n) if (kotlin.math.abs(a[row][column]) > kotlin.math.abs(a[pivot][column])) pivot = row
            val rowSwap = a[column]
            a[column] = a[pivot]
            a[pivot] = rowSwap
            val rhsSwap = rhs[column]
            rhs[column] = rhs[pivot]
            rhs[pivot] = rhsSwap
            val diagonal = a[column][column]
            for (row in column + 1 until n) {
                val factor = a[row][column] / diagonal
                if (factor == 0.0) continue
                for (k in column until n) a[row][k] -= factor * a[column][k]
                rhs[row] -= factor * rhs[column]
            }
        }
        val out = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = rhs[row]
            for (k in row + 1 until n) sum -= a[row][k] * out[k]
            out[row] = sum / a[row][row]
        }
        return out
    }

    private const val MIN_STRETCH_METERS = 1.0
    private const val SAME_PLACE_WEIGHT = 1_000.0
    private const val ANCHOR_WEIGHT = 1e-4
}
