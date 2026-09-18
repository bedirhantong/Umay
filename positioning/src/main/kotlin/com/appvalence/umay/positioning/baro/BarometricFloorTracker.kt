package com.appvalence.umay.positioning.baro

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Notices floor changes on its own during an on-site survey, where — unlike
 * navigation ([BarometricFloorChangeDetector]) — nothing says in advance
 * that one is coming, or in which direction. Feed every reading to
 * [onPressure]; it returns how many floors were just climbed (positive) or
 * descended (negative), or 0.
 *
 * A change counts once altitude has moved at least
 * [BarometricFloorChangeDetector.CONFIRM_FRACTION] of a floor from the
 * baseline *and* vertical movement has settled — the same two conditions as
 * during navigation, for the same reasons.
 *
 * The extra problem here is time: a survey can run for an hour, and indoor
 * air pressure wanders (weather, ventilation) by the equivalent of several
 * meters in that time — enough to fake a floor change. Real climbs are fast
 * (stairs ~0.3-0.5 m/s, elevators 1-2 m/s), drift is ~100x slower, so while
 * the altitude is steady the baseline slowly follows it
 * ([DRIFT_TIME_CONSTANT_SECONDS]); during an actual climb it's frozen until
 * the change is counted.
 */
class BarometricFloorTracker(
    private val floorHeightMeters: Double = BarometricFloorChangeDetector.DEFAULT_FLOOR_HEIGHT_METERS,
) {
    private val window = AltitudeWindow(BarometricFloorChangeDetector.SMOOTHING_WINDOW_MS)
    private var baselineMeters: Double? = null
    private var lastTimestampMs: Long? = null

    /** Call after a manual floor correction, so the current altitude becomes the new floor's reference. */
    fun resetBaseline() {
        baselineMeters = window.smoothedMeters()
    }

    fun onPressure(hPa: Double, timestampMs: Long): Int {
        window.add(hPa, timestampMs)
        val dtSeconds = lastTimestampMs?.let { (timestampMs - it) / 1000.0 } ?: 0.0
        lastTimestampMs = timestampMs

        val smoothed = window.smoothedMeters() ?: return 0
        val baseline = baselineMeters ?: smoothed.also { baselineMeters = it }
        val speed = window.verticalSpeedMps() ?: return 0
        if (abs(speed) > BarometricFloorChangeDetector.SETTLED_VERTICAL_SPEED_MPS) return 0 // mid-climb: hold the baseline

        val climbed = smoothed - baseline
        if (abs(climbed) >= BarometricFloorChangeDetector.CONFIRM_FRACTION * floorHeightMeters) {
            baselineMeters = smoothed
            // At least one floor once past the threshold, more for an elevator ride.
            val floors = (climbed / floorHeightMeters).roundToInt()
            return if (floors == 0) (if (climbed > 0) 1 else -1) else floors
        }

        // Steady: let the baseline follow slow drift so it never adds up to a fake floor.
        val alpha = (dtSeconds / (DRIFT_TIME_CONSTANT_SECONDS + dtSeconds)).coerceIn(0.0, 1.0)
        baselineMeters = baseline + alpha * (smoothed - baseline)
        return 0
    }

    companion object {
        /**
         * How quickly the baseline absorbs slow drift. At a pessimistic 1 hPa/hour
         * (~2.3 mm/s) the baseline lags by speed × this ≈ 0.3 m — far below the
         * ~2 m a floor change needs.
         */
        const val DRIFT_TIME_CONSTANT_SECONDS = 120.0
    }
}
