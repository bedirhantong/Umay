package com.appvalence.umay.cloudmap

import com.appvalence.umay.cloud.ConnectivityMonitor
import com.appvalence.umay.cloud.PublicBuildingSummary
import com.appvalence.umay.cloud.RemoteBuildingMapRepository
import com.appvalence.umay.cloud.RemoteMapFailure
import com.appvalence.umay.cloud.asRemoteMapFailure
import com.appvalence.umay.common.Clock
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.di.ApplicationScope
import com.appvalence.umay.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the "Keşfet" list currently shows. */
data class PoolListing(
    val summaries: List<PublicBuildingSummary> = emptyList(),
    val loading: Boolean = false,
    val failure: RemoteMapFailure? = null,
    val fetchedAtMs: Long = 0L,
)

/**
 * The shared map pool as the app sees it, living as long as the process rather
 * than as long as any screen.
 *
 * The listing is cached and only re-fetched once it's older than
 * [LISTING_MAX_AGE_MS] (or on an explicit "Yenile"), Firebase is only touched
 * once the pool is actually used — and then first on a background thread — and
 * connectivity is watched only while someone is looking at it.
 *
 * Publishing and unpublishing run in the app's scope, not a screen's: leaving
 * the screen mid-publish must not cancel the local bookkeeping while the write
 * itself still reaches the server, which would leave a publication this device
 * no longer knew it owned (and so couldn't take down).
 */
@Singleton
class SharedMapPool @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val remoteRepository: dagger.Lazy<RemoteBuildingMapRepository>,
    connectivity: ConnectivityMonitor,
    private val published: PublishedBuildings,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private suspend fun remote(): RemoteBuildingMapRepository = withContext(io) { remoteRepository.get() }

    val isOnline: StateFlow<Boolean> = connectivity.isOnline
        .stateIn(scope, SharingStarted.WhileSubscribed(CONNECTIVITY_KEEPALIVE_MS), false)

    private val _listing = MutableStateFlow(PoolListing())
    val listing: StateFlow<PoolListing> = _listing.asStateFlow()

    private val _publishedIds = MutableStateFlow(published.publishedLocalIds())

    /** Local building ids this device has published — observable, so rows update as soon as a publish lands. */
    val publishedIds: StateFlow<Set<String>> = _publishedIds.asStateFlow()

    // A refresh asked for while one is already in flight (say, right after a publish)
    // runs once that one finishes, instead of being dropped and leaving the list stale.
    private var refreshPending = false

    fun refreshIfStale() {
        val current = _listing.value
        val stale = current.fetchedAtMs == 0L || clock.nowMs() - current.fetchedAtMs > LISTING_MAX_AGE_MS
        if (stale || current.failure != null) refresh()
    }

    fun refresh() {
        if (_listing.value.loading) {
            refreshPending = true
            return
        }
        _listing.update { it.copy(loading = true, failure = null) }
        scope.launch {
            remote().listPublicBuildings()
                .onSuccess { summaries -> _listing.value = PoolListing(summaries = summaries, fetchedAtMs = clock.nowMs()) }
                .onFailure { error -> _listing.update { it.copy(loading = false, failure = error.asRemoteMapFailure()) } }
            if (refreshPending) {
                refreshPending = false
                refresh()
            }
        }
    }

    suspend fun publish(map: BuildingMap): Result<Unit> = scope.async {
        remote().publishBuildingMap(map).map { remoteId ->
            published.markPublished(map.id, remoteId)
            _publishedIds.update { it + map.id }
            refresh()
        }
    }.await()

    suspend fun unpublish(localBuildingId: String): Result<Unit> = scope.async {
        val remoteId = published.remoteIdFor(localBuildingId) ?: return@async Result.success(Unit)
        remote().unpublishBuildingMap(remoteId).map {
            published.markUnpublished(localBuildingId)
            _publishedIds.update { it - localBuildingId }
            refresh()
        }
    }.await()

    suspend fun download(remoteId: String): Result<BuildingMap> = remote().downloadBuildingMap(remoteId)

    companion object {
        const val LISTING_MAX_AGE_MS = 5 * 60_000L
        private const val CONNECTIVITY_KEEPALIVE_MS = 5_000L
    }
}
