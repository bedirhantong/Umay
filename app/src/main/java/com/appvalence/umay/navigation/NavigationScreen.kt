package com.appvalence.umay.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.orchestrator.NavigationOrchestrator
import com.appvalence.umay.orchestrator.NavigationState
import com.appvalence.umay.perception.CameraXObstacleDetector
import com.appvalence.umay.positioning.ble.BleBeaconScanner
import com.appvalence.umay.positioning.engine.FingerprintPositioningEngine
import com.appvalence.umay.positioning.engine.PdrPositioningEngine
import com.appvalence.umay.positioning.engine.PositionFusionEngine
import com.appvalence.umay.positioning.engine.PositioningEngine
import com.appvalence.umay.positioning.pdr.StepDetector
import com.appvalence.umay.storage.BuildingMapStorage
import com.appvalence.umay.survey.round1
import kotlinx.coroutines.flow.onEach
import kotlin.math.hypot

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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasPermissions = grants.values.all { it }
    }
    LaunchedEffect(Unit) {
        if (!hasPermissions) permissionLauncher.launch(NAVIGATION_PERMISSIONS)
    }

    var buildingMap by remember { mutableStateOf(BuildingMapStorage(context).load()) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (!hasPermissions) {
            EmptyState(
                icon = Icons.Filled.LocationSearching,
                title = "İzin gerekiyor",
                message = "Yönlendirme için kamera, konum ve Bluetooth izni vermen gerekiyor.",
            )
            return@Column
        }

        val map = buildingMap
        if (map == null) {
            EmptyState(
                icon = Icons.Filled.Map,
                title = "Henüz bir harita yok",
                message = "Önce \"Beacon Tarayıcı\" ekranında bir saha kaydı yapıp haritayı kaydet, sonra buraya dön.",
            )
            TextButton(
                modifier = Modifier.padding(top = 8.dp),
                onClick = { buildingMap = BuildingMapStorage(context).load() },
            ) {
                Text("Yeniden dene")
            }
            return@Column
        }

        val allNodes = map.floors.flatMap { it.nodes }
        if (allNodes.isEmpty()) {
            EmptyState(icon = Icons.Filled.Map, title = "Harita boş", message = "Kaydedilmiş haritada hiç nokta yok.")
            return@Column
        }
        val nodeFloor = remember(map) { map.floors.flatMap { floor -> floor.nodes.map { it.id to floor.floorIndex } }.toMap() }
        val showFloorInLabels = map.floors.size > 1

        var startIndex by remember { mutableIntStateOf(0) }
        var destinationIndex by remember { mutableIntStateOf((allNodes.size - 1).coerceAtLeast(0)) }
        var isNavigating by remember { mutableStateOf(false) }

        if (!isNavigating) {
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
    val detector = remember { CameraXObstacleDetector(context) }
    val stepDetector = remember { StepDetector(context) }
    val allNodes = remember(buildingMap) { buildingMap.floors.flatMap { it.nodes } }
    val allNodesById = remember(allNodes) { allNodes.associateBy { it.id } }
    val startFloorIndex = remember { nodeFloor[startNodeId] ?: 0 }

    var fingerprintCorrectionCount by remember { mutableIntStateOf(0) }

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
                fingerprintEngine.positionUpdates().onEach { fingerprintCorrectionCount++ },
                pdrEngine.positionUpdates(),
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
        scanner.start()
        // No preview surface — nothing to show for a raw camera feed here, and CameraX's
        // preview surface (SurfaceView-backed) can visually paint over other content on
        // this screen. Obstacle detection runs on ImageAnalysis frames either way.
        detector.bind(lifecycleOwner)
        onDispose {
            scanner.stop()
            detector.unbind()
            feedback.shutdown()
        }
    }

    fun confirmFloorChange(targetNodeId: String, targetFloor: Int, headingDeg: Double) {
        val targetNode = allNodesById[targetNodeId] ?: return
        pdrEngine.changeFloor(newFloor = targetFloor, x = targetNode.x, y = targetNode.y, headingDeg = headingDeg)
    }

    var state by remember { mutableStateOf<NavigationState?>(null) }
    var currentInstruction by remember { mutableStateOf("Yola çıkmaya hazırlanıyor...") }
    LaunchedEffect(orchestrator) {
        orchestrator.start().collect { newState ->
            state = newState
            newState.alerts.firstOrNull { it.id.startsWith("route-guide-") || it.id.startsWith("arrived-") || it.id.startsWith("no-route-") }
                ?.let { currentInstruction = it.message }

            // A STAIRS crossing confirms itself once enough footsteps have piled up
            // (see NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT) — no tap needed,
            // which matters since a blind user's hands may well be full on a staircase.
            // Elevators never set autoConfirm, so those still wait for the button below.
            newState.pendingFloorChange?.let { hint ->
                if (hint.autoConfirm) confirmFloorChange(hint.targetNodeId, hint.targetFloor, newState.position.headingDeg)
            }
        }
    }

    val currentState = state
    val destinationFloor = remember { nodeFloor[destinationNode.id] ?: 0 }
    val status = currentState.toNavigationStatus(destinationNode, destinationFloor)
    val hasFingerprintedWaypoints = remember(allNodes) { allNodes.any { it.fingerprint.isNotEmpty() } }

    val currentFloorIndex = currentState?.position?.floor ?: startFloorIndex
    val currentFloorMap = remember(buildingMap, currentFloorIndex) {
        buildingMap.floors.firstOrNull { it.floorIndex == currentFloorIndex } ?: buildingMap.floors.first()
    }
    val routeOnCurrentFloor = remember(currentState?.route, currentFloorIndex) {
        currentState?.route.orEmpty().filter { nodeFloor[it.id] == currentFloorIndex }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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

        Text(
            text = currentInstruction,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
        )

        val pendingFloorChange = currentState?.pendingFloorChange
        if (pendingFloorChange != null) {
            val direction = if (pendingFloorChange.targetFloor > currentFloorIndex) "yukarı" else "aşağı"
            Button(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                onClick = {
                    confirmFloorChange(pendingFloorChange.targetNodeId, pendingFloorChange.targetFloor, currentState.position.headingDeg)
                },
            ) {
                Icon(Icons.Filled.SwapVert, contentDescription = null)
                Text(text = "Kat değiştirdim ($direction, Kat ${pendingFloorChange.targetFloor})", modifier = Modifier.padding(start = 8.dp))
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onStop,
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Text(text = "Yönlendirmeyi durdur", modifier = Modifier.padding(start = 8.dp))
        }

        NavigationMapCanvas(
            floorMap = currentFloorMap,
            route = routeOnCurrentFloor,
            position = currentState?.position,
            obstacles = currentState?.obstacles ?: emptyList(),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "⚪ bina grafı (Kat $currentFloorIndex)   🔵 rota   🟠 hedef   🟢 senin konumun   🔴 algılanan engel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (currentState != null) {
            Text(
                text = "Rota: " + currentState.route.joinToString(" → ") { allNodesById[it.id]?.name ?: it.name },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "Teknik: x=${currentState.position.x.round1()} y=${currentState.position.y.round1()} " +
                    "kat=${currentState.position.floor} doğruluk≈${currentState.position.accuracyMeters.round1()}m " +
                    "kaynak=${currentState.position.source} fingerprint düzeltmesi=$fingerprintCorrectionCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
