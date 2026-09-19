package com.appvalence.umay.cloudmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appvalence.umay.cloud.PublicBuildingSummary
import com.appvalence.umay.cloud.RemoteMapFailure
import com.appvalence.umay.cloud.asRemoteMapFailure
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.storage.BuildingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The outcome of a pool action, for the dialog that reports it. */
sealed interface PoolMessage {
    data class Published(val buildingName: String) : PoolMessage
    data class Downloaded(val buildingName: String) : PoolMessage
    data class Failed(val failure: RemoteMapFailure) : PoolMessage
    data object MapCorrupt : PoolMessage
    data object SaveFailed : PoolMessage
}

/** Confirmations and results the pool section is showing right now. */
data class PoolDialogs(
    val pendingPublish: BuildingMap? = null,
    val pendingUnpublish: BuildingMap? = null,
    /** A downloaded map whose name already exists here — waiting for "replace it?". */
    val pendingOverwrite: BuildingMap? = null,
    val message: PoolMessage? = null,
)

data class PoolUiState(
    val isOnline: Boolean = false,
    val listing: PoolListing = PoolListing(),
    val publishedIds: Set<String> = emptySet(),
    val dialogs: PoolDialogs = PoolDialogs(),
)

/**
 * "Herkese aç" next to each local building and "Keşfet": everyone else's
 * published buildings, downloadable into this device's own collection. The
 * pool itself ([SharedMapPool]) outlives this screen.
 */
@HiltViewModel
class SharedMapPoolViewModel @Inject constructor(
    private val pool: SharedMapPool,
    private val buildings: BuildingRepository,
) : ViewModel() {
    private val dialogs = MutableStateFlow(PoolDialogs())

    val state: StateFlow<PoolUiState> = combine(
        // Only when the cached listing is actually old — coming back to Dosyalar a minute
        // later shows what was already fetched instead of downloading it all again.
        pool.isOnline.onEach { online -> if (online) pool.refreshIfStale() },
        pool.listing,
        pool.publishedIds,
        dialogs,
        ::PoolUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PoolUiState(listing = pool.listing.value, publishedIds = pool.publishedIds.value))

    fun refresh() = pool.refresh()

    fun requestPublish(building: BuildingMap) = dialogs.update { it.copy(pendingPublish = building) }

    fun cancelPublish() = dialogs.update { it.copy(pendingPublish = null) }

    fun confirmPublish() {
        val building = dialogs.value.pendingPublish ?: return
        dialogs.update { it.copy(pendingPublish = null) }
        viewModelScope.launch {
            pool.publish(building)
                .onSuccess { show(PoolMessage.Published(building.id)) }
                .onFailure { show(PoolMessage.Failed(it.asRemoteMapFailure())) }
        }
    }

    fun requestUnpublish(building: BuildingMap) = dialogs.update { it.copy(pendingUnpublish = building) }

    fun cancelUnpublish() = dialogs.update { it.copy(pendingUnpublish = null) }

    fun confirmUnpublish() {
        val building = dialogs.value.pendingUnpublish ?: return
        dialogs.update { it.copy(pendingUnpublish = null) }
        viewModelScope.launch {
            pool.unpublish(building.id).onFailure { show(PoolMessage.Failed(it.asRemoteMapFailure())) }
        }
    }

    fun download(summary: PublicBuildingSummary) {
        viewModelScope.launch {
            pool.download(summary.id)
                .onSuccess { saveDownloaded(it, overwrite = false) }
                .onFailure { show(PoolMessage.Failed(it.asRemoteMapFailure())) }
        }
    }

    fun confirmOverwrite() {
        val map = dialogs.value.pendingOverwrite ?: return
        dialogs.update { it.copy(pendingOverwrite = null) }
        viewModelScope.launch { saveDownloaded(map, overwrite = true) }
    }

    fun cancelOverwrite() = dialogs.update { it.copy(pendingOverwrite = null) }

    fun messageShown() = dialogs.update { it.copy(message = null) }

    private suspend fun saveDownloaded(map: BuildingMap, overwrite: Boolean) {
        if (BuildingMapJson.problems(map).isNotEmpty()) {
            show(PoolMessage.MapCorrupt)
            return
        }
        if (!overwrite && buildings.exists(map.id)) {
            dialogs.update { it.copy(pendingOverwrite = map) }
            return
        }
        show(if (buildings.save(map)) PoolMessage.Downloaded(map.id) else PoolMessage.SaveFailed)
    }

    private fun show(message: PoolMessage) = dialogs.update { it.copy(message = message) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
