package org.hhclicker.launcher.runtime

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class HttpReadinessProbe(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build(),
) {
    suspend fun await(url: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = withContext(Dispatchers.IO) {
                runCatching {
                    val request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build()
                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..399
                }.getOrDefault(false)
            }
            if (ready) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}
