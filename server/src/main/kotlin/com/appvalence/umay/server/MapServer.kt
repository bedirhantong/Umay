package com.appvalence.umay.server

import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.coremodel.NewerMapFormatException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The HTTP API (protocol in docs/SUNUCU.md):
 *
 *   GET    /v1/maps        list of [MapSummary]
 *   GET    /v1/maps/{id}   one map, as a .umay.json file
 *   PUT    /v1/maps/{id}   store a map (body: .umay.json); answers its id
 *   DELETE /v1/maps/{id}   remove one
 *
 * Reading needs [readToken] if one is set; writing always needs [publishToken].
 * Tokens travel as "Authorization: Bearer …" and are compared in constant time.
 * Maps are checked before they're stored: a broken or oversized one is refused.
 */
class MapServer(
    private val store: MapStore,
    private val publishToken: String,
    private val readToken: String?,
) {
    private val json = Json { encodeDefaults = true }

    init {
        require(publishToken.length >= MIN_TOKEN_LENGTH) { "The publish token must be at least $MIN_TOKEN_LENGTH characters" }
        require(readToken == null || readToken.length >= MIN_TOKEN_LENGTH) { "The read token must be at least $MIN_TOKEN_LENGTH characters" }
    }

    fun install(server: HttpServer) {
        server.createContext("/v1/maps") { exchange -> exchange.use(::handle) }
        server.executor = Executors.newFixedThreadPool(THREADS)
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/v1/maps").trim('/')
        val response = try {
            when {
                exchange.requestMethod == "GET" && path.isEmpty() -> authorized(exchange, readToken) { list() }
                exchange.requestMethod == "GET" -> authorized(exchange, readToken) { get(path) }
                exchange.requestMethod == "PUT" -> authorized(exchange, publishToken) { put(path, exchange) }
                exchange.requestMethod == "DELETE" -> authorized(exchange, publishToken) { delete(path) }
                else -> Response(405, "Method not allowed")
            }
        } catch (e: IllegalArgumentException) {
            Response(400, e.message ?: "Bad request")
        } catch (e: Exception) {
            System.err.println("Request failed: ${e.message}")
            Response(500, "Server error")
        }
        val bytes = response.body.toByteArray()
        exchange.responseHeaders.add("Content-Type", response.contentType)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    private fun list() = Response(200, json.encodeToString(ListSerializer(MapSummary.serializer()), store.list()), JSON)

    private fun get(id: String) = store.get(id)?.let { Response(200, it, JSON) } ?: Response(404, "No such map")

    private fun put(id: String, exchange: HttpExchange): Response {
        val body = exchange.requestBody.use { it.readNBytes(MAX_MAP_BYTES + 1) }
        if (body.size > MAX_MAP_BYTES) return Response(413, "Map too large")
        val map = try {
            BuildingMapJson.decode(body.decodeToString())
        } catch (e: NewerMapFormatException) {
            return Response(422, "Map format ${e.version} is newer than this server; update it")
        } catch (e: Exception) {
            return Response(400, "Not a map")
        }
        if (BuildingMapJson.problems(map).isNotEmpty()) return Response(422, "The map is inconsistent")
        if (MapStore.idFor(map.id) != id) return Response(400, "The id doesn't match the building's name")
        return Response(200, store.put(map))
    }

    private fun delete(id: String) = if (store.delete(id)) Response(204, "") else Response(404, "No such map")

    private inline fun authorized(exchange: HttpExchange, token: String?, block: () -> Response): Response {
        if (token == null) return block()
        val given = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        return if (MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) block() else Response(401, "Unauthorized")
    }

    private class Response(val status: Int, val body: String, val contentType: String = TEXT)

    companion object {
        const val MAX_MAP_BYTES = 5 * 1024 * 1024
        const val MIN_TOKEN_LENGTH = 16
        private const val THREADS = 4
        private const val JSON = "application/json; charset=utf-8"
        private const val TEXT = "text/plain; charset=utf-8"
    }
}
