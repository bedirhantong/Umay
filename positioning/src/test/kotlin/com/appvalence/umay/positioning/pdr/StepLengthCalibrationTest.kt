package com.appvalence.umay.positioning.pdr

import com.appvalence.umay.positioning.pdr.StepLengthCalibration.Result
import kotlin.test.Test
import kotlin.test.assertEquals

class StepLengthCalibrationTest {

    @Test
    fun `stride is distance divided by steps`() {
        assertEquals(Result.Success(0.625), StepLengthCalibration.compute(distanceMeters = 10.0, stepCount = 16))
    }

    @Test
    fun `a walk too short to average out is rejected`() {
        assertEquals(Result.DistanceTooShort, StepLengthCalibration.compute(distanceMeters = 3.0, stepCount = 5))
    }

    @Test
    fun `too few steps is rejected`() {
        assertEquals(Result.TooFewSteps(4), StepLengthCalibration.compute(distanceMeters = 10.0, stepCount = 4))
    }

    @Test
    fun `an implausible stride means miscounted steps and is rejected`() {
        assertEquals(Result.Implausible(0.2), StepLengthCalibration.compute(distanceMeters = 10.0, stepCount = 50))
    }
}
