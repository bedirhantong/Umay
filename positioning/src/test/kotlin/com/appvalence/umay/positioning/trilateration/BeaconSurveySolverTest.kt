package com.appvalence.umay.positioning.trilateration

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeaconSurveySolverTest {

    private fun pose(x: Double, y: Double, timestampMs: Long, floor: Int = 0) = Position(
        x = x, y = y, floor = floor, headingDeg = 0.0, accuracyMeters = 0.5,
        source = PositionSource.PEDESTRIAN_DEAD_RECKONING, timestampMs = timestampMs,
    )

    private fun readingAt(pose: Position, beaconId: String, beaconX: Double, beaconY: Double) = BleBeaconReading(
        beaconId = beaconId,
        macAddress = "AA:BB:CC:DD:EE:FF",
        deviceName = null,
        uuid = "uuid-1",
        major = 1,
        minor = 1,
        rssi = -70,
        txPowerAtOneMeter = -59,
        estimatedDistanceMeters = hypot(beaconX - pose.x, beaconY - pose.y),
        timestampMs = pose.timestampMs,
    )

    @Test
    fun `estimates a beacon position from PDR poses and time-matched BLE readings`() {
        val trajectory = listOf(
            pose(0.0, 0.0, 0L),
            pose(10.0, 0.0, 1_000L),
            pose(10.0, 10.0, 2_000L),
            pose(0.0, 10.0, 3_000L),
        )
        val beaconX = 6.0
        val beaconY = 4.0
        val readings = trajectory.map { readingAt(it, "uuid-1-1", beaconX, beaconY) }

        val results = BeaconSurveySolver.solve(trajectory, readings)

        assertEquals(1, results.size)
        val result = results.single()
        assertEquals("uuid-1-1", result.beaconId)
        assertEquals(4, result.observationCount)
        assertTrue(abs(result.x - beaconX) < 0.1, "expected x~$beaconX but was ${result.x}")
        assertTrue(abs(result.y - beaconY) < 0.1, "expected y~$beaconY but was ${result.y}")
    }

    @Test
    fun `skips a beacon seen from fewer than 3 poses`() {
        val trajectory = listOf(pose(0.0, 0.0, 0L), pose(10.0, 0.0, 1_000L))
        val readings = trajectory.map { readingAt(it, "uuid-1-1", 5.0, 5.0) }

        assertTrue(BeaconSurveySolver.solve(trajectory, readings).isEmpty())
    }

    @Test
    fun `ignores a reading whose timestamp is far from any trajectory pose`() {
        val trajectory = listOf(pose(0.0, 0.0, 0L), pose(10.0, 0.0, 1_000L), pose(10.0, 10.0, 2_000L))
        val validReadings = trajectory.map { readingAt(it, "uuid-1-1", 5.0, 5.0) }
        val staleReading = readingAt(pose(50.0, 50.0, 999_999L), "uuid-1-1", 5.0, 5.0)

        val results = BeaconSurveySolver.solve(trajectory, validReadings + staleReading)

        assertEquals(1, results.size)
        assertEquals(3, results.single().observationCount)
    }

    @Test
    fun `an empty trajectory yields no results`() {
        assertTrue(BeaconSurveySolver.solve(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `never pools observations of the same beacon id across different floors`() {
        // Same physical beacon ID reported on two floors (e.g. a duplicate deployment,
        // or simply walking past two different physical units that share an ID by
        // coincidence) must not be trilaterated together — PDR has no vertical axis, so
        // stairs/elevator time between floors would otherwise corrupt both positions.
        val floor0Trajectory = listOf(
            pose(0.0, 0.0, 0L, floor = 0),
            pose(10.0, 0.0, 1_000L, floor = 0),
            pose(10.0, 10.0, 2_000L, floor = 0),
        )
        val floor1Trajectory = listOf(
            pose(0.0, 0.0, 10_000L, floor = 1),
            pose(10.0, 0.0, 11_000L, floor = 1),
            pose(10.0, 10.0, 12_000L, floor = 1),
        )
        val floor0Readings = floor0Trajectory.map { readingAt(it, "shared-id", 4.0, 4.0) }
        val floor1Readings = floor1Trajectory.map { readingAt(it, "shared-id", 8.0, 2.0) }

        val results = BeaconSurveySolver.solve(floor0Trajectory + floor1Trajectory, floor0Readings + floor1Readings)

        assertEquals(2, results.size)
        val floor0Result = results.single { it.floor == 0 }
        val floor1Result = results.single { it.floor == 1 }
        assertTrue(abs(floor0Result.x - 4.0) < 0.1 && abs(floor0Result.y - 4.0) < 0.1)
        assertTrue(abs(floor1Result.x - 8.0) < 0.1 && abs(floor1Result.y - 2.0) < 0.1)
    }

    private fun abs(value: Double) = kotlin.math.abs(value)
}
