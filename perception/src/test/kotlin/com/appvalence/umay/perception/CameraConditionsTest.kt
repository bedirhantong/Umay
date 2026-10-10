package com.appvalence.umay.perception

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class CameraConditionsTest {

    /** Gravity as the phone reads it when its camera points [downDeg] below the horizon. */
    private fun gravity(downDeg: Double) = (9.8 * cos(Math.toRadians(downDeg))).toFloat() to (9.8 * sin(Math.toRadians(downDeg))).toFloat()

    private fun CameraAimJudge.at(downDeg: Double): CameraAim = gravity(downDeg).let { (y, z) -> update(y, z) }

    @Test
    fun `upright is fine, tipped toward the floor or the ceiling is not`() {
        assertEquals(CameraAim.OK, CameraAimJudge().at(10.0))
        assertEquals(CameraAim.TOO_LOW, CameraAimJudge().at(70.0))
        assertEquals(CameraAim.TOO_HIGH, CameraAimJudge().at(-50.0))
    }

    @Test
    fun `swaying around a limit doesn't flip the advice`() {
        val judge = CameraAimJudge()
        judge.at(55.0)

        assertEquals(CameraAim.TOO_LOW, judge.at(45.0)) // back under the limit, but not by enough
        assertEquals(CameraAim.OK, judge.at(35.0))
    }

    @Test
    fun `heat switches the camera off before the battery slows it down`() {
        assertEquals(CameraPower.FULL, CameraPowerPolicy.decide(thermalStatus = 0, batteryPercent = 80, charging = false))
        assertEquals(CameraPower.REDUCED, CameraPowerPolicy.decide(thermalStatus = 0, batteryPercent = 15, charging = false))
        assertEquals(CameraPower.FULL, CameraPowerPolicy.decide(thermalStatus = 0, batteryPercent = 15, charging = true))
        assertEquals(CameraPower.REDUCED, CameraPowerPolicy.decide(thermalStatus = 2, batteryPercent = 80, charging = false))
        assertEquals(CameraPower.OFF, CameraPowerPolicy.decide(thermalStatus = 3, batteryPercent = 80, charging = true))
    }
}
