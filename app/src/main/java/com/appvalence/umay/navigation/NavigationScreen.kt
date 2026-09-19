package com.appvalence.umay.navigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.appvalence.umay.R
import com.appvalence.umay.common.PermissionRationale
import com.appvalence.umay.common.VoiceStatusNotice
import com.appvalence.umay.common.buildingSummary
import com.appvalence.umay.common.rememberIsScreenReaderOn
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.GroundTruthEvent
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.files.rememberMapFilePicker
import com.appvalence.umay.survey.round1
import kotlin.math.roundToInt

private val NAVIGATION_PERMISSIONS = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
}.toTypedArray()

// Asked for together with the required ones, but navigation works without it: the
// foreground service runs regardless, only its notification stays hidden.
private val OPTIONAL_NAVIGATION_PERMISSIONS = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/**
 * Picks a building and a start/destination point by name from *any* floor,
 * then starts a [NavigationSession] — live position fused from continuous PDR
 * and sparse RSSI-fingerprint corrections, camera obstacle detection, A*
 * routing across floors with obstacle-triggered rerouting, spoken and
 * vibrated guidance — and shows it the way a consumer navigation app would:
 * one big status message people can read at a glance, with the technical
 * detail folded away. The session itself runs independently of this screen
 * (see [NavigationService]), so leaving it or turning the screen off doesn't
 * end the walk.
 */
@Composable
fun NavigationScreen(modifier: Modifier = Modifier, viewModel: NavigationViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermissions by remember {
        mutableStateOf(NAVIGATION_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    var rationaleShown by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasPermissions = NAVIGATION_PERMISSIONS.all { grants[it] ?: (ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED) }
    }

    val pickMapFile = rememberMapFilePicker()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (!hasPermissions) {
            if (!rationaleShown) {
                PermissionRationale(
                    icon = Icons.Filled.LocationSearching,
                    title = stringResource(R.string.common_permission_needed),
                    message = stringResource(R.string.nav_permission_message),
                    onContinue = {
                        rationaleShown = true
                        permissionLauncher.launch(NAVIGATION_PERMISSIONS + OPTIONAL_NAVIGATION_PERMISSIONS)
                    },
                    speak = viewModel::speak,
                    stopSpeaking = viewModel::stopSpeaking,
                )
            } else {
                EmptyState(
                    icon = Icons.Filled.LocationSearching,
                    title = stringResource(R.string.nav_permission_denied_title),
                    message = stringResource(R.string.nav_permission_denied_message),
                )
                TextButton(modifier = Modifier.padding(top = 8.dp), onClick = { permissionLauncher.launch(NAVIGATION_PERMISSIONS + OPTIONAL_NAVIGATION_PERMISSIONS) }) {
                    Text(stringResource(R.string.common_retry))
                }
            }
            return@Column
        }

        if (!viewModel.hasStepSensors) {
            EmptyState(
                icon = Icons.Filled.LocationSearching,
                title = stringResource(R.string.nav_unsupported_title),
                message = stringResource(R.string.nav_unsupported_message),
            )
            return@Column
        }

        // A walk already in progress (started earlier, screen since turned off, app
        // reopened from the notification...) takes over the screen, whatever else is set.
        activeSession?.let { session ->
            ActiveNavigationView(session, lifecycleOwner, keepScreenOn = viewModel.keepScreenOn)
            return@Column
        }

        // Still being read from disk (only ever on the very first screen after launch).
        val buildings = state.buildings ?: return@Column

        if (buildings.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Map,
                title = stringResource(R.string.nav_no_map_title),
                message = stringResource(R.string.nav_no_map_message),
                modifier = Modifier.weight(1f),
            )
            Button(modifier = Modifier.fillMaxWidth(), onClick = pickMapFile) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Text(stringResource(R.string.common_import_building), modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                onClick = viewModel::reloadBuildings,
            ) {
                Text(stringResource(R.string.common_reload))
            }
            return@Column
        }

        val selected = state.selected
        if (selected == null) {
            BuildingPicker(
                buildings = buildings,
                onSelect = { viewModel.selectBuilding(it.id) },
                onDelete = { viewModel.deleteBuilding(it.id) },
            )
            return@Column
        }

        if (selected.nodes.isEmpty()) {
            EmptyState(icon = Icons.Filled.Map, title = stringResource(R.string.nav_empty_map_title), message = stringResource(R.string.nav_empty_map_message))
            return@Column
        }
        if (buildings.size > 1) {
            TextButton(modifier = Modifier.padding(bottom = 8.dp), onClick = viewModel::changeBuilding) {
                Text(stringResource(R.string.nav_change_building, selected.map.id))
            }
        }
        RoutePicker(
            nodes = selected.nodes,
            nodeFloor = selected.nodeFloor,
            showFloor = selected.showFloorInLabels,
            startIndex = selected.startIndex,
            destinationIndex = selected.destinationIndex,
            onStartChange = viewModel::setStart,
            onDestinationChange = viewModel::setDestination,
            onBegin = viewModel::begin,
        )
        if (viewModel.isFieldLogRecording) {
            RecordingIndicator(
                text = stringResource(R.string.nav_recording_on_hint),
                modifier = Modifier.padding(top = 12.dp),
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
        Text(text = stringResource(R.string.nav_pick_building_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.nav_pick_building_subtitle),
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
                            text = buildingSummary(building.floors.size, nodeCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { pendingDelete = building }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.common_delete_building_description, building.id))
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.common_delete_building_title, toDelete.id)) },
            text = { Text(stringResource(R.string.nav_delete_building_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(toDelete)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
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
    Text(text = stringResource(R.string.nav_pick_route_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        text = stringResource(R.string.nav_pick_route_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
    NodeDropdown(
        label = stringResource(R.string.nav_start_label),
        icon = Icons.Filled.LocationSearching,
        nodes = nodes,
        nodeFloor = nodeFloor,
        showFloor = showFloor,
        selectedIndex = startIndex,
        onIndexChange = onStartChange,
    )
    NodeDropdown(
        label = stringResource(R.string.nav_destination_label),
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
        Text(text = stringResource(R.string.nav_begin), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun nodeLabel(node: MapNode, nodeFloor: Map<String, Int>, showFloor: Boolean): String =
    if (showFloor) stringResource(R.string.common_node_with_floor, node.name, nodeFloor[node.id] ?: 0) else node.name

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
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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

private enum class NavigationStatus(@param:StringRes val label: Int, val containerColor: @Composable () -> Color, val icon: ImageVector) {
    SEARCHING(R.string.nav_status_searching, { MaterialTheme.colorScheme.surfaceVariant }, Icons.Filled.LocationSearching),
    ON_ROUTE(R.string.nav_status_on_route, { MaterialTheme.colorScheme.primaryContainer }, Icons.Filled.CheckCircle),
    WARNING(R.string.nav_status_warning, { Color(0xFFFFE0B2) }, Icons.Filled.Warning),
    CRITICAL(R.string.nav_status_critical, { MaterialTheme.colorScheme.errorContainer }, Icons.Filled.Warning),
    ARRIVED(R.string.nav_status_arrived, { Color(0xFFC8E6C9) }, Icons.Filled.CheckCircle),
    ;

    companion object {
        fun of(phase: NavigationPhase): NavigationStatus = when (phase) {
            NavigationPhase.SEARCHING -> SEARCHING
            NavigationPhase.ON_ROUTE -> ON_ROUTE
            NavigationPhase.WARNING -> WARNING
            NavigationPhase.CRITICAL -> CRITICAL
            NavigationPhase.ARRIVED -> ARRIVED
        }
    }
}

/**
 * The live view of a running [NavigationSession]. Everything that makes
 * navigation *work* lives in the session, which outlives this screen (screen
 * off, other tab, app in the background); this only shows its state and
 * forwards the user's taps. The one thing it does own is the camera
 * attachment: obstacle detection runs exactly while this screen is visible.
 */
@Composable
private fun ActiveNavigationView(session: NavigationSession, lifecycleOwner: LifecycleOwner, keepScreenOn: Boolean) {
    val context = LocalContext.current
    val ui by session.ui.collectAsStateWithLifecycle()
    val voiceStatus by session.speech.voiceStatus.collectAsStateWithLifecycle()

    DisposableEffect(session, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.attachCamera(lifecycleOwner)
                Lifecycle.Event.ON_STOP -> session.detachCamera()
                else -> Unit
            }
        }
        // addObserver replays ON_START right away if the screen is already visible.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.detachCamera()
        }
    }
    KeepScreenOnIfRequested(keepScreenOn)

    val currentState = ui.state
    val status = NavigationStatus.of(ui.phase)
    val currentFloorIndex = currentState?.position?.floor ?: session.startFloorIndex
    val currentFloorMap = remember(session, currentFloorIndex) {
        session.buildingMap.floors.firstOrNull { it.floorIndex == currentFloorIndex } ?: session.buildingMap.floors.first()
    }
    val progress = currentState?.let {
        routeProgress(it.route, it.nextRouteIndex, it.position, session.nodeFloor, session.buildingMap.floorTransitions)
    }

    val pipelineError = ui.pipelineError
    if (pipelineError != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            EmptyState(icon = Icons.Filled.Warning, title = stringResource(R.string.nav_error_title), message = pipelineError)
            Button(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), onClick = { session.stop() }) {
                Text(stringResource(R.string.common_back))
            }
        }
        return
    }

    // "Yazı oku" / "Bir şey sor" live in a bar fixed to the bottom of the screen, not in
    // the scrolling content above — those two are the assistant actions someone reaches
    // for constantly while walking, and having to scroll to find them (past the status
    // banner, the map, technical details…) is exactly the "UI kötü" complaint this fixes.
    // Modern voice-assistant apps keep their mic/action bar sticky for the same reason.
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            StatusBanner(status)
            VoiceStatusNotice(voiceStatus, onRecheck = session.speech::refreshVoice, modifier = Modifier.padding(top = 8.dp))

            if (!session.hasFingerprintedWaypoints) {
                Text(
                    text = stringResource(R.string.nav_no_fingerprints_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Non-fatal: navigation (position, routing, TTS guidance) still works without
            // obstacle detection, so this is a note, not a blocking EmptyState.
            ui.cameraWarning?.let {
                Text(
                    text = stringResource(R.string.nav_camera_warning, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Text(
                text = ui.instruction,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 12.dp),
                enabled = currentState != null && status != NavigationStatus.ARRIVED,
                onClick = session::repeatInstruction,
            ) {
                Icon(Icons.Filled.Replay, contentDescription = null)
                Text(stringResource(R.string.nav_repeat), modifier = Modifier.padding(start = 8.dp))
            }

            ui.lastQuestionAnswer?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            if (progress != null && status != NavigationStatus.ARRIVED) {
                RouteProgressCard(progress, modifier = Modifier.padding(bottom = 12.dp))
            }

            val pendingFloorChange = currentState?.pendingFloorChange
            if (pendingFloorChange != null) {
                Button(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    onClick = session::confirmFloorChangeManually,
                ) {
                    Icon(Icons.Filled.SwapVert, contentDescription = null)
                    Text(
                        text = stringResource(
                            if (pendingFloorChange.targetFloor > currentFloorIndex) R.string.nav_floor_change_up else R.string.nav_floor_change_down,
                            pendingFloorChange.targetFloor,
                        ),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (session.hasBarometer) {
                    Text(
                        text = stringResource(R.string.nav_floor_change_barometer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { session.stop() },
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Text(text = stringResource(R.string.nav_stop), modifier = Modifier.padding(start = 8.dp))
            }

            if (session.isRecording) {
                GroundTruthControls(session = session, currentFloorIndex = currentFloorIndex)
            }

            NavigationMapCanvas(
                floorMap = currentFloorMap,
                floorLabel = stringResource(R.string.common_floor, currentFloorMap.floorIndex),
                route = currentState?.route.orEmpty(),
                nextRouteIndex = currentState?.nextRouteIndex ?: 1,
                nodeFloor = session.nodeFloor,
                position = currentState?.position,
                obstacles = currentState?.obstacles ?: emptyList(),
                description = mapDescription(context, currentFloorMap.floorIndex, progress, session.destinationNode, currentState?.obstacles?.size ?: 0),
                modifier = Modifier.padding(top = 16.dp),
            )

            if (currentState != null) {
                TechnicalDetails(
                    routeText = currentState.route.joinToString(" → ") { session.allNodesById[it.id]?.name ?: it.name },
                    technicalText = stringResource(
                        R.string.nav_technical_details,
                        currentState.position.x.round1().toString(),
                        currentState.position.y.round1().toString(),
                        currentState.position.floor,
                        currentState.position.accuracyMeters.round1().toString(),
                        currentState.position.source.name,
                        ui.fingerprintCorrections,
                        ui.barometerClimbMeters?.round1()?.let { "$it m" } ?: "-",
                    ),
                )
            }
        }

        StickyAssistantBar(session = session, ui = ui)
    }
}

/**
 * Fixed to the bottom of the active-navigation screen regardless of scroll
 * position — see the comment above this function's caller. Both actions are
 * fully offline (on-device OCR for sign reading, [com.appvalence.umay.orchestrator.BuildingQueryMatcher]
 * for questions); a disabled button (not a hidden one) while one is already in
 * flight is what stops an impatient double-tap from queuing a second announcement.
 */
@Composable
private fun StickyAssistantBar(session: NavigationSession, ui: NavigationSessionUi) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var pendingQuestion by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingQuestion) session.askQuestion()
        pendingQuestion = false
    }

    Surface(tonalElevation = 3.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                enabled = !ui.readingSigns,
                onClick = session::readSignsAloud,
            ) {
                Icon(Icons.Filled.TextFields, contentDescription = null)
                Text(stringResource(R.string.nav_read_signs), modifier = Modifier.padding(start = 8.dp))
            }

            // Speaking the reason before the system permission dialog follows the same
            // rule as every other permission ask in this app: a blind user can't read
            // the dialog's fine print, so the *why* has to be said out loud regardless.
            OutlinedButton(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                enabled = !ui.askingQuestion,
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        session.askQuestion()
                    } else {
                        session.speech.speakNow(resources.getString(R.string.voice_query_permission_message))
                        pendingQuestion = true
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Text(stringResource(R.string.nav_ask_question), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * Ayarlar's "Ekranı açık tut": keeps the screen (and with it the camera, so obstacle
 * detection) on for as long as the navigation view is showing. A screen-reader user
 * gets the lowest brightness instead of whatever the phone was set to — the light
 * serves no one who can't see it, and the battery serves everyone.
 */
@Composable
private fun KeepScreenOnIfRequested(keepOn: Boolean) {
    val context = LocalContext.current
    val screenReaderOn = rememberIsScreenReaderOn()
    if (!keepOn) return
    val window = (context as? Activity)?.window ?: return
    DisposableEffect(window, screenReaderOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val previousBrightness = window.attributes.screenBrightness
        if (screenReaderOn) {
            window.attributes = window.attributes.apply { screenBrightness = SCREEN_READER_BRIGHTNESS }
        }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
        }
    }
}

private const val SCREEN_READER_BRIGHTNESS = 0.01f

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
                Text(stringResource(R.string.nav_progress_next), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(progress.nextNode.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = progress.metersToNext?.let { stringResource(R.string.common_meters_short, it.roundToInt()) } ?: stringResource(R.string.nav_progress_floor_change),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.nav_progress_destination), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.nav_progress_remaining_meters, progress.metersToDestination.roundToInt()), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (progress.minutesToDestination <= 1) {
                        stringResource(R.string.nav_progress_under_a_minute)
                    } else {
                        pluralStringResource(R.plurals.nav_progress_minutes, progress.minutesToDestination, progress.minutesToDestination)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun mapDescription(context: Context, floorIndex: Int, progress: RouteProgress?, destination: MapNode, obstacleCount: Int): String =
    listOfNotNull(
        context.getString(R.string.nav_map_description_floor, floorIndex),
        progress?.let {
            val next = it.metersToNext
            if (next != null) {
                context.resources.getQuantityString(R.plurals.nav_map_description_next_distance, next.roundToInt(), it.nextNode.name, next.roundToInt())
            } else {
                context.getString(R.string.nav_map_description_next, it.nextNode.name)
            }
        },
        progress?.let {
            val meters = it.metersToDestination.roundToInt()
            context.resources.getQuantityString(R.plurals.nav_map_description_destination, meters, destination.name, meters)
        },
        obstacleCount.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.nav_map_description_obstacles, it, it) },
    ).joinToString(" ")

/**
 * Field-test ground truth: the tester taps this the moment they're physically
 * standing at a named place, and the log gets where they *really* were next to
 * where the app thought they were (see GroundTruthEvent). A handful of these
 * per walk is what makes the position error measurable rather than a feeling.
 */
@Composable
private fun GroundTruthControls(session: NavigationSession, currentFloorIndex: Int) {
    val resources = LocalResources.current
    var choosing by remember { mutableStateOf(false) }
    var lastMark by remember { mutableStateOf<String?>(null) }
    val buildingMap = session.buildingMap

    Column(modifier = Modifier.padding(top = 12.dp)) {
        RecordingIndicator(
            text = stringResource(if (session.recordingFailed) R.string.nav_recording_failed else R.string.nav_recording_active),
        )
        OutlinedButton(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), onClick = { choosing = true }) {
            Icon(Icons.Filled.PinDrop, contentDescription = null)
            Text(stringResource(R.string.nav_ground_truth_button), modifier = Modifier.padding(start = 8.dp))
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
            title = { Text(stringResource(R.string.nav_ground_truth_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(nodes, key = { it.id }) { node ->
                        val floor = session.nodeFloor[node.id] ?: 0
                        Text(
                            text = if (buildingMap.floors.size > 1) stringResource(R.string.common_node_with_floor, node.name, floor) else node.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val event = session.markGroundTruth(node)
                                    lastMark = event?.errorMeters
                                        ?.let { resources.getString(R.string.nav_ground_truth_marked_error, node.name, it.round1().toString()) }
                                        ?: resources.getString(R.string.nav_ground_truth_marked_other_floor, node.name)
                                    choosing = false
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun TechnicalDetails(routeText: String, technicalText: String) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(modifier = Modifier.padding(top = 8.dp), onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.nav_technical_hide else R.string.nav_technical_show))
    }
    if (expanded) {
        Text(text = stringResource(R.string.nav_technical_route, routeText), style = MaterialTheme.typography.bodySmall)
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
                    text = stringResource(status.label),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
