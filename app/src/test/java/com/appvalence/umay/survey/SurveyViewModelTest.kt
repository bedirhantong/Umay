package com.appvalence.umay.survey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.storage.BuildingRepository
import com.appvalence.umay.testing.FakeBeaconScanner
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.reflect.KClass

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val motion = FakeMotionSensors()
    private val store = FakeBuildingMapStore()
    private val settings = FakeUserSettings(calibratedStepLengthMeters = 0.68)

    private lateinit var recorder: SurveyRecorder

    private fun TestScope.viewModel(): SurveyViewModel {
        recorder = SurveyRecorder(FakeBeaconScanner(), motion, FakeCapabilities(), settings, FakeSurveyAnnouncer(), backgroundScope, main.dispatcher)
        return SurveyViewModel(
            recorder = recorder,
            buildings = BuildingRepository({ store }, backgroundScope, main.dispatcher),
            settings = settings,
            capabilities = FakeCapabilities(hasBarometer = true),
            speaker = FakeSpeaker(),
            default = main.dispatcher,
        )
    }

    @Test
    fun `a surveyed map is built from the tagged places and saved with the survey's stride`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.startRecording()
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
        viewModel.startRecording()
        motion.steps.emit(StepEvent(0.68, 0.0, 1))
        advanceTimeBy(1)

        viewModelStore.clear()
        advanceTimeBy(1)

        assertFalse(recorder.state.value.isRecording)
        assertEquals(1, recorder.state.value.trajectory.size)
    }
}
