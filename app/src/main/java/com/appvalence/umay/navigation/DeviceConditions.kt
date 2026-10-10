package com.appvalence.umay.navigation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** The phone's heat and battery, as [com.appvalence.umay.perception.CameraPowerPolicy] needs them. */
data class PowerState(val thermalStatus: Int, val batteryPercent: Int, val charging: Boolean)

/** Heat, battery and how the phone is held, for deciding how much the camera should run during a walk. */
class DeviceConditions(private val context: Context) {

    fun power(): Flow<PowerState> = combine(thermalStatus(), battery()) { thermal, (percent, charging) -> PowerState(thermal, percent, charging) }
        .distinctUntilChanged()

    /** Android's thermal status; always "none" before Android 10, which has no such signal. */
    private fun thermalStatus(): Flow<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return flowOf(PowerManager.THERMAL_STATUS_NONE)
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return flowOf(PowerManager.THERMAL_STATUS_NONE)
        return callbackFlow {
            trySend(powerManager.currentThermalStatus)
            val listener = PowerManager.OnThermalStatusChangedListener { trySend(it) }
            powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
            awaitClose { powerManager.removeThermalStatusListener(listener) }
        }
    }

    private fun battery(): Flow<Pair<Int, Boolean>> = callbackFlow {
        fun read(intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
            trySend(percent to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL))
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = read(intent)
        }
        // A sticky broadcast: registering returns the current state straight away.
        read(ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED))
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /** Gravity in the phone's axes (x, y, z), a few times a second; nothing on a phone without the sensors. */
    fun gravity(): Flow<FloatArray> = callbackFlow {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensorManager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(event.values.copyOf(3))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, GRAVITY_PERIOD_US)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private companion object {
        const val GRAVITY_PERIOD_US = 200_000
    }
}
