package com.appvalence.umay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.orchestrator.NavigationOrchestrator
import com.appvalence.umay.orchestrator.NavigationState
import com.appvalence.umay.perception.CameraXObstacleDetector
import com.appvalence.umay.positioning.BleBeaconScanner
import com.appvalence.umay.positioning.BleTrilaterationPositioningEngine
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
 * operator pick a start/destination point by name, then runs the real
 * [NavigationOrchestrator] — live BLE-trilaterated position, camera obstacle
 * detection, A* routing with obstacle-triggered rerouting, TTS alerts — and
 * presents it the way a consumer navigation app would: one big status
 * message people can read at a glance, with the technical detail (raw
 * coordinates, node ids) folded away rather than in their face.
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

        val floorMap = map.floors.firstOrNull()
        if (floorMap == null || floorMap.nodes.isEmpty()) {
            EmptyState(icon = Icons.Filled.Map, title = "Harita boş", message = "Kaydedilmiş haritada hiç nokta yok.")
            return@Column
        }

        var startIndex by remember { mutableStateOf(0) }
        var destinationIndex by remember { mutableStateOf((floorMap.nodes.size - 1).coerceAtLeast(0)) }
        var isNavigating by remember { mutableStateOf(false) }

        if (!isNavigating) {
            RoutePicker(
                nodes = floorMap.nodes,
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
                floorMap = floorMap,
                startNodeId = floorMap.nodes[startIndex].id,
                destinationNode = floorMap.nodes[destinationIndex],
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
    NodeDropdown(label = "Bulunduğum yer", icon = Icons.Filled.LocationSearching, nodes = nodes, selectedIndex = startIndex, onIndexChange = onStartChange)
    NodeDropdown(
        label = "Gitmek istediğim yer",
        icon = Icons.Filled.Flag,
        nodes = nodes,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeDropdown(
    label: String,
    icon: ImageVector,
    nodes: List<MapNode>,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = nodes[selectedIndex].name,
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
                    text = { Text(node.name) },
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
    floorMap: FloorMap,
    startNodeId: String,
    destinationNode: MapNode,
    onStop: () -> Unit,
) {
    val scanner = remember { BleBeaconScanner(context) }
    val feedback = remember { TtsFeedbackController(context) }
    val detector = remember { CameraXObstacleDetector(context) }
    val orchestrator = remember {
        NavigationOrchestrator(
            positioning = BleTrilaterationPositioningEngine(scanner.beaconUpdates(), buildingMap, floorMap.floorIndex),
            perception = detector,
            map = buildingMap,
            feedback = feedback,
            startNodeId = startNodeId,
            destinationNodeId = destinationNode.id,
        )
    }

    DisposableEffect(Unit) {
        scanner.start()
        onDispose {
            scanner.stop()
            detector.unbind()
            feedback.shutdown()
        }
    }

    var state by remember { mutableStateOf<NavigationState?>(null) }
    LaunchedEffect(orchestrator) {
        orchestrator.start().collect { state = it }
    }

    val currentState = state
    val status = currentState.toNavigationStatus(destinationNode)

    Column {
        StatusBanner(status)

        Button(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = onStop,
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Text(text = "Yönlendirmeyi durdur", modifier = Modifier.padding(start = 8.dp))
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(160.dp).padding(top = 12.dp),
            factory = { ctx ->
                PreviewView(ctx).also { previewView -> detector.bind(lifecycleOwner, previewView.surfaceProvider) }
            },
        )

        NavigationMapCanvas(
            floorMap = floorMap,
            route = currentState?.route ?: emptyList(),
            position = currentState?.position,
            obstacles = currentState?.obstacles ?: emptyList(),
        )

        if (currentState != null) {
            val nodesById = floorMap.nodes.associateBy { it.id }
            Text(
                text = "Rota: " + currentState.route.joinToString(" → ") { nodesById[it.id]?.name ?: it.name },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Teknik: x=${currentState.position.x.round1()} y=${currentState.position.y.round1()} " +
                    "doğruluk≈${currentState.position.accuracyMeters.round1()}m",
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

private fun NavigationState?.toNavigationStatus(destinationNode: MapNode): NavigationStatus {
    if (this == null) return NavigationStatus.SEARCHING

    val hasCritical = alerts.any { it.priority == AlertPriority.CRITICAL }
    val hasWarning = alerts.any { it.priority == AlertPriority.WARNING }
    if (hasCritical) return NavigationStatus.CRITICAL
    if (hasWarning) return NavigationStatus.WARNING

    val distanceToDestination = hypot(position.x - destinationNode.x, position.y - destinationNode.y)
    if (distanceToDestination <= ARRIVAL_DISTANCE_METERS) return NavigationStatus.ARRIVED

    return NavigationStatus.ON_ROUTE
}
