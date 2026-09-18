package com.appvalence.umay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.orchestrator.NavigationOrchestrator
import com.appvalence.umay.orchestrator.NavigationState
import com.appvalence.umay.perception.CameraXObstacleDetector
import com.appvalence.umay.positioning.BleBeaconScanner
import com.appvalence.umay.positioning.BleTrilaterationPositioningEngine

private val NAVIGATION_PERMISSIONS = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
}.toTypedArray()

/**
 * Faz 3 end-to-end screen: loads the [BuildingMap] saved from the beacon
 * survey (see [BuildingMapStorage], [BeaconScannerScreen]), lets the
 * operator pick a start/destination node, then runs the real
 * [NavigationOrchestrator] — live BLE-trilaterated position, camera obstacle
 * detection, A* routing with obstacle-triggered rerouting, TTS alerts — and
 * renders it on [NavigationMapCanvas].
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
            Text(
                text = "Navigasyon için kamera, konum ve Bluetooth izni gerekiyor.",
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }

        val map = buildingMap
        if (map == null) {
            Text(
                text = "Kaydedilmiş harita yok. Önce \"Beacon Tarayıcı\" ekranında saha kaydı yapıp haritayı kaydet.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(modifier = Modifier.padding(top = 8.dp), onClick = { buildingMap = BuildingMapStorage(context).load() }) {
                Text("Yeniden dene")
            }
            return@Column
        }

        val floorMap = map.floors.firstOrNull()
        if (floorMap == null || floorMap.nodes.isEmpty()) {
            Text(text = "Haritada hiç düğüm yok.", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        var startIndex by remember { mutableStateOf(0) }
        var destinationIndex by remember { mutableStateOf((floorMap.nodes.size - 1).coerceAtLeast(0)) }
        var isNavigating by remember { mutableStateOf(false) }

        if (!isNavigating) {
            Text(text = "Başlangıç ve hedef seç:", style = MaterialTheme.typography.titleMedium)
            NodePicker(label = "Başlangıç", nodes = floorMap.nodes, selectedIndex = startIndex, onIndexChange = { startIndex = it })
            NodePicker(label = "Hedef", nodes = floorMap.nodes, selectedIndex = destinationIndex, onIndexChange = { destinationIndex = it })
            Button(
                modifier = Modifier.padding(top = 12.dp),
                enabled = startIndex != destinationIndex,
                onClick = { isNavigating = true },
            ) {
                Text("Navigasyonu başlat")
            }
        } else {
            ActiveNavigation(
                context = context,
                lifecycleOwner = lifecycleOwner,
                buildingMap = map,
                floorMap = floorMap,
                startNodeId = floorMap.nodes[startIndex].id,
                destinationNodeId = floorMap.nodes[destinationIndex].id,
                onStop = { isNavigating = false },
            )
        }
    }
}

@Composable
private fun NodePicker(label: String, nodes: List<MapNode>, selectedIndex: Int, onIndexChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(text = "$label: ${nodes[selectedIndex].id}", modifier = Modifier.weight(1f))
        Button(onClick = { onIndexChange((selectedIndex - 1 + nodes.size) % nodes.size) }) { Text("<") }
        Button(modifier = Modifier.padding(start = 4.dp), onClick = { onIndexChange((selectedIndex + 1) % nodes.size) }) { Text(">") }
    }
}

@Composable
private fun ActiveNavigation(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    buildingMap: BuildingMap,
    floorMap: FloorMap,
    startNodeId: String,
    destinationNodeId: String,
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
            destinationNodeId = destinationNodeId,
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

    Column {
        Button(onClick = onStop) { Text("Durdur") }

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            factory = { ctx ->
                PreviewView(ctx).also { previewView -> detector.bind(lifecycleOwner, previewView.surfaceProvider) }
            },
        )

        NavigationMapCanvas(
            floorMap = floorMap,
            route = state?.route ?: emptyList(),
            position = state?.position,
            obstacles = state?.obstacles ?: emptyList(),
        )

        val currentState = state
        if (currentState == null) {
            Text(text = "Konum bekleniyor (en az 3 beacon görülmeli)...", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                text = "Konum: x=${currentState.position.x.round1()} y=${currentState.position.y.round1()} " +
                    "(doğruluk ${currentState.position.accuracyMeters.round1()}m)",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Rota: ${currentState.route.joinToString(" -> ") { it.id }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
