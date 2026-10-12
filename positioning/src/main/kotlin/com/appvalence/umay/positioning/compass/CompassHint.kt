package com.appvalence.umay.positioning.compass

import com.appvalence.umay.coremodel.CompassCalibration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One moment's heading by the gyro and by the compass; see [HeadingSensors]. */
data class HeadingReading(
    val timestampMs: Long,
    val gameHeadingDeg: Double,
    val magneticHeadingDeg: Double,
    /** False while the compass reports itself uncalibrated or unsure. */
    val magneticReliable: Boolean,
)

/**
 * Which way the walker faces at the start, from the compass, as the particle
 * filter wants it (map heading minus gyro heading; see
 * GraphParticleFilter.observeHeadingOffset). Without it the filter learns the
 * heading from the first steps alone, which at a junction or in an open space
 * takes a while. On real walks (Indoor Location Competition data, the
 * simulation module's IlcReplayTest) it halved the mean error on an
 * open-plan floor (9.5 → 4.8 m) and cut it 15–25% on corridor floors
 * (docs/YOL_HARITASI.md 3.5).
 *
 * The compass is read for a moment where the walker stands, compared with the
 * gyro over the same moment, and turned onto the map with the floor's
 * [CompassCalibration]. Readings are averaged so a twitching needle doesn't
 * decide it, and none is used while the compass says it's unsure.
 */
class CompassHint(private val calibration: CompassCalibration) {
    private var sinSum = 0.0
    private var cosSum = 0.0
    private var count = 0

    /** One reading; ignored while the compass says it's unsure. */
    fun add(reading: HeadingReading) {
        if (!reading.magneticReliable) return
        val difference = Math.toRadians(reading.magneticHeadingDeg - reading.gameHeadingDeg)
        sinSum += sin(difference)
        cosSum += cos(difference)
        count++
    }

    val isReady: Boolean get() = count >= MIN_READINGS

    /** Map heading minus gyro heading, and how far to trust it (one standard deviation); null if not ready or inconsistent. */
    fun offset(): Pair<Double, Double>? {
        if (!isReady) return null
        val resultant = hypot(sinSum, cosSum) / count
        val spread = Math.toDegrees(sqrt(-2 * ln(resultant.coerceIn(1e-9, 1.0))))
        if (spread > MAX_READING_SPREAD_DEG) return null
        val magneticMinusGame = Math.toDegrees(atan2(sinSum, cosSum))
        val offset = ((calibration.offsetDeg + magneticMinusGame) % 360.0 + 360.0) % 360.0
        // A spot's own distortion comes on top of the floor's spread: never trust it closer than this.
        val sigma = max(MIN_SIGMA_DEG, hypot(calibration.spreadDeg, spread))
        return offset to sigma
    }

    companion object {
        /** About two seconds of readings. */
        const val MIN_READINGS = 8

        /** How long navigation waits for them before going without. */
        const val WAIT_MS = 4_000L

        /** Measured on real walks (0.10): the compass's starting direction was off 12–17° (median), 26–33° for 80%. */
        const val MIN_SIGMA_DEG = 30.0

        private const val MAX_READING_SPREAD_DEG = 25.0

        /** A field log's status line for a hint used: "compass_hint <offset> <sigma>", so a replay applies it too. */
        const val LOG_STATUS_PREFIX = "compass_hint"

        fun logStatus(offsetDeg: Double, sigmaDeg: Double): String =
            "$LOG_STATUS_PREFIX ${"%.2f".format(java.util.Locale.ROOT, offsetDeg)} ${"%.2f".format(java.util.Locale.ROOT, sigmaDeg)}"

        /** The (offset, sigma) of a [logStatus] line; null for any other status. */
        fun fromLogStatus(status: String): Pair<Double, Double>? {
            val parts = status.split(' ')
            if (parts.size != 3 || parts[0] != LOG_STATUS_PREFIX) return null
            val offset = parts[1].toDoubleOrNull() ?: return null
            val sigma = parts[2].toDoubleOrNull() ?: return null
            return offset to sigma
        }
    }
}
