package com.appvalence.umay.cloudmap

import android.content.Context
import androidx.core.content.edit

/**
 * Which local buildings this device has published to the shared pool, and
 * under which remote id — the only place that association is recorded.
 * [com.appvalence.umay.cloud.RemoteBuildingMapRepository] doesn't expose a
 * "list what I published" query (the pool has no accounts to list against),
 * so this is what lets the Dosyalar screen show "yayından kaldır" only next
 * to a building this same device actually published, and what supplies the
 * remote id that action needs.
 *
 * Deliberately not a security boundary — Firestore's own rules are what
 * actually stop someone else from touching a publication (see
 * firestore.rules' ownership check via anonymous auth). This is just a
 * local, best-effort convenience: reinstalling the app or clearing storage
 * loses it, same as it loses the anonymous identity itself.
 */
class PublishedBuildingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The remote id [localBuildingId] was published under, or null if this device hasn't published it (or has unpublished it since). */
    fun remoteIdFor(localBuildingId: String): String? = preferences.getString(keyFor(localBuildingId), null)

    fun markPublished(localBuildingId: String, remoteId: String) {
        preferences.edit { putString(keyFor(localBuildingId), remoteId) }
    }

    fun markUnpublished(localBuildingId: String) {
        preferences.edit { remove(keyFor(localBuildingId)) }
    }

    /** Every local building this device has published, by local id. */
    fun publishedLocalIds(): Set<String> =
        preferences.all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }.toSet()

    private fun keyFor(localBuildingId: String) = "$KEY_PREFIX$localBuildingId"

    private companion object {
        const val PREFERENCES_NAME = "published_buildings"
        const val KEY_PREFIX = "remote_id:"
    }
}
