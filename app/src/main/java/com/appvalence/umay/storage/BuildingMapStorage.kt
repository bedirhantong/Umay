package com.appvalence.umay.storage

import android.content.Context
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import java.io.File

/** Where building maps live — an interface so [BuildingRepository] can be tested without a device. */
interface BuildingMapStore {
    fun listBuildings(): List<BuildingMap>

    /** Returns true on success; false (never throws) if the write failed — e.g. storage full. */
    fun save(map: BuildingMap): Boolean
    fun exists(buildingId: String): Boolean
    fun delete(buildingId: String): Boolean

    /** A shareable copy of [map] for another device; null if it couldn't be written. */
    fun exportCopy(map: BuildingMap): File?
}

/**
 * Persists every surveyed [BuildingMap] this app knows about — one JSON file
 * per building, named after [BuildingMap.id], under the app's private
 * storage. Multiple buildings can be surveyed and kept side by side; saving
 * again under the same id overwrites that one building only.
 *
 * Every method does disk I/O: call from a background thread (see
 * [BuildingRepository], the only intended caller).
 */
class BuildingMapStorage(context: Context) : BuildingMapStore {
    private val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    private val exportDirectory = File(context.cacheDir, EXPORT_DIRECTORY_NAME)

    init {
        migrateLegacySingleFile(context)
        migrateUnhashedFileNames()
    }

    override fun save(map: BuildingMap): Boolean =
        runCatching { fileFor(map.id).writeText(BuildingMapJson.encode(map)) }.isSuccess

    fun load(buildingId: String): BuildingMap? {
        val file = fileFor(buildingId)
        if (!file.exists()) return null
        return runCatching { BuildingMapJson.decode(file.readText()) }.getOrNull()
    }

    override fun exists(buildingId: String): Boolean = fileFor(buildingId).exists()

    override fun delete(buildingId: String): Boolean = fileFor(buildingId).delete()

    override fun listBuildings(): List<BuildingMap> =
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { BuildingMapJson.decode(file.readText()) }.getOrNull() }
            ?.sortedBy { it.id }
            .orEmpty()

    /**
     * Writes [map] to a shareable copy named after the building itself (not the
     * internal hashed file name), for handing to another device via
     * [shareFiles]. Lives in the cache directory: it's a throwaway copy, the
     * real one stays in [directory]. Null if the write failed.
     */
    override fun exportCopy(map: BuildingMap): File? = runCatching {
        exportDirectory.mkdirs()
        File(exportDirectory, "${sanitizeFileName(map.id)}$EXPORT_SUFFIX").apply {
            writeText(BuildingMapJson.encode(map))
        }
    }.getOrNull()

    // The sanitized name alone isn't unique: it replaces every non-ASCII letter with
    // "_", so "Şube" and "Çube" would land on the same file and one silently overwrite
    // the other. A short hash of the exact id keeps distinct names in distinct files
    // (String.hashCode is specified by the language, so this is stable across runs).
    private fun fileFor(buildingId: String): File = File(directory, fileNameFor(buildingId))

    private fun fileNameFor(buildingId: String): String =
        "${sanitizeFileName(buildingId)}-${"%08x".format(buildingId.hashCode())}.json"

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

    // Files saved before the hash suffix existed (see fileFor) are renamed once, so
    // load/exists/delete by id keep finding them.
    private fun migrateUnhashedFileNames() {
        directory.listFiles { file -> file.extension == "json" && !HASHED_FILE_NAME.matches(file.name) }
            ?.forEach { file ->
                val map = runCatching { BuildingMapJson.decode(file.readText()) }.getOrNull() ?: return@forEach
                val target = fileFor(map.id)
                if (!target.exists()) file.renameTo(target) else file.delete()
            }
    }

    companion object {
        private const val DIRECTORY_NAME = "buildings"
        private const val EXPORT_DIRECTORY_NAME = "exports"
        private const val EXPORT_SUFFIX = ".umay.json"
        private const val LEGACY_FILE_NAME = "building_map.json"
        private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9_-]")
        private val HASHED_FILE_NAME = Regex(".*-[0-9a-f]{8}\\.json")
    }
}
