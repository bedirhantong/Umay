package com.appvalence.umay.positioning.ble

import kotlin.math.pow

/**
 * RSSI -> distance estimate using the standard log-distance path-loss model:
 * distance = 10 ^ ((measuredPowerAtOneMeter - rssi) / (10 * pathLossExponent)).
 *
 * [measuredPowerAtOneMeter] must be the RSSI you'd measure standing exactly
 * 1 meter from the beacon — for iBeacon frames that's the "measured power"
 * byte in the advertisement, which is what this is designed for. It must
 * NOT be a device's raw radio transmit power (e.g. Android's generic
 * `ScanRecord.txPowerLevel`), which is a different physical quantity and
 * produces wildly wrong (multi-kilometer) results if plugged in here — that
 * is why [BleBeaconScanner] only ever passes this for iBeacon-decoded
 * readings and leaves it null (unknown distance) for everything else.
 *
 * This is uncalibrated for the actual site (assumes [DEFAULT_PATH_LOSS_EXPONENT])
 * and will need per-beacon/per-site calibration once real anchors are
 * surveyed (NFR-2); it exists now so the scanner has something to display
 * during on-site beacon discovery.
 */
object BleDistanceEstimator {
    fun estimateMeters(
        rssi: Int,
        measuredPowerAtOneMeter: Int?,
        pathLossExponent: Double = DEFAULT_PATH_LOSS_EXPONENT,
    ): Double {
        if (measuredPowerAtOneMeter == null) return UNKNOWN_DISTANCE_METERS
        val exponent = (measuredPowerAtOneMeter - rssi) / (10.0 * pathLossExponent)
        return 10.0.pow(exponent)
    }

    const val UNKNOWN_DISTANCE_METERS = -1.0
    const val DEFAULT_PATH_LOSS_EXPONENT = 2.5
}
