package com.appvalence.umay.positioning.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.appvalence.umay.positioning.SensorThread
import com.appvalence.umay.positioning.pdr.RotationVectors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The phone's heading two ways at once, about 5 times a second: from the gyro
 * (TYPE_GAME_ROTATION_VECTOR, the frame footsteps are counted in; steady but
 * its north is arbitrary) and from the compass (TYPE_ROTATION_VECTOR, true
 * magnetic north, but bent by steel and wiring). Both by
 * [RotationVectors.headingDeg], so they agree however the phone is held.
 * Ends quietly on a phone without a compass.
 */
class HeadingSensors(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val game: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val magnetic: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    fun readings(): Flow<HeadingReading> = callbackFlow {
        val gameSensor = game
        val magneticSensor = magnetic
        if (gameSensor == null || magneticSensor == null) {
            close()
            return@callbackFlow
        }
        // Touched only on SensorThread.
        var gameHeading: Double? = null
        var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> gameHeading = RotationVectors.headingDeg(event.values)
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        val gameNow = gameHeading ?: return
                        // values[4], where given, is the sensor's own estimate of its heading error.
                        val estimatedErrorDeg = event.values.getOrNull(4)?.takeIf { it >= 0f }?.let { Math.toDegrees(it.toDouble()) }
                        trySend(
                            HeadingReading(
                                timestampMs = System.currentTimeMillis(),
                                gameHeadingDeg = gameNow,
                                magneticHeadingDeg = RotationVectors.headingDeg(event.values),
                                magneticReliable = magneticAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM &&
                                    (estimatedErrorDeg == null || estimatedErrorDeg <= MAX_ESTIMATED_ERROR_DEG),
                            ),
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) magneticAccuracy = accuracy
            }
        }
        sensorManager.registerListener(listener, gameSensor, SAMPLING_PERIOD_US, SensorThread.handler)
        sensorManager.registerListener(listener, magneticSensor, SAMPLING_PERIOD_US, SensorThread.handler)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private companion object {
        const val SAMPLING_PERIOD_US = 200_000
        const val MAX_ESTIMATED_ERROR_DEG = 30.0
    }
}
