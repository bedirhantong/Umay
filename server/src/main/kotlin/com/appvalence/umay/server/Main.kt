package com.appvalence.umay.server

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.system.exitProcess

/**
 * Starts the institution map server. Settings come from the environment
 * (see docs/SUNUCU.md):
 *
 *   UMAY_PUBLISH_TOKEN      required; staff phones use it to publish
 *   UMAY_READ_TOKEN         optional; if set, reading needs it too
 *   UMAY_DATA_DIR           where maps are kept (default ./umay-maps)
 *   UMAY_PORT               default 8443
 *   UMAY_KEYSTORE           PKCS#12 file with the server's certificate and key
 *   UMAY_KEYSTORE_PASSWORD  its password
 *   UMAY_PLAIN_HTTP=1       no TLS — only behind a TLS-terminating proxy
 */
fun main() {
    val env = System.getenv()
    val publishToken = env["UMAY_PUBLISH_TOKEN"] ?: fail("UMAY_PUBLISH_TOKEN is required")
    val readToken = env["UMAY_READ_TOKEN"]?.takeIf { it.isNotBlank() }
    val dataDir = File(env["UMAY_DATA_DIR"] ?: "umay-maps")
    val port = env["UMAY_PORT"]?.toIntOrNull() ?: 8443
    val plain = env["UMAY_PLAIN_HTTP"] == "1"

    val address = InetSocketAddress(port)
    val server: HttpServer = if (plain) {
        HttpServer.create(address, 0)
    } else {
        val keystore = env["UMAY_KEYSTORE"] ?: fail("UMAY_KEYSTORE is required (or UMAY_PLAIN_HTTP=1 behind a TLS proxy)")
        val password = (env["UMAY_KEYSTORE_PASSWORD"] ?: "").toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply { File(keystore).inputStream().use { load(it, password) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }.keyManagers
        val tls = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        HttpsServer.create(address, 0).apply { httpsConfigurator = HttpsConfigurator(tls) }
    }
    MapServer(MapStore(dataDir), publishToken, readToken).install(server)
    server.start()
    println("Umay map server on port $port (${if (plain) "plain HTTP" else "HTTPS"}), maps in ${dataDir.absolutePath}")
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
