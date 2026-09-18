package com.appvalence.umay.positioning

import com.appvalence.umay.coremodel.Position
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Wires [StepDetector] into [PdrIntegrator] to satisfy [PositioningEngine] (FR-2). */
class PdrPositioningEngine(
    private val stepDetector: StepDetector,
    startX: Double = 0.0,
    startY: Double = 0.0,
    startHeadingDeg: Double = 0.0,
    floor: Int = 0,
) : PositioningEngine, Resettable {

    private val integrator = PdrIntegrator(
        startX = startX,
        startY = startY,
        startHeadingDeg = startHeadingDeg,
        floor = floor,
    )

    override fun positionUpdates(): Flow<Position> = stepDetector.stepEvents().map(integrator::integrate)

    override fun resetTo(x: Double, y: Double, headingDeg: Double, accuracyMeters: Double) {
        integrator.resetTo(x, y, headingDeg, accuracyMeters)
    }
}
