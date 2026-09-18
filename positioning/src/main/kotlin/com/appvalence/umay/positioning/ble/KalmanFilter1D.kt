package com.appvalence.umay.positioning.ble

/**
 * Scalar Kalman filter used to smooth BLE RSSI readings (replaces the fixed
 * exponential moving average previously used in [BleBeaconScanner]).
 *
 * BLE RSSI fast-fades roughly +/-4-6dB from multipath even when both ends
 * are stationary. A fixed-ratio EMA either reacts too slowly to genuine
 * movement or amplifies that noise, and its blend ratio is a single
 * hand-tuned constant for the whole filter's lifetime. A Kalman filter
 * instead tracks its own uncertainty and computes the statistically optimal
 * blend (the Kalman gain) at every step, converging tighter as more
 * measurements arrive.
 *
 * State model: constant-position (the true RSSI is assumed roughly steady
 * between updates); [processNoise] (Q) is how much that true value is
 * allowed to drift per update, [measurementNoise] (R) is the expected
 * variance of a single raw RSSI sample.
 */
class KalmanFilter1D(
    private val processNoise: Double = DEFAULT_PROCESS_NOISE,
    private val measurementNoise: Double = DEFAULT_MEASUREMENT_NOISE,
) {
    private var estimate: Double? = null
    private var errorCovariance = 1.0

    fun update(measurement: Double): Double {
        val previousEstimate = estimate
        if (previousEstimate == null) {
            estimate = measurement
            errorCovariance = measurementNoise
            return measurement
        }

        // Predict: constant-position model, so the a-priori estimate is
        // unchanged; only its uncertainty grows by the process noise.
        val predictedErrorCovariance = errorCovariance + processNoise

        // Update: blend prediction and measurement, weighted by their
        // relative uncertainties.
        val kalmanGain = predictedErrorCovariance / (predictedErrorCovariance + measurementNoise)
        val newEstimate = previousEstimate + kalmanGain * (measurement - previousEstimate)
        errorCovariance = (1 - kalmanGain) * predictedErrorCovariance

        estimate = newEstimate
        return newEstimate
    }

    fun reset() {
        estimate = null
        errorCovariance = 1.0
    }

    companion object {
        const val DEFAULT_PROCESS_NOISE = 0.5
        const val DEFAULT_MEASUREMENT_NOISE = 16.0 // ~= (4 dBm stddev)^2
    }
}
