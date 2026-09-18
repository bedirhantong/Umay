package com.appvalence.umay.storage

import android.content.Context
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import java.io.File

/**
 * Persists every surveyed [BuildingMap] this app knows about — one JSON file
 * per building, named after [BuildingMap.id], under the app's private
 * storage. Multiple buildings can be surveyed and kept side by side; saving
 * again under the same id overwrites that one building only.
 */
class BuildingMapStorage(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    init {
        migrateLegacySingleFile(context)
    }

    /** Returns true on success; false (never throws) if the disk write failed — e.g. storage full. */
    fun save(map: BuildingMap): Boolean =
        runCatching { fileFor(map.id).writeText(BuildingMapJson.encode(map)) }.isSuccess

    fun load(buildingId: String): BuildingMap? {
        val file = fileFor(buildingId)
        if (!file.exists()) return null
        return runCatching { BuildingMapJson.decode(file.readText()) }.getOrNull()
    }

    fun delete(buildingId: String): Boolean = fileFor(buildingId).delete()

    fun listBuildings(): List<BuildingMap> =
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { BuildingMapJson.decode(file.readText()) }.getOrNull() }
            ?.sortedBy { it.id }
            .orEmpty()

    private fun fileFor(buildingId: String): File = File(directory, "${sanitizeFileName(buildingId)}.json")

    private fun sanitizeFileName(id: String): String = id.replace(UNSAFE_FILENAME_CHARS, "_").ifBlank { "building" }

    // Earlier versions of this app kept exactly one map at <filesDir>/building_map.json.
    // On first use of the new multi-building layout, adopt it instead of silently losing
    // a survey someone already did — this only ever runs once, since the legacy file is
    // renamed away as part of the migration.
    private fun migrateLegacySingleFile(context: Context) {
        val legacyFile = File(context.filesDir, LEGACY_FILE_NAME)
        if (!legacyFile.exists()) return
        val map = runCatching { BuildingMapJson.decode(legacyFile.readText()) }.getOrNull()
        if (map != null) {
            runCatching { fileFor(map.id).writeText(BuildingMapJson.encode(map)) }
        }
        legacyFile.delete()
    }

    companion object {
        private const val DIRECTORY_NAME = "buildings"
        private const val LEGACY_FILE_NAME = "building_map.json"
        private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}
