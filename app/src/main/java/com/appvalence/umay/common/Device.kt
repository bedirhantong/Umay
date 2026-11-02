package com.appvalence.umay.common

import android.content.Context
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.baro.BarometerSensor
import com.appvalence.umay.positioning.baro.PressureReading
import com.appvalence.umay.positioning.compass.HeadingReading
import com.appvalence.umay.positioning.compass.HeadingSensors
import com.appvalence.umay.positioning.pdr.StepDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Which of the sensors this app relies on this phone actually has. */
interface DeviceCapabilities {
    /** Accelerometer + gyroscope rotation: without them there's no dead reckoning at all. */
    val hasStepSensors: Boolean

    /** Many phones — especially cheaper ones — have none; floor changes then fall back to footsteps or a button. */
    val hasBarometer: Boolean
}

/** Motion sensor streams, as something a ViewModel can depend on and a test can fake. */
interface MotionSensors {
    fun steps(stepLengthMeters: Double): Flow<StepEvent>

    fun pressure(): Flow<PressureReading>

    /** Heading by gyro and by compass together (see [HeadingSensors]); empty without a compass. */
    fun headings(): Flow<HeadingReading>
}

@Singleton
class AndroidDeviceCapabilities @Inject constructor(@ApplicationContext context: Context) : DeviceCapabilities {
    override val hasStepSensors: Boolean = StepDetector.isAvailable(context)
    override val hasBarometer: Boolean = BarometerSensor.isAvailable(context)
}

class AndroidMotionSensors @Inject constructor(@param:ApplicationContext private val context: Context) : MotionSensors {
    override fun steps(stepLengthMeters: Double): Flow<StepEvent> = StepDetector(context, stepLengthMeters).stepEvents()

    override fun pressure(): Flow<PressureReading> = BarometerSensor(context).readings()

    override fun headings(): Flow<HeadingReading> = HeadingSensors(context).readings()
}
