package com.appvalence.umay.cloud

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.InstitutionMapIds
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Against a stand-in server speaking the protocol (the real one is tested in :server). */
class HttpBuildingMapRepositoryTest {
    private lateinit var server: HttpServer
    private val stored = mutableMapOf<String, String>()
    private val authorizations = mutableListOf<String?>()

    private val office = BuildingMap(
        id = "Ana Bina",
        floors = listOf(FloorMap(0, listOf(MapNode("a", 0.0, 0.0, NodeType.WAYPOINT, "Giriş"), MapNode("b", 5.0, 0.0, NodeType.WAYPOINT, "Danışma")), listOf(MapEdge("a", "b", true, 5.0)))),
    )

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/maps") { exchange ->
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            val id = exchange.requestURI.path.removePrefix("/v1/maps").trim('/')
            val (status, body) = when (exchange.requestMethod) {
                "GET" -> if (id.isEmpty()) {
                    200 to stored.keys.joinToString(prefix = "[", postfix = "]") { """{"id":"$it","displayName":"Ana Bina","floorCount":1,"nodeCount":2,"sizeBytes":10,"updatedAtMs":5,"extra":true}""" }
                } else {
                    stored[id]?.let { 200 to it } ?: (404 to "")
                }
                "PUT" -> {
                    if (exchange.requestHeaders.getFirst("Authorization") != "Bearer publish") 401 to "" else {
                        stored[id] = exchange.requestBody.readBytes().decodeToString()
                        200 to id
                    }
                }
                "DELETE" -> if (stored.remove(id) != null) 204 to "" else 404 to ""
                else -> 405 to ""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun repository(publish: String? = "publish") = HttpBuildingMapRepository("http://127.0.0.1:${server.address.port}/", "read", publish)

    @Test
    fun `publish, list, download and take down, with the right tokens`() = runTest {
        val repository = repository()

        val id = repository.publishBuildingMap(office).getOrThrow()
        assertEquals(InstitutionMapIds.idFor("Ana Bina"), id)
        assertEquals(listOf("Ana Bina"), repository.listPublicBuildings().getOrThrow().map { it.displayName })
        assertEquals(office, repository.downloadBuildingMap(id).getOrThrow())
        repository.unpublishBuildingMap(id).getOrThrow()

        assertEquals(listOf<String?>("Bearer publish", "Bearer read", "Bearer read", "Bearer publish"), authorizations.toList())
    }

    @Test
    fun `a build without the publish token can browse but not publish`() = runTest {
        val failure = repository(publish = null).publishBuildingMap(office).exceptionOrNull()!!.asRemoteMapFailure()

        assertEquals(RemoteMapFailure.NotOwner, failure)
        assertEquals(emptyList<String?>(), authorizations.toList())
    }

    @Test
    fun `server answers become typed failures`() = runTest {
        assertEquals(RemoteMapFailure.NotFound, repository().downloadBuildingMap("yok-1234").exceptionOrNull()!!.asRemoteMapFailure())
        assertEquals(RemoteMapFailure.NotOwner, repository(publish = "wrong").publishBuildingMap(office).exceptionOrNull()!!.asRemoteMapFailure())
        stored["eski-1"] = BuildingMapJson.encode(office).replaceFirst("\"formatVersion\": 2", "\"formatVersion\": 99")
        assertEquals(RemoteMapFailure.NewerFormat, repository().downloadBuildingMap("eski-1").exceptionOrNull()!!.asRemoteMapFailure())
    }

    @Test
    fun `an unreachable server is no connection`() = runTest {
        val failure = HttpBuildingMapRepository("http://127.0.0.1:1", null, null).listPublicBuildings().exceptionOrNull()!!.asRemoteMapFailure()

        assertEquals(RemoteMapFailure.NoConnection, failure)
    }
}
