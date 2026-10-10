package com.appvalence.umay.survey

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.ble.BleScanState
import com.appvalence.umay.positioning.mapping.ArPose
import com.appvalence.umay.positioning.mapping.DepthFrame
import com.appvalence.umay.survey.ar.ArCameraStatus
import com.appvalence.umay.survey.ar.ArFrameSample
import com.appvalence.umay.survey.ar.ArTrackingProblem
import com.appvalence.umay.testing.FakeArSurveyCamera
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
import org.junit.Assert.assertNotNull
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
    private val camera = FakeArSurveyCamera()
    private val announcer = FakeSurveyAnnouncer()

    private fun TestScope.recorder() = SurveyRecorder(
        scanner = scanner,
        motion = motion,
        capabilities = FakeCapabilities(hasBarometer = false),
        settings = settings,
        announcer = announcer,
        camera = camera,
        scope = backgroundScope,
        default = main.dispatcher,
        mapping = main.dispatcher,
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

    // --- Camera survey ---

    private var frameTime = 10_000L

    /** A tracked frame with the camera at AR (x, −z = [forward]), facing AR −Z. */
    private suspend fun cameraAt(x: Double, forward: Double, depth: DepthFrame? = null, planes: List<Double> = listOf(0.0)) {
        frameTime += 1_100
        camera.frames.emit(ArFrameSample(ArPose(x, 1.35, -forward, 0.0, frameTime), planes, depth))
    }

    /** What a level camera at AR (x, 1.35, z) sees of a wall [distance] m ahead. */
    private fun wallAhead(x: Float, z: Float, distance: Double) = DepthFrame(
        width = 32, height = 24,
        depthMm = ShortArray(32 * 24) { (distance * 1000).toInt().toShort() },
        confidence = null,
        fx = 25f, fy = 25f, cx = 16f, cy = 12f,
        cameraPose = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, 1.35f, z, 1f),
    )

    @Test
    fun `a camera walk takes its path from the camera, in metres, and only while on screen`() = runTest(main.dispatcher) {
        val recorder = recorder()

        recorder.start(SurveyMode.CAMERA)
        assertFalse("the camera must not open before the screen is shown", camera.resumed)
        recorder.setScreenVisible(true)
        assertTrue(camera.resumed)

        camera.status.value = ArCameraStatus.Tracking
        cameraAt(0.0, 0.0)
        cameraAt(0.0, 0.1) // too close to the last point to add one
        cameraAt(0.0, 2.0)
        advanceUntilIdle()

        val state = recorder.state.value
        assertEquals(SurveyMode.CAMERA, state.mode)
        assertEquals(2, state.trajectory.size)
        assertEquals(2.0, state.trajectory.last().y, 1e-9)
        assertEquals(2.0, state.livePosition!!.y, 1e-9)

        recorder.setScreenVisible(false)
        assertFalse(camera.resumed)
    }

    @Test
    fun `depth draws the wall map of the floor being walked`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.setScreenVisible(true)
        recorder.start(SurveyMode.CAMERA)
        camera.status.value = ArCameraStatus.Tracking

        repeat(3) { cameraAt(0.0, 0.0, depth = wallAhead(0f, 0f, 3.0)) }
        recorder.stop()
        advanceUntilIdle()

        val wallMap = recorder.state.value.wallMaps[0]
        assertNotNull(wallMap)
        val cells = wallMap!!.decodeCells()!!
        assertTrue(cells.any { it == OccupancyRaster.OCCUPIED })
        assertTrue(cells.any { it == OccupancyRaster.FREE })
        assertFalse("stopping must release the camera", camera.resumed)
    }

    @Test
    fun `a place tagged on a camera walk is where the camera is now, with its point in the path`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.setScreenVisible(true)
        recorder.start(SurveyMode.CAMERA)
        camera.status.value = ArCameraStatus.Tracking
        assertFalse("nothing to tag before the camera has a position", recorder.tag("Giriş", NodeType.WAYPOINT))

        cameraAt(0.0, 0.0)
        cameraAt(0.0, 3.0)
        cameraAt(0.0, 3.1)
        advanceUntilIdle()
        assertTrue(recorder.tag("Kapı", NodeType.DOOR))

        val tagged = recorder.state.value.taggedWaypoints.single()
        assertEquals(3.1, tagged.y, 1e-9)
        assertEquals(recorder.state.value.trajectory.size, tagged.trajectoryIndex)
    }

    @Test
    fun `a lasting camera loss is said out loud once, and so is its end`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.setScreenVisible(true)
        recorder.start(SurveyMode.CAMERA)
        camera.status.value = ArCameraStatus.Tracking
        advanceUntilIdle()

        camera.status.value = ArCameraStatus.Lost(ArTrackingProblem.INSUFFICIENT_LIGHT)
        advanceTimeBy(1_000)
        assertTrue("a short glitch isn't announced", announcer.cameraLosses.isEmpty())
        advanceTimeBy(1_500)
        assertEquals(listOf(ArTrackingProblem.INSUFFICIENT_LIGHT), announcer.cameraLosses)

        camera.status.value = ArCameraStatus.Tracking
        advanceUntilIdle()
        assertEquals(1, announcer.cameraBacks)
        recorder.stop()
    }

    @Test
    fun `a resumed walk keeps the mode it was started with`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.setScreenVisible(true)
        recorder.start(SurveyMode.CAMERA)
        camera.status.value = ArCameraStatus.Tracking
        cameraAt(0.0, 0.0)
        advanceUntilIdle()
        recorder.stop()
        advanceUntilIdle()

        recorder.start(SurveyMode.FOOTSTEPS)

        assertEquals(SurveyMode.CAMERA, recorder.state.value.mode)
        assertTrue(camera.resumed)
        recorder.stop()
    }

    @Test
    fun `clearing a camera walk ends the camera session for a fresh frame`() = runTest(main.dispatcher) {
        val recorder = recorder()
        recorder.setScreenVisible(true)
        recorder.start(SurveyMode.CAMERA)
        camera.status.value = ArCameraStatus.Tracking
        cameraAt(0.0, 0.0)
        advanceUntilIdle()
        recorder.stop()
        advanceUntilIdle()

        recorder.clear()
        advanceUntilIdle()

        assertEquals(1, camera.closed)
        assertEquals(SurveyMode.FOOTSTEPS, recorder.state.value.mode)
        assertTrue(recorder.state.value.wallMaps.isEmpty())
    }
}
