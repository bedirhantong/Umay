package com.appvalence.umay.cloud

import com.appvalence.umay.coremodel.BuildingMap

/**
 * A building map someone has published to the shared pool: small enough to
 * list many of at once (the map itself, which can be a few hundred KB, is
 * fetched separately and only on demand — see
 * [RemoteBuildingMapRepository.downloadBuildingMap]). This is the whole
 * point of keeping listing and content apart: browsing available buildings
 * must never require downloading all of them.
 */
data class PublicBuildingSummary(
    val id: String,
    val displayName: String,
    val floorCount: Int,
    val nodeCount: Int,
    val sizeBytes: Long,
    val updatedAtMs: Long,
)

/** Why a cloud building-map operation didn't complete. */
sealed interface RemoteMapFailure {
    /** [ConnectivityMonitor] said online, but the request still couldn't reach the server. */
    data object NoConnection : RemoteMapFailure
    data object NotFound : RemoteMapFailure
    data class TooLarge(val maxBytes: Long) : RemoteMapFailure
    data class InvalidMap(val problems: List<com.appvalence.umay.coremodel.MapProblem>) : RemoteMapFailure

    /** Trying to update or delete someone else's publication — only its original publisher may. */
    data object NotOwner : RemoteMapFailure
    data class Other(val message: String?) : RemoteMapFailure
}

/**
 * The shared pool of building maps: anyone can publish a map they surveyed
 * so the next person who visits the same building doesn't have to survey it
 * again. Every method requires connectivity — check
 * [ConnectivityMonitor.isOnline] first and show the offline alternative
 * instead of calling these when it's false (they still fail gracefully with
 * [RemoteMapFailure.NoConnection] either way, for a connection that drops
 * mid-call).
 *
 * There's no sign-in screen anywhere in this app; publishing and unpublishing
 * sign in anonymously the first time they're needed (a silent, deviceless
 * identity — see [FirestoreBuildingMapRepository]) so the pool's security
 * rules can tell who published what, without ever asking anyone to log in.
 */
interface RemoteBuildingMapRepository {
    suspend fun listPublicBuildings(): Result<List<PublicBuildingSummary>>

    suspend fun downloadBuildingMap(id: String): Result<BuildingMap>

    /**
     * Publishing is always an explicit, informed choice the surveyor makes —
     * never automatic — since it makes the map visible to anyone. Overwrites
     * an existing publication with the same [BuildingMap.id], but only if
     * this device published it in the first place ([RemoteMapFailure.NotOwner]
     * otherwise).
     */
    suspend fun publishBuildingMap(map: BuildingMap): Result<Unit>

    /** [RemoteMapFailure.NotOwner] unless this device is the one that published [id]. */
    suspend fun unpublishBuildingMap(id: String): Result<Unit>
}

internal fun Result.Companion.remoteFailure(failure: RemoteMapFailure): Result<Nothing> = failure(RemoteMapException(failure))

/** Carries a typed [RemoteMapFailure] through a [Result] without callers needing to catch exceptions. */
class RemoteMapException(val failure: RemoteMapFailure) : Exception()

/** Unwraps a failed [Result] produced by this repository back to its [RemoteMapFailure], defaulting to [RemoteMapFailure.Other]. */
fun Throwable.asRemoteMapFailure(): RemoteMapFailure = (this as? RemoteMapException)?.failure ?: RemoteMapFailure.Other(message)
