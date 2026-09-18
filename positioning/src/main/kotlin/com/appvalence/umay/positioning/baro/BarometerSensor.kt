package com.appvalence.umay.positioning.baro

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class PressureReading(val hPa: Double, val timestampMs: Long)

/**
 * Raw air pressure from the phone's barometer, for [BarometricFloorChangeDetector].
 * Many phones — especially cheaper ones — have no barometer at all; check
 * [isAvailable] first and fall back to the manual/footstep floor-change flow.
 */
class BarometerSensor(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressure: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    fun readings(): Flow<PressureReading> = callbackFlow {
        val sensor = pressure
        if (sensor == null) {
            // registerListener throws on a null Sensor; callers are expected to have
            // checked isAvailable, so just end quietly — the fallback flow still works.
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(PressureReading(event.values[0].toDouble(), System.currentTimeMillis()))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    companion object {
        fun isAvailable(context: Context): Boolean =
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PRESSURE) != null
    }
}
