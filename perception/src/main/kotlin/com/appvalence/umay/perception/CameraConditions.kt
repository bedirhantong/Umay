package com.appvalence.umay.perception

import kotlin.math.atan2

/** Whether the back camera is aimed where obstacles and markers are. */
enum class CameraAim { OK, TOO_LOW, TOO_HIGH }

/**
 * Judges where the back camera points from gravity in the phone's own axes
 * (x right, y up the screen, z out of the screen; the back camera looks along
 * −z). Held upright the camera looks at the horizon; tipped back, at the floor.
 * With hysteresis, so a hand's sway near a limit doesn't flip the advice.
 * Not thread-safe.
 */
class CameraAimJudge(
    private val tooLowDeg: Double = TOO_LOW_DEG,
    private val tooHighDeg: Double = TOO_HIGH_DEG,
    private val hysteresisDeg: Double = HYSTERESIS_DEG,
) {
    var aim: CameraAim = CameraAim.OK
        private set

    /** Degrees the camera points below the horizon (negative: above). */
    fun downwardAngleDeg(gravityY: Float, gravityZ: Float): Double = Math.toDegrees(atan2(gravityZ.toDouble(), gravityY.toDouble()))

    fun update(gravityY: Float, gravityZ: Float): CameraAim {
        val down = downwardAngleDeg(gravityY, gravityZ)
        aim = when (aim) {
            CameraAim.OK -> when {
                down > tooLowDeg -> CameraAim.TOO_LOW
                down < -tooHighDeg -> CameraAim.TOO_HIGH
                else -> CameraAim.OK
            }
            CameraAim.TOO_LOW -> if (down < tooLowDeg - hysteresisDeg) CameraAim.OK else CameraAim.TOO_LOW
            CameraAim.TOO_HIGH -> if (down > -tooHighDeg + hysteresisDeg) CameraAim.OK else CameraAim.TOO_HIGH
        }
        return aim
    }

    companion object {
        // A chest-height camera tipped this far down sees little but floor a metre ahead.
        const val TOO_LOW_DEG = 50.0
        const val TOO_HIGH_DEG = 35.0
        const val HYSTERESIS_DEG = 10.0
    }
}

/** How much the navigation camera may run. */
enum class CameraPower { FULL, REDUCED, OFF }

/**
 * The camera is the biggest drain in a walk. When the phone gets hot (Android's
 * thermal status) it's switched off before the system throttles everything —
 * guidance carries on with steps and beacons — and when the battery runs low it
 * looks less often. Statuses are android.os.PowerManager.THERMAL_STATUS_* values.
 */
object CameraPowerPolicy {
    // PowerManager.THERMAL_STATUS_MODERATE / SEVERE.
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3
    const val LOW_BATTERY_PERCENT = 20
    const val REDUCED_FRAME_INTERVAL_MS = 400L

    fun decide(thermalStatus: Int, batteryPercent: Int, charging: Boolean): CameraPower = when {
        thermalStatus >= THERMAL_SEVERE -> CameraPower.OFF
        thermalStatus >= THERMAL_MODERATE -> CameraPower.REDUCED
        !charging && batteryPercent in 0..LOW_BATTERY_PERCENT -> CameraPower.REDUCED
        else -> CameraPower.FULL
    }
}
