package com.appvalence.umay.server

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import kotlinx.serialization.Serializable
import java.io.File
import com.appvalence.umay.coremodel.InstitutionMapIds

/** What the listing says about one map, without sending the map itself. */
@Serializable
data class MapSummary(
    val id: String,
    val displayName: String,
    val floorCount: Int,
    val nodeCount: Int,
    val sizeBytes: Long,
    val updatedAtMs: Long,
)

/**
 * The maps, one `.umay.json` file each in [directory]. A file is replaced by
 * writing beside it and renaming, so a reader never sees half a map. The file
 * name is derived from the building's name (safe characters plus a hash), so
 * any name works and none can reach outside the directory.
 */
class MapStore(private val directory: File) {
    init {
        directory.mkdirs()
    }

    @Synchronized
    fun list(): List<MapSummary> = directory.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty()
        .mapNotNull { file -> runCatching { summaryOf(BuildingMapJson.decode(file.readText()), file) }.getOrNull() }
        .sortedBy { it.displayName }

    @Synchronized
    fun get(id: String): String? = fileFor(id).takeIf { it.isFile }?.readText()

    /** Stores [map] under its own name; returns its id. */
    @Synchronized
    fun put(map: BuildingMap): String {
        val id = idFor(map.id)
        val target = fileFor(id)
        val temporary = File(directory, "$id$SUFFIX.tmp")
        temporary.writeText(BuildingMapJson.encode(map))
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Could not store $id" }
        }
        return id
    }

    @Synchronized
    fun delete(id: String): Boolean = fileFor(id).delete()

    private fun summaryOf(map: BuildingMap, file: File) = MapSummary(
        id = file.name.removeSuffix(SUFFIX),
        displayName = map.id,
        floorCount = map.floors.size,
        nodeCount = map.floors.sumOf { it.nodes.size },
        sizeBytes = file.length(),
        updatedAtMs = file.lastModified(),
    )

    private fun fileFor(id: String): File {
        require(SAFE_ID.matches(id)) { "Bad map id" }
        return File(directory, id + SUFFIX)
    }

    companion object {
        private const val SUFFIX = ".umay.json"
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")

        /** The id rule the app uses too (see [InstitutionMapIds]). */
        fun idFor(buildingName: String): String = InstitutionMapIds.idFor(buildingName)
    }
}
