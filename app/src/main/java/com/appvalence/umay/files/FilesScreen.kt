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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appvalence.umay.R
import com.appvalence.umay.cloudmap.SharedMapPoolDialogs
import com.appvalence.umay.cloudmap.SharedMapPoolViewModel
import com.appvalence.umay.cloudmap.sharedMapPoolItems
import com.appvalence.umay.common.StoredFile
import com.appvalence.umay.common.buildingSummary
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.diagnostics.CrashLog
import com.appvalence.umay.fieldlog.FieldLogStore
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
 * of it up front on every visit made this tab slower the longer the app was used.
 */
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The same answer for the whole life of the app (it's the build's), so this
    // conditional call always takes the same branch.
    val poolViewModel: SharedMapPoolViewModel? = if (viewModel.showsSharedMapPool) hiltViewModel() else null
    val pool = poolViewModel?.state?.collectAsStateWithLifecycle()?.value
    val pickMapFile = rememberMapFilePicker()
    val dateTimeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    // Confirmations are this screen's own business; nothing else needs to know about them.
    var pendingBuildingDelete by remember { mutableStateOf<BuildingMap?>(null) }
    var pendingLogDelete by remember { mutableStateOf<StoredFile?>(null) }
    var pendingCrashDelete by remember { mutableStateOf<StoredFile?>(null) }
    var confirmDeleteAllLogs by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshFiles() }

    val exportChooserTitle = stringResource(R.string.files_export_chooser)
    LaunchedEffect(state.pendingExport) {
        val file = state.pendingExport ?: return@LaunchedEffect
        viewModel.exportHandled()
        if (!shareFiles(context, listOf(file), mimeType = "application/json", chooserTitle = exportChooserTitle)) {
            viewModel.showMessage(FilesMessage.EXPORT_FAILED)
        }
    }

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
                OutlinedButton(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), onClick = pickMapFile) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Text(stringResource(R.string.common_import_building), modifier = Modifier.padding(start = 8.dp))
                }
                if (state.buildings.isEmpty()) EmptyLine(stringResource(R.string.files_no_buildings))
            }
        }
        items(state.buildings, key = { "building:${it.id}" }) { building ->
            FileRow(
                title = building.id,
                subtitle = buildingSummary(building.floors.size, building.floors.sumOf { it.nodes.size }),
                shareDescription = stringResource(R.string.files_export_building_description, building.id),
                deleteDescription = stringResource(R.string.common_delete_building_description, building.id),
                onShare = { viewModel.export(building) },
                onDelete = { pendingBuildingDelete = building },
            )
        }

        if (pool != null && poolViewModel != null) {
            item(key = "divider-pool") { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            sharedMapPoolItems(pool, state.buildings, poolViewModel)
        }

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
                        .toggleable(value = state.recordingEnabled, role = Role.Switch, onValueChange = viewModel::setRecordingEnabled),
                ) {
                    Text(text = stringResource(R.string.files_logs_toggle), style = MaterialTheme.typography.bodyLarge)
                    // Row's toggleable owns the click and the merged accessible name (see BeaconScannerScreen).
                    Switch(checked = state.recordingEnabled, onCheckedChange = null)
                }
                if (state.logs.isEmpty()) {
                    EmptyLine(stringResource(R.string.files_no_logs))
                } else {
                    val logsChooserTitle = stringResource(R.string.files_logs_chooser)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (!shareFiles(context, state.logs.map { it.file }, FieldLogStore.SHARE_MIME_TYPE, chooserTitle = logsChooserTitle)) {
                                    viewModel.showMessage(FilesMessage.LOGS_SHARE_FAILED)
                                }
                            },
                        ) { Text(stringResource(R.string.files_share_all, state.logs.size)) }
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = { confirmDeleteAllLogs = true }) {
                            Text(stringResource(R.string.files_delete_all))
                        }
                    }
                }
            }
        }
        items(state.logs, key = { "log:${it.file.path}" }) { log ->
            val chooserTitle = stringResource(R.string.files_log_chooser)
            FileRow(
                title = log.name,
                subtitle = "${dateTimeFormat.format(Date(log.modifiedAtMs))} · ${Formatter.formatShortFileSize(context, log.sizeBytes)}",
                shareDescription = stringResource(R.string.files_log_share_description),
                deleteDescription = stringResource(R.string.files_log_delete_description),
                onShare = {
                    if (!shareFiles(context, listOf(log.file), FieldLogStore.SHARE_MIME_TYPE, chooserTitle = chooserTitle)) {
                        viewModel.showMessage(FilesMessage.LOG_SHARE_FAILED)
                    }
                },
                onDelete = { pendingLogDelete = log },
            )
        }

        // Only shown when there's something to show: most people will never see this.
        if (state.crashReports.isNotEmpty()) {
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
            items(state.crashReports, key = { "crash:${it.file.path}" }) { report ->
                val chooserTitle = stringResource(R.string.files_crash_chooser)
                FileRow(
                    title = report.name,
                    subtitle = dateTimeFormat.format(Date(report.modifiedAtMs)),
                    shareDescription = stringResource(R.string.files_crash_share_description),
                    deleteDescription = stringResource(R.string.files_crash_delete_description),
                    onShare = {
                        if (!shareFiles(context, listOf(report.file), CrashLog.SHARE_MIME_TYPE, chooserTitle = chooserTitle)) {
                            viewModel.showMessage(FilesMessage.CRASH_SHARE_FAILED)
                        }
                    },
                    onDelete = { pendingCrashDelete = report },
                )
            }
        }
    }

    if (pool != null && poolViewModel != null) SharedMapPoolDialogs(pool.dialogs, poolViewModel)

    pendingCrashDelete?.let { report ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.files_log_delete_title),
            body = stringResource(R.string.files_log_delete_body, report.name),
            onConfirm = { viewModel.deleteCrashReport(report.file) },
            onDismiss = { pendingCrashDelete = null },
        )
    }
    pendingBuildingDelete?.let { building ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete_building_title, building.id),
            body = stringResource(R.string.files_delete_building_body),
            onConfirm = { viewModel.deleteBuilding(building.id) },
            onDismiss = { pendingBuildingDelete = null },
        )
    }
    pendingLogDelete?.let { log ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.files_log_delete_title),
            body = stringResource(R.string.files_log_delete_body, log.name),
            onConfirm = { viewModel.deleteLog(log.file) },
            onDismiss = { pendingLogDelete = null },
        )
    }
    if (confirmDeleteAllLogs) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.files_logs_delete_all_title),
            body = pluralStringResource(R.plurals.files_logs_delete_all_body, state.logs.size, state.logs.size),
            onConfirm = viewModel::deleteAllLogs,
            onDismiss = { confirmDeleteAllLogs = false },
        )
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::messageShown,
            title = { Text(stringResource(R.string.common_error_title)) },
            text = {
                Text(
                    stringResource(
                        when (message) {
                            FilesMessage.EXPORT_FAILED -> R.string.files_export_failed
                            FilesMessage.LOGS_SHARE_FAILED -> R.string.files_logs_share_failed
                            FilesMessage.LOG_SHARE_FAILED, FilesMessage.CRASH_SHARE_FAILED -> R.string.files_log_share_failed
                        },
                    ),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::messageShown) { Text(stringResource(R.string.common_ok)) } },
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
