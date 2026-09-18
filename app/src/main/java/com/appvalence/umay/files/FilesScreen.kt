package com.appvalence.umay.files

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.fieldlog.FieldLogStore
import com.appvalence.umay.storage.BuildingMapStorage
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Everything that moves data on or off this device: building maps (export
 * to share a survey with another phone, import one received from another)
 * and field-test logs (turn recording on, share the logs for offline
 * calibration, clean them up).
 */
@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val buildingStorage = remember { BuildingMapStorage(context) }
    val logStore = remember { FieldLogStore(context) }

    var buildings by remember { mutableStateOf(buildingStorage.listBuildings()) }
    var logs by remember { mutableStateOf(logStore.listLogs()) }
    var recordingEnabled by remember { mutableStateOf(logStore.isRecordingEnabled) }
    var pendingBuildingDelete by remember { mutableStateOf<BuildingMap?>(null) }
    var pendingLogDelete by remember { mutableStateOf<File?>(null) }
    var confirmDeleteAllLogs by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val importBuilding = rememberBuildingImporter(onImported = { buildings = buildingStorage.listBuildings() })

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionHeading("Binalar")
        Text(
            text = "Bir cihazda taranan haritayı dışa aktarıp başka bir cihazda içe aktarabilirsin.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), onClick = importBuilding) {
            Icon(Icons.Filled.FileOpen, contentDescription = null)
            Text("Haritayı içe aktar", modifier = Modifier.padding(start = 8.dp))
        }
        if (buildings.isEmpty()) {
            EmptyLine("Henüz kayıtlı bina yok.")
        }
        buildings.forEach { building ->
            FileRow(
                title = building.id,
                subtitle = "${building.floors.size} kat, ${building.floors.sumOf { it.nodes.size }} nokta",
                shareDescription = "${building.id} haritasını dışa aktar",
                deleteDescription = "${building.id} binasını sil",
                onShare = {
                    val copy = buildingStorage.exportCopy(building)
                    val shared = copy != null &&
                        shareFiles(context, listOf(copy), mimeType = "application/json", chooserTitle = "Haritayı paylaş")
                    if (!shared) errorMessage = "Harita dışa aktarılamadı."
                },
                onDelete = { pendingBuildingDelete = building },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        SectionHeading("Saha kayıtları")
        Text(
            text = "Açıkken her yönlendirme; konum, adım, sinyal gücü ve verilen yönergelerle birlikte bir dosyaya kaydedilir. " +
                "Bu dosyalar paylaşılana kadar cihazından çıkmaz.",
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
            Text(text = "Navigasyon sırasında kayıt tut", style = MaterialTheme.typography.bodyLarge)
            // Row's toggleable owns the click and the merged accessible name (see BeaconScannerScreen).
            Switch(checked = recordingEnabled, onCheckedChange = null)
        }

        if (logs.isEmpty()) {
            EmptyLine("Henüz saha kaydı yok.")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val shared = shareFiles(context, logs, FieldLogStore.SHARE_MIME_TYPE, chooserTitle = "Saha kayıtlarını paylaş")
                        if (!shared) errorMessage = "Kayıtlar paylaşılamadı."
                    },
                ) { Text("Tümünü paylaş (${logs.size})") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { confirmDeleteAllLogs = true }) {
                    Text("Tümünü sil")
                }
            }
            logs.forEach { log ->
                FileRow(
                    title = log.nameWithoutExtension,
                    subtitle = "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(log.lastModified()))} · " +
                        Formatter.formatShortFileSize(context, log.length()),
                    shareDescription = "Bu kaydı paylaş",
                    deleteDescription = "Bu kaydı sil",
                    onShare = {
                        val shared = shareFiles(context, listOf(log), FieldLogStore.SHARE_MIME_TYPE, chooserTitle = "Saha kaydını paylaş")
                        if (!shared) errorMessage = "Kayıt paylaşılamadı."
                    },
                    onDelete = { pendingLogDelete = log },
                )
            }
        }
    }

    pendingBuildingDelete?.let { building ->
        ConfirmDeleteDialog(
            title = "'${building.id}' silinsin mi?",
            body = "Bu binanın haritası kalıcı olarak silinecek. Önce dışa aktarmadıysan geri alınamaz.",
            onConfirm = {
                buildingStorage.delete(building.id)
                buildings = buildingStorage.listBuildings()
            },
            onDismiss = { pendingBuildingDelete = null },
        )
    }
    pendingLogDelete?.let { log ->
        ConfirmDeleteDialog(
            title = "Kayıt silinsin mi?",
            body = "${log.nameWithoutExtension} kalıcı olarak silinecek.",
            onConfirm = {
                logStore.delete(log)
                logs = logStore.listLogs()
            },
            onDismiss = { pendingLogDelete = null },
        )
    }
    if (confirmDeleteAllLogs) {
        ConfirmDeleteDialog(
            title = "Tüm kayıtlar silinsin mi?",
            body = "${logs.size} saha kaydı kalıcı olarak silinecek.",
            onConfirm = {
                logStore.deleteAll()
                logs = logStore.listLogs()
            },
            onDismiss = { confirmDeleteAllLogs = false },
        )
    }
    errorMessage?.let {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Bir sorun oluştu") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("Tamam") } },
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
            }) { Text("Sil", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
