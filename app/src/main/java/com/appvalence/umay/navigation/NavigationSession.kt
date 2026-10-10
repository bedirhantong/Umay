package com.appvalence.umay.navigation

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.LifecycleOwner
import com.appvalence.umay.R
import com.appvalence.umay.common.speechLocale
import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FieldLogEvent
import com.appvalence.umay.coremodel.FloorChangeEvent
import com.appvalence.umay.coremodel.FloorChangePhase
import com.appvalence.umay.coremodel.GroundTruthEvent
import com.appvalence.umay.coremodel.GuidanceEvent
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.MarkerSighting
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.PositionEvent
import com.appvalence.umay.coremodel.PositionStream
import com.appvalence.umay.coremodel.PressureEvent
import com.appvalence.umay.coremodel.RssiEvent
import com.appvalence.umay.coremodel.SessionEndEvent
import com.appvalence.umay.coremodel.SessionStartEvent
import com.appvalence.umay.coremodel.StatusEvent
import com.appvalence.umay.feedback.CompositeFeedbackController
import com.appvalence.umay.feedback.HapticFeedbackController
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.fieldlog.FieldLogFiles
import com.appvalence.umay.fieldlog.FieldLogRecorder
import com.appvalence.umay.orchestrator.BuildingQueryMatcher
import com.appvalence.umay.orchestrator.BuildingQueryResult
import com.appvalence.umay.orchestrator.NavigationOrchestrator
import com.appvalence.umay.orchestrator.NavigationState
import com.appvalence.umay.perception.CameraAim
import com.appvalence.umay.perception.CameraAimJudge
import com.appvalence.umay.perception.CameraFailure
import com.appvalence.umay.perception.CameraPower
import com.appvalence.umay.perception.CameraPowerPolicy
import com.appvalence.umay.perception.CameraXObstacleDetector
import com.appvalence.umay.perception.MlKitSignTextReader
import com.appvalence.umay.perception.SignTextReader
import com.appvalence.umay.positioning.baro.BarometerSensor
import com.appvalence.umay.positioning.baro.BarometricFloorChangeDetector
import com.appvalence.umay.positioning.ble.BeaconScanTargets
import com.appvalence.umay.positioning.ble.BleBeaconScanner
import com.appvalence.umay.positioning.engine.ParticleFilterPositioningEngine
import com.appvalence.umay.positioning.engine.PositioningEngine
import com.appvalence.umay.positioning.fingerprint.FingerprintMatcher
import com.appvalence.umay.positioning.particle.GraphParticleFilter
import com.appvalence.umay.positioning.pdr.StepDetector
import com.appvalence.umay.positioning.trilateration.BeaconMapBuilder
import com.appvalence.umay.settings.UserSettings
import com.appvalence.umay.voice.SpeechRecognitionFailure
import com.appvalence.umay.voice.SpeechRecognitionResult
import com.appvalence.umay.voice.SpeechToText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the navigation screen (and the notification) shows about a running session. */
data class NavigationSessionUi(
    val state: NavigationState? = null,
    val phase: NavigationPhase = NavigationPhase.SEARCHING,
    val instruction: String,
    val pipelineError: String? = null,
    val cameraWarning: String? = null,
    val fingerprintCorrections: Int = 0,
    val barometerClimbMeters: Double? = null,
    val readingSigns: Boolean = false,
    val lastSignReading: String? = null,
    val askingQuestion: Boolean = false,
    val lastQuestionAnswer: String? = null,
)

/**
 * One guided walk from [startNodeId] to [destinationNode]: the whole
 * positioning → orchestrator → speech/vibration pipeline, plus field logging
 * and barometric floor detection.
 *
 * This deliberately lives outside any screen. It used to be part of the
 * navigation composable, which meant it only really worked while that screen
 * was in front of the user — but a blind user normally walks with the phone
 * locked in a pocket. Owned instead by [NavigationSessions] and kept alive by
 * [NavigationService] (a foreground service, so Android lets sensors, BLE
 * scanning and speech keep running with the screen off), the screen is now
 * just a view onto it.
 *
 * The one thing that genuinely can't continue with the screen off is the
 * camera: Android doesn't let a backgrounded app use it. So the screen
 * [attachCamera]s while it's visible and [detachCamera]s when it isn't, and
 * the user is *told* when obstacle detection pauses and resumes rather than
 * silently losing it (Ayarlar can keep the screen on for the whole walk).
 *
 * All methods are main-thread only.
 */
class NavigationSession internal constructor(
    context: Context,
    val buildingMap: BuildingMap,
    val startNodeId: String,
    val destinationNode: MapNode,
    private val settings: UserSettings,
    fieldLogs: FieldLogFiles,
    private val onClosed: (NavigationSession) -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val nodeFloor: Map<String, Int> = buildingMap.floors.flatMap { floor -> floor.nodes.map { it.id to floor.floorIndex } }.toMap()
    val allNodesById: Map<String, MapNode> = buildingMap.floors.flatMap { it.nodes }.associateBy { it.id }
    val startFloorIndex: Int = nodeFloor[startNodeId] ?: 0
    val destinationFloor: Int = nodeFloor[destinationNode.id] ?: 0
    val hasFingerprintedWaypoints: Boolean = allNodesById.values.any { it.fingerprint.isNotEmpty() }

    private val stepLengthMeters = settings.stepLengthMeters

    // Its own voice rather than the app-wide one: it's shut down with the session.
    val speech = TtsFeedbackController(appContext, speechLocale(appContext), settings.speechRate)
    private val haptics = HapticFeedbackController(appContext)

    // Every alert goes out as speech and, unless switched off in Ayarlar, as a vibration
    // cue too — the vibration still gets through where speech can't be heard (or in a call).
    private val feedback = CompositeFeedbackController(listOfNotNull(speech, haptics.takeIf { settings.hapticsEnabled }))

    private val scanner = BleBeaconScanner(appContext)
    private val fingerprintBeaconIds = allNodesById.values.flatMap { it.fingerprint }.map { it.beaconId }.toSet()

    // Filtering on the building's own beacons is what keeps BLE scanning — and with it
    // fingerprint corrections — alive with the screen off (see BeaconScanTargets).
    private val scanTargets = BeaconScanTargets.forBeaconIds(fingerprintBeaconIds)

    private val detector = CameraXObstacleDetector(
        appContext,
        onBindFailed = { failure ->
            val message = appContext.getString(
                when (failure) {
                    CameraFailure.MODEL_UNAVAILABLE -> R.string.camera_failure_model
                    CameraFailure.CAMERA_UNAVAILABLE -> R.string.camera_failure_camera
                },
            )
            _ui.update { it.copy(cameraWarning = message) }
        },
    )
    private var cameraAttached = false
    private var cameraPauseAnnounced = false

    // How much the camera may run (heat, battery: see CameraPowerPolicy) and whether it's
    // aimed where it can see anything (see CameraAimJudge). Main thread only.
    private val conditions = DeviceConditions(appContext)
    private var cameraPower = CameraPower.FULL
    private var screenOwner: LifecycleOwner? = null
    private var aimJob: Job? = null

    // On-device, offline, free (see MlKitSignTextReader) — reads whatever frame the
    // obstacle detector last saw rather than binding a second camera session.
    private val signTextReader: SignTextReader = MlKitSignTextReader()

    // Local, free, on-device question answering (see BuildingQueryMatcher) — only
    // RECORD_AUDIO is needed for speech-to-text, no cloud, no connection, no language
    // model. The transcript never leaves the device; it's matched against this
    // building's own graph and thrown away.
    private val speechToText = SpeechToText(appContext)

    // With a barometer, floor changes confirm themselves from air pressure — stairs and
    // elevators alike — so the footstep-count guess (NavigationOrchestrator's autoConfirm)
    // is only used on phones without one. The manual button stays either way, as the
    // fallback if the pressure change doesn't register (a split-level half floor, say).
    val hasBarometer: Boolean = BarometerSensor.isAvailable(appContext)
    private val barometer = BarometerSensor(appContext)
    private val floorChangeDetector = BarometricFloorChangeDetector()
    private var barometerConfirmedTargetId: String? = null

    // Field-test log for this one session (see FieldLogRecorder) — null unless recording
    // was switched on in "Dosyalar". Everything records through `recorder?.`, so with
    // recording off none of this costs anything.
    private val recorder: FieldLogRecorder? =
        if (fieldLogs.isRecordingEnabled) FieldLogRecorder(fieldLogs.newSessionFile(buildingMap.id)) else null
    val isRecording: Boolean get() = recorder != null
    val recordingFailed: Boolean get() = recorder?.failed == true

    // Walks a cloud of hypotheses along the surveyed corridors (see GraphParticleFilter):
    // dead reckoning can't drift through walls, the starting heading is learned from the
    // first few steps instead of assumed, and fingerprint matches (see FingerprintMatcher)
    // reweight the cloud rather than snapping it. RSSI-to-distance trilateration stays
    // out of it — too noisy in real buildings (see BeaconMapBuilder's field notes).
    private val particleConfig = GraphParticleFilter.Config()
    private val positioningEngine = ParticleFilterPositioningEngine(
        stepEvents = StepDetector(appContext, stepLengthMeters).stepEvents(),
        beaconReadings = scanner.beaconUpdates(),
        map = buildingMap,
        startNodeId = startNodeId,
        config = particleConfig,
        // A printed place marker the camera sees settles where the walker is (see PlaceMarkers).
        markerSightings = detector.markerSightings(),
    )

    // Said once per marker per MARKER_ANNOUNCE_INTERVAL_MS: seeing it again a second later isn't news.
    private val markersAnnouncedAtMs = HashMap<String, Long>()

    private val orchestrator: NavigationOrchestrator = run {
        val loggedPositioning = object : PositioningEngine {
            override fun positionUpdates() = positioningEngine.updates()
                .onEach { update ->
                    update.rawPdr?.let { recorder?.record(PositionEvent.of(PositionStream.PDR, it)) }
                    update.fingerprintFix?.let {
                        _ui.update { ui -> ui.copy(fingerprintCorrections = ui.fingerprintCorrections + 1) }
                        recorder?.record(PositionEvent.of(PositionStream.FINGERPRINT, it))
                    }
                    update.marker?.let(::onMarkerSeen)
                }
                .map { it.position }
        }
        NavigationOrchestrator(
            positioning = loggedPositioning,
            perception = detector,
            map = buildingMap,
            feedback = feedback,
            startNodeId = startNodeId,
            destinationNodeId = destinationNode.id,
            phrases = ResourceGuidancePhrases(appContext),
        )
    }

    private val _ui = MutableStateFlow(NavigationSessionUi(instruction = appContext.getString(R.string.nav_preparing)))
    val ui: StateFlow<NavigationSessionUi> = _ui.asStateFlow()

    private var endReason = "unknown"
    private var closed = false

    private var radiosWereOn = true
    private val radioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onRadioStateChanged()
    }

    internal fun start() {
        recorder?.record(sessionStartEvent())
        radiosWereOn = radiosOn()
        if (radiosWereOn) scanner.start(scanTargets) else announce(R.string.nav_radio_lost)
        ContextCompat.registerReceiver(
            appContext,
            radioReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch { runGuidance() }
        if (recorder != null) scope.launch { logRssi(recorder) }
        if (hasBarometer) scope.launch { watchBarometer() }
        scope.launch { conditions.power().collect { applyCameraPower(CameraPowerPolicy.decide(it.thermalStatus, it.batteryPercent, it.charging)) } }
    }

    private suspend fun runGuidance() {
        var lastLoggedPositionMs = -1L
        var lastPromptedFloorChange: String? = null
        try {
            orchestrator.start().collect { newState ->
                val guidance = newState.alerts.firstOrNull {
                    it.id.startsWith("route-guide-") || it.id.startsWith("arrived-") || it.id.startsWith("no-route-")
                }
                val phase = navigationPhase(newState, destinationNode, destinationFloor)
                val previousPhase = _ui.value.phase
                _ui.update {
                    it.copy(
                        state = newState,
                        phase = phase,
                        instruction = guidance?.message ?: it.instruction,
                        barometerClimbMeters = floorChangeDetector.climbedMeters(),
                    )
                }

                if (recorder != null) {
                    if (phase != previousPhase) recorder.record(StatusEvent(System.currentTimeMillis(), phase.name))
                    // combine() re-emits on every camera frame with the same position; log each position once.
                    if (newState.position.timestampMs != lastLoggedPositionMs) {
                        lastLoggedPositionMs = newState.position.timestampMs
                        recorder.record(PositionEvent.of(PositionStream.FUSED, newState.position))
                    }
                    newState.alerts.forEach { alert ->
                        recorder.record(GuidanceEvent(alert.createdAtMs, alert.id, alert.priority.name, alert.message, alert.cue.name))
                    }
                    val hint = newState.pendingFloorChange
                    if (hint != null && hint.targetNodeId != lastPromptedFloorChange) {
                        recorder.record(FloorChangeEvent(System.currentTimeMillis(), FloorChangePhase.PROMPTED, hint.targetNodeId, hint.targetFloor))
                    }
                    lastPromptedFloorChange = hint?.targetNodeId
                }

                val hint = newState.pendingFloorChange
                if (hint == null) {
                    floorChangeDetector.disarm()
                    barometerConfirmedTargetId = null
                } else if (hasBarometer) {
                    // Don't re-arm for a crossing the barometer already confirmed: the hint
                    // lingers until the next footstep produces a position on the new floor.
                    if (hint.targetNodeId != barometerConfirmedTargetId) {
                        floorChangeDetector.arm(fromFloor = newState.position.floor, toFloor = hint.targetFloor)
                    }
                } else if (hint.autoConfirm) {
                    // No barometer: a STAIRS crossing confirms itself once enough footsteps have
                    // piled up (see NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT) — no tap
                    // needed, which matters since a blind user's hands may well be full on a
                    // staircase. Elevators never set autoConfirm; those wait for the button.
                    confirmFloorChange(hint.targetNodeId, hint.targetFloor, FloorChangePhase.CONFIRMED_AUTOMATICALLY)
                }
            }
        } catch (e: CancellationException) {
            throw e // normal teardown (session stopped) — not a failure
        } catch (e: Exception) {
            // Last-resort safety net: nothing in this pipeline is expected to throw (every
            // known failure mode is already caught and surfaced instead — BleScanState.Failed,
            // CameraXObstacleDetector's onBindFailed, StepDetector.isAvailable gating before
            // this even starts) but an unanticipated one should be said out loud and shown
            // as a recoverable screen, not crash the app mid-walk.
            Log.e(TAG, "Navigation pipeline failed", e)
            val message = appContext.getString(
                R.string.nav_pipeline_error,
                e.message ?: appContext.getString(R.string.nav_pipeline_error_unknown),
            )
            _ui.update { it.copy(pipelineError = message) }
            speech.speakNow(message)
            endReason = "error: ${e.message}"
        }
    }

    private suspend fun logRssi(recorder: FieldLogRecorder) {
        scanner.rawReadingUpdates().collect { reading ->
            // Only beacons the map actually fingerprinted matter for calibration; every phone,
            // watch and headset advertising nearby would otherwise bury them. With no
            // fingerprints yet, keep iBeacons — likely what the next survey will use.
            val relevant = if (fingerprintBeaconIds.isEmpty()) reading.uuid != null else reading.beaconId in fingerprintBeaconIds
            if (relevant) recorder.record(RssiEvent(reading.timestampMs, reading.beaconId, reading.rssi))
        }
    }

    private suspend fun watchBarometer() {
        var lastLoggedAtMs = 0L
        barometer.readings().collect { reading ->
            if (recorder != null && reading.timestampMs - lastLoggedAtMs >= PRESSURE_LOG_INTERVAL_MS) {
                lastLoggedAtMs = reading.timestampMs
                recorder.record(PressureEvent(reading.timestampMs, reading.hPa))
            }
            if (floorChangeDetector.onPressure(reading.hPa, reading.timestampMs)) {
                val current = _ui.value.state ?: return@collect
                val hint = current.pendingFloorChange ?: return@collect
                barometerConfirmedTargetId = hint.targetNodeId
                confirmFloorChange(hint.targetNodeId, hint.targetFloor, FloorChangePhase.CONFIRMED_BY_BAROMETER)
            }
        }
    }

    private fun confirmFloorChange(targetNodeId: String, targetFloor: Int, phase: FloorChangePhase) {
        if (targetNodeId !in allNodesById) return
        positioningEngine.changeFloor(targetNodeId, System.currentTimeMillis())
        recorder?.record(FloorChangeEvent(System.currentTimeMillis(), phase, targetNodeId, targetFloor))
    }

    /** The "Kat değiştirdim" button. */
    fun confirmFloorChangeManually() {
        val current = _ui.value.state ?: return
        val hint = current.pendingFloorChange ?: return
        confirmFloorChange(hint.targetNodeId, hint.targetFloor, FloorChangePhase.CONFIRMED_MANUALLY)
    }

    /**
     * Guidance is spoken once per leg and then only every few seconds; someone who missed
     * it (a noisy corridor, a conversation) shouldn't have to wait. The repeat is re-worded
     * from the live position, so the distance in it is current, not the one from when it
     * was first announced.
     */
    fun repeatInstruction() {
        val current = _ui.value.state
        val fresh = current?.let { orchestrator.currentInstruction(it.position) }
        if (fresh != null) _ui.update { it.copy(instruction = fresh) }
        val remaining = current
            ?.let { routeProgress(it.route, it.nextRouteIndex, it.position, nodeFloor, buildingMap.floorTransitions) }
            ?.takeIf { it.nextNode.id != destinationNode.id }
            ?.metersToDestination?.roundToInt()
            ?.let { appContext.resources.getQuantityString(R.plurals.nav_repeat_remaining, it, it) }
        val text = listOfNotNull(fresh ?: _ui.value.instruction, remaining).joinToString(" ")
        speech.speakNow(text)
        recorder?.record(GuidanceEvent(System.currentTimeMillis(), "repeat-request", "INFO", text))
    }

    /**
     * "What does this say" — reads any text in the camera's current view (a room
     * number, a sign) and speaks it. Works fully offline, no cloud involved. A no-op
     * while a read is already in flight, so an impatient double-tap doesn't queue two.
     */
    fun readSignsAloud() {
        if (_ui.value.readingSigns) return
        val frame = detector.latestFrameOrNull()
        if (frame == null) {
            speakSignReadingResult(appContext.getString(R.string.sign_reading_no_camera))
            return
        }
        _ui.update { it.copy(readingSigns = true) }
        scope.launch {
            val texts = signTextReader.read(frame)
            _ui.update { it.copy(readingSigns = false) }
            val message = if (texts.isEmpty()) appContext.getString(R.string.sign_reading_nothing_found) else texts.joinToString(". ")
            speakSignReadingResult(message)
        }
    }

    private fun speakSignReadingResult(message: String) {
        _ui.update { it.copy(lastSignReading = message) }
        speech.speakNow(message)
        recorder?.record(GuidanceEvent(System.currentTimeMillis(), "sign-reading-${System.nanoTime()}", "INFO", message))
    }

    /**
     * "Bir şey sor" — listens for a single question ("en yakın tuvalet nerede",
     * "asansör nerede") and answers it against this building's own graph
     * ([BuildingQueryMatcher]), entirely offline: recognition is on-device only
     * (see [SpeechToText]), so the voice never leaves the phone. Caller must already hold
     * RECORD_AUDIO; this doesn't request it. A no-op while a question is
     * already being asked.
     */
    fun askQuestion() {
        if (_ui.value.askingQuestion) return
        if (!speechToText.isAvailable()) {
            answerQuestion(appContext.getString(R.string.voice_query_not_available))
            return
        }
        _ui.update { it.copy(askingQuestion = true) }
        speech.speakNow(appContext.getString(R.string.voice_query_listening))
        scope.launch {
            when (val heard = speechToText.listenOnce()) {
                is SpeechRecognitionResult.Recognized -> {
                    val position = _ui.value.state?.position
                    when (val result = BuildingQueryMatcher.match(heard.text, allNodesById.values.toList(), nodeFloor, position)) {
                        is BuildingQueryResult.Found -> {
                            val distanceMeters = result.distanceMeters
                            answerQuestion(
                                if (distanceMeters != null) {
                                    appContext.getString(R.string.voice_query_answer_with_distance, result.node.name, formatMeters(distanceMeters))
                                } else {
                                    appContext.getString(R.string.voice_query_answer_no_distance, result.node.name)
                                },
                            )
                        }
                        BuildingQueryResult.NotUnderstood -> answerQuestion(appContext.getString(R.string.voice_query_not_understood))
                    }
                }
                is SpeechRecognitionResult.Failed -> answerQuestion(
                    appContext.getString(
                        when (heard.reason) {
                            SpeechRecognitionFailure.NOT_AVAILABLE -> R.string.voice_query_not_available
                            SpeechRecognitionFailure.LANGUAGE_DOWNLOADING -> R.string.voice_query_language_downloading
                            SpeechRecognitionFailure.LANGUAGE_NOT_SUPPORTED -> R.string.voice_query_language_not_supported
                            SpeechRecognitionFailure.NO_SPEECH -> R.string.voice_query_no_speech
                            SpeechRecognitionFailure.PERMISSION_DENIED -> R.string.voice_query_permission_denied
                            SpeechRecognitionFailure.OTHER -> R.string.voice_query_error
                        },
                    ),
                )
            }
        }
    }

    private fun formatMeters(distanceMeters: Double): String {
        val rounded = distanceMeters.roundToInt().coerceAtLeast(1)
        return appContext.resources.getQuantityString(R.plurals.say_meters, rounded, rounded)
    }

    private fun answerQuestion(message: String) {
        _ui.update { it.copy(askingQuestion = false, lastQuestionAnswer = message) }
        speech.speakNow(message)
        recorder?.record(GuidanceEvent(System.currentTimeMillis(), "voice-query-${System.nanoTime()}", "INFO", message))
    }

    /** Field-test ground truth (see GroundTruthEvent); null when not recording. */
    fun markGroundTruth(node: MapNode): GroundTruthEvent? {
        val recorder = recorder ?: return null
        val event = GroundTruthEvent.of(System.currentTimeMillis(), node, nodeFloor[node.id] ?: 0, _ui.value.state?.position)
        recorder.record(event)
        return event
    }

    /** Starts obstacle detection; call whenever the navigation screen becomes visible. */
    fun attachCamera(lifecycleOwner: LifecycleOwner) {
        if (closed || cameraAttached) return
        cameraAttached = true
        screenOwner = lifecycleOwner
        // No preview surface — nothing to show for a raw camera feed, and detection runs on
        // ImageAnalysis frames either way. Too hot, it waits until the phone cools down.
        if (cameraPower != CameraPower.OFF) {
            detector.bind(lifecycleOwner)
            watchAim()
        }
        if (cameraPauseAnnounced) announce(R.string.nav_camera_resumed)
    }

    private fun applyCameraPower(power: CameraPower) {
        if (closed || power == cameraPower) return
        val previous = cameraPower
        cameraPower = power
        detector.setFrameInterval(if (power == CameraPower.REDUCED) CameraPowerPolicy.REDUCED_FRAME_INTERVAL_MS else CameraXObstacleDetector.MIN_FRAME_INTERVAL_MS)
        recorder?.record(StatusEvent(System.currentTimeMillis(), "CAMERA_${power.name}"))
        when {
            power == CameraPower.OFF -> {
                if (cameraAttached) {
                    detector.unbind()
                    aimJob?.cancel()
                }
                announce(R.string.nav_camera_hot)
            }
            previous == CameraPower.OFF -> {
                screenOwner?.takeIf { cameraAttached }?.let {
                    detector.bind(it)
                    watchAim()
                }
                announce(R.string.nav_camera_cooled)
            }
            power == CameraPower.REDUCED -> announce(R.string.nav_camera_saving)
        }
    }

    /**
     * While the camera looks, says so when it's aimed at the floor or the ceiling (the
     * phone tipped in a hand or a chest strap), and again every [AIM_REPEAT_MS] it stays so.
     */
    private fun watchAim() {
        aimJob?.cancel()
        val judge = CameraAimJudge()
        var lastToldMs = 0L
        aimJob = scope.launch {
            conditions.gravity().collect { gravity ->
                val aim = judge.update(gravity[1], gravity[2])
                val now = System.currentTimeMillis()
                if (aim != CameraAim.OK && now - lastToldMs >= AIM_REPEAT_MS) {
                    lastToldMs = now
                    announce(if (aim == CameraAim.TOO_LOW) R.string.nav_aim_too_low else R.string.nav_aim_too_high)
                } else if (aim == CameraAim.OK) {
                    lastToldMs = 0L
                }
            }
        }
    }

    /**
     * Stops obstacle detection (screen off, app in the background, another tab) and says so:
     * positioning and guidance carry on, but the user shouldn't believe they're still being
     * warned about people ahead when they aren't.
     */
    fun detachCamera() {
        if (closed || !cameraAttached) return
        cameraAttached = false
        screenOwner = null
        aimJob?.cancel()
        detector.unbind()
        announce(if (cameraPauseAnnounced) R.string.nav_camera_paused else R.string.nav_camera_paused_first)
        cameraPauseAnnounced = true
    }

    private fun radiosOn(): Boolean {
        val bluetoothOn = appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        val locationOn = appContext.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) == true
        return bluetoothOn && locationOn
    }

    // Bluetooth or Location switched off mid-walk silently stops fingerprint corrections;
    // say so, and pick scanning back up by itself once they're on again.
    private fun onRadioStateChanged() {
        if (closed) return
        val on = radiosOn()
        if (on == radiosWereOn) return
        radiosWereOn = on
        if (on) {
            scanner.start(scanTargets)
            announce(R.string.nav_radio_back)
        } else {
            scanner.stop()
            announce(R.string.nav_radio_lost)
        }
    }

    private fun onMarkerSeen(sighting: MarkerSighting) {
        val place = allNodesById[sighting.nodeId] ?: return
        val now = sighting.timestampMs
        if (now - (markersAnnouncedAtMs[place.id] ?: Long.MIN_VALUE / 2) < MARKER_ANNOUNCE_INTERVAL_MS) return
        markersAnnouncedAtMs[place.id] = now
        val message = appContext.getString(R.string.nav_marker_seen, place.name)
        speech.emit(NavigationAlert(id = "marker-${place.id}-$now", priority = AlertPriority.INFO, message = message, relatedObstacle = null, createdAtMs = now))
        recorder?.record(GuidanceEvent(now, "marker-${place.id}", "INFO", message))
    }

    private fun announce(messageRes: Int) {
        speech.emit(
            NavigationAlert(
                id = "system-${System.nanoTime()}",
                priority = AlertPriority.WARNING,
                message = appContext.getString(messageRes),
                relatedObstacle = null,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Ends the session and releases everything it holds. Safe to call more than once. */
    fun stop(reason: String = if (_ui.value.phase == NavigationPhase.ARRIVED) "arrived" else "stopped") {
        if (closed) return
        closed = true
        if (!endReason.startsWith("error")) endReason = reason
        runCatching { appContext.unregisterReceiver(radioReceiver) }
        scope.cancel()
        scanner.stop()
        detector.release()
        speech.shutdown()
        haptics.stop()
        (signTextReader as? MlKitSignTextReader)?.close()
        recorder?.record(SessionEndEvent(System.currentTimeMillis(), endReason))
        recorder?.close()
        onClosed(this)
    }

    private fun sessionStartEvent(): FieldLogEvent {
        val appVersion = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull()
        return SessionStartEvent(
            t = System.currentTimeMillis(),
            appVersion = appVersion ?: "?",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidSdk = Build.VERSION.SDK_INT,
            buildingId = buildingMap.id,
            startNodeId = startNodeId,
            destinationNodeId = destinationNode.id,
            // Everything that was guessed rather than measured, so offline analysis knows
            // exactly which values produced this walk.
            constants = mapOf(
                "stepLengthMetersInUse" to stepLengthMeters,
                "StepDetector.AVERAGE_STEP_LENGTH_METERS" to StepDetector.AVERAGE_STEP_LENGTH_METERS,
                "StepDetector.STEP_RISE_THRESHOLD_MPS2" to StepDetector.STEP_RISE_THRESHOLD_MPS2,
                "StepDetector.STEP_FALL_THRESHOLD_MPS2" to StepDetector.STEP_FALL_THRESHOLD_MPS2,
                "StepDetector.MIN_STEP_INTERVAL_MS" to StepDetector.MIN_STEP_INTERVAL_MS.toDouble(),
                "StepDetector.BASELINE_TIME_CONSTANT_SECONDS" to StepDetector.BASELINE_TIME_CONSTANT_SECONDS,
                "FingerprintMatcher.MIN_SHARED_BEACONS" to FingerprintMatcher.MIN_SHARED_BEACONS.toDouble(),
                "FingerprintMatcher.MAX_MATCH_DISTANCE" to FingerprintMatcher.MAX_MATCH_DISTANCE,
                "FingerprintMatcher.AMBIGUITY_MARGIN" to FingerprintMatcher.AMBIGUITY_MARGIN,
                "FingerprintMatcher.MISSING_BEACON_RSSI" to FingerprintMatcher.MISSING_BEACON_RSSI,
                "GraphParticleFilter.particleCount" to particleConfig.particleCount.toDouble(),
                "GraphParticleFilter.stepLengthNoiseFraction" to particleConfig.stepLengthNoiseFraction,
                "GraphParticleFilter.stepScaleSpread" to particleConfig.stepScaleSpread,
                "GraphParticleFilter.headingNoiseDeg" to particleConfig.headingNoiseDeg,
                "GraphParticleFilter.headingOffsetDriftDeg" to particleConfig.headingOffsetDriftDeg,
                "GraphParticleFilter.edgeAlignmentSigmaDeg" to particleConfig.edgeAlignmentSigmaDeg,
                "GraphParticleFilter.waitAtNodeLikelihood" to particleConfig.waitAtNodeLikelihood,
                "GraphParticleFilter.fingerprintSigmaMeters" to particleConfig.fingerprintSigmaMeters,
                "GraphParticleFilter.minFingerprintIntervalMs" to particleConfig.minFingerprintIntervalMs.toDouble(),
                "GraphParticleFilter.reseedDistanceMeters" to particleConfig.reseedDistanceMeters,
                "GraphParticleFilter.reseedFraction" to particleConfig.reseedFraction,
                "GraphParticleFilter.MAX_TURN_AT_NODE_DEG" to GraphParticleFilter.MAX_TURN_AT_NODE_DEG,
                "NavigationOrchestrator.WAYPOINT_ARRIVAL_METERS" to NavigationOrchestrator.WAYPOINT_ARRIVAL_METERS,
                "NavigationOrchestrator.ARRIVAL_DISTANCE_METERS" to NavigationOrchestrator.ARRIVAL_DISTANCE_METERS,
                "NavigationOrchestrator.TURN_ANGLE_THRESHOLD_DEG" to NavigationOrchestrator.TURN_ANGLE_THRESHOLD_DEG,
                "NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT" to NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT.toDouble(),
                "NavigationOrchestrator.CRITICAL_DISTANCE_METERS" to NavigationOrchestrator.CRITICAL_DISTANCE_METERS,
                "NavigationOrchestrator.WARNING_DISTANCE_METERS" to NavigationOrchestrator.WARNING_DISTANCE_METERS,
                "BeaconMapBuilder.FLOOR_TRANSITION_COST_METERS" to BeaconMapBuilder.FLOOR_TRANSITION_COST_METERS,
                "hasBarometer" to if (hasBarometer) 1.0 else 0.0,
                "bleScanFiltered" to if (scanTargets != null) 1.0 else 0.0,
                "hapticsEnabled" to if (settings.hapticsEnabled) 1.0 else 0.0,
                "speechRate" to settings.speechRate.toDouble(),
                "keepScreenOn" to if (settings.keepScreenOnDuringNavigation) 1.0 else 0.0,
                "BarometricFloorChangeDetector.DEFAULT_FLOOR_HEIGHT_METERS" to BarometricFloorChangeDetector.DEFAULT_FLOOR_HEIGHT_METERS,
                "BarometricFloorChangeDetector.CONFIRM_FRACTION" to BarometricFloorChangeDetector.CONFIRM_FRACTION,
                "BarometricFloorChangeDetector.SETTLED_VERTICAL_SPEED_MPS" to BarometricFloorChangeDetector.SETTLED_VERTICAL_SPEED_MPS,
                "CameraXObstacleDetector.NUM_THREADS" to CameraXObstacleDetector.NUM_THREADS.toDouble(),
                "CameraXObstacleDetector.MIN_FRAME_INTERVAL_MS" to CameraXObstacleDetector.MIN_FRAME_INTERVAL_MS.toDouble(),
            ) + listOfNotNull(
                // Omitted, not NaN, for maps saved before this was tracked.
                buildingMap.surveyStepLengthMeters?.let { "map.surveyStepLengthMeters" to it },
            ),
            map = buildingMap,
        )
    }

    private companion object {
        const val TAG = "NavigationSession"
        const val PRESSURE_LOG_INTERVAL_MS = 1_000L
        const val MARKER_ANNOUNCE_INTERVAL_MS = 30_000L
        const val AIM_REPEAT_MS = 15_000L
    }
}

/**
 * The single active [NavigationSession], if any — process-wide, so it survives
 * the screen that started it and whichever screen (or notification) shows it
 * next. Main-thread only.
 *
 * A walk deliberately isn't owned by a ViewModel: it has to keep going with
 * the screen off and the activity gone (see [NavigationService]). ViewModels
 * observe [active] instead.
 */
interface NavigationSessions {
    val active: StateFlow<NavigationSession?>

    /** Starts a walk, ending any walk already in progress. */
    fun start(map: BuildingMap, startNodeId: String, destinationNode: MapNode)
}

@Singleton
class NavigationSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: UserSettings,
    private val fieldLogs: FieldLogFiles,
) : NavigationSessions {
    private val _active = MutableStateFlow<NavigationSession?>(null)
    override val active: StateFlow<NavigationSession?> = _active.asStateFlow()

    override fun start(map: BuildingMap, startNodeId: String, destinationNode: MapNode) {
        _active.value?.stop("replaced")
        val session = NavigationSession(context, map, startNodeId, destinationNode, settings, fieldLogs, onClosed = { closed ->
            if (_active.value === closed) _active.value = null
            NavigationService.stop(context)
        })
        _active.value = session
        session.start()
        NavigationService.start(context)
    }
}
