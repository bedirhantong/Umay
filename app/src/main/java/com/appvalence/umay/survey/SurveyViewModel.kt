package com.appvalence.umay.survey

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appvalence.umay.common.DeviceCapabilities
import com.appvalence.umay.coremodel.MapDoors
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.di.DefaultDispatcher
import com.appvalence.umay.feedback.Speaker
import com.appvalence.umay.positioning.fingerprint.RadioMapBudget
import com.appvalence.umay.positioning.mapping.WallMapBudget
import com.appvalence.umay.positioning.trilateration.BeaconMapBuilder
import com.appvalence.umay.settings.UserSettings
import com.appvalence.umay.storage.BuildingRepository
import com.appvalence.umay.survey.ar.ArAvailability
import com.appvalence.umay.survey.ar.ArInstallResult
import com.appvalence.umay.survey.ar.ArSurveyCamera
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The result of saving a surveyed map. */
sealed interface SurveySaveResult {
    data class Saved(val points: Int, val connections: Int, val floors: Int) : SurveySaveResult
    data object Failed : SurveySaveResult
}

data class SurveySaveState(val saving: Boolean = false, val result: SurveySaveResult? = null)

@HiltViewModel
class SurveyViewModel @Inject constructor(
    private val recorder: SurveyRecorder,
    private val buildings: BuildingRepository,
    private val settings: UserSettings,
    capabilities: DeviceCapabilities,
    private val speaker: Speaker,
    private val camera: ArSurveyCamera,
    @param:DefaultDispatcher private val default: CoroutineDispatcher,
) : ViewModel() {
    val survey: StateFlow<SurveyState> = recorder.state

    /** Whether "survey with the camera" can be offered, and what's missing if not. */
    val cameraAvailability: StateFlow<ArAvailability> = camera.availability

    /** Null until the camera has started once; false means the path is tracked but walls can't be measured. */
    val cameraMeasuresWalls: StateFlow<Boolean?> = camera.supportsDepth

    private val _save = MutableStateFlow(SurveySaveState())
    val save: StateFlow<SurveySaveState> = _save.asStateFlow()

    val hasStepSensors: Boolean = capabilities.hasStepSensors
    val hasBarometer: Boolean = capabilities.hasBarometer

    /** An uncalibrated stride stretches or shrinks the whole map (every coordinate is steps × stride). */
    val strideCalibrated: Boolean get() = settings.calibratedStepLengthMeters != null

    init {
        camera.checkAvailability()
    }

    fun startRecording(mode: SurveyMode) {
        _save.value = SurveySaveState()
        recorder.start(mode)
    }

    /** The survey screen came on or went off screen (the camera may only run while it's shown). */
    fun setScreenVisible(visible: Boolean) {
        if (visible && camera.availability.value != ArAvailability.READY) camera.checkAvailability()
        recorder.setScreenVisible(visible)
    }

    /** Asks ARCore again, after a check that got no answer. */
    fun recheckCameraSupport() = camera.checkAvailability()

    fun installCameraSupport(activity: Activity): ArInstallResult = camera.requestInstall(activity, userRequested = true)

    fun stopRecording() = recorder.stop()

    fun changeFloor(delta: Int) = recorder.changeFloor(delta)

    fun tag(name: String, type: NodeType): Boolean = recorder.tag(name, type)

    fun deleteWaypoint(index: Int) = recorder.deleteWaypoint(index)

    fun clear() {
        _save.value = SurveySaveState()
        recorder.clear()
    }

    fun clearDiscovered() = recorder.clearDiscovered()

    /** [anchorName] names the beacons placed on the map ("Kat 1 · Beacon 3") — wording is the UI's. */
    fun saveMap(buildingName: String, anchorName: (floor: Int, number: Int) -> String) {
        val survey = survey.value
        val name = buildingName.trim()
        if (name.isEmpty() || _save.value.saving) return
        _save.value = SurveySaveState(saving = true)
        viewModelScope.launch {
            val map = withContext(default) {
                val cameraSurvey = survey.mode == SurveyMode.CAMERA
                val built = BeaconMapBuilder.buildFromWaypoints(
                    buildingId = name,
                    taggedWaypoints = survey.taggedWaypoints,
                    beaconResults = survey.results,
                    anchorName = anchorName,
                    trajectory = survey.trajectory,
                    radioSamples = RadioMapBudget.fit(survey.radioSamples),
                )
                val wallMaps = WallMapBudget.fit(survey.wallMaps)
                built.copy(
                    // Doors cut into the walls after fitting, so a coarser map still gets them.
                    floors = built.floors.map { MapDoors.apply(it.copy(occupancy = wallMaps[it.floorIndex])) },
                    // A camera-measured map isn't steps × stride, so there's no stride to rescale by.
                    surveyStepLengthMeters = if (cameraSurvey) null else survey.stepLengthMeters ?: settings.stepLengthMeters,
                )
            }
            val result = if (buildings.save(map)) {
                SurveySaveResult.Saved(
                    points = map.floors.sumOf { it.nodes.size },
                    connections = map.floors.sumOf { it.edges.size },
                    floors = map.floors.size,
                )
            } else {
                SurveySaveResult.Failed
            }
            _save.value = SurveySaveState(result = result)
        }
    }

    fun speak(text: String) = speaker.speakNow(text)

    fun stopSpeaking() = speaker.stop()

    // The activity is gone for good (not just rotated): don't keep the radio and the
    // sensors running with no one to see it. What was recorded is kept.
    override fun onCleared() {
        if (recorder.state.value.isRecording) recorder.stop()
    }
}
