package com.appvalence.umay.survey

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appvalence.umay.R
import com.appvalence.umay.common.PermissionRationale
import com.appvalence.umay.common.countText
import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.TaggedWaypoint
import com.appvalence.umay.positioning.ble.BleScanFailure
import com.appvalence.umay.positioning.ble.BleScanState
import com.appvalence.umay.positioning.trilateration.BeaconSurveySolver
import com.appvalence.umay.survey.ar.ArAvailability
import com.appvalence.umay.survey.ar.ArCameraFailure
import com.appvalence.umay.survey.ar.ArCameraStatus
import com.appvalence.umay.survey.ar.ArInstallResult
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private val REQUIRED_BLE_PERMISSIONS = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
}.toTypedArray()

/**
 * On-site mapping walk (see [SurveyRecorder]): the operator starts a recording,
 * walks the building naming places as they go, then stops and saves the
 * resulting map. The recording itself belongs to [SurveyRecorder] and keeps
 * going if this screen is left; this only shows it and forwards taps.
 * Everything below the main record/save flow (raw beacon list, UUID grouping,
 * copyable survey text) is advanced/debug detail, folded away by default.
 */
@Composable
fun BeaconScannerScreen(modifier: Modifier = Modifier, viewModel: SurveyViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current

    var hasPermissions by remember {
        mutableStateOf(REQUIRED_BLE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    var rationaleShown by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasPermissions = grants.values.all { it }
    }

    if (!hasPermissions) {
        if (!rationaleShown) {
            PermissionRationale(
                icon = Icons.Filled.LocationOn,
                title = stringResource(R.string.common_permission_needed),
                message = stringResource(R.string.survey_permission_message),
                onContinue = {
                    rationaleShown = true
                    permissionLauncher.launch(REQUIRED_BLE_PERMISSIONS)
                },
                speak = viewModel::speak,
                stopSpeaking = viewModel::stopSpeaking,
                modifier = modifier,
            )
        } else {
            Column(modifier = modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.survey_permission_denied),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(
                    modifier = Modifier.padding(top = 8.dp),
                    onClick = { permissionLauncher.launch(REQUIRED_BLE_PERMISSIONS) },
                ) {
                    Text(stringResource(R.string.common_retry))
                }
            }
        }
        return
    }

    if (!viewModel.hasStepSensors) {
        Text(
            text = stringResource(R.string.survey_unsupported),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.padding(16.dp),
        )
        return
    }

    val survey by viewModel.survey.collectAsStateWithLifecycle()
    val save by viewModel.save.collectAsStateWithLifecycle()
    val cameraAvailability by viewModel.cameraAvailability.collectAsStateWithLifecycle()
    val cameraMeasuresWalls by viewModel.cameraMeasuresWalls.collectAsStateWithLifecycle()

    // The camera may only run while this screen is actually shown.
    LifecycleResumeEffect(viewModel) {
        viewModel.setScreenVisible(true)
        onPauseOrDispose { viewModel.setScreenVisible(false) }
    }
    // Camera tracking stops when the screen sleeps; a camera walk keeps it awake.
    val keepAwake = survey.isRecording && survey.mode == SurveyMode.CAMERA
    val view = LocalView.current
    DisposableEffect(view, keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    // A new walk picks its mode; a resumed one keeps the mode it was started with.
    val newWalk = survey.trajectory.isEmpty() && survey.taggedWaypoints.isEmpty()
    var modeChoice by rememberSaveable { mutableStateOf<SurveyMode?>(null) }
    val walkMode = when {
        !newWalk -> survey.mode
        cameraAvailability != ArAvailability.READY -> SurveyMode.FOOTSTEPS
        else -> modeChoice ?: SurveyMode.CAMERA
    }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    var installFailed by rememberSaveable { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied = !granted
        if (granted) viewModel.startRecording(SurveyMode.CAMERA)
    }
    val startRecording = {
        if (walkMode == SurveyMode.CAMERA &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            viewModel.startRecording(walkMode)
        }
    }
    val walkedMeters = remember(survey.trajectory) { walkedMeters(survey.trajectory) }
    val progressText = if (walkMode == SurveyMode.CAMERA) {
        stringResource(R.string.survey_walked_meters, walkedMeters.roundToInt())
    } else {
        countText(R.plurals.count_steps, survey.trajectory.size)
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showOnlyIBeacon by rememberSaveable { mutableStateOf(true) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val floorTrajectory = remember(survey.trajectory, survey.currentFloor) { survey.trajectory.filter { it.floor == survey.currentFloor } }
    val floorResults = remember(survey.results, survey.currentFloor) { survey.results.filter { it.floor == survey.currentFloor } }
    val floorWaypoints = remember(survey.taggedWaypoints, survey.currentFloor) { survey.taggedWaypoints.filter { it.floor == survey.currentFloor } }

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val scanState = survey.scanState
        if (scanState is BleScanState.Failed) {
            item {
                Text(
                    text = stringResource(R.string.survey_scan_failed, scanFailureText(scanState)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item {
            RecordingCard(
                isRecording = survey.isRecording,
                trajectoryPointCount = survey.trajectory.size,
                progressText = progressText,
                rawReadingCount = survey.rawReadingCount,
                taggedWaypointCount = survey.taggedWaypoints.size,
                currentFloor = survey.currentFloor,
                // A camera walk is measured in metres directly; the stride doesn't matter.
                strideCalibrated = viewModel.strideCalibrated || walkMode == SurveyMode.CAMERA,
                autoFloorDetection = viewModel.hasBarometer,
                lastAutoFloorChange = survey.lastAutoFloor?.let {
                    stringResource(R.string.survey_floor_auto, stringResource(R.string.common_floor, it))
                },
                onStart = startRecording,
                onStop = viewModel::stopRecording,
                onFloorChange = viewModel::changeFloor,
                onClear = viewModel::clear,
                startOptions = {
                    if (newWalk) {
                        SurveyModeSection(
                            availability = cameraAvailability,
                            selected = walkMode,
                            onSelect = { modeChoice = it },
                            onInstall = {
                                val activity = context.findActivity()
                                installFailed = activity == null || viewModel.installCameraSupport(activity).let {
                                    it == ArInstallResult.FAILED || it == ArInstallResult.DECLINED
                                }
                            },
                            cameraDenied = cameraDenied,
                            installFailed = installFailed,
                        )
                    }
                },
                recordingDetails = {
                    if (survey.mode == SurveyMode.CAMERA) CameraStatusSection(survey.cameraStatus, cameraMeasuresWalls)
                },
            )
        }

        if (survey.isRecording) {
            item {
                WaypointTaggingSection(
                    taggedWaypoints = survey.taggedWaypoints,
                    onTag = { name, type -> viewModel.tag(name, type) },
                    onDelete = viewModel::deleteWaypoint,
                )
            }
        }

        item {
            SurveyMapCanvas(
                trajectory = floorTrajectory,
                results = floorResults,
                waypoints = floorWaypoints,
                wallMap = survey.wallMaps[survey.currentFloor],
                livePosition = survey.livePosition?.takeIf { it.floor == survey.currentFloor },
                height = if (survey.mode == SurveyMode.CAMERA) 360.dp else 240.dp,
            )
        }

        if (survey.solving) {
            item { CircularProgressIndicator() }
        } else if (survey.hasSolved && !survey.isRecording) {
            item {
                SaveMapCard(
                    resultCount = survey.results.size,
                    taggedWaypointCount = survey.taggedWaypoints.size,
                    trajectoryPointCount = survey.trajectory.size,
                    save = save,
                    onSave = { name ->
                        viewModel.saveMap(name) { floor, number -> resources.getString(R.string.survey_anchor_name, floor, number) }
                    },
                )
            }
        }

        item {
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Icon(if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                Text(
                    text = stringResource(if (showAdvanced) R.string.survey_advanced_hide else R.string.survey_advanced_show),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        if (showAdvanced) {
            val beacons = survey.beacons
            val visibleBeacons = if (showOnlyIBeacon) beacons.filter { it.uuid != null } else beacons
            val uuidGroups = beacons.filter { it.uuid != null }
                .groupBy { it.uuid!! }
                .map { (uuid, readings) ->
                    UuidGroup(uuid = uuid, distinctMinors = readings.mapNotNull { it.minor }.toSet().size, sightingCount = readings.size)
                }
                .sortedByDescending { it.distinctMinors }

            item {
                DiscoveredHeader(
                    discoveredCount = survey.discovered.size,
                    onCopyAll = {
                        val text = survey.discovered.values
                            .sortedWith(compareBy({ it.uuid }, { it.minor }))
                            .joinToString("\n") { it.toSurveyLine() }
                        scope.launch { clipboard.setClipEntry(ClipData.newPlainText(null, text).toClipEntry()) }
                    },
                    onClear = viewModel::clearDiscovered,
                )
            }

            if (uuidGroups.isNotEmpty()) {
                item { UuidGroupsSection(uuidGroups) }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = pluralStringResource(R.plurals.survey_device_count, beacons.size, visibleBeacons.size, beacons.size), style = MaterialTheme.typography.titleMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.toggleable(
                            value = showOnlyIBeacon,
                            role = Role.Switch,
                            onValueChange = { showOnlyIBeacon = it },
                        ),
                    ) {
                        Text(text = stringResource(R.string.survey_only_ibeacon), style = MaterialTheme.typography.bodySmall)
                        // The Row's toggleable modifier owns the click handling and merged
                        // accessible name ("Sadece iBeacon, açık/kapalı") — the Switch itself
                        // must not also be independently focusable, or TalkBack sees two
                        // controls (an unlabeled switch and a plain text) instead of one.
                        Switch(checked = showOnlyIBeacon, onCheckedChange = null)
                    }
                }
            }

            items(visibleBeacons, key = { it.beaconId }) { beacon -> BeaconRow(beacon) }
        }
    }
}

@Composable
private fun RecordingCard(
    isRecording: Boolean,
    trajectoryPointCount: Int,
    progressText: String,
    rawReadingCount: Int,
    taggedWaypointCount: Int,
    currentFloor: Int,
    strideCalibrated: Boolean,
    autoFloorDetection: Boolean,
    lastAutoFloorChange: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFloorChange: (Int) -> Unit,
    onClear: () -> Unit,
    startOptions: @Composable () -> Unit,
    recordingDetails: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isRecording) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!isRecording) {
                Text(text = stringResource(R.string.survey_new_area_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.survey_new_area_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                startOptions()
                if (!strideCalibrated) {
                    Text(
                        text = stringResource(R.string.survey_calibrate_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (trajectoryPointCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.survey_unfinished,
                            progressText,
                            countText(R.plurals.count_named_places, taggedWaypointCount),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    onClick = onStart,
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                    Text(
                        text = stringResource(if (trajectoryPointCount > 0) R.string.survey_resume else R.string.survey_start),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (trajectoryPointCount > 0) {
                    TextButton(modifier = Modifier.padding(top = 4.dp), onClick = onClear) {
                        Text(stringResource(R.string.survey_clear))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = stringResource(R.string.survey_recording),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = stringResource(
                        R.string.survey_recording_stats,
                        progressText,
                        countText(R.plurals.count_beacon_readings, rawReadingCount),
                        countText(R.plurals.count_named_places, taggedWaypointCount),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                recordingDetails()
                Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(R.string.common_floor, currentFloor), style = MaterialTheme.typography.bodyMedium)
                    // No explicit size here: IconButton's Material3 default already
                    // reserves a full 48dp touch target even though the icon itself is
                    // smaller — pinning this to 36dp would shrink the tappable area below
                    // the accessible minimum.
                    IconButton(onClick = { onFloorChange(-1) }) {
                        Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.survey_floor_down))
                    }
                    IconButton(onClick = { onFloorChange(1) }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.survey_floor_up))
                    }
                    Text(
                        text = stringResource(if (autoFloorDetection) R.string.survey_floor_hint_auto else R.string.survey_floor_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                lastAutoFloorChange?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = onStop,
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text(text = stringResource(R.string.survey_stop), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SurveyModeSection(
    availability: ArAvailability,
    selected: SurveyMode,
    onSelect: (SurveyMode) -> Unit,
    onInstall: () -> Unit,
    cameraDenied: Boolean,
    installFailed: Boolean,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(text = stringResource(R.string.survey_mode_title), style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == SurveyMode.CAMERA,
                enabled = availability == ArAvailability.READY,
                onClick = { onSelect(SurveyMode.CAMERA) },
                label = { Text(stringResource(R.string.survey_mode_camera)) },
                leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
            )
            FilterChip(
                selected = selected == SurveyMode.FOOTSTEPS,
                onClick = { onSelect(SurveyMode.FOOTSTEPS) },
                label = { Text(stringResource(R.string.survey_mode_footsteps)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null) },
            )
        }
        val explanation = when (availability) {
            ArAvailability.READY -> if (selected == SurveyMode.CAMERA) R.string.survey_mode_camera_body else R.string.survey_mode_footsteps_body
            ArAvailability.CHECKING -> R.string.survey_mode_checking
            ArAvailability.NEEDS_INSTALL -> R.string.survey_mode_needs_install
            ArAvailability.UNSUPPORTED -> R.string.survey_mode_unsupported
        }
        Text(
            text = stringResource(explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (availability == ArAvailability.NEEDS_INSTALL) {
            TextButton(onClick = onInstall) { Text(stringResource(R.string.survey_mode_install)) }
            if (installFailed) {
                Text(
                    text = stringResource(R.string.survey_mode_install_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (cameraDenied && selected == SurveyMode.CAMERA) {
            Text(
                text = stringResource(R.string.survey_camera_permission_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** What the camera is doing, and what to do when it can't track (a lasting loss is also spoken, see SurveyAnnouncer). */
@Composable
private fun CameraStatusSection(status: ArCameraStatus, measuresWalls: Boolean?) {
    val (text, isProblem) = when (status) {
        ArCameraStatus.Off, ArCameraStatus.Starting -> stringResource(R.string.survey_camera_starting) to false
        ArCameraStatus.Tracking ->
            stringResource(if (measuresWalls == false) R.string.survey_camera_tracking_no_walls else R.string.survey_camera_tracking) to false
        is ArCameraStatus.Lost -> stringResource(R.string.survey_camera_lost, stringResource(trackingProblemText(status.problem))) to true
        is ArCameraStatus.Failed -> stringResource(
            when (status.failure) {
                ArCameraFailure.NOT_INSTALLED -> R.string.survey_camera_failed_not_installed
                ArCameraFailure.UNSUPPORTED -> R.string.survey_camera_failed_unsupported
                ArCameraFailure.NO_PERMISSION -> R.string.survey_camera_failed_permission
                ArCameraFailure.CAMERA_IN_USE -> R.string.survey_camera_failed_in_use
                ArCameraFailure.OTHER -> R.string.survey_camera_failed_other
            },
        ) + " " + stringResource(R.string.survey_camera_failed_hint) to true
    }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (status == ArCameraStatus.Tracking) {
            Text(
                text = stringResource(R.string.survey_camera_hold_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Distance along the path, floor by floor — a floor change isn't a stretch of corridor. */
private fun walkedMeters(trajectory: List<Position>): Double =
    trajectory.zipWithNext().sumOf { (a, b) -> if (a.floor == b.floor) hypot(b.x - a.x, b.y - a.y) else 0.0 }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// The types worth offering at tagging time — plain WAYPOINT is the default and not
// listed as its own chip (deselecting a hazard chip already means "just a point").
private val TAGGABLE_HAZARD_TYPES = listOf(
    NodeType.DOOR to R.string.survey_type_door,
    NodeType.STAIRS to R.string.survey_type_stairs,
    NodeType.ELEVATOR to R.string.survey_type_elevator,
    NodeType.RAMP to R.string.survey_type_ramp,
)

@Composable
private fun WaypointTaggingSection(
    taggedWaypoints: List<TaggedWaypoint>,
    onTag: (String, NodeType) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var nameInput by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(NodeType.WAYPOINT) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null)
                Text(text = stringResource(R.string.survey_tag_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                text = stringResource(R.string.survey_tag_body),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TAGGABLE_HAZARD_TYPES.forEach { (type, label) ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = if (selectedType == type) NodeType.WAYPOINT else type },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.survey_tag_name_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        onTag(nameInput.trim(), selectedType)
                        nameInput = ""
                        selectedType = NodeType.WAYPOINT
                    },
                ) {
                    Text(stringResource(R.string.survey_tag_button))
                }
            }
            taggedWaypoints.forEachIndexed { index, waypoint ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val typeLabel = TAGGABLE_HAZARD_TYPES.firstOrNull { it.first == waypoint.type }?.second
                    val label = if (typeLabel != null) {
                        stringResource(R.string.survey_tagged_with_type, waypoint.name, stringResource(typeLabel), waypoint.floor)
                    } else {
                        stringResource(R.string.survey_tagged, waypoint.name, waypoint.floor)
                    }
                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    // Includes the waypoint's own name, not just "Sil" — a screen reader
                    // swiping past several tagged waypoints would otherwise hear the same
                    // ambiguous "Sil, düğme" repeated with no way to tell which is which.
                    IconButton(onClick = { onDelete(index) }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.survey_delete_waypoint, waypoint.name))
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveMapCard(
    resultCount: Int,
    taggedWaypointCount: Int,
    trajectoryPointCount: Int,
    save: SurveySaveState,
    onSave: (String) -> Unit,
) {
    var buildingName by rememberSaveable { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (resultCount == 0 && taggedWaypointCount == 0) {
                Text(
                    text = pluralStringResource(R.plurals.survey_nothing_mapped, trajectoryPointCount, BeaconSurveySolver.MIN_OBSERVATIONS_FOR_FIX, trajectoryPointCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            Text(text = stringResource(R.string.survey_map_ready), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.survey_map_ready_stats,
                    countText(R.plurals.count_named_places, taggedWaypointCount),
                    countText(R.plurals.count_located_beacons, resultCount),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = buildingName,
                onValueChange = { buildingName = it },
                label = { Text(stringResource(R.string.survey_building_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.survey_building_name_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                enabled = buildingName.isNotBlank() && !save.saving,
                onClick = { onSave(buildingName) },
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text(text = stringResource(R.string.survey_save), modifier = Modifier.padding(start = 8.dp))
            }
            save.result?.let { result ->
                Text(
                    text = when (result) {
                        is SurveySaveResult.Saved -> stringResource(
                            R.string.survey_saved,
                            countText(R.plurals.count_points, result.points),
                            countText(R.plurals.count_connections, result.connections),
                            countText(R.plurals.count_floors, result.floors),
                        )
                        SurveySaveResult.Failed -> stringResource(R.string.survey_save_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result is SurveySaveResult.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DiscoveredHeader(discoveredCount: Int, onCopyAll: () -> Unit, onClear: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = pluralStringResource(R.plurals.survey_discovered, discoveredCount, discoveredCount), style = MaterialTheme.typography.titleMedium)
            Row {
                OutlinedButton(enabled = discoveredCount > 0, onClick = onCopyAll) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = stringResource(R.string.survey_copy), modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(modifier = Modifier.padding(start = 4.dp), enabled = discoveredCount > 0, onClick = onClear) {
                    Text(stringResource(R.string.survey_clear_list))
                }
            }
        }
        Text(
            text = stringResource(R.string.survey_discovered_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UuidGroupsSection(uuidGroups: List<UuidGroup>) {
    Column {
        Text(
            text = stringResource(R.string.survey_uuid_groups_title),
            style = MaterialTheme.typography.titleSmall,
        )
        uuidGroups.forEach { group ->
            val hint = stringResource(if (group.distinctMinors > 1) R.string.survey_uuid_group_network else R.string.survey_uuid_group_single)
            Text(
                text = stringResource(
                    R.string.survey_uuid_group_line,
                    group.uuid,
                    countText(R.plurals.count_minors, group.distinctMinors),
                    countText(R.plurals.count_readings, group.sightingCount),
                    hint,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class UuidGroup(val uuid: String, val distinctMinors: Int, val sightingCount: Int)

private fun BleBeaconReading.toSurveyLine(): String {
    // A machine-readable key=value line for pasting into notes/spreadsheets, so it stays
    // language-neutral rather than going through string resources.
    val distance = if (estimatedDistanceMeters >= 0) "${estimatedDistanceMeters.round1()}m" else "?"
    return "uuid=$uuid major=$major minor=$minor mac=$macAddress rssi=${rssi}dBm distance=$distance"
}

@Composable
private fun scanFailureText(failure: BleScanState.Failed): String = when (failure.reason) {
    BleScanFailure.LOCATION_OFF -> stringResource(R.string.ble_failure_location_off)
    BleScanFailure.BLUETOOTH_OFF -> stringResource(R.string.ble_failure_bluetooth_off)
    BleScanFailure.UNSUPPORTED -> stringResource(R.string.ble_failure_unsupported)
    BleScanFailure.PERMISSION_DENIED -> stringResource(R.string.ble_failure_permission)
    BleScanFailure.SCAN_FAILED -> stringResource(R.string.ble_failure_scan, failure.errorCode ?: 0)
}

internal fun Double.round1(): Double = (this * 10).roundToInt() / 10.0

@Composable
private fun BeaconRow(beacon: BleBeaconReading) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            val deviceName = beacon.deviceName
            val title = when {
                beacon.uuid != null -> "iBeacon ${beacon.uuid}"
                deviceName != null -> deviceName
                else -> stringResource(R.string.survey_unknown_device)
            }
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (beacon.uuid != null) {
                Text(text = "major=${beacon.major} minor=${beacon.minor}", style = MaterialTheme.typography.bodySmall)
            }
            Text(text = "MAC: ${beacon.macAddress}", style = MaterialTheme.typography.bodySmall)
            val distanceText = if (beacon.estimatedDistanceMeters >= 0) {
                stringResource(R.string.survey_distance_uncalibrated, beacon.estimatedDistanceMeters.round1().toString())
            } else {
                stringResource(R.string.survey_unknown)
            }
            Text(text = stringResource(R.string.survey_rssi_line, beacon.rssi, distanceText), style = MaterialTheme.typography.bodySmall)
        }
    }
}
