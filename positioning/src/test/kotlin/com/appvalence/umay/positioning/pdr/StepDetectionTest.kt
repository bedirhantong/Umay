package com.appvalence.umay.positioning.pdr

import com.appvalence.umay.coremodel.StepEvent
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepDetectionTest {

    /** [seconds] of accelerometer samples at 50 Hz: gravity plus a vertical swing of [amplitude] at [cadenceHz]. */
    private fun run(
        detection: StepDetection,
        seconds: Double,
        cadenceHz: Double,
        amplitude: Double,
        noise: Double = 0.0,
        heading: (Double) -> Double = { 0.0 },
    ): List<StepEvent> {
        val random = Random(1)
        val steps = mutableListOf<StepEvent>()
        val samples = (seconds * 50).toInt()
        for (i in 0 until samples) {
            val t = i / 50.0
            detection.onHeading(heading(t))
            val z = 9.80665 + amplitude * sin(2 * PI * cadenceHz * t) + noise * (random.nextDouble() - 0.5)
            detection.onAcceleration(0f, 0f, z.toFloat(), (t * 1e9).toLong(), (t * 1000).toLong())?.let(steps::add)
        }
        return steps
    }

    @Test
    fun `counts one step per footfall of an ordinary walk`() {
        // 1.8 steps a second for 20 s: 36 footfalls, the baseline settling over the first second or two.
        val steps = run(StepDetection(0.7), seconds = 20.0, cadenceHz = 1.8, amplitude = 2.5)
        assertTrue(steps.size in 33..36, "${steps.size} steps")
        assertTrue(steps.all { it.stepLengthMeters == 0.7 })
    }

    @Test
    fun `standing still, however jittery the sensor, is no step`() {
        val steps = run(StepDetection(0.7), seconds = 20.0, cadenceHz = 0.0, amplitude = 0.0, noise = 1.0)
        assertEquals(0, steps.size)
    }

    @Test
    fun `shaking the phone fast is not a run of steps`() {
        // 8 swings a second: the minimum interval between steps caps what's counted.
        val steps = run(StepDetection(0.7), seconds = 10.0, cadenceHz = 8.0, amplitude = 4.0)
        assertTrue(steps.size <= 10.0 * 1000 / StepDetector.MIN_STEP_INTERVAL_MS, "${steps.size} steps")
    }

    @Test
    fun `each step carries the turn since the previous one`() {
        // Turning 90° right halfway through the walk.
        val steps = run(StepDetection(0.7), seconds = 20.0, cadenceHz = 1.8, amplitude = 2.5, heading = { t -> if (t < 10) 10.0 else 100.0 })
        // The first step carries the starting heading itself (from 0): the filter learns
        // where that points, so only the changes after it matter.
        assertEquals(10.0, steps.first().headingDeltaDeg, 1e-9)
        assertEquals(listOf(90.0), steps.drop(1).map { it.headingDeltaDeg }.filter { it != 0.0 })
    }

    @Test
    fun `turning past north is a small turn, not a full circle`() {
        val steps = run(StepDetection(0.7), seconds = 6.0, cadenceHz = 1.8, amplitude = 2.5, heading = { t -> if (t < 3) 350.0 else 10.0 })
        assertEquals(20.0, steps.sumOf { it.headingDeltaDeg } - steps.first().headingDeltaDeg, 1e-9)
    }
}

class RotationVectorsTest {
    @Test
    fun `a phone lying flat facing north has azimuth 0`() {
        assertEquals(0.0, RotationVectors.azimuthDeg(floatArrayOf(0f, 0f, 0f, 1f)), 1e-6)
    }

    @Test
    fun `turning the phone left by a right angle is azimuth 270`() {
        val half = (sqrt(0.5)).toFloat()
        assertEquals(270.0, RotationVectors.azimuthDeg(floatArrayOf(0f, 0f, half, half)), 1e-4)
    }

    @Test
    fun `the fourth component is optional, as on older sensors`() {
        val s = sin(PI / 8).toFloat() // 45° about the vertical
        val c = kotlin.math.cos(PI / 8).toFloat()
        assertEquals(RotationVectors.azimuthDeg(floatArrayOf(0f, 0f, s, c)), RotationVectors.azimuthDeg(floatArrayOf(0f, 0f, s)), 1e-4)
        assertEquals(315.0, RotationVectors.azimuthDeg(floatArrayOf(0f, 0f, s, c)), 1e-4)
    }

    /** The rotation vector (x, y, z, w) of a phone facing [headingDeg] clockwise, its top tilted back by [pitchDeg] (90: upright). */
    private fun phone(headingDeg: Double, pitchDeg: Double): FloatArray {
        val yaw = Math.toRadians(-headingDeg) / 2
        val pitch = Math.toRadians(pitchDeg) / 2
        // Turn about the vertical, after tilting about the phone's own x axis.
        val w = kotlin.math.cos(yaw) * kotlin.math.cos(pitch)
        val x = kotlin.math.cos(yaw) * sin(pitch)
        val y = sin(yaw) * sin(pitch)
        val z = sin(yaw) * kotlin.math.cos(pitch)
        return floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
    }

    @Test
    fun `heading holds as a phone held at the chest passes upright`() {
        for (pitch in listOf(0.0, 45.0, 85.0, 90.0, 95.0, 120.0)) {
            assertEquals(30.0, RotationVectors.headingDeg(phone(30.0, pitch)), 0.01, "tilted $pitch°")
        }
    }

    @Test
    fun `the old azimuth flips round past upright, which is why heading replaced it`() {
        assertEquals(30.0, RotationVectors.azimuthDeg(phone(30.0, 85.0)), 0.01)
        assertEquals(210.0, RotationVectors.azimuthDeg(phone(30.0, 95.0)), 0.01)
    }

    @Test
    fun `flat on a table, heading and azimuth agree`() {
        for (heading in listOf(0.0, 45.0, 170.0, 300.0)) {
            assertEquals(RotationVectors.azimuthDeg(phone(heading, 0.0)), RotationVectors.headingDeg(phone(heading, 0.0)), 0.01)
        }
    }
}
