package com.appvalence.umay.cloudmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appvalence.umay.R
import com.appvalence.umay.cloud.AndroidConnectivityMonitor
import com.appvalence.umay.cloud.FirestoreBuildingMapRepository
import com.appvalence.umay.cloud.PublicBuildingSummary
import com.appvalence.umay.cloud.RemoteMapFailure
import com.appvalence.umay.cloud.asRemoteMapFailure
import com.appvalence.umay.common.buildingSummary
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.storage.BuildingMapStorage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * "Herkese aç" next to each local building (a small extension of Dosyalar's
 * existing building rows) and "Keşfet": everyone else's published buildings,
 * downloadable into this device's own local collection.
 *
 * Everything here requires connectivity, checked once up front via
 * [AndroidConnectivityMonitor] and shown honestly instead of letting an
 * action just fail: offline, the whole section explains that and points
 * back at the local import/export this screen already offers, which never
 * needed a connection to begin with.
 */
@Composable
fun SharedMapPoolSection(
    localBuildings: List<BuildingMap>,
    onLocalBuildingsChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val storage = remember { BuildingMapStorage(context) }
    val published = remember { PublishedBuildingsStore(context) }
    val repository = remember { FirestoreBuildingMapRepository() }
    val connectivity = remember { AndroidConnectivityMonitor(context) }
    val isOnline by connectivity.isOnline.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    var publicBuildings by remember { mutableStateOf<List<PublicBuildingSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingUnpublish by remember { mutableStateOf<BuildingMap?>(null) }
    var pendingPublish by remember { mutableStateOf<BuildingMap?>(null) }
    var pendingOverwrite by remember { mutableStateOf<BuildingMap?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    fun refreshListing() {
        loading = true
        listError = null
        scope.launch {
            repository.listPublicBuildings()
                .onSuccess { publicBuildings = it }
                .onFailure { listError = context.remoteMapFailureText(it.asRemoteMapFailure()) }
            loading = false
        }
    }
    // Loads once as soon as there's a connection, so opening this screen with data
    // already on doesn't require an extra tap — a manual "Yenile" covers the rest.
    LaunchedEffect(isOnline) { if (isOnline && publicBuildings.isEmpty() && !loading) refreshListing() }

    fun saveDownloaded(map: BuildingMap) {
        val problems = BuildingMapJson.problems(map)
        if (problems.isNotEmpty()) {
            errorMessage = context.getString(R.string.cloud_map_corrupt)
            return
        }
        if (storage.exists(map.id)) {
            pendingOverwrite = map
            return
        }
        if (storage.save(map)) {
            onLocalBuildingsChanged()
            successMessage = context.getString(R.string.cloud_download_success, map.id)
        } else {
            errorMessage = context.getString(R.string.import_save_failed_body)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.cloud_pool_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.cloud_pool_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (!isOnline) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = stringResource(R.string.cloud_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (localBuildings.isEmpty()) {
            Text(
                text = stringResource(R.string.cloud_no_local_buildings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            localBuildings.forEach { building ->
                val remoteId = published.remoteIdFor(building.id)
                PublishRow(
                    building = building,
                    isPublished = remoteId != null,
                    enabled = isOnline,
                    onPublish = { pendingPublish = building },
                    onUnpublish = { pendingUnpublish = building },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.cloud_discover_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(enabled = isOnline && !loading, onClick = { refreshListing() }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cloud_refresh))
            }
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
        }
        listError?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (isOnline && !loading && listError == null && publicBuildings.isEmpty()) {
            Text(
                text = stringResource(R.string.cloud_pool_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        publicBuildings.forEach { summary ->
            DownloadRow(
                summary = summary,
                onDownload = {
                    scope.launch {
                        repository.downloadBuildingMap(summary.id)
                            .onSuccess { saveDownloaded(it) }
                            .onFailure { errorMessage = context.remoteMapFailureText(it.asRemoteMapFailure()) }
                    }
                },
            )
        }
    }

    pendingPublish?.let { building ->
        AlertDialog(
            onDismissRequest = { pendingPublish = null },
            title = { Text(stringResource(R.string.cloud_publish_confirm_title, building.id)) },
            text = { Text(stringResource(R.string.cloud_publish_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPublish = null
                    scope.launch {
                        repository.publishBuildingMap(building)
                            .onSuccess { remoteId ->
                                published.markPublished(building.id, remoteId)
                                successMessage = context.getString(R.string.cloud_publish_success, building.id)
                                refreshListing()
                            }
                            .onFailure { errorMessage = context.remoteMapFailureText(it.asRemoteMapFailure()) }
                    }
                }) { Text(stringResource(R.string.cloud_publish_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { pendingPublish = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    pendingUnpublish?.let { building ->
        AlertDialog(
            onDismissRequest = { pendingUnpublish = null },
            title = { Text(stringResource(R.string.cloud_unpublish_confirm_title, building.id)) },
            text = { Text(stringResource(R.string.cloud_unpublish_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val remoteId = published.remoteIdFor(building.id)
                    pendingUnpublish = null
                    if (remoteId == null) return@TextButton
                    scope.launch {
                        repository.unpublishBuildingMap(remoteId)
                            .onSuccess {
                                published.markUnpublished(building.id)
                                refreshListing()
                            }
                            .onFailure { errorMessage = context.remoteMapFailureText(it.asRemoteMapFailure()) }
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingUnpublish = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    pendingOverwrite?.let { map ->
        AlertDialog(
            onDismissRequest = { pendingOverwrite = null },
            title = { Text(stringResource(R.string.import_exists_title, map.id)) },
            text = { Text(stringResource(R.string.import_exists_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingOverwrite = null
                    if (storage.save(map)) {
                        onLocalBuildingsChanged()
                        successMessage = context.getString(R.string.cloud_download_success, map.id)
                    } else {
                        errorMessage = context.getString(R.string.import_save_failed_body)
                    }
                }) { Text(stringResource(R.string.import_overwrite)) }
            },
            dismissButton = { TextButton(onClick = { pendingOverwrite = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    successMessage?.let {
        AlertDialog(
            onDismissRequest = { successMessage = null },
            title = { Text(it) },
            text = null,
            confirmButton = { TextButton(onClick = { successMessage = null }) { Text(stringResource(R.string.common_ok)) } },
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
private fun PublishRow(building: BuildingMap, isPublished: Boolean, enabled: Boolean, onPublish: () -> Unit, onUnpublish: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = building.id, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(if (isPublished) R.string.cloud_status_published else R.string.cloud_status_local_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isPublished) {
                IconButton(enabled = enabled, onClick = onUnpublish) {
                    Icon(Icons.Filled.CloudOff, contentDescription = stringResource(R.string.cloud_unpublish_description, building.id))
                }
            } else {
                IconButton(enabled = enabled, onClick = onPublish) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = stringResource(R.string.cloud_publish_description, building.id))
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(summary: PublicBuildingSummary, onDownload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = summary.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = buildingSummary(summary.floorCount, summary.nodeCount) + " · " +
                        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(summary.updatedAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.CloudDownload, contentDescription = stringResource(R.string.cloud_download_description, summary.displayName))
            }
        }
    }
}

private fun android.content.Context.remoteMapFailureText(failure: RemoteMapFailure): String = when (failure) {
    RemoteMapFailure.NoConnection -> getString(R.string.cloud_error_no_connection)
    RemoteMapFailure.NotFound -> getString(R.string.cloud_error_not_found)
    is RemoteMapFailure.TooLarge -> getString(R.string.cloud_error_too_large)
    is RemoteMapFailure.InvalidMap -> getString(R.string.cloud_error_invalid_map)
    RemoteMapFailure.NotOwner -> getString(R.string.cloud_error_not_owner)
    is RemoteMapFailure.Other -> getString(R.string.cloud_error_other, failure.message ?: "?")
}
