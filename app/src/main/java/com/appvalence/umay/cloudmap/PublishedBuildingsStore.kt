package com.appvalence.umay.cloudmap

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which local buildings this device has published to the shared pool, and
 * under which remote id — the only place that association is recorded. The
 * pool has no accounts to ask "what did I publish" against, so this is what
 * lets Dosyalar offer "yayından kaldır" only next to a building this same
 * device published, and what supplies the remote id that action needs.
 *
 * Deliberately not a security boundary — firestore.rules' ownership check
 * (via anonymous auth) is what actually stops someone else from touching a
 * publication. This is a local convenience: reinstalling the app or clearing
 * its storage loses it, just as it loses the anonymous identity itself.
 */
interface PublishedBuildings {
    /** The remote id [localBuildingId] was published under, or null if this device hasn't published it (or has unpublished it since). */
    fun remoteIdFor(localBuildingId: String): String?

    fun markPublished(localBuildingId: String, remoteId: String)

    fun markUnpublished(localBuildingId: String)

    /** Every local building this device has published, by local id. */
    fun publishedLocalIds(): Set<String>
}

@Singleton
class PublishedBuildingsStore @Inject constructor(@ApplicationContext context: Context) : PublishedBuildings {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun remoteIdFor(localBuildingId: String): String? = preferences.getString(keyFor(localBuildingId), null)

    override fun markPublished(localBuildingId: String, remoteId: String) {
        preferences.edit { putString(keyFor(localBuildingId), remoteId) }
    }

    override fun markUnpublished(localBuildingId: String) {
        preferences.edit { remove(keyFor(localBuildingId)) }
    }

    override fun publishedLocalIds(): Set<String> =
        preferences.all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }.toSet()

    private fun keyFor(localBuildingId: String) = "$KEY_PREFIX$localBuildingId"

    private companion object {
        const val PREFERENCES_NAME = "published_buildings"
        const val KEY_PREFIX = "remote_id:"
    }
}
