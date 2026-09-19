package com.appvalence.umay.cloudmap

import android.content.Context
import com.appvalence.umay.cloud.AndroidConnectivityMonitor
import com.appvalence.umay.cloud.FirestoreBuildingMapRepository
import com.appvalence.umay.cloud.PublicBuildingSummary
import com.appvalence.umay.cloud.RemoteBuildingMapRepository
import com.appvalence.umay.cloud.RemoteMapFailure
import com.appvalence.umay.cloud.asRemoteMapFailure
import com.appvalence.umay.coremodel.BuildingMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the "Keşfet" list currently shows. */
data class PoolListing(
    val summaries: List<PublicBuildingSummary> = emptyList(),
    val loading: Boolean = false,
    val failure: RemoteMapFailure? = null,
    val fetchedAtMs: Long = 0L,
)

/**
 * The shared map pool as the app sees it, living as long as the process rather
 * than as long as the Dosyalar screen is on screen.
 *
 * Owned per screen, all of this was rebuilt on every visit: a Firestore client,
 * a fresh network callback registration, and a full re-download of the public
 * listing. Here the listing is cached and only re-fetched once it's older than
 * [LISTING_MAX_AGE_MS] (or on an explicit "Yenile"), Firebase is only touched
 * once the pool is actually used, and connectivity is watched only while
 * someone is looking at it.
 *
 * Publishing and unpublishing run in the app's scope, not the screen's:
 * leaving the screen mid-publish used to cancel the local bookkeeping while
 * the write itself still reached the server, leaving a publication this
 * device no longer knew it owned (and so couldn't take down).
 */
class SharedMapPool(
    context: Context,
    private val scope: CoroutineScope,
    repositoryFactory: () -> RemoteBuildingMapRepository = { FirestoreBuildingMapRepository() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val repository by lazy(repositoryFactory)

    // Creating the Firestore/Auth clients sets Firebase up on first touch, which isn't
    // free — so that first touch happens on a background thread, not the main one.
    private suspend fun remote(): RemoteBuildingMapRepository = withContext(Dispatchers.IO) { repository }

    private val published = PublishedBuildingsStore(context)

    private val _publishedIds = MutableStateFlow(published.publishedLocalIds())

    /** Local building ids this device has published — observable, so rows update as soon as a publish lands. */
    val publishedIds: StateFlow<Set<String>> = _publishedIds.asStateFlow()

    val isOnline: StateFlow<Boolean> = AndroidConnectivityMonitor(context).isOnline
        .stateIn(scope, SharingStarted.WhileSubscribed(CONNECTIVITY_KEEPALIVE_MS), false)

    private val _listing = MutableStateFlow(PoolListing())
    val listing: StateFlow<PoolListing> = _listing.asStateFlow()

    // A refresh asked for while one is already in flight (say, right after a publish)
    // runs once that one finishes, instead of being dropped and leaving the list stale.
    private var refreshPending = false

    fun refreshIfStale() {
        val current = _listing.value
        val stale = current.fetchedAtMs == 0L || clock() - current.fetchedAtMs > LISTING_MAX_AGE_MS
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
                .onSuccess { summaries -> _listing.value = PoolListing(summaries = summaries, fetchedAtMs = clock()) }
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
