package com.appvalence.umay.coremodel

import kotlinx.serialization.json.Json

/**
 * JSON (de)serialization for [BuildingMap] (Faz 3). This is deliberately just
 * string-in/string-out — where the resulting JSON is actually stored (a file
 * under the app's private storage, a shared asset, etc.) is a platform
 * concern that lives outside `core-model`.
 */
object BuildingMapJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun encode(map: BuildingMap): String = json.encodeToString(BuildingMap.serializer(), map)

    fun decode(text: String): BuildingMap = json.decodeFromString(BuildingMap.serializer(), text)
}
