package com.appvalence.umay.cloud

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.coremodel.InstitutionMapIds
import com.appvalence.umay.coremodel.NewerMapFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The shared map pool on an institution's own server (the `server` module,
 * docs/SUNUCU.md) instead of Firebase: same listing, download, publish and
 * take-down, over HTTPS to [baseUrl] only.
 *
 * Who may publish isn't "the device that uploaded it" here but whoever holds
 * the institution's [publishToken]; a build without one (visitors' phones)
 * can only browse and download.
 */
class HttpBuildingMapRepository(
    baseUrl: String,
    private val readToken: String?,
    private val publishToken: String?,
) : RemoteBuildingMapRepository {
    private val maps = baseUrl.trimEnd('/') + "/v1/maps"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Summary(
        val id: String,
        val displayName: String,
        val floorCount: Int,
        val nodeCount: Int,
        val sizeBytes: Long,
        val updatedAtMs: Long,
    )

    override suspend fun listPublicBuildings(): Result<List<PublicBuildingSummary>> = request("GET", maps, readToken) { body ->
        json.decodeFromString(ListSerializer(Summary.serializer()), body).map {
            PublicBuildingSummary(it.id, it.displayName, it.floorCount, it.nodeCount, it.sizeBytes, it.updatedAtMs)
        }
    }

    override suspend fun downloadBuildingMap(id: String): Result<BuildingMap> = request("GET", "$maps/$id", readToken) { body ->
        try {
            BuildingMapJson.decode(body)
        } catch (e: NewerMapFormatException) {
            throw RemoteMapException(RemoteMapFailure.NewerFormat)
        } catch (e: Exception) {
            throw RemoteMapException(RemoteMapFailure.InvalidMap(emptyList()))
        }
    }

    override suspend fun publishBuildingMap(map: BuildingMap): Result<String> {
        val token = publishToken ?: return Result.remoteFailure(RemoteMapFailure.NotOwner)
        val problems = BuildingMapJson.problems(map)
        if (problems.isNotEmpty()) return Result.remoteFailure(RemoteMapFailure.InvalidMap(problems))
        val encoded = BuildingMapJson.encode(map)
        if (encoded.length > MAX_MAP_BYTES) return Result.remoteFailure(RemoteMapFailure.TooLarge(MAX_MAP_BYTES.toLong()))
        return request("PUT", "$maps/${InstitutionMapIds.idFor(map.id)}", token, encoded) { it }
    }

    override suspend fun unpublishBuildingMap(id: String): Result<Unit> {
        val token = publishToken ?: return Result.remoteFailure(RemoteMapFailure.NotOwner)
        return request("DELETE", "$maps/$id", token) { }
    }

    private suspend fun <T> request(method: String, url: String, token: String?, body: String? = null, read: (String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val connection = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return@withContext Result.remoteFailure(RemoteMapFailure.NoConnection)
            }
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                if (status !in 200..299) return@withContext Result.remoteFailure(failureFor(status, connection))
                val text = if (status == 204) "" else connection.inputStream.use { it.readBytes().decodeToString() }
                Result.success(read(text))
            } catch (e: RemoteMapException) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.remoteFailure(RemoteMapFailure.NoConnection)
            } catch (e: Exception) {
                Result.remoteFailure(RemoteMapFailure.Other(e.message))
            } finally {
                connection.disconnect()
            }
        }

    private fun failureFor(status: Int, connection: HttpURLConnection): RemoteMapFailure = when (status) {
        401, 403 -> RemoteMapFailure.NotOwner
        404 -> RemoteMapFailure.NotFound
        413 -> RemoteMapFailure.TooLarge(MAX_MAP_BYTES.toLong())
        400, 422 -> RemoteMapFailure.InvalidMap(emptyList())
        else -> RemoteMapFailure.Other(runCatching { connection.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull() ?: "HTTP $status")
    }

    companion object {
        /** The server's own limit (MapServer.MAX_MAP_BYTES). */
        const val MAX_MAP_BYTES = 5 * 1024 * 1024
        private const val TIMEOUT_MS = 15_000
    }
}
