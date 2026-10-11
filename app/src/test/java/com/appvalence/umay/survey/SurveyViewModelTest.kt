package com.appvalence.umay.survey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.appvalence.umay.coremodel.CompassSums
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.positioning.mapping.ArPose
import com.appvalence.umay.positioning.mapping.DepthFrame
import com.appvalence.umay.survey.ar.ArCameraStatus
import com.appvalence.umay.survey.ar.ArFrameSample
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.compass.HeadingReading
import com.appvalence.umay.storage.BuildingRepository
import com.appvalence.umay.testing.FakeArSurveyCamera
import com.appvalence.umay.testing.FakeBeaconScanner
import com.appvalence.umay.testing.FakeSurveyDrafts
import com.appvalence.umay.testing.FakeSurveyForeground
import com.appvalence.umay.testing.FakeBuildingMapStore
import com.appvalence.umay.testing.FakeCapabilities
import com.appvalence.umay.testing.FakeMotionSensors
import com.appvalence.umay.testing.FakeSpeaker
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.reflect.KClass

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val motion = FakeMotionSensors()
    private val camera = FakeArSurveyCamera()
    private val store = FakeBuildingMapStore()
    private val settings = FakeUserSettings(calibratedStepLengthMeters = 0.68)

    private lateinit var recorder: SurveyRecorder

    private fun TestScope.viewModel(): SurveyViewModel {
        recorder = SurveyRecorder(FakeBeaconScanner(), motion, FakeCapabilities(), settings, FakeSurveyAnnouncer(), camera, FakeSurveyDrafts(), FakeSurveyForeground(), backgroundScope, main.dispatcher, main.dispatcher)
        return SurveyViewModel(
            recorder = recorder,
            buildings = BuildingRepository({ store }, backgroundScope, main.dispatcher),
            settings = settings,
            capabilities = FakeCapabilities(hasBarometer = true),
            speaker = FakeSpeaker(),
            camera = camera,
            default = main.dispatcher,
        )
    }

    @Test
    fun `a surveyed map is built from the tagged places and saved with the survey's stride`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.startRecording(SurveyMode.FOOTSTEPS)
        motion.steps.emit(StepEvent(0.68, 0.0, 1))
        advanceTimeBy(1)
        viewModel.tag("Giriş", NodeType.WAYPOINT)
        repeat(4) { motion.steps.emit(StepEvent(0.68, 0.0, 2L + it)) }
        advanceTimeBy(1)
        viewModel.tag("Salon", NodeType.DOOR)
        viewModel.stopRecording()
        advanceTimeBy(1)

        viewModel.saveMap("  Ofis  ") { floor, number -> "Kat $floor beacon $number" }
        advanceUntilIdle()

        val saved = store.files.getValue("Ofis")
        assertEquals(0.68, saved.surveyStepLengthMeters!!, 0.0)
        assertEquals(setOf("Giriş", "Salon"), saved.floors.flatMap { it.nodes }.map { it.name }.toSet())
        val result = viewModel.save.value.result as SurveySaveResult.Saved
        assertEquals(2, result.points)
        assertEquals(1, result.floors)
        assertFalse(viewModel.save.value.saving)
    }

    @Test
    fun `the saved map knows how its floor lines up with the compass`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.startRecording(SurveyMode.FOOTSTEPS)
        motion.steps.emit(StepEvent(0.68, 0.0, System.currentTimeMillis()))
        advanceTimeBy(1)
        viewModel.tag("Giriş", NodeType.WAYPOINT)
        motion.headings.emit(HeadingReading(System.currentTimeMillis(), 0.0, magneticHeadingDeg = 350.0, magneticReliable = true))
        advanceTimeBy(1)
        repeat(CompassSums.MIN_SAMPLES) { motion.steps.emit(StepEvent(0.68, 0.0, System.currentTimeMillis())) }
        advanceTimeBy(1)
        viewModel.tag("Salon", NodeType.WAYPOINT)
        viewModel.stopRecording()
        advanceTimeBy(1)

        viewModel.saveMap("Ofis") { _, _ -> "" }
        advanceUntilIdle()

        assertEquals(10.0, store.files.getValue("Ofis").floors.single().compass!!.offsetDeg, 1e-6)
    }

    @Test
    fun `a camera-surveyed map follows the walked corner and carries its wall map`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.setScreenVisible(true)
        viewModel.startRecording(SurveyMode.CAMERA)
        camera.status.value = ArCameraStatus.Tracking
        var t = 1_000L
        suspend fun at(x: Double, y: Double, depth: DepthFrame? = null) {
            t += 1_100
            camera.frames.emit(ArFrameSample(ArPose(x, 1.35, -y, 0.0, t), listOf(0.0), depth))
        }
        val wall = DepthFrame(
            32, 24, ShortArray(32 * 24) { 3000 }, null, 25f, 25f, 16f, 12f,
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1.35f, 0f, 1f),
        )

        repeat(3) { at(0.0, 0.0, wall) }
        advanceUntilIdle()
        viewModel.tag("Giriş", NodeType.WAYPOINT)
        (1..20).forEach { at(0.0, it * 0.5) } // 10 m north…
        (1..20).forEach { at(it * 0.5, 10.0) } // …then 10 m east
        advanceUntilIdle()
        viewModel.tag("Oda", NodeType.WAYPOINT)
        viewModel.stopRecording()
        advanceUntilIdle()

        viewModel.saveMap("Ofis") { _, _ -> "" }
        advanceUntilIdle()

        val floor = store.files.getValue("Ofis").floors.single()
        assertNull("a camera map isn't steps × stride", store.files.getValue("Ofis").surveyStepLengthMeters)
        val corner = floor.nodes.single { it.type == NodeType.CORRIDOR }
        assertEquals(0.0, corner.x, 0.3)
        assertEquals(10.0, corner.y, 0.3)
        assertEquals(20.0, floor.edges.sumOf { it.distanceMeters }, 0.5)
        assertNotNull(floor.occupancy?.decodeCells())
    }

    @Test
    fun `a failed save is reported`() = runTest(main.dispatcher) {
        store.failWrites = true
        val viewModel = viewModel()

        viewModel.saveMap("Ofis") { _, _ -> "" }
        advanceUntilIdle()

        assertEquals(SurveySaveResult.Failed, viewModel.save.value.result)
    }

    @Test
    fun `a blank name saves nothing`() = runTest(main.dispatcher) {
        val viewModel = viewModel()

        viewModel.saveMap("   ") { _, _ -> "" }
        advanceUntilIdle()

        assertTrue(store.files.isEmpty())
    }

    @Test
    fun `when the screen goes away for good, recording stops but what was recorded stays`() = runTest(main.dispatcher) {
        val viewModelStore = ViewModelStore()
        val created = viewModel()
        val viewModel = ViewModelProvider.create(viewModelStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = created as T
        })[SurveyViewModel::class]
        viewModel.startRecording(SurveyMode.FOOTSTEPS)
        motion.steps.emit(StepEvent(0.68, 0.0, 1))
        advanceTimeBy(1)

        viewModelStore.clear()
        advanceTimeBy(1)

        assertFalse(recorder.state.value.isRecording)
        assertEquals(1, recorder.state.value.trajectory.size)
    }
}
