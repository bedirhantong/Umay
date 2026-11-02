package com.appvalence.umay.positioning.baro

import kotlin.math.abs
import kotlin.math.sign

/**
 * Confirms a floor change from air pressure, with no tap and — unlike the
 * footstep-count fallback — for elevators too (standing still in a moving
 * elevator produces no steps, but plenty of pressure change).
 *
 * Usage: [arm] when the route says a floor change is coming (from the floor
 * the user is on to the one the route needs), then feed every reading to
 * [onPressure]; it returns true exactly once, when the user has actually
 * made the change. [disarm] if the route stops waiting for one.
 *
 * "Actually made the change" means two things, both required:
 * 1. Altitude moved at least [CONFIRM_FRACTION] of [floorHeightMeters] per
 *    floor *in the expected direction* since arming — a phone barometer
 *    resolves roughly half a meter, well under one floor, but a door slam
 *    or HVAC gust can still nudge it, so the bar is most of a floor, never
 *    a small fraction of one.
 * 2. Vertical movement has settled (below [SETTLED_VERTICAL_SPEED_MPS]).
 *    Floor heights vary from ~2.7 m (homes) to 4.5 m+ (offices, hospitals);
 *    confirming the instant the threshold is crossed would snap tracking to
 *    the arrival node while someone is still halfway up a tall staircase or
 *    the elevator is still moving.
 *
 * Readings are averaged over [SMOOTHING_WINDOW_MS] to smooth out the
 * sensor's sample-to-sample noise.
 *
 * Disarmed and armed again for the same floor soon after (a new route worked
 * out halfway up the stairs, whose first leg is on this floor and so expects
 * no change for a moment), the climb is still measured from where it began,
 * not from wherever the walker had got to: re-measured from there it never
 * reaches the bar, and the change is never confirmed (simulated walks: the
 * walker upstairs, the app sending them up the stairs again and again).
 */
class BarometricFloorChangeDetector(
    private val floorHeightMeters: Double = DEFAULT_FLOOR_HEIGHT_METERS,
) {
    private val window = AltitudeWindow(SMOOTHING_WINDOW_MS)
    private var expectedFloorDelta = 0
    private var baselineMeters: Double? = null
    private var armedFromFloor = 0
    private var latestMs: Long? = null

    // The baseline of the last arming, kept a while after disarming (see class doc).
    private class Baseline(val floor: Int, val meters: Double, val disarmedAtMs: Long)

    private var previousBaseline: Baseline? = null

    val isArmed: Boolean get() = expectedFloorDelta != 0

    /** No-op if already armed for the same change, so it can be called on every state update. */
    fun arm(fromFloor: Int, toFloor: Int) {
        val delta = toFloor - fromFloor
        if (delta == 0 || delta == expectedFloorDelta) return
        expectedFloorDelta = delta
        // Baseline is where the user was *before* the climb started; null until the
        // first reading arrives if nothing has been measured yet.
        val recent = previousBaseline?.takeIf { it.floor == fromFloor && (latestMs ?: it.disarmedAtMs) - it.disarmedAtMs <= BASELINE_REUSE_MS }
        baselineMeters = recent?.meters ?: window.smoothedMeters()
        armedFromFloor = fromFloor
        previousBaseline = null
    }

    fun disarm() {
        val baseline = baselineMeters
        if (isArmed && baseline != null) previousBaseline = Baseline(armedFromFloor, baseline, latestMs ?: 0L)
        expectedFloorDelta = 0
        baselineMeters = null
    }

    /** Altitude change since [arm], for display/logging; null when not armed or no reading yet. */
    fun climbedMeters(): Double? {
        val baseline = baselineMeters ?: return null
        return window.smoothedMeters()?.minus(baseline)
    }

    fun onPressure(hPa: Double, timestampMs: Long): Boolean {
        window.add(hPa, timestampMs)
        latestMs = timestampMs

        if (!isArmed) return false
        val smoothed = window.smoothedMeters() ?: return false
        val baseline = baselineMeters ?: smoothed.also { baselineMeters = it }

        val climbedInExpectedDirection = (smoothed - baseline) * sign(expectedFloorDelta.toDouble())
        val needed = CONFIRM_FRACTION * floorHeightMeters * abs(expectedFloorDelta)
        if (climbedInExpectedDirection < needed) return false
        if (abs(window.verticalSpeedMps() ?: return false) > SETTLED_VERTICAL_SPEED_MPS) return false

        disarm()
        // Done: the walker is on another floor now, nothing to measure from.
        previousBaseline = null
        return true
    }

    companion object {
        /** Typical storey height; the confirmation bar below leaves room for 2.7-4.5 m floors. */
        const val DEFAULT_FLOOR_HEIGHT_METERS = 3.2
        const val CONFIRM_FRACTION = 0.65
        const val SMOOTHING_WINDOW_MS = 2_000L

        // Walking up stairs climbs ~0.3-0.5 m/s, elevators ~1-2 m/s; standing still reads ~0.
        const val SETTLED_VERTICAL_SPEED_MPS = 0.15

        // Weather moves pressure by a metre's worth in well under an hour; a minute's is nothing.
        const val BASELINE_REUSE_MS = 60_000L
    }
}
