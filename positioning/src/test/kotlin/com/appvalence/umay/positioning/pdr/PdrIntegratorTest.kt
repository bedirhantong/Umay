package com.appvalence.umay.positioning.pdr

import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.StepEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdrIntegratorTest {

    @Test
    fun `a step heading due north moves forward on y and leaves x unchanged`() {
        val integrator = PdrIntegrator(startX = 0.0, startY = 0.0, startHeadingDeg = 0.0)
        val position = integrator.integrate(StepEvent(stepLengthMeters = 0.75, headingDeltaDeg = 0.0, timestampMs = 1_000L))
        assertEquals(0.0, position.x, 1e-9)
        assertEquals(0.75, position.y, 1e-9)
        assertEquals(PositionSource.PEDESTRIAN_DEAD_RECKONING, position.source)
    }

    @Test
    fun `a step heading due east moves forward on x and leaves y unchanged`() {
        val integrator = PdrIntegrator(startX = 0.0, startY = 0.0, startHeadingDeg = 90.0)
        val position = integrator.integrate(StepEvent(stepLengthMeters = 1.0, headingDeltaDeg = 0.0, timestampMs = 1_000L))
        assertEquals(1.0, position.x, 1e-9)
        assertEquals(0.0, position.y, 1e-9)
    }

    @Test
    fun `heading wraps around 360 degrees`() {
        val integrator = PdrIntegrator(startX = 0.0, startY = 0.0, startHeadingDeg = 350.0)
        val position = integrator.integrate(StepEvent(stepLengthMeters = 1.0, headingDeltaDeg = 20.0, timestampMs = 1_000L))
        assertEquals(10.0, position.headingDeg, 1e-9)
    }

    @Test
    fun `accuracy degrades with every step`() {
        val integrator = PdrIntegrator(startAccuracyMeters = 0.5, driftPerStepMeters = 0.1)
        val first = integrator.integrate(StepEvent(stepLengthMeters = 0.5, headingDeltaDeg = 0.0, timestampMs = 0L))
        val second = integrator.integrate(StepEvent(stepLengthMeters = 0.5, headingDeltaDeg = 0.0, timestampMs = 500L))
        assertTrue(second.accuracyMeters > first.accuracyMeters)
    }

    @Test
    fun `resetTo overrides position, heading and accuracy for subsequent steps`() {
        val integrator = PdrIntegrator(startX = 100.0, startY = 100.0)
        integrator.resetTo(x = 0.0, y = 0.0, headingDeg = 0.0, accuracyMeters = 0.2)
        val position = integrator.integrate(StepEvent(stepLengthMeters = 1.0, headingDeltaDeg = 0.0, timestampMs = 0L))
        assertEquals(0.0, position.x, 1e-9)
        assertEquals(1.0, position.y, 1e-9)
        assertTrue(position.accuracyMeters < 0.5)
    }
}
