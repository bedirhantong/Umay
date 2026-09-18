package com.appvalence.umay.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.appvalence.umay.common.PermissionRationale
import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FieldLogEvent
import com.appvalence.umay.coremodel.FloorChangeEvent
import com.appvalence.umay.coremodel.FloorChangePhase
import com.appvalence.umay.coremodel.GroundTruthEvent
import com.appvalence.umay.coremodel.GuidanceEvent
import com.appvalence.umay.coremodel.PositionEvent
import com.appvalence.umay.coremodel.PositionStream
import com.appvalence.umay.coremodel.RssiEvent
import com.appvalence.umay.coremodel.SessionEndEvent
import com.appvalence.umay.coremodel.SessionStartEvent
import com.appvalence.umay.coremodel.StatusEvent
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.fieldlog.FieldLogRecorder
import com.appvalence.umay.fieldlog.FieldLogStore
import com.appvalence.umay.files.rememberBuildingImporter
import com.appvalence.umay.orchestrator.NavigationOrchestrator
import com.appvalence.umay.orchestrator.NavigationState
import com.appvalence.umay.perception.CameraXObstacleDetector
import com.appvalence.umay.positioning.ble.BleBeaconScanner
import com.appvalence.umay.positioning.engine.FingerprintPositioningEngine
import com.appvalence.umay.positioning.engine.PdrPositioningEngine
import com.appvalence.umay.positioning.engine.PositionFusionEngine
import com.appvalence.umay.positioning.engine.PositioningEngine
import com.appvalence.umay.positioning.fingerprint.FingerprintMatcher
import com.appvalence.umay.positioning.pdr.PdrIntegrator
import com.appvalence.umay.positioning.pdr.StepDetector
import com.appvalence.umay.positioning.trilateration.BeaconMapBuilder
import com.appvalence.umay.storage.BuildingMapStorage
import com.appvalence.umay.survey.round1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onEach
import kotlin.math.hypot
import kotlin.math.roundToInt

private val NAVIGATION_PERMISSIONS = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
}.toTypedArray()

private const val ARRIVAL_DISTANCE_METERS = 1.5

/**
 * Faz 3 end-to-end screen: loads the [BuildingMap] saved from the beacon
 * survey (see [BuildingMapStorage], [BeaconScannerScreen]), lets the
 * operator pick a start/destination point by name from *any* floor, then
 * runs the real [NavigationOrchestrator] — live position fused from
 * continuous PDR and sparse RSSI-fingerprint corrections (see
 * [PositionFusionEngine]), camera obstacle detection, A* routing across
 * floors with obstacle-triggered rerouting, TTS alerts — and presents it the
 * way a consumer navigation app would: one big status message people can
 * read at a glance, with the technical detail (raw coordinates, node ids)
 * folded away rather than in their face.
 */
@Composable
fun NavigationScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    var hasPermissions by remember {
        mutableStateOf(NAVIGATION_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    var rationaleShown by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasPermissions = grants.values.all { it }
    }

    var buildings by remember { mutableStateOf(BuildingMapStorage(context).listBuildings()) }
    var selectedBuildingId by remember { mutableStateOf<String?>(null) }
    val importBuilding = rememberBuildingImporter(onImported = { buildings = BuildingMapStorage(context).listBuildings() })

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (!hasPermissions) {
            if (!rationaleShown) {
                PermissionRationale(
                    icon = Icons.Filled.LocationSearching,
                    title = "İzin gerekiyor",
                    message = "Nerede olduğunu bulmak için konum ve Bluetooth'a, önündeki engelleri görmek için " +
                        "kameraya ihtiyacım var. Bu bilgiler cihazından dışarı çıkmaz.",
                    onContinue = {
                        rationaleShown = true
                        permissionLauncher.launch(NAVIGATION_PERMISSIONS)
                    },
                )
            } else {
                EmptyState(
                    icon = Icons.Filled.LocationSearching,
                    title = "İzin verilmedi",
                    message = "Yönlendirme için kamera, konum ve Bluetooth izni gerekiyor.",
                )
                TextButton(modifier = Modifier.padding(top = 8.dp), onClick = { permissionLauncher.launch(NAVIGATION_PERMISSIONS) }) {
                    Text("Tekrar dene")
                }
            }
            return@Column
        }

        if (!remember { StepDetector.isAvailable(context) }) {
            EmptyState(
                icon = Icons.Filled.LocationSearching,
                title = "Cihaz desteklenmiyor",
                message = "Bu cihazda konum takibi için gereken ivmeölçer veya jiroskop sensörü yok, navigasyon kullanılamıyor.",
            )
            return@Column
        }

        if (buildings.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Map,
                title = "Henüz bir harita yok",
                message = "Önce \"Beacon Tarayıcı\" ekranında bir saha kaydı yapıp haritayı kaydet, " +
                    "ya da başka bir cihazdan dışa aktarılmış bir haritayı içe aktar.",
                modifier = Modifier.weight(1f),
            )
            Button(modifier = Modifier.fillMaxWidth(), onClick = importBuilding) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Text("Haritayı içe aktar", modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                onClick = { buildings = BuildingMapStorage(context).listBuildings() },
            ) {
                Text("Yeniden dene")
            }
            return@Column
        }

        // Skip the picker entirely when there's only one building — that's the common
        // case, and making someone choose from a list of one is pure friction.
        val map = if (buildings.size == 1) buildings.single() else buildings.firstOrNull { it.id == selectedBuildingId }
        if (map == null) {
            BuildingPicker(
                buildings = buildings,
                onSelect = { selectedBuildingId = it.id },
                onDelete = { building ->
                    BuildingMapStorage(context).delete(building.id)
                    buildings = BuildingMapStorage(context).listBuildings()
                },
            )
            return@Column
        }

        val allNodes = map.floors.flatMap { it.nodes }
        if (allNodes.isEmpty()) {
            EmptyState(icon = Icons.Filled.Map, title = "Harita boş", message = "Kaydedilmiş haritada hiç nokta yok.")
            return@Column
        }
        val nodeFloor = remember(map) { map.floors.flatMap { floor -> floor.nodes.map { it.id to floor.floorIndex } }.toMap() }
        val showFloorInLabels = map.floors.size > 1

        var startIndex by remember(map.id) { mutableIntStateOf(0) }
        var destinationIndex by remember(map.id) { mutableIntStateOf((allNodes.size - 1).coerceAtLeast(0)) }
        var isNavigating by remember(map.id) { mutableStateOf(false) }

        if (!isNavigating) {
            if (buildings.size > 1) {
                TextButton(modifier = Modifier.padding(bottom = 8.dp), onClick = { selectedBuildingId = null }) {
                    Text("Bina değiştir (şu an: ${map.id})")
                }
            }
            RoutePicker(
                nodes = allNodes,
                nodeFloor = nodeFloor,
                showFloor = showFloorInLabels,
                startIndex = startIndex,
                destinationIndex = destinationIndex,
                onStartChange = { startIndex = it },
                onDestinationChange = { destinationIndex = it },
                onBegin = { isNavigating = true },
            )
            if (remember { FieldLogStore(context).isRecordingEnabled }) {
                RecordingIndicator(
                    text = "Saha kaydı açık: bu yönlendirme kaydedilecek. \"Dosyalar\" sekmesinden kapatabilir ya da paylaşabilirsin.",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            ActiveNavigation(
                context = context,
                lifecycleOwner = lifecycleOwner,
                buildingMap = map,
                nodeFloor = nodeFloor,
                startNodeId = allNodes[startIndex].id,
                destinationNode = allNodes[destinationIndex],
                onStop = { isNavigating = false },
            )
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Text(text = title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun BuildingPicker(buildings: List<BuildingMap>, onSelect: (BuildingMap) -> Unit, onDelete: (BuildingMap) -> Unit) {
    var pendingDelete by remember { mutableStateOf<BuildingMap?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = "Hangi binada gitmek istiyorsun?", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Birden fazla saha kaydın var — birini seç.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        buildings.forEach { building ->
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).clickable { onSelect(building) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(text = building.id, style = MaterialTheme.typography.titleMedium)
                        val nodeCount = building.floors.sumOf { it.nodes.size }
                        Text(
                            text = "${building.floors.size} kat, $nodeCount nokta",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { pendingDelete = building }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "${building.id} binasını sil")
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("'${toDelete.id}' silinsin mi?") },
            text = { Text("Bu binanın haritası kalıcı olarak silinecek. Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(toDelete)
                    pendingDelete = null
                }) {
                    Text("Sil", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Vazgeç") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutePicker(
    nodes: List<MapNode>,
    nodeFloor: Map<String, Int>,
    showFloor: Boolean,
    startIndex: Int,
    destinationIndex: Int,
    onStartChange: (Int) -> Unit,
    onDestinationChange: (Int) -> Unit,
    onBegin: () -> Unit,
) {
    Text(text = "Nereye gitmek istiyorsun?", style = MaterialTheme.typography.headlineSmall)
    Text(
        text = "Bulunduğun noktayı ve gitmek istediğin yeri seç.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
    NodeDropdown(
        label = "Bulunduğum yer",
        icon = Icons.Filled.LocationSearching,
        nodes = nodes,
        nodeFloor = nodeFloor,
        showFloor = showFloor,
        selectedIndex = startIndex,
        onIndexChange = onStartChange,
    )
    NodeDropdown(
        label = "Gitmek istediğim yer",
        icon = Icons.Filled.Flag,
        nodes = nodes,
        nodeFloor = nodeFloor,
        showFloor = showFloor,
        selectedIndex = destinationIndex,
        onIndexChange = onDestinationChange,
        modifier = Modifier.padding(top = 12.dp),
    )
    Button(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        enabled = startIndex != destinationIndex,
        onClick = onBegin,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Text(text = "Yönlendirmeyi başlat", modifier = Modifier.padding(start = 8.dp))
    }
}

private fun nodeLabel(node: MapNode, nodeFloor: Map<String, Int>, showFloor: Boolean): String =
    if (showFloor) "${node.name} (Kat ${nodeFloor[node.id] ?: 0})" else node.name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeDropdown(
    label: String,
    icon: ImageVector,
    nodes: List<MapNode>,
    nodeFloor: Map<String, Int>,
    showFloor: Boolean,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = nodeLabel(nodes[selectedIndex], nodeFloor, showFloor),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.exposedDropdownSize()) {
            nodes.forEachIndexed { index, node ->
                DropdownMenuItem(
                    text = { Text(nodeLabel(node, nodeFloor, showFloor)) },
                    onClick = {
                        onIndexChange(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

private enum class NavigationStatus(val label: String, val containerColor: @Composable () -> Color, val icon: ImageVector) {
    SEARCHING("Konumun aranıyor...", { MaterialTheme.colorScheme.surfaceVariant }, Icons.Filled.LocationSearching),
    ON_ROUTE("Yoldasın", { MaterialTheme.colorScheme.primaryContainer }, Icons.Filled.CheckCircle),
    WARNING("Dikkat, yakında bir engel var", { Color(0xFFFFE0B2) }, Icons.Filled.Warning),
    CRITICAL("DUR! Hemen önünde bir engel var", { MaterialTheme.colorScheme.errorContainer }, Icons.Filled.Warning),
    ARRIVED("Hedefe ulaştın", { Color(0xFFC8E6C9) }, Icons.Filled.CheckCircle),
}

@Composable
private fun ActiveNavigation(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    buildingMap: BuildingMap,
    nodeFloor: Map<String, Int>,
    startNodeId: String,
    destinationNode: MapNode,
    onStop: () -> Unit,
) {
    val scanner = remember { BleBeaconScanner(context) }
    val feedback = remember { TtsFeedbackController(context) }
    var cameraWarning by remember { mutableStateOf<String?>(null) }
    val detector = remember { CameraXObstacleDetector(context, onBindFailed = { cameraWarning = it }) }
    val stepDetector = remember { StepDetector(context) }
    val allNodes = remember(buildingMap) { buildingMap.floors.flatMap { it.nodes } }
    val allNodesById = remember(allNodes) { allNodes.associateBy { it.id } }
    val startFloorIndex = remember { nodeFloor[startNodeId] ?: 0 }

    var fingerprintCorrectionCount by remember { mutableIntStateOf(0) }

    // Field-test log for this one session (see FieldLogRecorder) — null unless recording
    // was switched on in "Dosyalar". Everything below records through `recorder?.`, so
    // with recording off none of this costs anything.
    val recorder = remember {
        val store = FieldLogStore(context)
        if (store.isRecordingEnabled) FieldLogRecorder(store.newSessionFile(buildingMap.id)) else null
    }
    val sessionEnd = remember { SessionEndReason() }
    // Only beacons the map actually fingerprinted matter for calibration; every phone,
    // watch and headset advertising nearby would otherwise bury them. With no
    // fingerprints yet, keep iBeacons — likely what the next survey will use.
    val fingerprintBeaconIds = remember(allNodes) { allNodes.flatMap { it.fingerprint }.map { it.beaconId }.toSet() }

    // Hoisted out of the orchestrator's own remember block so the "Kat değiştirdim"
    // button below can reset it directly when the user confirms a floor crossing —
    // there's no barometer to detect that automatically (see NavigationOrchestrator's
    // pendingFloorChange).
    val pdrEngine = remember {
        val startNode = allNodesById.getValue(startNodeId)
        PdrPositioningEngine(stepDetector = stepDetector, startX = startNode.x, startY = startNode.y, floor = startFloorIndex)
    }

    val orchestrator = remember {
        // RSSI-to-distance trilateration is too noisy in real buildings to trust as
        // the primary signal (see BeaconMapBuilder's field notes); instead we walk
        // continuously via PDR and only correct against named waypoints' recorded
        // RSSI fingerprints (see FingerprintMatcher) — the RF-engineering-standard
        // approach for indoor radio positioning.
        val fingerprintEngine = FingerprintPositioningEngine(
            beaconReadings = scanner.beaconUpdates(),
            waypoints = allNodes.filter { it.fingerprint.isNotEmpty() },
            floor = startFloorIndex,
        )
        val fusion = PositionFusionEngine(pdrResettable = pdrEngine)
        val fusedPositioning = object : PositioningEngine {
            override fun positionUpdates() = fusion.fuse(
                fingerprintEngine.positionUpdates().onEach {
                    fingerprintCorrectionCount++
                    recorder?.record(PositionEvent.of(PositionStream.FINGERPRINT, it))
                },
                pdrEngine.positionUpdates().onEach { recorder?.record(PositionEvent.of(PositionStream.PDR, it)) },
            )
        }
        NavigationOrchestrator(
            positioning = fusedPositioning,
            perception = detector,
            map = buildingMap,
            feedback = feedback,
            startNodeId = startNodeId,
            destinationNodeId = destinationNode.id,
        )
    }

    DisposableEffect(Unit) {
        recorder?.record(sessionStartEvent(context, buildingMap, startNodeId, destinationNode.id))
        scanner.start()
        // No preview surface — nothing to show for a raw camera feed here, and CameraX's
        // preview surface (SurfaceView-backed) can visually paint over other content on
        // this screen. Obstacle detection runs on ImageAnalysis frames either way.
        detector.bind(lifecycleOwner)
        onDispose {
            scanner.stop()
            detector.unbind()
            feedback.shutdown()
            recorder?.record(SessionEndEvent(System.currentTimeMillis(), sessionEnd.reason))
            recorder?.close()
        }
    }

    if (recorder != null) {
        LaunchedEffect(recorder) {
            scanner.rawReadingUpdates().collect { reading ->
                val relevant = if (fingerprintBeaconIds.isEmpty()) reading.uuid != null else reading.beaconId in fingerprintBeaconIds
                if (relevant) recorder.record(RssiEvent(reading.timestampMs, reading.beaconId, reading.rssi))
            }
        }
    }

    fun confirmFloorChange(targetNodeId: String, targetFloor: Int, headingDeg: Double, phase: FloorChangePhase) {
        val targetNode = allNodesById[targetNodeId] ?: return
        pdrEngine.changeFloor(newFloor = targetFloor, x = targetNode.x, y = targetNode.y, headingDeg = headingDeg)
        recorder?.record(FloorChangeEvent(System.currentTimeMillis(), phase, targetNodeId, targetFloor))
    }

    var state by remember { mutableStateOf<NavigationState?>(null) }
    var currentInstruction by remember { mutableStateOf("Yola çıkmaya hazırlanıyor...") }
    var pipelineError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(orchestrator) {
        var lastLoggedPositionMs = -1L
        var lastPromptedFloorChange: String? = null
        try {
            orchestrator.start().collect { newState ->
                state = newState
                newState.alerts.firstOrNull { it.id.startsWith("route-guide-") || it.id.startsWith("arrived-") || it.id.startsWith("no-route-") }
                    ?.let { currentInstruction = it.message }

                if (recorder != null) {
                    // combine() re-emits on every camera frame with the same position; log each position once.
                    if (newState.position.timestampMs != lastLoggedPositionMs) {
                        lastLoggedPositionMs = newState.position.timestampMs
                        recorder.record(PositionEvent.of(PositionStream.FUSED, newState.position))
                    }
                    newState.alerts.forEach { alert ->
                        recorder.record(GuidanceEvent(alert.createdAtMs, alert.id, alert.priority.name, alert.message))
                    }
                    val hint = newState.pendingFloorChange
                    if (hint != null && hint.targetNodeId != lastPromptedFloorChange) {
                        recorder.record(FloorChangeEvent(System.currentTimeMillis(), FloorChangePhase.PROMPTED, hint.targetNodeId, hint.targetFloor))
                    }
                    lastPromptedFloorChange = hint?.targetNodeId
                }

                // A STAIRS crossing confirms itself once enough footsteps have piled up
                // (see NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT) — no tap needed,
                // which matters since a blind user's hands may well be full on a staircase.
                // Elevators never set autoConfirm, so those still wait for the button below.
                newState.pendingFloorChange?.let { hint ->
                    if (hint.autoConfirm) {
                        confirmFloorChange(hint.targetNodeId, hint.targetFloor, newState.position.headingDeg, FloorChangePhase.CONFIRMED_AUTOMATICALLY)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e // normal collection teardown (screen left, "Durdur" pressed) — not a failure
        } catch (e: Exception) {
            // Last-resort safety net: nothing in this pipeline is expected to throw (every
            // known failure mode below is already caught and surfaced visibly instead —
            // BleBeaconScanner's BleScanState.Failed, CameraXObstacleDetector's
            // onBindFailed, StepDetector.isAvailable gating steps before this even starts)
            // but an unanticipated one should show a recoverable screen, not crash the app.
            pipelineError = "Navigasyon durduruldu: ${e.message ?: "beklenmeyen bir hata oluştu"}"
            sessionEnd.reason = "error: ${e.message}"
        }
    }

    val currentState = state
    val destinationFloor = remember { nodeFloor[destinationNode.id] ?: 0 }
    val status = currentState.toNavigationStatus(destinationNode, destinationFloor)
    val hasFingerprintedWaypoints = remember(allNodes) { allNodes.any { it.fingerprint.isNotEmpty() } }
    if (recorder != null) {
        LaunchedEffect(status) { recorder.record(StatusEvent(System.currentTimeMillis(), status.name)) }
    }

    val currentFloorIndex = currentState?.position?.floor ?: startFloorIndex
    val currentFloorMap = remember(buildingMap, currentFloorIndex) {
        buildingMap.floors.firstOrNull { it.floorIndex == currentFloorIndex } ?: buildingMap.floors.first()
    }
    val progress = currentState?.let {
        routeProgress(it.route, it.nextRouteIndex, it.position, nodeFloor, buildingMap.floorTransitions)
    }
    val stop = {
        sessionEnd.reason = if (status == NavigationStatus.ARRIVED) "arrived" else "stopped"
        onStop()
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (pipelineError != null) {
            EmptyState(icon = Icons.Filled.Warning, title = "Bir şeyler ters gitti", message = pipelineError.orEmpty())
            Button(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), onClick = stop) {
                Text("Geri dön")
            }
            return@Column
        }

        StatusBanner(status)

        if (!hasFingerprintedWaypoints) {
            Text(
                text = "Bu haritada isimlendirilmiş nokta yok, konum sadece adım sayımıyla tahmin ediliyor ve zamanla sapabilir. " +
                    "Daha güvenilir takip için \"Beacon Tarayıcı\" ekranında yürürken yer isimlendir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Non-fatal: navigation (position, routing, TTS guidance) still works without
        // obstacle detection, so this is a note, not a blocking EmptyState.
        cameraWarning?.let {
            Text(
                text = "$it — engel algılama olmadan devam ediliyor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Text(
            text = currentInstruction,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )

        if (progress != null && status != NavigationStatus.ARRIVED) {
            RouteProgressCard(progress, modifier = Modifier.padding(bottom = 12.dp))
        }

        val pendingFloorChange = currentState?.pendingFloorChange
        if (pendingFloorChange != null) {
            val direction = if (pendingFloorChange.targetFloor > currentFloorIndex) "yukarı" else "aşağı"
            Button(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                onClick = {
                    confirmFloorChange(
                        pendingFloorChange.targetNodeId,
                        pendingFloorChange.targetFloor,
                        currentState.position.headingDeg,
                        FloorChangePhase.CONFIRMED_MANUALLY,
                    )
                },
            ) {
                Icon(Icons.Filled.SwapVert, contentDescription = null)
                Text(text = "Kat değiştirdim ($direction, Kat ${pendingFloorChange.targetFloor})", modifier = Modifier.padding(start = 8.dp))
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = stop,
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Text(text = "Yönlendirmeyi durdur", modifier = Modifier.padding(start = 8.dp))
        }

        if (recorder != null) {
            GroundTruthControls(
                recorder = recorder,
                currentFloorIndex = currentFloorIndex,
                buildingMap = buildingMap,
                nodeFloor = nodeFloor,
                estimate = currentState?.position,
            )
        }

        NavigationMapCanvas(
            floorMap = currentFloorMap,
            floorLabel = "Kat ${currentFloorMap.floorIndex}",
            route = currentState?.route.orEmpty(),
            nextRouteIndex = currentState?.nextRouteIndex ?: 1,
            nodeFloor = nodeFloor,
            position = currentState?.position,
            obstacles = currentState?.obstacles ?: emptyList(),
            description = mapDescription(currentFloorMap.floorIndex, progress, destinationNode, currentState?.obstacles?.size ?: 0),
            modifier = Modifier.padding(top = 16.dp),
        )

        if (currentState != null) {
            TechnicalDetails(
                routeText = currentState.route.joinToString(" → ") { allNodesById[it.id]?.name ?: it.name },
                technicalText = "x=${currentState.position.x.round1()} y=${currentState.position.y.round1()} " +
                    "kat=${currentState.position.floor} doğruluk≈${currentState.position.accuracyMeters.round1()}m " +
                    "kaynak=${currentState.position.source} fingerprint düzeltmesi=$fingerprintCorrectionCount",
            )
        }
    }
}

/** Why a field-log session ended — set by whatever ends it, read once when the screen is disposed. */
private class SessionEndReason {
    var reason: String = "left_screen"
}

private fun sessionStartEvent(context: Context, map: BuildingMap, startNodeId: String, destinationNodeId: String): FieldLogEvent {
    val appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    return SessionStartEvent(
        t = System.currentTimeMillis(),
        appVersion = appVersion ?: "?",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidSdk = Build.VERSION.SDK_INT,
        buildingId = map.id,
        startNodeId = startNodeId,
        destinationNodeId = destinationNodeId,
        // Everything that was guessed rather than measured, so offline analysis knows
        // exactly which values produced this walk.
        constants = mapOf(
            "StepDetector.AVERAGE_STEP_LENGTH_METERS" to StepDetector.AVERAGE_STEP_LENGTH_METERS,
            "StepDetector.STEP_RISE_THRESHOLD_MPS2" to StepDetector.STEP_RISE_THRESHOLD_MPS2,
            "StepDetector.STEP_FALL_THRESHOLD_MPS2" to StepDetector.STEP_FALL_THRESHOLD_MPS2,
            "StepDetector.MIN_STEP_INTERVAL_MS" to StepDetector.MIN_STEP_INTERVAL_MS.toDouble(),
            "StepDetector.BASELINE_TIME_CONSTANT_SECONDS" to StepDetector.BASELINE_TIME_CONSTANT_SECONDS,
            "PdrIntegrator.DEFAULT_DRIFT_PER_STEP_METERS" to PdrIntegrator.DEFAULT_DRIFT_PER_STEP_METERS,
            "FingerprintMatcher.MIN_SHARED_BEACONS" to FingerprintMatcher.MIN_SHARED_BEACONS.toDouble(),
            "FingerprintMatcher.MAX_MATCH_DISTANCE" to FingerprintMatcher.MAX_MATCH_DISTANCE,
            "FingerprintMatcher.AMBIGUITY_MARGIN" to FingerprintMatcher.AMBIGUITY_MARGIN,
            "FingerprintMatcher.MISSING_BEACON_RSSI" to FingerprintMatcher.MISSING_BEACON_RSSI,
            "FingerprintPositioningEngine.ASSUMED_ACCURACY_METERS" to FingerprintPositioningEngine.ASSUMED_ACCURACY_METERS,
            "NavigationOrchestrator.WAYPOINT_ARRIVAL_METERS" to NavigationOrchestrator.WAYPOINT_ARRIVAL_METERS,
            "NavigationOrchestrator.ARRIVAL_DISTANCE_METERS" to NavigationOrchestrator.ARRIVAL_DISTANCE_METERS,
            "NavigationOrchestrator.TURN_ANGLE_THRESHOLD_DEG" to NavigationOrchestrator.TURN_ANGLE_THRESHOLD_DEG,
            "NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT" to NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT.toDouble(),
            "NavigationOrchestrator.CRITICAL_DISTANCE_METERS" to NavigationOrchestrator.CRITICAL_DISTANCE_METERS,
            "NavigationOrchestrator.WARNING_DISTANCE_METERS" to NavigationOrchestrator.WARNING_DISTANCE_METERS,
            "BeaconMapBuilder.FLOOR_TRANSITION_COST_METERS" to BeaconMapBuilder.FLOOR_TRANSITION_COST_METERS,
        ),
        map = map,
    )
}

@Composable
private fun RecordingIndicator(text: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** "Sonraki: Kapı · 8 m" / "Hedefe ~34 m · yaklaşık 1 dakika" — the two numbers people actually ask. */
@Composable
private fun RouteProgressCard(progress: RouteProgress, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sonraki", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(progress.nextNode.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = progress.metersToNext?.let { "${it.roundToInt()} m" } ?: "Kat değişimi",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Hedefe", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("~${progress.metersToDestination.roundToInt()} m", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (progress.minutesToDestination <= 1) "1 dakikadan az" else "yaklaşık ${progress.minutesToDestination} dakika",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun mapDescription(floorIndex: Int, progress: RouteProgress?, destination: MapNode, obstacleCount: Int): String = buildString {
    append("Kat $floorIndex haritası. ")
    if (progress != null) {
        append("Sonraki nokta ${progress.nextNode.name}")
        progress.metersToNext?.let { append(", ${it.roundToInt()} metre") }
        append(". ${destination.name} hedefine yaklaşık ${progress.metersToDestination.roundToInt()} metre. ")
    }
    if (obstacleCount > 0) append("$obstacleCount engel görünüyor.")
}

/**
 * Field-test ground truth: the tester taps this the moment they're physically
 * standing at a named place, and the log gets where they *really* were next to
 * where the app thought they were (see GroundTruthEvent). A handful of these
 * per walk is what makes the position error measurable rather than a feeling.
 */
@Composable
private fun GroundTruthControls(
    recorder: FieldLogRecorder,
    currentFloorIndex: Int,
    buildingMap: BuildingMap,
    nodeFloor: Map<String, Int>,
    estimate: Position?,
) {
    var choosing by remember { mutableStateOf(false) }
    var lastMark by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(top = 12.dp)) {
        RecordingIndicator(
            text = if (recorder.failed) "Saha kaydı yazılamadı (depolama dolu olabilir), kayıt durdu." else "Saha kaydı alınıyor",
        )
        OutlinedButton(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), onClick = { choosing = true }) {
            Icon(Icons.Filled.PinDrop, contentDescription = null)
            Text("Buradayım: gerçek konumu işaretle", modifier = Modifier.padding(start = 8.dp))
        }
        lastMark?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (choosing) {
        // Current floor first — that's almost always where the tester is — but every
        // floor stays reachable, since floor tracking being wrong is itself worth logging.
        val nodes = remember(buildingMap, currentFloorIndex) {
            buildingMap.floors
                .sortedBy { if (it.floorIndex == currentFloorIndex) Int.MIN_VALUE else it.floorIndex }
                .flatMap { floor -> floor.nodes.filter { it.type != NodeType.BLE_ANCHOR }.sortedBy { it.name } }
        }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Şu an neredesin?") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(nodes, key = { it.id }) { node ->
                        val floor = nodeFloor[node.id] ?: 0
                        Text(
                            text = if (buildingMap.floors.size > 1) "${node.name} (Kat $floor)" else node.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val event = GroundTruthEvent.of(System.currentTimeMillis(), node, floor, estimate)
                                    recorder.record(event)
                                    lastMark = "✓ ${node.name} işaretlendi" +
                                        (event.errorMeters?.let { " — tahmin ${it.round1()} m uzaktaydı" } ?: " — tahmin başka bir kattaydı")
                                    choosing = false
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun TechnicalDetails(routeText: String, technicalText: String) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(modifier = Modifier.padding(top = 8.dp), onClick = { expanded = !expanded }) {
        Text(if (expanded) "Teknik ayrıntıları gizle" else "Teknik ayrıntıları göster")
    }
    if (expanded) {
        Text(text = "Rota: $routeText", style = MaterialTheme.typography.bodySmall)
        Text(text = technicalText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusBanner(status: NavigationStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = status.containerColor()),
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(status.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun NavigationState?.toNavigationStatus(destinationNode: MapNode, destinationFloor: Int): NavigationStatus {
    if (this == null) return NavigationStatus.SEARCHING
    if (pendingFloorChange != null) return NavigationStatus.ON_ROUTE

    val hasCritical = alerts.any { it.priority == AlertPriority.CRITICAL }
    val hasWarning = alerts.any { it.priority == AlertPriority.WARNING }
    if (hasCritical) return NavigationStatus.CRITICAL
    if (hasWarning) return NavigationStatus.WARNING

    // Arrival can only be judged once the user is actually on the destination's floor
    // — comparing (x, y) across floors is meaningless (independent PDR origins).
    if (position.floor != destinationFloor) return NavigationStatus.ON_ROUTE
    val distanceToDestination = hypot(position.x - destinationNode.x, position.y - destinationNode.y)
    if (distanceToDestination <= ARRIVAL_DISTANCE_METERS) return NavigationStatus.ARRIVED

    return NavigationStatus.ON_ROUTE
}
