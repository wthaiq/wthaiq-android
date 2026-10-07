package com.wthaiq.signing.sample

import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks your backend for a signing URL. The backend calls the Wthaiq API with its secret key (for example
 * `POST /signers/{id}/signing_session` with `mode: "iframe"`) and answers `{"url": "<signing_url>"}`.
 * The app never holds an API key.
 */
object SigningBackend {
    fun fetchSigningUrl(endpoint: String): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write("{}".toByteArray()) }
            check(connection.responseCode in 200..299) { "Backend answered HTTP ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val match = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body) ?: error("No url in the backend response")
            return match.groupValues[1].replace("\\/", "/")
        } finally {
            connection.disconnect()
        }
    }
}
