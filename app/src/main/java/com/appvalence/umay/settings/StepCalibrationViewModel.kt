package com.appvalence.umay.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appvalence.umay.common.DeviceCapabilities
import com.appvalence.umay.common.MotionSensors
import com.appvalence.umay.feedback.Speaker
import com.appvalence.umay.positioning.pdr.StepDetector
import com.appvalence.umay.positioning.pdr.StepLengthCalibration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CalibrationPhase { SETUP, WALKING, RESULT }

data class CalibrationUiState(
    val phase: CalibrationPhase = CalibrationPhase.SETUP,
    val distanceText: String = DEFAULT_CALIBRATION_DISTANCE_METERS.toString(),
    val steps: Int = 0,
    val result: StepLengthCalibration.Result? = null,
) {
    /** Accepts a decimal comma, the way it's typed on a Turkish keyboard. */
    val distanceMeters: Double? get() = distanceText.replace(',', '.').toDoubleOrNull()
    val distanceValid: Boolean get() = distanceMeters?.let { it >= StepLengthCalibration.MIN_DISTANCE_METERS } == true
}

private const val DEFAULT_CALIBRATION_DISTANCE_METERS = 10

/** Walk a known distance, count the steps, divide (see [StepLengthCalibration]). */
@HiltViewModel
class StepCalibrationViewModel @Inject constructor(
    private val motion: MotionSensors,
    capabilities: DeviceCapabilities,
    private val speaker: Speaker,
) : ViewModel() {
    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    val sensorsAvailable: Boolean = capabilities.hasStepSensors

    private var counting: Job? = null

    fun setDistanceText(text: String) = _state.update { it.copy(distanceText = text) }

    fun startWalking() {
        if (!_state.value.distanceValid) return
        counting?.cancel()
        _state.update { it.copy(phase = CalibrationPhase.WALKING, steps = 0, result = null) }
        // The stride passed in doesn't matter here — only the step count is used.
        counting = viewModelScope.launch {
            motion.steps(StepDetector.AVERAGE_STEP_LENGTH_METERS).collect { _state.update { it.copy(steps = it.steps + 1) } }
        }
    }

    /** Stops counting and computes the stride; the caller announces the returned result. */
    fun finishWalking(): StepLengthCalibration.Result {
        counting?.cancel()
        counting = null
        val current = _state.value
        val result = StepLengthCalibration.compute(current.distanceMeters ?: 0.0, current.steps)
        _state.update { it.copy(phase = CalibrationPhase.RESULT, result = result) }
        return result
    }

    fun backToSetup() {
        counting?.cancel()
        counting = null
        _state.update { it.copy(phase = CalibrationPhase.SETUP, steps = 0, result = null) }
    }

    /** Back to a fresh start for the next time calibration is opened. */
    fun reset() {
        counting?.cancel()
        counting = null
        _state.value = CalibrationUiState()
    }

    fun speak(text: String) = speaker.speakNow(text)

    fun stopSpeaking() = speaker.stop()
}
