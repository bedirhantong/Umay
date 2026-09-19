package com.appvalence.umay.cloudmap

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.appvalence.umay.R
import com.appvalence.umay.appGraph
import com.appvalence.umay.cloud.PublicBuildingSummary
import com.appvalence.umay.cloud.RemoteMapFailure
import com.appvalence.umay.cloud.asRemoteMapFailure
import com.appvalence.umay.common.buildingSummary
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.storage.BuildingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * "Herkese aç" next to each local building and "Keşfet": everyone else's
 * published buildings, downloadable into this device's own collection. The pool
 * itself ([SharedMapPool]) outlives this screen; this only holds what the
 * screen is showing right now (a confirmation, a result message).
 *
 * Everything here requires connectivity, and says so honestly instead of
 * letting an action just fail: offline, the section explains that and points
 * back at the local import/export Dosyalar already offers.
 */
@Stable
class SharedMapPoolUi internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    val pool: SharedMapPool,
    private val buildings: BuildingRepository,
) {
    var errorMessage by mutableStateOf<String?>(null)
    var successMessage by mutableStateOf<String?>(null)
    var pendingPublish by mutableStateOf<BuildingMap?>(null)
    var pendingUnpublish by mutableStateOf<BuildingMap?>(null)
    var pendingOverwrite by mutableStateOf<BuildingMap?>(null)

    fun publish(building: BuildingMap) {
        pendingPublish = null
        scope.launch {
            pool.publish(building)
                .onSuccess { successMessage = context.getString(R.string.cloud_publish_success, building.id) }
                .onFailure { errorMessage = context.remoteMapFailureText(it.asRemoteMapFailure()) }
        }
    }

    fun unpublish(building: BuildingMap) {
        pendingUnpublish = null
        scope.launch {
            pool.unpublish(building.id).onFailure { errorMessage = context.remoteMapFailureText(it.asRemoteMapFailure()) }
        }
    }

    fun download(summary: PublicBuildingSummary) {
        scope.launch {
            pool.download(summary.id)
                .onSuccess { saveDownloaded(it, overwrite = false) }
                .onFailure { errorMessage = context.remoteMapFailureText(it.asRemoteMapFailure()) }
        }
    }

    fun confirmOverwrite(map: BuildingMap) {
        pendingOverwrite = null
        scope.launch { saveDownloaded(map, overwrite = true) }
    }

    private suspend fun saveDownloaded(map: BuildingMap, overwrite: Boolean) {
        if (BuildingMapJson.problems(map).isNotEmpty()) {
            errorMessage = context.getString(R.string.cloud_map_corrupt)
            return
        }
        if (!overwrite && buildings.exists(map.id)) {
            pendingOverwrite = map
            return
        }
        if (buildings.save(map)) {
            successMessage = context.getString(R.string.cloud_download_success, map.id)
        } else {
            errorMessage = context.getString(R.string.import_save_failed_body)
        }
    }
}

@Composable
fun rememberSharedMapPoolUi(): SharedMapPoolUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = remember { SharedMapPoolUi(context, scope, context.appGraph.sharedMapPool, context.appGraph.buildings) }
    val isOnline by ui.pool.isOnline.collectAsState()
    // Only when the cached listing is actually old — reopening Dosyalar a minute later
    // shows what was already fetched instead of downloading it all again.
    LaunchedEffect(isOnline) { if (isOnline) ui.pool.refreshIfStale() }
    return ui
}

/**
 * The pool's rows, as items of the caller's lazy list — so a long public
 * listing is only ever composed as far as it's actually on screen, rather than
 * all at once every time Dosyalar opens.
 */
fun LazyListScope.sharedMapPoolItems(
    ui: SharedMapPoolUi,
    localBuildings: List<BuildingMap>,
    publishedIds: Set<String>,
    isOnline: Boolean,
    listing: PoolListing,
) {
    item(key = "pool-header") {
        Column {
            Text(
                text = stringResource(R.string.cloud_pool_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
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
            }
        }
    }

    items(localBuildings, key = { "publish:${it.id}" }) { building ->
        PublishRow(
            building = building,
            isPublished = building.id in publishedIds,
            enabled = isOnline,
            onPublish = { ui.pendingPublish = building },
            onUnpublish = { ui.pendingUnpublish = building },
        )
    }

    item(key = "pool-discover-header") {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text(
                    text = stringResource(R.string.cloud_discover_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(enabled = isOnline && !listing.loading, onClick = ui.pool::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cloud_refresh))
                }
            }
            if (listing.loading) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
            }
            listing.failure?.let { failure ->
                Text(
                    text = LocalContext.current.remoteMapFailureText(failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            val fetchedEmpty = listing.fetchedAtMs != 0L && listing.summaries.isEmpty()
            if (isOnline && !listing.loading && listing.failure == null && fetchedEmpty) {
                Text(
                    text = stringResource(R.string.cloud_pool_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }

    items(listing.summaries, key = { "remote:${it.id}" }) { summary ->
        DownloadRow(summary = summary, onDownload = { ui.download(summary) })
    }
}

/** The confirmation and result dialogs for [sharedMapPoolItems]; call once, outside the list. */
@Composable
fun SharedMapPoolDialogs(ui: SharedMapPoolUi) {
    ui.pendingPublish?.let { building ->
        AlertDialog(
            onDismissRequest = { ui.pendingPublish = null },
            title = { Text(stringResource(R.string.cloud_publish_confirm_title, building.id)) },
            text = { Text(stringResource(R.string.cloud_publish_confirm_body)) },
            confirmButton = { TextButton(onClick = { ui.publish(building) }) { Text(stringResource(R.string.cloud_publish_confirm_action)) } },
            dismissButton = { TextButton(onClick = { ui.pendingPublish = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    ui.pendingUnpublish?.let { building ->
        AlertDialog(
            onDismissRequest = { ui.pendingUnpublish = null },
            title = { Text(stringResource(R.string.cloud_unpublish_confirm_title, building.id)) },
            text = { Text(stringResource(R.string.cloud_unpublish_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { ui.unpublish(building) }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { ui.pendingUnpublish = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    ui.pendingOverwrite?.let { map ->
        AlertDialog(
            onDismissRequest = { ui.pendingOverwrite = null },
            title = { Text(stringResource(R.string.import_exists_title, map.id)) },
            text = { Text(stringResource(R.string.import_exists_body)) },
            confirmButton = { TextButton(onClick = { ui.confirmOverwrite(map) }) { Text(stringResource(R.string.import_overwrite)) } },
            dismissButton = { TextButton(onClick = { ui.pendingOverwrite = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    ui.successMessage?.let {
        AlertDialog(
            onDismissRequest = { ui.successMessage = null },
            title = { Text(it) },
            text = null,
            confirmButton = { TextButton(onClick = { ui.successMessage = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
    ui.errorMessage?.let {
        AlertDialog(
            onDismissRequest = { ui.errorMessage = null },
            title = { Text(stringResource(R.string.common_error_title)) },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { ui.errorMessage = null }) { Text(stringResource(R.string.common_ok)) } },
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
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.SHORT) }
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = summary.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = buildingSummary(summary.floorCount, summary.nodeCount) + " · " + dateFormat.format(Date(summary.updatedAtMs)),
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

private fun Context.remoteMapFailureText(failure: RemoteMapFailure): String = when (failure) {
    RemoteMapFailure.NoConnection -> getString(R.string.cloud_error_no_connection)
    RemoteMapFailure.NotFound -> getString(R.string.cloud_error_not_found)
    is RemoteMapFailure.TooLarge -> getString(R.string.cloud_error_too_large)
    is RemoteMapFailure.InvalidMap -> getString(R.string.cloud_error_invalid_map)
    RemoteMapFailure.NotOwner -> getString(R.string.cloud_error_not_owner)
    is RemoteMapFailure.Other -> getString(R.string.cloud_error_other, failure.message ?: "?")
}
