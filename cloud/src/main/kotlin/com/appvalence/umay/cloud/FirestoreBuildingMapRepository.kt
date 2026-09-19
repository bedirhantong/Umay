package com.appvalence.umay.cloud

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The whole map — not just its listing metadata — lives in one Firestore
 * document. There is deliberately no Cloud Storage bucket anywhere in this
 * project: since February 2026, Cloud Storage for Firebase requires a
 * linked billing account (the paid Blaze plan) regardless of how little is
 * actually used, and this app stays on the free Spark plan on purpose. A
 * Firestore document has its own hard 1 MiB limit, which is why
 * [MAX_PUBLISHED_MAP_BYTES] sits well under that.
 *
 * The tradeoff this accepts: browsing the pool reads whole documents,
 * including their `mapJson`, not just the small summary fields — Firestore
 * has no way to fetch a subset of a document's fields on a query, so
 * [listPublicBuildings] only *uses* the summary fields in code, but the
 * server-side read (and its cost against the free quota) is still by
 * document, not by field. At the pool sizes this project can reach on
 * Spark's free quotas (50k reads/day), that's not a real cost — but it's
 * why nothing here should be mistaken for how a large-scale version of this
 * feature would have to be built.
 *
 * There's no login screen anywhere in the app; [publishBuildingMap] and
 * [unpublishBuildingMap] sign in anonymously (see [FirebaseAuth.signInAnonymously])
 * the first time either is needed. That identity is what
 * firestore.rules' ownership check is built on — it's what stops a
 * stranger from overwriting or deleting someone else's publication, without
 * ever asking anyone to create an account.
 */
class FirestoreBuildingMapRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : RemoteBuildingMapRepository {

    private val collection get() = firestore.collection(BUILDINGS_COLLECTION)

    override suspend fun listPublicBuildings(): Result<List<PublicBuildingSummary>> = runCatching {
        val snapshot = collection
            .orderBy(FIELD_UPDATED_AT, Query.Direction.DESCENDING)
            .limit(LIST_LIMIT)
            .getSuspending()
        snapshot.documents.mapNotNull { doc ->
            val displayName = doc.getString(FIELD_DISPLAY_NAME) ?: return@mapNotNull null
            PublicBuildingSummary(
                id = doc.id,
                displayName = displayName,
                floorCount = (doc.getLong(FIELD_FLOOR_COUNT) ?: 0).toInt(),
                nodeCount = (doc.getLong(FIELD_NODE_COUNT) ?: 0).toInt(),
                sizeBytes = doc.getLong(FIELD_SIZE_BYTES) ?: 0,
                updatedAtMs = doc.getLong(FIELD_UPDATED_AT) ?: 0,
            )
        }
    }.recoverFirestoreFailure()

    override suspend fun downloadBuildingMap(id: String): Result<BuildingMap> = runCatching {
        val doc = collection.document(id).getSuspending()
        val json = doc.getString(FIELD_MAP_JSON) ?: return Result.remoteFailure(RemoteMapFailure.NotFound)
        runCatching { BuildingMapJson.decode(json) }.getOrNull()
            ?: return Result.remoteFailure(RemoteMapFailure.Other("Bozuk harita verisi"))
    }.recoverFirestoreFailure()

    override suspend fun publishBuildingMap(map: BuildingMap): Result<Unit> = runCatching {
        val problems = BuildingMapJson.problems(map)
        if (problems.isNotEmpty()) return Result.remoteFailure(RemoteMapFailure.InvalidMap(problems))

        val encoded = BuildingMapJson.encode(map)
        val encodedBytes = encoded.encodeToByteArray().size
        if (encodedBytes > MAX_PUBLISHED_MAP_BYTES) return Result.remoteFailure(RemoteMapFailure.TooLarge(MAX_PUBLISHED_MAP_BYTES))

        val uid = ensureSignedIn()
        val id = sanitizeBuildingDocumentId(map.id)
        collection.document(id).setSuspending(
            mapOf(
                FIELD_OWNER_ID to uid,
                FIELD_DISPLAY_NAME to map.id,
                FIELD_FLOOR_COUNT to map.floors.size,
                FIELD_NODE_COUNT to map.floors.sumOf { it.nodes.size },
                FIELD_SIZE_BYTES to encodedBytes,
                FIELD_MAP_JSON to encoded,
                FIELD_UPDATED_AT to System.currentTimeMillis(),
            ),
        )
    }.recoverFirestoreFailure()

    override suspend fun unpublishBuildingMap(id: String): Result<Unit> = runCatching {
        ensureSignedIn()
        collection.document(id).deleteSuspending()
    }.recoverFirestoreFailure()

    // Anonymous sign-in is silent and deviceless — no UI, no credential the person has to
    // manage — and only ever needed for a write; listing and downloading stay signed-out.
    private suspend fun ensureSignedIn(): String {
        auth.currentUser?.let { return it.uid }
        return auth.signInAnonymouslySuspending().uid
    }

    companion object {
        private const val BUILDINGS_COLLECTION = "public_buildings"
        private const val FIELD_OWNER_ID = "ownerId"
        private const val FIELD_DISPLAY_NAME = "displayName"
        private const val FIELD_FLOOR_COUNT = "floorCount"
        private const val FIELD_NODE_COUNT = "nodeCount"
        private const val FIELD_SIZE_BYTES = "sizeBytes"
        private const val FIELD_MAP_JSON = "mapJson"
        private const val FIELD_UPDATED_AT = "updatedAtMs"

        /**
         * Firestore documents have a hard 1 MiB (1,048,576 byte) limit for everything in
         * them combined. This leaves generous headroom for the summary fields alongside
         * `mapJson` while still comfortably fitting a real multi-floor survey — see
         * firestore.rules' isValidBuilding, which enforces the same number server-side.
         */
        const val MAX_PUBLISHED_MAP_BYTES = 750_000L
        private const val LIST_LIMIT = 200L
    }
}

// Firestore document ids can't contain '/' and shouldn't be trusted as-is from a
// human-typed building name; sanitized the same way BuildingMapStorage names local
// files, with a hash so two different names that sanitize the same way don't collide.
internal fun sanitizeBuildingDocumentId(buildingId: String): String {
    // ifBlank on the *replaced* string would never trigger — every character (including
    // spaces) maps to "_", so an all-whitespace name would wrongly become "___-<hash>"
    // instead of falling back to "building-<hash>". Blank has to be checked first.
    val safe = if (buildingId.isBlank()) "building" else buildingId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
    return "$safe-${"%08x".format(buildingId.hashCode())}"
}

/** Extracted for testability: whether an [error] looks like "never reached the server" rather than a real server-side failure. */
internal fun looksLikeNoConnection(error: Throwable): Boolean {
    if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.UNAVAILABLE) return true
    val message = error.message.orEmpty()
    return message.contains("UNAVAILABLE", ignoreCase = true) ||
        message.contains("network", ignoreCase = true) ||
        message.contains("offline", ignoreCase = true)
}

private fun looksLikeNotOwner(error: Throwable): Boolean =
    error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED

/**
 * Normalizes Firestore's own exception types to a [RemoteMapFailure] so callers never
 * need to know them: a request that never reached the server becomes
 * [RemoteMapFailure.NoConnection], a security-rules rejection becomes
 * [RemoteMapFailure.NotOwner] (the only reason this app's own rules ever reject a
 * write), everything else passes through as [RemoteMapFailure.Other].
 */
private fun <T> Result<T>.recoverFirestoreFailure(): Result<T> = recoverCatching { error ->
    if (error is CancellationException) throw error
    if (error is RemoteMapException) throw error
    val failure = when {
        looksLikeNoConnection(error) -> RemoteMapFailure.NoConnection
        looksLikeNotOwner(error) -> RemoteMapFailure.NotOwner
        else -> RemoteMapFailure.Other(error.message)
    }
    throw RemoteMapException(failure)
}

private suspend fun com.google.firebase.firestore.Query.getSuspending() =
    suspendCancellableCoroutine { continuation ->
        get()
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

private suspend fun com.google.firebase.firestore.DocumentReference.getSuspending() =
    suspendCancellableCoroutine { continuation ->
        get()
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

private suspend fun com.google.firebase.firestore.DocumentReference.setSuspending(data: Map<String, Any>) =
    suspendCancellableCoroutine<Unit> { continuation ->
        set(data)
            .addOnSuccessListener { continuation.resume(Unit) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

private suspend fun com.google.firebase.firestore.DocumentReference.deleteSuspending() =
    suspendCancellableCoroutine<Unit> { continuation ->
        delete()
            .addOnSuccessListener { continuation.resume(Unit) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

private suspend fun FirebaseAuth.signInAnonymouslySuspending() =
    suspendCancellableCoroutine { continuation ->
        signInAnonymously()
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    continuation.resume(user)
                } else {
                    continuation.resumeWithException(IllegalStateException("Anonymous sign-in returned no user"))
                }
            }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }
