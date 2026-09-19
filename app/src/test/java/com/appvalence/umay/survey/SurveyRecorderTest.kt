package com.appvalence.umay.survey

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.ble.BleScanState
import com.appvalence.umay.testing.FakeBeaconScanner
import com.appvalence.umay.testing.FakeCapabilities
import com.appvalence.umay.testing.FakeMotionSensors
import com.appvalence.umay.testing.FakeSurveyAnnouncer
import com.appvalence.umay.testing.FakeUserSettings
import com.appvalence.umay.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyRecorderTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val scanner = FakeBeaconScanner()
    private val motion = FakeMotionSensors()
    private val settings = FakeUserSettings(calibratedStepLengthMeters = 0.7)

    private fun TestScope.recorder() = SurveyRecorder(
        scanner = scanner,
        motion = motion,
        capabilities = FakeCapabilities(hasBarometer = false),
        settings = settings,
        announcer = FakeSurveyAnnouncer(),
        scope = backgroundScope,
        default = main.dispatcher,
    )

    private var stepTime = 0L

    private suspend fun step() = motion.steps.emit(StepEvent(stepLengthMeters = 0.7, headingDeltaDeg = 0.0, timestampMs = ++stepTime))

    private fun iBeacon(id: String, rssi: Int) = BleBeaconReading(
        beaconId = id, macAddress = "AA", deviceName = null, uuid = "u", major = 1, minor = 1,
        rssi = rssi, txPowerAtOneMeter = -59, estimatedDistanceMeters = 2.0, timestampMs = 0,
    )

    @Test
    fun `recording scans and turns footsteps into a trajectory with the calibrated stride`() = runTest(main.dispatcher) {
        val recorder = recorder()

        recorder.start()
        repeat(3) { step() }
        advanceUntilIdle()

        val state = recorder.state.value
        assertTrue(state.isRecording)
        assertTrue(scanner.started)
        assertEquals(3, state.trajectory.size)
        assertEquals(0.7, state.stepLengthMeters!!, 0.0)
        assertEquals(BleScanState.Scanning, state.scanState)
    }

    @Test
    fun `a floor change starts that floor's walk from a fresh origin`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.start()
        repeat(5) { step() }

        recorder.changeFloor(+1)
        step()
        advanceUntilIdle()

        val onNewFloor = recorder.state.value.trajectory.filter { it.floor == 1 }
        assertEquals(1, onNewFloor.size)
        val firstOnFloor0 = recorder.state.value.trajectory.first()
        assertEquals(firstOnFloor0.x, onNewFloor.single().x, 1e-9)
        assertEquals(firstOnFloor0.y, onNewFloor.single().y, 1e-9)
    }

    @Test
    fun `tagging needs a position on the current floor and captures the iBeacons heard there`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.start()
        scanner.beacons.value = listOf(iBeacon("b1", -60), iBeacon("b2", -75), iBeacon("phone", -50).copy(uuid = null))
        advanceTimeBy(SurveyRecorder.UI_REFRESH_MS + 1)

        assertFalse(recorder.tag("Kapı", NodeType.DOOR))

        step()
        advanceTimeBy(1)
        assertTrue(recorder.tag("Kapı", NodeType.DOOR))

        val waypoint = recorder.state.value.taggedWaypoints.single()
        assertEquals(NodeType.DOOR, waypoint.type)
        assertEquals(listOf(RadioFingerprint("b1", -60.0), RadioFingerprint("b2", -75.0)), waypoint.fingerprint)
    }

    @Test
    fun `stopping solves the walk in the background and stops the radio`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.start()
        step()
        scanner.raw.emit(iBeacon("b1", -60))
        scanner.raw.emit(iBeacon("b1", -61))
        advanceTimeBy(1)

        recorder.stop()
        advanceTimeBy(1)

        val state = recorder.state.value
        assertFalse(state.isRecording)
        assertFalse(scanner.started)
        assertEquals(2, state.rawReadingCount)
        assertTrue(state.hasSolved)
        assertFalse(state.solving)
    }

    @Test
    fun `steps after stopping aren't recorded`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.start()
        step()
        advanceTimeBy(1)
        recorder.stop()

        step()
        advanceTimeBy(1)

        assertEquals(1, recorder.state.value.trajectory.size)
    }

    @Test
    fun `clearing throws the walk away but keeps the beacons discovered so far`() = runTest(main.dispatcher) {
        val recorder = recorder()
        scanner.beacons.value = listOf(iBeacon("b1", -60))
        advanceTimeBy(SurveyRecorder.UI_REFRESH_MS + 1)
        recorder.start()
        step()
        advanceTimeBy(1)
        recorder.tag("Giriş", NodeType.WAYPOINT)
        recorder.stop()
        advanceTimeBy(1)

        recorder.clear()

        val state = recorder.state.value
        assertTrue(state.trajectory.isEmpty())
        assertTrue(state.taggedWaypoints.isEmpty())
        assertFalse(state.hasSolved)
        assertEquals(setOf("b1"), state.discovered.keys)
    }

    @Test
    fun `a tagged place can be removed`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.start()
        step()
        advanceTimeBy(1)
        recorder.tag("A", NodeType.WAYPOINT)
        recorder.tag("B", NodeType.WAYPOINT)

        recorder.deleteWaypoint(0)

        assertEquals(listOf("B"), recorder.state.value.taggedWaypoints.map { it.name })
    }
}
