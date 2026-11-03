package org.hhclicker.launcher.runtime

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Bytes received so far and the announced total, when the server sent a content length. */
data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?)

/** Result of one download attempt. Every failure is distinguishable. */
sealed interface DownloadOutcome {
    data class Success(val file: Path, val sizeBytes: Long) : DownloadOutcome

    /** The server answered, but not with a success status. */
    data class HttpFailure(val status: Int) : DownloadOutcome

    /** The connection failed, timed out or was reset. */
    data object NetworkFailure : DownloadOutcome

    /** The response is larger than the accepted installer size. */
    data object TooLarge : DownloadOutcome

    /** The caller cancelled while the body was being read. */
    data object Cancelled : DownloadOutcome

    /** The URL, or a redirect target, was not HTTPS. */
    data object InsecureUrl : DownloadOutcome

    /** Too many redirects. */
    data object TooManyRedirects : DownloadOutcome
}

/**
 * Downloads one file.
 *
 * The abstraction exists so that the preparation flow can be unit tested with a fake transport: no test
 * ever opens a socket or fetches a real Docker installer.
 */
interface FileDownloader {
    suspend fun download(
        url: String,
        target: Path,
        maxBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutcome
}

/**
 * HTTPS downloader built on the JDK HTTP client.
 *
 * Redirects are followed manually so that the hop count is bounded and every hop can be required to stay
 * on HTTPS. The body is streamed to a temporary file next to the target and only moved into place after a
 * complete, size-checked read; any failure removes the partial file.
 */
class HttpFileDownloader(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val progressIntervalBytes: Long = PROGRESS_INTERVAL_BYTES,
) : FileDownloader {

    override suspend fun download(
        url: String,
        target: Path,
        maxBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        var current = runCatching { URI.create(url) }.getOrNull() ?: return@withContext DownloadOutcome.NetworkFailure
        var hops = 0

        while (true) {
            if (!current.scheme.equals("https", ignoreCase = true)) return@withContext DownloadOutcome.InsecureUrl

            val response = try {
                client.send(
                    HttpRequest.newBuilder(current)
                        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            } catch (_: InterruptedException) {
                return@withContext DownloadOutcome.Cancelled
            } catch (_: Exception) {
                return@withContext DownloadOutcome.NetworkFailure
            }

            val status = response.statusCode()
            if (status in 300..399) {
                response.body().use { it.readNBytes(0) }
                if (hops >= maxRedirects) return@withContext DownloadOutcome.TooManyRedirects
                val location = response.headers().firstValue("location").orElse(null)
                    ?: return@withContext DownloadOutcome.HttpFailure(status)
                current = runCatching { current.resolve(location) }.getOrNull()
                    ?: return@withContext DownloadOutcome.NetworkFailure
                hops++
                continue
            }

            if (status !in 200..299) {
                response.body().use { it.readNBytes(0) }
                return@withContext DownloadOutcome.HttpFailure(status)
            }

            val announced = response.headers().firstValueAsLong("content-length").orElse(-1L)
            if (announced > maxBytes) {
                response.body().use { it.readNBytes(0) }
                return@withContext DownloadOutcome.TooLarge
            }

            return@withContext response.body().use { body ->
                writeBody(body, target, maxBytes, announced.takeIf { it >= 0 }, onProgress)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        DownloadOutcome.NetworkFailure
    }

    /**
     * Streams a response body to disk.
     *
     * Internal so that the size limit, the progress reporting, the truncation check and the removal of a
     * partial file are unit tested against a plain in-memory stream, without opening a socket.
     */
    internal suspend fun writeBody(
        body: InputStream,
        target: Path,
        maxBytes: Long,
        totalBytes: Long?,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val parent = target.parent
        if (parent != null) runCatching { Files.createDirectories(parent) }
        val partial = target.resolveSibling(target.fileName.toString() + PARTIAL_SUFFIX)

        var written = 0L
        var lastReported = 0L
        try {
            Files.newOutputStream(partial).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    if (!currentCoroutineContext().isActive) return@withContext abort(partial, DownloadOutcome.Cancelled)
                    val read = body.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > maxBytes) return@withContext abort(partial, DownloadOutcome.TooLarge)
                    output.write(buffer, 0, read)
                    if (written - lastReported >= progressIntervalBytes) {
                        lastReported = written
                        onProgress(DownloadProgress(written, totalBytes))
                    }
                }
            }
        } catch (_: Exception) {
            return@withContext abort(partial, DownloadOutcome.NetworkFailure)
        }

        // A truncated response must never be handed to the signature check as a complete installer.
        if (totalBytes != null && written != totalBytes) return@withContext abort(partial, DownloadOutcome.NetworkFailure)

        onProgress(DownloadProgress(written, totalBytes))
        return@withContext try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            DownloadOutcome.Success(target, written)
        } catch (_: Exception) {
            abort(partial, DownloadOutcome.NetworkFailure)
        }
    }

    private fun abort(partial: Path, outcome: DownloadOutcome): DownloadOutcome {
        runCatching { Files.deleteIfExists(partial) }
        return outcome
    }

    companion object {
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val REQUEST_TIMEOUT_SECONDS = 120L
        const val MAX_REDIRECTS = 5
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_BYTES = 2L * 1024 * 1024
        const val PARTIAL_SUFFIX = ".partial"
    }
}
