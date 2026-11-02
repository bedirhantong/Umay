package com.appvalence.umay.coremodel

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the compass lines up with a floor's map: add [offsetDeg] to a phone's
 * magnetic heading to get a heading on the map. Measured during the survey,
 * averaged over every step of it, so one spot's magnetic distortion (a steel
 * column, wiring) doesn't decide it. [spreadDeg] is how much it varied along
 * the walk: a building that bends the compass a lot has a large one, and its
 * compass is trusted that much less.
 */
@Serializable
data class CompassCalibration(
    val offsetDeg: Double,
    val spreadDeg: Double,
    val samples: Int,
)

/**
 * The running sums a survey keeps per floor to make a [CompassCalibration]:
 * each sample is how far the map's heading was from the compass's at one
 * moment of the walk. A circular mean, so 359° and 1° average to 0°.
 */
@Serializable
data class CompassSums(val sin: Double = 0.0, val cos: Double = 0.0, val count: Int = 0) {
    fun plus(mapHeadingDeg: Double, magneticHeadingDeg: Double): CompassSums {
        val difference = Math.toRadians(mapHeadingDeg - magneticHeadingDeg)
        return CompassSums(sin + sin(difference), cos + cos(difference), count + 1)
    }

    /** Null until there's enough, or when the compass was too inconsistent to trust. */
    fun calibration(): CompassCalibration? {
        if (count < MIN_SAMPLES) return null
        val resultant = hypot(sin, cos) / count
        if (resultant <= 0.0) return null
        // Circular standard deviation.
        val spread = Math.toDegrees(sqrt(-2 * ln(resultant.coerceAtMost(1.0))))
        if (spread > MAX_SPREAD_DEG) return null
        val offset = (Math.toDegrees(atan2(sin, cos)) + 360.0) % 360.0
        return CompassCalibration(offset, spread, count)
    }

    companion object {
        /** About fifteen metres of walking. */
        const val MIN_SAMPLES = 20

        /** Beyond this the compass says little about which way is which. */
        const val MAX_SPREAD_DEG = 40.0
    }
}
