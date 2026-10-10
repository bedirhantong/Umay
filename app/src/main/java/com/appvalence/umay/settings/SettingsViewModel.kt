package com.appvalence.umay.settings

import androidx.lifecycle.ViewModel
import com.appvalence.umay.common.DeviceCapabilities
import com.appvalence.umay.coremodel.GuidanceCue
import com.appvalence.umay.feedback.HapticPatterns
import com.appvalence.umay.feedback.HapticPlayer
import com.appvalence.umay.feedback.Speaker
import com.appvalence.umay.feedback.VoiceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsUiState(
    val calibratedStepLengthMeters: Double?,
    val speechRate: Float,
    val hapticsEnabled: Boolean,
    val keepScreenOn: Boolean,
    val avoidStairs: Boolean,
    val clockDirections: Boolean,
    /** The step-length calibration walk is showing instead of the settings list. */
    val calibrating: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: UserSettings,
    private val speaker: Speaker,
    private val haptics: HapticPlayer,
    capabilities: DeviceCapabilities,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SettingsUiState(
            calibratedStepLengthMeters = settings.calibratedStepLengthMeters,
            speechRate = settings.speechRate,
            hapticsEnabled = settings.hapticsEnabled,
            keepScreenOn = settings.keepScreenOnDuringNavigation,
            avoidStairs = settings.avoidStairs,
            clockDirections = settings.clockDirections,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    val voiceStatus: StateFlow<VoiceStatus> = speaker.voiceStatus
    val hasBarometer: Boolean = capabilities.hasBarometer
    val hapticsAvailable: Boolean get() = haptics.isAvailable

    fun startCalibration() = _state.update { it.copy(calibrating = true) }

    fun cancelCalibration() = _state.update { it.copy(calibrating = false) }

    fun saveCalibratedStepLength(meters: Double) {
        settings.calibratedStepLengthMeters = meters
        _state.update { it.copy(calibratedStepLengthMeters = meters, calibrating = false) }
    }

    fun resetStepLength() {
        settings.calibratedStepLengthMeters = null
        _state.update { it.copy(calibratedStepLengthMeters = null) }
    }

    /**
     * One step slower (-1) or faster (+1) through [SPEECH_RATE_STEPS]; returns the
     * new rate, which the caller announces in a sample at that pace.
     */
    fun changeSpeechRate(step: Int): Float {
        val rate = nextSpeechRate(_state.value.speechRate, step)
        settings.speechRate = rate
        speaker.speechRate = rate
        _state.update { it.copy(speechRate = rate) }
        return rate
    }

    fun setHapticsEnabled(enabled: Boolean) {
        settings.hapticsEnabled = enabled
        _state.update { it.copy(hapticsEnabled = enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        settings.keepScreenOnDuringNavigation = enabled
        _state.update { it.copy(keepScreenOn = enabled) }
    }

    fun setAvoidStairs(enabled: Boolean) {
        settings.avoidStairs = enabled
        _state.update { it.copy(avoidStairs = enabled) }
    }

    fun setClockDirections(enabled: Boolean) {
        settings.clockDirections = enabled
        _state.update { it.copy(clockDirections = enabled) }
    }

    fun playHaptic(cue: GuidanceCue) {
        HapticPatterns.forCue(cue)?.let(haptics::play)
    }

    fun speak(text: String) = speaker.speakNow(text)

    fun stopSpeaking() = speaker.stop()

    fun refreshVoice() = speaker.refreshVoice()

    override fun onCleared() {
        haptics.stop()
    }

    companion object {
        // From "new to speech output" to "experienced screen-reader user" pace.
        val SPEECH_RATE_STEPS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

        /** Snaps [current] to the nearest step at or above it, then moves [step] steps, within bounds. */
        internal fun nextSpeechRate(current: Float, step: Int): Float {
            val index = (SPEECH_RATE_STEPS.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: SPEECH_RATE_STEPS.lastIndex) + step
            return SPEECH_RATE_STEPS[index.coerceIn(0, SPEECH_RATE_STEPS.lastIndex)]
        }
    }
}
