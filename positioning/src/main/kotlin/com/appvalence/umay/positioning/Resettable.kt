package com.appvalence.umay.positioning

/** Lets [PositionFusionEngine] correct dead-reckoning drift whenever a fresh UWB fix arrives. */
interface Resettable {
    fun resetTo(x: Double, y: Double, headingDeg: Double, accuracyMeters: Double)
}
