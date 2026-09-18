package com.appvalence.umay.positioning.engine

/** Lets [PositionFusionEngine] correct dead-reckoning drift whenever a fresh UWB fix arrives. */
interface Resettable {
    fun resetTo(x: Double, y: Double, headingDeg: Double, accuracyMeters: Double)
}
