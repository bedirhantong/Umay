package com.appvalence.umay.survey

import android.content.Context
import androidx.annotation.StringRes
import com.appvalence.umay.R
import com.appvalence.umay.common.DeviceCapabilities
import com.appvalence.umay.common.MotionSensors
import com.appvalence.umay.common.throttleLatest
import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.RadioSample
import com.appvalence.umay.coremodel.SurveyDraft
import com.appvalence.umay.coremodel.TaggedWaypoint
import com.appvalence.umay.di.ApplicationScope
import com.appvalence.umay.di.DefaultDispatcher
import com.appvalence.umay.di.SerialDispatcher
import com.appvalence.umay.feedback.Speaker
import com.appvalence.umay.positioning.baro.BarometricFloorTracker
import com.appvalence.umay.positioning.ble.BeaconScanner
import com.appvalence.umay.positioning.ble.BleScanState
import com.appvalence.umay.positioning.fingerprint.RadioSampler
import com.appvalence.umay.positioning.mapping.ArPoseAligner
import com.appvalence.umay.positioning.mapping.DepthProjector
import com.appvalence.umay.positioning.mapping.FloorHeightEstimator
import com.appvalence.umay.positioning.mapping.OccupancyGrid
import com.appvalence.umay.positioning.pdr.PdrIntegrator
import com.appvalence.umay.positioning.trilateration.BeaconSurveyResult
import com.appvalence.umay.positioning.trilateration.BeaconSurveySolver
import com.appvalence.umay.settings.UserSettings
import com.appvalence.umay.survey.ar.ArCameraStatus
import com.appvalence.umay.survey.ar.ArSurveyCamera
import com.appvalence.umay.survey.ar.ArTrackingProblem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

/** How the walked path is measured: by counting footsteps, or by the camera tracking the phone. */
enum class SurveyMode { FOOTSTEPS, CAMERA }

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
    /** Fixed when the walk starts; a walk is measured one way throughout. */
    val mode: SurveyMode = SurveyMode.FOOTSTEPS,
    val cameraStatus: ArCameraStatus = ArCameraStatus.Off,
    /** Where the camera puts the phone right now, between trajectory points (camera mode only). */
    val livePosition: Position? = null,
    /** The wall map measured so far, per floor (camera mode, on phones that measure depth). */
    val wallMaps: Map<Int, OccupancyRaster> = emptyMap(),
    /** This walk was brought back from disk after the app was ended mid-survey. */
    val restoredFromDraft: Boolean = false,
    /** Beacon signatures taken every few metres along the path, per floor (see [RadioSampler]). */
    val radioSamples: Map<Int, List<RadioSample>> = emptyMap(),
)

/** Says out loud what the surveyor should know without looking at the screen. */
interface SurveyAnnouncer {
    fun floorChanged(floor: Int)

    /** The camera lost track of where the phone is, for long enough that the surveyor should know. */
    fun cameraLost(problem: ArTrackingProblem)

    fun cameraBack()
}

class ResourceSurveyAnnouncer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val speaker: Speaker,
) : SurveyAnnouncer {
    override fun floorChanged(floor: Int) {
        speaker.speakNow(context.getString(R.string.survey_floor_auto, context.getString(R.string.common_floor, floor)))
    }

    override fun cameraLost(problem: ArTrackingProblem) {
        speaker.speakNow(context.getString(R.string.survey_camera_lost_spoken, context.getString(trackingProblemText(problem))))
    }

    override fun cameraBack() {
        speaker.speakNow(context.getString(R.string.survey_camera_back))
    }
}

/** What the surveyor can do about a tracking loss, as a string resource. */
@StringRes
fun trackingProblemText(problem: ArTrackingProblem): Int = when (problem) {
    ArTrackingProblem.INSUFFICIENT_LIGHT -> R.string.survey_camera_problem_light
    ArTrackingProblem.EXCESSIVE_MOTION -> R.string.survey_camera_problem_motion
    ArTrackingProblem.INSUFFICIENT_FEATURES -> R.string.survey_camera_problem_features
    ArTrackingProblem.CAMERA_UNAVAILABLE -> R.string.survey_camera_problem_camera
    ArTrackingProblem.OTHER -> R.string.survey_camera_problem_other
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
 * While it is, a foreground service ([SurveyForeground]) keeps the process
 * alive behind other apps, and the walk is written to disk every
 * [DRAFT_INTERVAL_MS] ([SurveyDrafts]) to come back if the app is ended anyway.
 *
 * In [SurveyMode.CAMERA] the path comes from [ArSurveyCamera] instead of
 * footsteps — centimetre-accurate, and with depth, a wall map is drawn as the
 * surveyor walks, the way a robot vacuum maps a home. The camera only runs
 * while the survey screen is on screen ([setScreenVisible]); meanwhile, and
 * whenever tracking drops, footsteps bridge the gap (see [ArPoseAligner]).
 *
 * Main-thread only, except the camera mapping, which runs on [mapping].
 */
@Singleton
class SurveyRecorder @Inject constructor(
    private val scanner: BeaconScanner,
    private val motion: MotionSensors,
    private val capabilities: DeviceCapabilities,
    private val settings: UserSettings,
    private val announcer: SurveyAnnouncer,
    private val camera: ArSurveyCamera,
    private val drafts: SurveyDrafts,
    private val foreground: SurveyForeground,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:DefaultDispatcher private val default: CoroutineDispatcher,
    // Camera mapping state below is touched only here, one frame at a time.
    @param:SerialDispatcher private val mapping: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(SurveyState())
    val state: StateFlow<SurveyState> = _state.asStateFlow()

    // Every BLE sighting of the walk — often tens of thousands — kept out of the
    // observable state: only its size is shown, refreshed a couple of times a second.
    private val rawReadings = ArrayList<BleBeaconReading>()
    private var recordingJob: Job? = null
    private val floorTracker = BarometricFloorTracker()
    private var screenVisible = false

    // How many of [rawReadings] are already in the draft on disk.
    private var flushedReadings = 0

    // Fed from the footstep collector (main) or the camera one (mapping), never both at once.
    private val radioSampler = RadioSampler()

    // Camera mapping state: touched only on [mapping].
    private var aligner = ArPoseAligner()
    private val grids = mutableMapOf<Int, OccupancyGrid>()
    private val dirtyFloors = mutableSetOf<Int>()
    private val floorHeight = FloorHeightEstimator()

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
        scope.launch { restoreDraft() }
    }

    /**
     * Starts (or resumes) recording. [mode] only matters for a new walk: a resumed one
     * keeps the mode it was started with.
     */
    fun start(mode: SurveyMode = SurveyMode.FOOTSTEPS) {
        if (recordingJob != null) return
        val current = _state.value
        val walkMode = if (current.trajectory.isEmpty() && current.taggedWaypoints.isEmpty()) mode else current.mode
        val stepLength = settings.stepLengthMeters
        _state.update { it.copy(isRecording = true, stepLengthMeters = stepLength, mode = walkMode, restoredFromDraft = false) }
        scanner.start(null)
        foreground.recordingStarted()
        recordingJob = scope.launch {
            launch {
                while (true) {
                    delay(DRAFT_INTERVAL_MS)
                    saveDraft()
                }
            }
            when (walkMode) {
                SurveyMode.FOOTSTEPS -> launch { recordFootsteps(stepLength) }
                SurveyMode.CAMERA -> {
                    launch { recordCamera() }
                    launch { watchCamera() }
                    launch { motion.steps(stepLength).collect { step -> withContext(mapping) { aligner.onStep(step.stepLengthMeters) } } }
                    if (screenVisible) camera.resume()
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

    private suspend fun recordFootsteps(stepLength: Double) {
        var trackedFloor = _state.value.currentFloor
        var pdr = continueOnFloor(trackedFloor)
        motion.steps(stepLength).collect { step ->
            val floor = _state.value.currentFloor
            if (floor != trackedFloor) {
                // PDR has no vertical axis: each floor keeps its own (x, y) frame instead of
                // letting stairwell walking distort this floor's shape.
                trackedFloor = floor
                pdr = continueOnFloor(floor)
            }
            val pose = pdr.integrate(step)
            _state.update { it.copy(trajectory = it.trajectory + pose) }
            sampleRadio(pose)
        }
    }

    /** A beacon signature every few metres of the walk, not only at named places. */
    private fun sampleRadio(position: Position) {
        val sample = synchronized(radioSampler) { radioSampler.offer(position, _state.value.beacons) } ?: return
        _state.update { it.copy(radioSamples = it.radioSamples + (position.floor to (it.radioSamples[position.floor].orEmpty() + sample))) }
    }

    /**
     * Dead reckoning for [floor], picking up where the walk last was on it — after
     * "Kayda devam et", or back on a floor left earlier (most often by the same stairs,
     * so its last point is where the walker is again). A floor never walked starts at
     * its own origin.
     */
    private fun continueOnFloor(floor: Int): PdrIntegrator {
        val last = _state.value.trajectory.lastOrNull { it.floor == floor } ?: return PdrIntegrator(floor = floor)
        return PdrIntegrator(startX = last.x, startY = last.y, startHeadingDeg = last.headingDeg, floor = floor)
    }

    /**
     * Camera frames → the walked path and the wall map. Unlike footsteps, the camera's
     * frame carries on across floors (it measures height too), so a floor change keeps
     * the same plan coordinates, and each floor gets its own wall map.
     */
    private suspend fun recordCamera(): Unit = withContext(mapping) {
        var lastSnapshotMs = 0L
        camera.frames.collect { sample ->
            val floor = _state.value.currentFloor
            val pose = aligner.onTracked(sample.pose)
            val position = Position(pose.x, pose.y, floor, pose.headingDeg, CAMERA_ACCURACY_METERS, PositionSource.MAP_MATCHED, pose.timestampMs)
            _state.update { current ->
                val last = current.trajectory.lastOrNull()
                val moved = last == null || last.floor != floor || hypot(last.x - position.x, last.y - position.y) >= TRAJECTORY_SPACING_METERS
                current.copy(livePosition = position, trajectory = if (moved) current.trajectory + position else current.trajectory)
            }
            sampleRadio(position)

            val depth = sample.depth ?: return@collect
            val toSurvey = aligner.transform ?: return@collect
            val floorY = floorHeight.update(sample.pose.y, sample.upwardPlaneHeights)
            if (DepthProjector.integrate(depth, floorY, toSurvey, grids.getOrPut(floor) { OccupancyGrid() }) > 0) dirtyFloors += floor
            if (pose.timestampMs - lastSnapshotMs >= WALL_MAP_REFRESH_MS) {
                lastSnapshotMs = pose.timestampMs
                publishWallMaps()
            }
        }
    }

    private suspend fun watchCamera(): Unit = coroutineScope {
        var lossAnnounced = false
        var lossJob: Job? = null
        camera.status.collect { status ->
            _state.update { it.copy(cameraStatus = status) }
            if (status == ArCameraStatus.Tracking) {
                lossJob?.cancel()
                if (lossAnnounced) announcer.cameraBack()
                lossAnnounced = false
            } else {
                withContext(mapping) { aligner.onTrackingLost() }
                // A glitch of a second or two isn't worth interrupting anyone for.
                if (status is ArCameraStatus.Lost && lossJob?.isActive != true && !lossAnnounced) {
                    lossJob = launch {
                        delay(LOSS_ANNOUNCE_DELAY_MS)
                        (_state.value.cameraStatus as? ArCameraStatus.Lost)?.let {
                            announcer.cameraLost(it.problem)
                            lossAnnounced = true
                        }
                    }
                }
            }
        }
    }

    /** Must be called on [mapping]. */
    private fun publishWallMaps() {
        if (dirtyFloors.isEmpty()) return
        val updated = dirtyFloors.mapNotNull { floor -> grids[floor]?.snapshot()?.let { floor to it } }.toMap()
        dirtyFloors.clear()
        _state.update { it.copy(wallMaps = it.wallMaps + updated) }
    }

    /**
     * The survey screen's own visibility: Android lets only a visible app use the
     * camera, so camera tracking follows it (footsteps carry the walk meanwhile).
     */
    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (recordingJob == null || _state.value.mode != SurveyMode.CAMERA) return
        if (visible) camera.resume() else camera.pause()
    }

    /** Stops recording and computes beacon positions — stopping is exactly when there's something new to solve. */
    fun stop() {
        val job = recordingJob ?: return
        job.cancel()
        recordingJob = null
        scanner.stop()
        foreground.recordingStopped()
        // Paused, not closed: resuming the walk continues in the same camera frame.
        if (_state.value.mode == SurveyMode.CAMERA) camera.pause()
        _state.update { it.copy(isRecording = false, rawReadingCount = rawReadings.size, livePosition = null) }
        solve()
    }

    private fun solve() {
        val trajectory = _state.value.trajectory
        val readings = rawReadings.toList()
        _state.update { it.copy(solving = true) }
        scope.launch {
            withContext(mapping) { publishWallMaps() }
            val results = withContext(default) { BeaconSurveySolver.solve(trajectory, readings) }
            _state.update { it.copy(results = results, hasSolved = true, solving = false) }
            saveDraft()
        }
    }

    /** Writes what's new since the last call; main thread, like everything that touches [rawReadings]. */
    private suspend fun saveDraft() {
        val current = _state.value
        if (current.trajectory.isEmpty() && current.taggedWaypoints.isEmpty()) return
        val newReadings = if (flushedReadings < rawReadings.size) ArrayList(rawReadings.subList(flushedReadings, rawReadings.size)) else emptyList()
        flushedReadings = rawReadings.size
        drafts.appendReadings(newReadings)
        drafts.save(
            SurveyDraft(
                mode = current.mode.name,
                currentFloor = current.currentFloor,
                stepLengthMeters = current.stepLengthMeters,
                trajectory = current.trajectory,
                taggedWaypoints = current.taggedWaypoints,
                wallMaps = current.wallMaps,
                radioSamples = current.radioSamples,
                savedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Brings back a walk the app was ended in the middle of. Its beacons are solved
     * straight away, so it can be saved as it is or recording resumed. A camera walk
     * continues in a new AR session, from its last point and heading (see
     * [ArPoseAligner]), with its wall maps as they were.
     */
    private suspend fun restoreDraft() {
        val restored = drafts.load() ?: return
        val current = _state.value
        if (recordingJob != null || current.trajectory.isNotEmpty() || current.taggedWaypoints.isNotEmpty()) return
        val draft = restored.draft
        val mode = SurveyMode.entries.firstOrNull { it.name == draft.mode } ?: SurveyMode.FOOTSTEPS
        rawReadings.addAll(restored.readings)
        flushedReadings = rawReadings.size
        withContext(mapping) {
            grids.clear()
            draft.wallMaps.forEach { (floor, raster) -> grids[floor] = OccupancyGrid().apply { seed(raster) } }
            val last = draft.trajectory.lastOrNull()
            aligner = if (mode == SurveyMode.CAMERA && last != null) ArPoseAligner(last.x, last.y, startHeadingDeg = last.headingDeg) else ArPoseAligner()
        }
        _state.update {
            it.copy(
                mode = mode,
                currentFloor = draft.currentFloor,
                stepLengthMeters = draft.stepLengthMeters,
                trajectory = draft.trajectory,
                taggedWaypoints = draft.taggedWaypoints,
                wallMaps = draft.wallMaps,
                radioSamples = draft.radioSamples,
                rawReadingCount = rawReadings.size,
                restoredFromDraft = true,
            )
        }
        solve()
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
        val fingerprint = current.beacons.filter { it.uuid != null }.map { RadioFingerprint(it.beaconId, it.rssi.toDouble()) }
        val waypoint = if (current.mode == SurveyMode.CAMERA) {
            // The camera's live position, not the last trajectory point up to 25 cm behind;
            // the index lets the map follow the walked path round corners (BeaconMapBuilder).
            val position = current.livePosition?.takeIf { it.floor == current.currentFloor } ?: return false
            TaggedWaypoint(name, position.x, position.y, current.currentFloor, fingerprint, type, trajectoryIndex = current.trajectory.size)
        } else {
            val position = current.trajectory.lastOrNull { it.floor == current.currentFloor } ?: return false
            TaggedWaypoint(name, position.x, position.y, position.floor, fingerprint, type)
        }
        _state.update { it.copy(taggedWaypoints = it.taggedWaypoints + waypoint) }
        return true
    }

    fun deleteWaypoint(index: Int) {
        _state.update { current ->
            if (index !in current.taggedWaypoints.indices) current else current.copy(taggedWaypoints = current.taggedWaypoints.filterIndexed { i, _ -> i != index })
        }
        if (recordingJob == null) scope.launch { saveDraft() }
    }

    /** Throws the whole walk away (the list of discovered beacons stays). */
    fun clear() {
        if (recordingJob != null) return
        rawReadings.clear()
        flushedReadings = 0
        synchronized(radioSampler) { radioSampler.reset() }
        scope.launch { drafts.delete() }
        // A new walk gets a new camera frame and empty wall maps.
        if (_state.value.mode == SurveyMode.CAMERA) camera.close()
        scope.launch(mapping) {
            aligner = ArPoseAligner()
            grids.clear()
            dirtyFloors.clear()
        }
        _state.update { SurveyState(beacons = it.beacons, discovered = it.discovered, scanState = it.scanState) }
    }

    fun clearDiscovered() {
        _state.update { it.copy(discovered = emptyMap()) }
    }

    companion object {
        const val UI_REFRESH_MS = 500L

        // Coarse enough to keep the trajectory list small over a long walk, fine
        // enough that corners survive (see BeaconMapBuilder.CORNER_TOLERANCE_METERS).
        const val TRAJECTORY_SPACING_METERS = 0.25
        const val CAMERA_ACCURACY_METERS = 0.1
        const val WALL_MAP_REFRESH_MS = 1_000L
        const val LOSS_ANNOUNCE_DELAY_MS = 2_000L

        // At most this much of a walk is lost if the app is ended mid-survey.
        const val DRAFT_INTERVAL_MS = 10_000L
    }
}
