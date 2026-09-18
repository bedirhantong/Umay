package com.appvalence.umay

import android.content.Context
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import java.io.File

/**
 * Persists the single [BuildingMap] this app works with to a JSON file under
 * the app's private storage. One building at a time is all Faz 3 needs;
 * this is intentionally not a general-purpose multi-map store.
 */
class BuildingMapStorage(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun save(map: BuildingMap) {
        file.writeText(BuildingMapJson.encode(map))
    }

    fun load(): BuildingMap? {
        if (!file.exists()) return null
        return runCatching { BuildingMapJson.decode(file.readText()) }.getOrNull()
    }

    fun exists(): Boolean = file.exists()

    companion object {
        private const val FILE_NAME = "building_map.json"
    }
}
