package com.appvalence.umay.files

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appvalence.umay.R
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.positioning.ble.BeaconStatus
import com.appvalence.umay.positioning.ble.BleScanState
import com.appvalence.umay.survey.REQUIRED_BLE_PERMISSIONS
import com.appvalence.umay.survey.scanFailureText

/**
 * A building's beacons, checked while walking past them (see
 * [BeaconHealthViewModel]): which the map relies on but can't be heard, which
 * report a low battery, which were added since the survey, and where the map
 * says another beacon would help. Plain lists, for TalkBack.
 */
@Composable
fun BeaconHealthScreen(
    building: BuildingMap,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BeaconHealthViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(REQUIRED_BLE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasPermissions = grants.values.all { it }
    }
    BackHandler(onBack = onClose)
    if (hasPermissions) {
        DisposableEffect(building.id) {
            viewModel.start(building)
            onDispose { viewModel.stop() }
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val heard = state.statuses.filter { it.heard }
    val unheard = state.statuses.filter { !it.heard }
    val lowBattery = heard.filter { it.lowBattery }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Text(
                    text = stringResource(R.string.beacons_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(text = stringResource(R.string.beacons_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!hasPermissions) {
                Text(text = stringResource(R.string.survey_permission_denied), modifier = Modifier.padding(top = 8.dp))
                Button(modifier = Modifier.padding(top = 8.dp), onClick = { permissionLauncher.launch(REQUIRED_BLE_PERMISSIONS) }) {
                    Text(stringResource(R.string.common_retry))
                }
            } else {
                val scanState = state.scanState
                Text(
                    text = if (scanState is BleScanState.Failed) {
                        stringResource(R.string.beacons_scan_failed, scanFailureText(scanState))
                    } else {
                        pluralStringResource(R.plurals.beacons_scanning, state.scanSeconds, state.scanSeconds)
                    },
                    color = if (scanState is BleScanState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.statuses.isEmpty()) Text(stringResource(R.string.beacons_none), modifier = Modifier.padding(top = 8.dp))
        }

        beaconSection("unheard", R.string.beacons_unheard, unheard, state.beaconNames, highlight = true)
        beaconSection("low", R.string.beacons_low_battery, lowBattery, state.beaconNames, highlight = true)
        beaconSection("heard", R.string.beacons_heard, heard, state.beaconNames, highlight = false)
        if (state.unknown.isNotEmpty()) {
            item(key = "unknown-body") {
                Text(text = stringResource(R.string.beacons_unknown_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            beaconSection("unknown", R.string.beacons_unknown, state.unknown, state.beaconNames, highlight = false)
        }

        item(key = "coverage") {
            Column {
                SectionTitle(stringResource(R.string.beacons_coverage))
                state.coverage.entries.sortedBy { it.key }.forEach { (floorIndex, spots) ->
                    val floorLabel = stringResource(R.string.common_floor, floorIndex)
                    when {
                        spots == null -> Text(stringResource(R.string.beacons_coverage_unknown, floorLabel), style = MaterialTheme.typography.bodyMedium)
                        spots.isEmpty() -> Text(stringResource(R.string.beacons_coverage_ok, floorLabel), style = MaterialTheme.typography.bodyMedium)
                        else -> spots.forEach { spot ->
                            val place = spot.nearestPlace?.name ?: stringResource(R.string.beacons_coverage_place_unknown)
                            val where = "$floorLabel, $place"
                            Text(
                                text = if (spot.beaconsHeard == 0) {
                                    stringResource(R.string.beacons_coverage_none_near, where)
                                } else {
                                    pluralStringResource(R.plurals.beacons_coverage_weak_near, spot.beaconsHeard, where, spot.beaconsHeard)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        item(key = "bottom") { Column(modifier = Modifier.padding(bottom = 24.dp)) {} }
    }
}

private fun LazyListScope.beaconSection(key: String, @StringRes title: Int, beacons: List<BeaconStatus>, names: Map<String, String>, highlight: Boolean) {
    if (beacons.isEmpty()) return
    item(key = "$key-title") { SectionTitle(stringResource(title, beacons.size)) }
    items(beacons, key = { "$key-${it.beaconId}" }) { beacon -> BeaconStatusRow(beacon, names[beacon.beaconId], highlight) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp).semantics { heading() },
    )
}

@Composable
private fun BeaconStatusRow(beacon: BeaconStatus, name: String?, highlight: Boolean) {
    val colors = if (highlight) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = name ?: stringResource(R.string.beacons_id, shortId(beacon.beaconId)), style = MaterialTheme.typography.bodyLarge)
            val details = buildList {
                val rssi = beacon.rssi
                add(if (rssi != null) stringResource(R.string.beacons_signal, rssi) else stringResource(R.string.beacons_not_heard_yet))
                beacon.batteryMillivolts?.let { mv ->
                    add(stringResource(if (beacon.lowBattery) R.string.beacons_battery_low else R.string.beacons_battery, mv / 1000.0))
                }
            }
            Text(text = details.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = if (highlight) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "uuid-major-minor" reads as "major-minor": the UUID is the same for every beacon of a building. */
private fun shortId(beaconId: String): String {
    val parts = beaconId.split('-')
    return if (parts.size >= 7) "${parts[parts.size - 2]}-${parts.last()}" else beaconId
}
