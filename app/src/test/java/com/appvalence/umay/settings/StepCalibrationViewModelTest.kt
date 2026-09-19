package com.appvalence.umay.settings

import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.pdr.StepLengthCalibration
import com.appvalence.umay.testing.FakeCapabilities
import com.appvalence.umay.testing.FakeMotionSensors
import com.appvalence.umay.testing.FakeSpeaker
import com.appvalence.umay.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StepCalibrationViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val motion = FakeMotionSensors()
    private val viewModel = StepCalibrationViewModel(motion, FakeCapabilities(), FakeSpeaker())

    private suspend fun takeSteps(count: Int) = repeat(count) { motion.steps.emit(StepEvent(0.75, 0.0, it.toLong())) }

    @Test
    fun `a distance typed with a comma is understood`() {
        viewModel.setDistanceText("12,5")
        assertEquals(12.5, viewModel.state.value.distanceMeters!!, 0.0)
        assertTrue(viewModel.state.value.distanceValid)
    }

    @Test
    fun `walking can't start with a distance too short to measure`() {
        viewModel.setDistanceText("2")

        viewModel.startWalking()

        assertFalse(viewModel.state.value.distanceValid)
        assertEquals(CalibrationPhase.SETUP, viewModel.state.value.phase)
    }

    @Test
    fun `steps are counted while walking and the stride is computed at the end`() = runTest(main.dispatcher) {
        viewModel.setDistanceText("10")
        viewModel.startWalking()
        advanceUntilIdle()

        takeSteps(14)
        advanceUntilIdle()
        assertEquals(14, viewModel.state.value.steps)

        val result = viewModel.finishWalking()
        assertEquals(StepLengthCalibration.compute(10.0, 14), result)
        assertEquals(CalibrationPhase.RESULT, viewModel.state.value.phase)

        // Counting stopped with the walk.
        takeSteps(3)
        advanceUntilIdle()
        assertEquals(14, viewModel.state.value.steps)
    }

    @Test
    fun `going back to setup forgets the walk`() = runTest(main.dispatcher) {
        viewModel.startWalking()
        advanceUntilIdle()
        takeSteps(5)
        advanceUntilIdle()

        viewModel.backToSetup()

        assertEquals(CalibrationPhase.SETUP, viewModel.state.value.phase)
        assertEquals(0, viewModel.state.value.steps)
    }
}
