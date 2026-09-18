package com.appvalence.umay.positioning.baro

import kotlin.math.pow

/**
 * International barometric formula (the same one Android's
 * SensorManager.getAltitude uses), kept here as plain math so it's unit
 * testable. The absolute result drifts with the weather and is meaningless
 * indoors; only *differences* over a short time — a floor's worth of
 * climbing — are used.
 */
object PressureAltitude {
    const val STANDARD_SEA_LEVEL_HPA = 1013.25
    private const val EXPONENT = 1 / 5.255
    private const val SCALE_METERS = 44330.0

    fun metersFromHpa(hPa: Double): Double = SCALE_METERS * (1 - (hPa / STANDARD_SEA_LEVEL_HPA).pow(EXPONENT))

    fun hpaFromMeters(meters: Double): Double = STANDARD_SEA_LEVEL_HPA * (1 - meters / SCALE_METERS).pow(1 / EXPONENT)
}
