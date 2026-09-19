package com.appvalence.umay.settings

import com.appvalence.umay.coremodel.GuidanceCue
import com.appvalence.umay.testing.FakeCapabilities
import com.appvalence.umay.testing.FakeHapticPlayer
import com.appvalence.umay.testing.FakeSpeaker
import com.appvalence.umay.testing.FakeUserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {
    private val settings = FakeUserSettings()
    private val speaker = FakeSpeaker()
    private val haptics = FakeHapticPlayer()
    private val viewModel = SettingsViewModel(settings, speaker, haptics, FakeCapabilities(hasBarometer = true))

    @Test
    fun `speech rate moves one step at a time and stops at both ends`() {
        assertEquals(1.25f, SettingsViewModel.nextSpeechRate(1.0f, +1))
        assertEquals(0.75f, SettingsViewModel.nextSpeechRate(1.0f, -1))
        assertEquals(0.75f, SettingsViewModel.nextSpeechRate(0.75f, -1))
        assertEquals(3.0f, SettingsViewModel.nextSpeechRate(3.0f, +1))
    }

    @Test
    fun `a rate between steps snaps to the next step up before moving`() {
        assertEquals(1.5f, SettingsViewModel.nextSpeechRate(1.1f, +1))
        assertEquals(1.25f, SettingsViewModel.nextSpeechRate(1.1f, 0))
    }

    @Test
    fun `changing the rate is saved and heard right away`() {
        val rate = viewModel.changeSpeechRate(+1)

        assertEquals(1.25f, rate)
        assertEquals(1.25f, settings.speechRate)
        assertEquals(1.25f, speaker.speechRate)
        assertEquals(1.25f, viewModel.state.value.speechRate)
    }

    @Test
    fun `switches are saved`() {
        viewModel.setHapticsEnabled(false)
        viewModel.setKeepScreenOn(true)

        assertFalse(settings.hapticsEnabled)
        assertTrue(settings.keepScreenOnDuringNavigation)
        assertFalse(viewModel.state.value.hapticsEnabled)
        assertTrue(viewModel.state.value.keepScreenOn)
    }

    @Test
    fun `a calibrated stride is saved and closes calibration, and resetting forgets it`() {
        viewModel.startCalibration()
        assertTrue(viewModel.state.value.calibrating)

        viewModel.saveCalibratedStepLength(0.68)
        assertEquals(0.68, settings.calibratedStepLengthMeters)
        assertFalse(viewModel.state.value.calibrating)

        viewModel.resetStepLength()
        assertNull(settings.calibratedStepLengthMeters)
        assertNull(viewModel.state.value.calibratedStepLengthMeters)
    }

    @Test
    fun `trying a haptic cue plays its pattern`() {
        viewModel.playHaptic(GuidanceCue.TURN_LEFT)

        assertEquals(1, haptics.played.size)
    }

    @Test
    fun `device capabilities are exposed as they are`() {
        assertTrue(viewModel.hasBarometer)
        assertTrue(viewModel.hapticsAvailable)
    }
}
