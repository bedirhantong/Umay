package com.appvalence.umay.cloudmap

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.appvalence.umay.R
import com.appvalence.umay.cloud.PublicBuildingSummary
import com.appvalence.umay.cloud.RemoteMapFailure
import com.appvalence.umay.common.buildingSummary
import com.appvalence.umay.coremodel.BuildingMap
import java.text.DateFormat
import java.util.Date

/**
 * The pool's rows, as items of the caller's lazy list — so a long public
 * listing is only ever composed as far as it's actually on screen.
 *
 * Everything here requires connectivity, and says so honestly instead of
 * letting an action just fail: offline, the section explains that and points
 * back at the local import/export Dosyalar already offers.
 */
fun LazyListScope.sharedMapPoolItems(
    state: PoolUiState,
    localBuildings: List<BuildingMap>,
    viewModel: SharedMapPoolViewModel,
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
            if (!state.isOnline) {
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
            isPublished = building.id in state.publishedIds,
            enabled = state.isOnline,
            onPublish = { viewModel.requestPublish(building) },
            onUnpublish = { viewModel.requestUnpublish(building) },
        )
    }

    item(key = "pool-discover-header") {
        val listing = state.listing
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text(
                    text = stringResource(R.string.cloud_discover_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(enabled = state.isOnline && !listing.loading, onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cloud_refresh))
                }
            }
            if (listing.loading) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
            }
            listing.failure?.let { failure ->
                Text(
                    text = remoteMapFailureText(failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            val fetchedEmpty = listing.fetchedAtMs != 0L && listing.summaries.isEmpty()
            if (state.isOnline && !listing.loading && listing.failure == null && fetchedEmpty) {
                Text(
                    text = stringResource(R.string.cloud_pool_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }

    items(state.listing.summaries, key = { "remote:${it.id}" }) { summary ->
        DownloadRow(summary = summary, onDownload = { viewModel.download(summary) })
    }
}

/** The confirmation and result dialogs for [sharedMapPoolItems]; call once, outside the list. */
@Composable
fun SharedMapPoolDialogs(dialogs: PoolDialogs, viewModel: SharedMapPoolViewModel) {
    dialogs.pendingPublish?.let { building ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPublish,
            title = { Text(stringResource(R.string.cloud_publish_confirm_title, building.id)) },
            text = { Text(stringResource(R.string.cloud_publish_confirm_body)) },
            confirmButton = { TextButton(onClick = viewModel::confirmPublish) { Text(stringResource(R.string.cloud_publish_confirm_action)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelPublish) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    dialogs.pendingUnpublish?.let { building ->
        AlertDialog(
            onDismissRequest = viewModel::cancelUnpublish,
            title = { Text(stringResource(R.string.cloud_unpublish_confirm_title, building.id)) },
            text = { Text(stringResource(R.string.cloud_unpublish_confirm_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUnpublish) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelUnpublish) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    dialogs.pendingOverwrite?.let { map ->
        AlertDialog(
            onDismissRequest = viewModel::cancelOverwrite,
            title = { Text(stringResource(R.string.import_exists_title, map.id)) },
            text = { Text(stringResource(R.string.import_exists_body)) },
            confirmButton = { TextButton(onClick = viewModel::confirmOverwrite) { Text(stringResource(R.string.import_overwrite)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelOverwrite) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    when (val message = dialogs.message) {
        null -> Unit
        is PoolMessage.Published, is PoolMessage.Downloaded -> AlertDialog(
            onDismissRequest = viewModel::messageShown,
            title = {
                Text(
                    when (message) {
                        is PoolMessage.Published -> stringResource(R.string.cloud_publish_success, message.buildingName)
                        is PoolMessage.Downloaded -> stringResource(R.string.cloud_download_success, message.buildingName)
                        else -> ""
                    },
                )
            },
            text = null,
            confirmButton = { TextButton(onClick = viewModel::messageShown) { Text(stringResource(R.string.common_ok)) } },
        )
        is PoolMessage.Failed, PoolMessage.MapCorrupt, PoolMessage.SaveFailed -> AlertDialog(
            onDismissRequest = viewModel::messageShown,
            title = { Text(stringResource(R.string.common_error_title)) },
            text = {
                Text(
                    when (message) {
                        is PoolMessage.Failed -> remoteMapFailureText(message.failure)
                        PoolMessage.MapCorrupt -> stringResource(R.string.cloud_map_corrupt)
                        else -> stringResource(R.string.import_save_failed_body)
                    },
                )
            },
            confirmButton = { TextButton(onClick = viewModel::messageShown) { Text(stringResource(R.string.common_ok)) } },
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

@Composable
private fun remoteMapFailureText(failure: RemoteMapFailure): String = when (failure) {
    RemoteMapFailure.NoConnection -> stringResource(R.string.cloud_error_no_connection)
    RemoteMapFailure.NotFound -> stringResource(R.string.cloud_error_not_found)
    is RemoteMapFailure.TooLarge -> stringResource(R.string.cloud_error_too_large)
    is RemoteMapFailure.InvalidMap -> stringResource(R.string.cloud_error_invalid_map)
    RemoteMapFailure.NotOwner -> stringResource(R.string.cloud_error_not_owner)
    is RemoteMapFailure.Other -> stringResource(R.string.cloud_error_other, failure.message ?: "?")
}
