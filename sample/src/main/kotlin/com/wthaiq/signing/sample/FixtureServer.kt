package com.wthaiq.signing.sample

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny HTTP server on 127.0.0.1 that serves the offline fixture pages from `assets/`, so the sample can show the
 * whole flow without a backend. It is for the sample only: production links are always https on Wthaiq hosts.
 */
class FixtureServer(private val assets: AssetManager) {
    private var socket: ServerSocket? = null

    /** The base URL, for example `http://127.0.0.1:41234`. Starts the server on first use. */
    @get:Synchronized
    val baseUrl: String
        get() {
            val server = socket ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also {
                socket = it
                thread(name = "fixture-server", isDaemon = true) { accept(it) }
            }
            return "http://127.0.0.1:${server.localPort}"
        }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            thread(isDaemon = true) { client.use(::respond) }
        }
    }

    private fun respond(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
        val asset = when {
            path.startsWith("/embed/sign/") -> "embed.html"
            path == "/sign.php" -> "sign.html"
            else -> null
        }
        val output = client.getOutputStream()
        if (asset == null) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val body = assets.open(asset).use { it.readBytes() }
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray())
        output.write(body)
        output.flush()
    }
}
