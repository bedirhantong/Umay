package com.appvalence.umay.files

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.appvalence.umay.R
import com.appvalence.umay.appGraph
import com.appvalence.umay.cloudmap.SharedMapPoolDialogs
import com.appvalence.umay.cloudmap.rememberSharedMapPoolUi
import com.appvalence.umay.cloudmap.sharedMapPoolItems
import com.appvalence.umay.common.StoredFile
import com.appvalence.umay.common.buildingSummary
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.diagnostics.CrashLog
import com.appvalence.umay.fieldlog.FieldLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Everything that moves data on or off this device: building maps (export
 * to share a survey with another phone, import one received from another,
 * the shared online pool) and field-test logs (turn recording on, share the
 * logs for offline calibration, clean them up).
 *
 * A lazy list, since every section here grows with use — up to 50 field logs,
 * 20 crash reports, a public pool of up to 200 buildings — and composing all
 * of that up front on every visit is what made this tab slower the longer the
 * app was used. File listings are read off the main thread.
 */
@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val buildingRepository = context.appGraph.buildings
    val buildings = buildingRepository.buildings.collectAsState().value.orEmpty()
    val logStore = remember { FieldLogStore(context) }
    val scope = rememberCoroutineScope()

    var logs by remember { mutableStateOf<List<StoredFile>>(emptyList()) }
    var crashReports by remember { mutableStateOf<List<StoredFile>>(emptyList()) }
    var recordingEnabled by remember { mutableStateOf(logStore.isRecordingEnabled) }
    var pendingBuildingDelete by remember { mutableStateOf<BuildingMap?>(null) }
    var pendingLogDelete by remember { mutableStateOf<StoredFile?>(null) }
    var pendingCrashDelete by remember { mutableStateOf<StoredFile?>(null) }
    var confirmDeleteAllLogs by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reloadLogs() {
        scope.launch { logs = withContext(Dispatchers.IO) { StoredFile.of(logStore.listLogs()) } }
    }
    fun reloadCrashReports() {
        scope.launch { crashReports = withContext(Dispatchers.IO) { StoredFile.of(CrashLog.list(context)) } }
    }
    LaunchedEffect(Unit) {
        reloadLogs()
        reloadCrashReports()
    }

    val importBuilding = rememberBuildingImporter()
    val pool = rememberSharedMapPoolUi()
    val poolOnline by pool.pool.isOnline.collectAsState()
    val poolListing by pool.pool.listing.collectAsState()
    val publishedIds by pool.pool.publishedIds.collectAsState()
    val dateTimeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item(key = "buildings-header") {
            Column {
                SectionHeading(stringResource(R.string.files_buildings_title))
                Text(
                    text = stringResource(R.string.files_buildings_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), onClick = importBuilding.pickFile) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Text(stringResource(R.string.common_import_building), modifier = Modifier.padding(start = 8.dp))
                }
                if (buildings.isEmpty()) EmptyLine(stringResource(R.string.files_no_buildings))
            }
        }
        items(buildings, key = { "building:${it.id}" }) { building ->
            FileRow(
                title = building.id,
                subtitle = buildingSummary(building.floors.size, building.floors.sumOf { it.nodes.size }),
                shareDescription = stringResource(R.string.files_export_building_description, building.id),
                deleteDescription = stringResource(R.string.common_delete_building_description, building.id),
                onShare = {
                    scope.launch {
                        val copy = buildingRepository.exportCopy(building)
                        val shared = copy != null &&
                            shareFiles(context, listOf(copy), mimeType = "application/json", chooserTitle = context.getString(R.string.files_export_chooser))
                        if (!shared) errorMessage = context.getString(R.string.files_export_failed)
                    }
                },
                onDelete = { pendingBuildingDelete = building },
            )
        }

        item(key = "divider-pool") { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }

        sharedMapPoolItems(pool, buildings, publishedIds, poolOnline, poolListing)

        item(key = "divider-logs") { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }

        item(key = "logs-header") {
            Column {
                SectionHeading(stringResource(R.string.files_logs_title))
                Text(
                    text = stringResource(R.string.files_logs_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .toggleable(
                            value = recordingEnabled,
                            role = Role.Switch,
                            onValueChange = {
                                recordingEnabled = it
                                logStore.isRecordingEnabled = it
                            },
                        ),
                ) {
                    Text(text = stringResource(R.string.files_logs_toggle), style = MaterialTheme.typography.bodyLarge)
                    // Row's toggleable owns the click and the merged accessible name (see BeaconScannerScreen).
                    Switch(checked = recordingEnabled, onCheckedChange = null)
                }
                if (logs.isEmpty()) {
                    EmptyLine(stringResource(R.string.files_no_logs))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val shared = shareFiles(context, logs.map { it.file }, FieldLogStore.SHARE_MIME_TYPE, chooserTitle = context.getString(R.string.files_logs_chooser))
                                if (!shared) errorMessage = context.getString(R.string.files_logs_share_failed)
                            },
                        ) { Text(stringResource(R.string.files_share_all, logs.size)) }
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = { confirmDeleteAllLogs = true }) {
                            Text(stringResource(R.string.files_delete_all))
                        }
                    }
                }
            }
        }
        items(logs, key = { "log:${it.file.path}" }) { log ->
            FileRow(
                title = log.name,
                subtitle = "${dateTimeFormat.format(Date(log.modifiedAtMs))} · ${Formatter.formatShortFileSize(context, log.sizeBytes)}",
                shareDescription = stringResource(R.string.files_log_share_description),
                deleteDescription = stringResource(R.string.files_log_delete_description),
                onShare = {
                    val shared = shareFiles(context, listOf(log.file), FieldLogStore.SHARE_MIME_TYPE, chooserTitle = context.getString(R.string.files_log_chooser))
                    if (!shared) errorMessage = context.getString(R.string.files_log_share_failed)
                },
                onDelete = { pendingLogDelete = log },
            )
        }

        // Only shown when there's something to show: most people will never see this.
        if (crashReports.isNotEmpty()) {
            item(key = "crashes-header") {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    SectionHeading(stringResource(R.string.files_crashes_title))
                    Text(
                        text = stringResource(R.string.files_crashes_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            items(crashReports, key = { "crash:${it.file.path}" }) { report ->
                FileRow(
                    title = report.name,
                    subtitle = dateTimeFormat.format(Date(report.modifiedAtMs)),
                    shareDescription = stringResource(R.string.files_crash_share_description),
                    deleteDescription = stringResource(R.string.files_crash_delete_description),
                    onShare = {
                        val shared = shareFiles(context, listOf(report.file), CrashLog.SHARE_MIME_TYPE, chooserTitle = context.getString(R.string.files_crash_chooser))
                        if (!shared) errorMessage = context.getString(R.string.files_log_share_failed)
                    },
                    onDelete = { pendingCrashDelete = report },
                )
            }
        }
    }

    SharedMapPoolDialogs(pool)

    pendingCrashDelete?.let { report ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.files_log_delete_title),
            body = stringResource(R.string.files_log_delete_body, report.name),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { CrashLog.delete(context, report.file) }
                    reloadCrashReports()
                }
            },
            onDismiss = { pendingCrashDelete = null },
        )
    }
    pendingBuildingDelete?.let { building ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete_building_title, building.id),
            body = stringResource(R.string.files_delete_building_body),
            onConfirm = { scope.launch { buildingRepository.delete(building.id) } },
            onDismiss = { pendingBuildingDelete = null },
        )
    }
    pendingLogDelete?.let { log ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.files_log_delete_title),
            body = stringResource(R.string.files_log_delete_body, log.name),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { logStore.delete(log.file) }
                    reloadLogs()
                }
            },
            onDismiss = { pendingLogDelete = null },
        )
    }
    if (confirmDeleteAllLogs) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.files_logs_delete_all_title),
            body = pluralStringResource(R.plurals.files_logs_delete_all_body, logs.size, logs.size),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { logStore.deleteAll() }
                    reloadLogs()
                }
            },
            onDismiss = { confirmDeleteAllLogs = false },
        )
    }
    errorMessage?.let {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(R.string.common_error_title)) },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun FileRow(
    title: String,
    subtitle: String,
    shareDescription: String,
    deleteDescription: String,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = shareDescription) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, contentDescription = deleteDescription) }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
