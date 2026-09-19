package com.appvalence.umay

import androidx.lifecycle.SavedStateHandle
import com.appvalence.umay.testing.FakeUserSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelTest {
    @Test
    fun `the tour shows on first launch and is remembered once finished`() {
        val settings = FakeUserSettings(onboardingCompleted = false)
        val viewModel = MainViewModel(settings, SavedStateHandle())
        assertTrue(viewModel.showTour.value)

        viewModel.onTourFinished()

        assertFalse(viewModel.showTour.value)
        assertTrue(settings.onboardingCompleted)
        assertFalse(MainViewModel(settings, SavedStateHandle()).showTour.value)
    }

    @Test
    fun `the tour can be replayed`() {
        val viewModel = MainViewModel(FakeUserSettings(onboardingCompleted = true), SavedStateHandle())
        assertFalse(viewModel.showTour.value)

        viewModel.replayTour()

        assertTrue(viewModel.showTour.value)
    }
}
