package com.appvalence.umay.survey

import android.content.Context
import com.appvalence.umay.R
import com.appvalence.umay.common.DeviceCapabilities
import com.appvalence.umay.common.MotionSensors
import com.appvalence.umay.common.throttleLatest
import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.TaggedWaypoint
import com.appvalence.umay.di.ApplicationScope
import com.appvalence.umay.di.DefaultDispatcher
import com.appvalence.umay.feedback.Speaker
import com.appvalence.umay.positioning.baro.BarometricFloorTracker
import com.appvalence.umay.positioning.ble.BeaconScanner
import com.appvalence.umay.positioning.ble.BleScanState
import com.appvalence.umay.positioning.pdr.PdrIntegrator
import com.appvalence.umay.positioning.trilateration.BeaconSurveyResult
import com.appvalence.umay.positioning.trilateration.BeaconSurveySolver
import com.appvalence.umay.settings.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the survey screen shows about the walk being recorded. */
data class SurveyState(
    val isRecording: Boolean = false,
    val trajectory: List<Position> = emptyList(),
    val rawReadingCount: Int = 0,
    val taggedWaypoints: List<TaggedWaypoint> = emptyList(),
    val currentFloor: Int = 0,
    val results: List<BeaconSurveyResult> = emptyList(),
    val hasSolved: Boolean = false,
    val solving: Boolean = false,
    /** The stride every coordinate of this walk was computed with (see [UserSettings.stepLengthMeters]). */
    val stepLengthMeters: Double? = null,
    val beacons: List<BleBeaconReading> = emptyList(),
    val discovered: Map<String, BleBeaconReading> = emptyMap(),
    val scanState: BleScanState = BleScanState.Idle,
    /** The floor the barometer last moved the survey to on its own, if it did since the last manual change. */
    val lastAutoFloor: Int? = null,
)

/** Says out loud what the surveyor should know without looking at the screen. */
interface SurveyAnnouncer {
    fun floorChanged(floor: Int)
}

class ResourceSurveyAnnouncer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val speaker: Speaker,
) : SurveyAnnouncer {
    override fun floorChanged(floor: Int) {
        speaker.speakNow(context.getString(R.string.survey_floor_auto, context.getString(R.string.common_floor, floor)))
    }
}

/**
 * On-site mapping walk (see [BeaconSurveySolver]): the operator starts a
 * recording, walks the building naming places as they go, then stops and saves
 * the resulting map.
 *
 * Lives as long as the process, not a screen: a survey is exactly the kind of
 * long, unrepeatable data collection that must not stop — or silently restart
 * dead reckoning from the origin — because someone switched tabs mid-walk.
 * BLE scanning and the motion sensors only run while a recording is active.
 *
 * Main-thread only.
 */
@Singleton
class SurveyRecorder @Inject constructor(
    private val scanner: BeaconScanner,
    private val motion: MotionSensors,
    private val capabilities: DeviceCapabilities,
    private val settings: UserSettings,
    private val announcer: SurveyAnnouncer,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:DefaultDispatcher private val default: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(SurveyState())
    val state: StateFlow<SurveyState> = _state.asStateFlow()

    // Every BLE sighting of the walk — often tens of thousands — kept out of the
    // observable state: only its size is shown, refreshed a couple of times a second.
    private val rawReadings = ArrayList<BleBeaconReading>()
    private var recordingJob: Job? = null
    private val floorTracker = BarometricFloorTracker()

    init {
        scope.launch { scanner.scanState().collect { scanState -> _state.update { it.copy(scanState = scanState) } } }
        // Sightings change far faster than anyone can read a list.
        scope.launch {
            scanner.beaconUpdates().throttleLatest(UI_REFRESH_MS).collect { beacons ->
                _state.update { current ->
                    current.copy(
                        beacons = beacons,
                        discovered = current.discovered + beacons.filter { it.uuid != null }.associateBy { it.beaconId },
                    )
                }
            }
        }
    }

    fun start() {
        if (recordingJob != null) return
        val stepLength = settings.stepLengthMeters
        _state.update { it.copy(isRecording = true, stepLengthMeters = stepLength) }
        scanner.start(null)
        recordingJob = scope.launch {
            launch {
                var trackedFloor = _state.value.currentFloor
                var pdr = PdrIntegrator(floor = trackedFloor)
                motion.steps(stepLength).collect { step ->
                    val floor = _state.value.currentFloor
                    if (floor != trackedFloor) {
                        // PDR has no vertical axis: a floor change starts a fresh (x, y) origin
                        // instead of letting stairwell walking distort this floor's shape.
                        trackedFloor = floor
                        pdr = PdrIntegrator(floor = floor)
                    }
                    val pose = pdr.integrate(step)
                    _state.update { it.copy(trajectory = it.trajectory + pose) }
                }
            }
            launch { scanner.rawReadingUpdates().collect { rawReadings.add(it) } }
            launch {
                while (true) {
                    _state.update { it.copy(rawReadingCount = rawReadings.size) }
                    delay(UI_REFRESH_MS)
                }
            }
            // With a barometer, floor changes are noticed on their own; the manual
            // stepper stays for corrections (and for phones without one).
            if (capabilities.hasBarometer) {
                launch {
                    floorTracker.resetBaseline()
                    motion.pressure().collect { reading ->
                        val floors = floorTracker.onPressure(reading.hPa, reading.timestampMs)
                        if (floors != 0) {
                            val newFloor = _state.value.currentFloor + floors
                            _state.update { it.copy(currentFloor = newFloor, lastAutoFloor = newFloor) }
                            announcer.floorChanged(newFloor)
                        }
                    }
                }
            }
        }
    }

    /** Stops recording and computes beacon positions — stopping is exactly when there's something new to solve. */
    fun stop() {
        val job = recordingJob ?: return
        job.cancel()
        recordingJob = null
        scanner.stop()
        val trajectory = _state.value.trajectory
        val readings = rawReadings.toList()
        _state.update { it.copy(isRecording = false, rawReadingCount = readings.size, solving = true) }
        scope.launch {
            val results = withContext(default) { BeaconSurveySolver.solve(trajectory, readings) }
            _state.update { it.copy(results = results, hasSolved = true, solving = false) }
        }
    }

    /** A manual floor correction is the truth: further automatic changes are measured from here. */
    fun changeFloor(delta: Int) {
        _state.update { it.copy(currentFloor = it.currentFloor + delta, lastAutoFloor = null) }
        floorTracker.resetBaseline()
    }

    /**
     * Names the spot the surveyor is standing on, with the radio fingerprint heard
     * there. False if there's no position on this floor yet (no step taken since
     * arriving on it).
     */
    fun tag(name: String, type: NodeType): Boolean {
        val current = _state.value
        val position = current.trajectory.lastOrNull { it.floor == current.currentFloor } ?: return false
        val fingerprint = current.beacons.filter { it.uuid != null }.map { RadioFingerprint(it.beaconId, it.rssi.toDouble()) }
        val waypoint = TaggedWaypoint(name, position.x, position.y, position.floor, fingerprint, type)
        _state.update { it.copy(taggedWaypoints = it.taggedWaypoints + waypoint) }
        return true
    }

    fun deleteWaypoint(index: Int) {
        _state.update { current ->
            if (index !in current.taggedWaypoints.indices) current else current.copy(taggedWaypoints = current.taggedWaypoints.filterIndexed { i, _ -> i != index })
        }
    }

    /** Throws the whole walk away (the list of discovered beacons stays). */
    fun clear() {
        if (recordingJob != null) return
        rawReadings.clear()
        _state.update { SurveyState(beacons = it.beacons, discovered = it.discovered, scanState = it.scanState) }
    }

    fun clearDiscovered() {
        _state.update { it.copy(discovered = emptyMap()) }
    }

    companion object {
        const val UI_REFRESH_MS = 500L
    }
}
