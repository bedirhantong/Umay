package com.appvalence.umay.testing

import android.app.Activity
import com.appvalence.umay.cloud.ConnectivityMonitor
import com.appvalence.umay.cloud.PublicBuildingSummary
import com.appvalence.umay.cloud.RemoteBuildingMapRepository
import com.appvalence.umay.cloud.RemoteMapException
import com.appvalence.umay.cloud.RemoteMapFailure
import com.appvalence.umay.cloudmap.PublishedBuildings
import com.appvalence.umay.common.DeviceCapabilities
import com.appvalence.umay.common.MotionSensors
import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.diagnostics.CrashReports
import com.appvalence.umay.feedback.HapticPattern
import com.appvalence.umay.feedback.HapticPlayer
import com.appvalence.umay.feedback.Speaker
import com.appvalence.umay.feedback.VoiceStatus
import com.appvalence.umay.fieldlog.FieldLogFiles
import com.appvalence.umay.navigation.NavigationSession
import com.appvalence.umay.navigation.NavigationSessions
import com.appvalence.umay.positioning.baro.PressureReading
import com.appvalence.umay.positioning.ble.BeaconScanner
import com.appvalence.umay.positioning.ble.BleScanState
import com.appvalence.umay.positioning.ble.ScanTarget
import com.appvalence.umay.settings.UserSettings
import com.appvalence.umay.storage.BuildingMapStore
import com.appvalence.umay.survey.SurveyAnnouncer
import com.appvalence.umay.survey.ar.ArAvailability
import com.appvalence.umay.survey.ar.ArCameraStatus
import com.appvalence.umay.survey.ar.ArFrameSample
import com.appvalence.umay.survey.ar.ArInstallResult
import com.appvalence.umay.survey.ar.ArSurveyCamera
import com.appvalence.umay.survey.ar.ArTrackingProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** Runs viewModelScope (Dispatchers.Main) on a test dispatcher for the duration of a test. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = kotlinx.coroutines.Dispatchers.resetMain()
}

/** A small two-node, one-floor building: "A" (id a) to "B" (id b), 5 m apart. */
fun building(id: String, nodes: List<MapNode> = listOf(node("a", 0.0), node("b", 5.0))) = BuildingMap(
    id = id,
    floors = listOf(FloorMap(floorIndex = 0, nodes = nodes, edges = nodes.zipWithNext { from, to -> MapEdge(from.id, to.id, true, to.x - from.x) })),
)

fun node(id: String, x: Double) = MapNode(id = id, x = x, y = 0.0, type = NodeType.WAYPOINT, name = id.uppercase())

class FakeBuildingMapStore(initial: List<BuildingMap> = emptyList()) : BuildingMapStore {
    val files = initial.associateBy { it.id }.toMutableMap()
    var listCalls = 0
    var failWrites = false
    var exportFile: File? = null

    override fun listBuildings(): List<BuildingMap> {
        listCalls++
        return files.values.sortedBy { it.id }
    }

    override fun save(map: BuildingMap): Boolean {
        if (failWrites) return false
        files[map.id] = map
        return true
    }

    override fun exists(buildingId: String) = buildingId in files
    override fun delete(buildingId: String) = files.remove(buildingId) != null
    override fun exportCopy(map: BuildingMap): File? = exportFile
}

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    var stopCount = 0
    var refreshCount = 0
    override val voiceStatus = MutableStateFlow(VoiceStatus.READY)
    override var speechRate: Float = 1f

    override fun speakNow(text: String) {
        spoken += text
    }

    override fun stop() {
        stopCount++
    }

    override fun refreshVoice() {
        refreshCount++
    }
}

class FakeHapticPlayer(override val isAvailable: Boolean = true) : HapticPlayer {
    val played = mutableListOf<HapticPattern>()
    var stopped = false

    override fun play(pattern: HapticPattern) {
        played += pattern
    }

    override fun stop() {
        stopped = true
    }
}

class FakeUserSettings(
    override var calibratedStepLengthMeters: Double? = null,
    override var hapticsEnabled: Boolean = true,
    override var speechRate: Float = 1f,
    override var keepScreenOnDuringNavigation: Boolean = false,
    override var onboardingCompleted: Boolean = false,
) : UserSettings

class FakeFieldLogFiles(var files: MutableList<File> = mutableListOf()) : FieldLogFiles {
    override var isRecordingEnabled: Boolean = false
    override fun listLogs(): List<File> = files.toList()
    override fun delete(file: File): Boolean = files.remove(file)
    override fun deleteAll() = files.clear()
    override fun newSessionFile(buildingId: String, nowMs: Long): File = File("$buildingId-$nowMs.jsonl")
}

class FakeCrashReports(var files: MutableList<File> = mutableListOf()) : CrashReports {
    override fun list(): List<File> = files.toList()
    override fun delete(file: File): Boolean = files.remove(file)
}

class FakeCapabilities(override val hasStepSensors: Boolean = true, override val hasBarometer: Boolean = false) : DeviceCapabilities

class FakeMotionSensors : MotionSensors {
    val steps = MutableSharedFlow<StepEvent>(extraBufferCapacity = 64)
    val pressure = MutableSharedFlow<PressureReading>(extraBufferCapacity = 64)
    override fun steps(stepLengthMeters: Double): Flow<StepEvent> = steps
    override fun pressure(): Flow<PressureReading> = pressure
}

class FakeBeaconScanner : BeaconScanner {
    val beacons = MutableStateFlow<List<BleBeaconReading>>(emptyList())
    val raw = MutableSharedFlow<BleBeaconReading>(extraBufferCapacity = 64)
    val state = MutableStateFlow<BleScanState>(BleScanState.Idle)
    var started = false

    override fun beaconUpdates(): Flow<List<BleBeaconReading>> = beacons
    override fun rawReadingUpdates(): Flow<BleBeaconReading> = raw
    override fun scanState(): Flow<BleScanState> = state

    override fun start(targets: List<ScanTarget>?) {
        started = true
        state.value = BleScanState.Scanning
    }

    override fun stop() {
        started = false
        state.value = BleScanState.Idle
    }
}

class FakeSurveyAnnouncer : SurveyAnnouncer {
    val floors = mutableListOf<Int>()
    val cameraLosses = mutableListOf<ArTrackingProblem>()
    var cameraBacks = 0
    override fun floorChanged(floor: Int) {
        floors += floor
    }
    override fun cameraLost(problem: ArTrackingProblem) {
        cameraLosses += problem
    }
    override fun cameraBack() {
        cameraBacks++
    }
}

class FakeArSurveyCamera(availability: ArAvailability = ArAvailability.READY) : ArSurveyCamera {
    override val availability = MutableStateFlow(availability)
    override val supportsDepth = MutableStateFlow<Boolean?>(null)
    override val status = MutableStateFlow<ArCameraStatus>(ArCameraStatus.Off)
    override val frames = MutableSharedFlow<ArFrameSample>(extraBufferCapacity = 64)
    var resumed = false
    var closed = 0
    var installResult = ArInstallResult.INSTALLED

    override fun checkAvailability() = Unit
    override fun requestInstall(activity: Activity, userRequested: Boolean) = installResult
    override fun resume() {
        resumed = true
    }
    override fun pause() {
        resumed = false
    }
    override fun close() {
        resumed = false
        closed++
    }
}

class FakePublishedBuildings(initial: Map<String, String> = emptyMap()) : PublishedBuildings {
    val remoteIds = initial.toMutableMap()
    override fun remoteIdFor(localBuildingId: String): String? = remoteIds[localBuildingId]
    override fun markPublished(localBuildingId: String, remoteId: String) {
        remoteIds[localBuildingId] = remoteId
    }

    override fun markUnpublished(localBuildingId: String) {
        remoteIds.remove(localBuildingId)
    }

    override fun publishedLocalIds(): Set<String> = remoteIds.keys.toSet()
}

class FakeConnectivityMonitor(online: Boolean = true) : ConnectivityMonitor {
    override val isOnline = MutableStateFlow(online)
}

class FakeRemoteRepository : RemoteBuildingMapRepository {
    var listing: Result<List<PublicBuildingSummary>> = Result.success(emptyList())
    val maps = mutableMapOf<String, BuildingMap>()
    var failure: RemoteMapFailure? = null
    var listCalls = 0
    val published = mutableListOf<BuildingMap>()
    val unpublished = mutableListOf<String>()

    override suspend fun listPublicBuildings(): Result<List<PublicBuildingSummary>> {
        listCalls++
        return listing
    }

    override suspend fun downloadBuildingMap(id: String): Result<BuildingMap> =
        failure?.let { Result.failure(RemoteMapException(it)) } ?: maps[id]?.let { Result.success(it) } ?: Result.failure(RemoteMapException(RemoteMapFailure.NotFound))

    override suspend fun publishBuildingMap(map: BuildingMap): Result<String> {
        failure?.let { return Result.failure(RemoteMapException(it)) }
        published += map
        return Result.success("remote-${map.id}")
    }

    override suspend fun unpublishBuildingMap(id: String): Result<Unit> {
        failure?.let { return Result.failure(RemoteMapException(it)) }
        unpublished += id
        return Result.success(Unit)
    }
}

fun summary(id: String, name: String = id) = PublicBuildingSummary(id, name, floorCount = 1, nodeCount = 2, sizeBytes = 100, updatedAtMs = 0)

class FakeNavigationSessions : NavigationSessions {
    data class Start(val map: BuildingMap, val startNodeId: String, val destinationNodeId: String)

    override val active: StateFlow<NavigationSession?> = MutableStateFlow(null)
    val starts = mutableListOf<Start>()

    override fun start(map: BuildingMap, startNodeId: String, destinationNode: MapNode) {
        starts += Start(map, startNodeId, destinationNode.id)
    }
}
