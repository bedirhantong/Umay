package com.appvalence.umay.server

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapEdge
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapServerTest {
    private val publish = "publish-token-0123456789"
    private val read = "read-token-0123456789ab"
    private lateinit var server: HttpServer
    private lateinit var base: String

    private val office = BuildingMap(
        id = "Ana Bina",
        floors = listOf(FloorMap(0, listOf(MapNode("a", 0.0, 0.0, NodeType.WAYPOINT, "Giriş"), MapNode("b", 5.0, 0.0, NodeType.WAYPOINT, "Danışma")), listOf(MapEdge("a", "b", true, 5.0)))),
    )

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        MapServer(MapStore(Files.createTempDirectory("umay-maps").toFile()), publish, read).install(server)
        server.start()
        base = "http://127.0.0.1:${server.address.port}/v1/maps"
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun call(method: String, path: String = "", token: String? = read, body: String? = null): Pair<Int, String> {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return status to text
    }

    @Test
    fun `a published map is listed and downloaded whole`() {
        val id = MapStore.idFor(office.id)
        assertEquals(200 to id, call("PUT", "/$id", publish, BuildingMapJson.encode(office)))

        val (status, listing) = call("GET")
        assertEquals(200, status)
        assertTrue("\"displayName\":\"Ana Bina\"" in listing, listing)
        assertEquals(office, BuildingMapJson.decode(call("GET", "/$id").second))
    }

    @Test
    fun `reading needs the read token, writing the publish token`() {
        val id = MapStore.idFor(office.id)

        assertEquals(401, call("GET", token = null).first)
        assertEquals(401, call("PUT", "/$id", read, BuildingMapJson.encode(office)).first)
        assertEquals(401, call("DELETE", "/$id", read).first)
    }

    @Test
    fun `a broken map, or one stored under another name, is refused`() {
        val id = MapStore.idFor(office.id)
        val broken = office.copy(floors = listOf(office.floors[0].copy(edges = listOf(MapEdge("a", "ghost", true, 1.0)))))

        assertEquals(400, call("PUT", "/$id", publish, "not json").first)
        assertEquals(422, call("PUT", "/$id", publish, BuildingMapJson.encode(broken)).first)
        assertEquals(400, call("PUT", "/${MapStore.idFor("Başka")}", publish, BuildingMapJson.encode(office)).first)
    }

    @Test
    fun `a map can be taken down`() {
        val id = MapStore.idFor(office.id)
        call("PUT", "/$id", publish, BuildingMapJson.encode(office))

        assertEquals(204, call("DELETE", "/$id", publish).first)
        assertEquals(404, call("GET", "/$id").first)
    }

    @Test
    fun `ids can't reach outside the map directory`() {
        assertEquals(400, call("GET", "/..%2F..%2Fetc%2Fpasswd").first)
        assertTrue(MapStore.idFor("../../x").matches(Regex("[A-Za-z0-9_-]+")))
    }
}
