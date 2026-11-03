package org.hhclicker.launcher.runtime

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The streaming half of the downloader is exercised with an in-memory body.
 *
 * No socket is opened and no real installer is fetched: the test drives the same code that writes the
 * response to disk, enforces the size limit, reports progress and removes a partial file.
 */
class HttpFileDownloaderTest {

    private val downloader = HttpFileDownloader(progressIntervalBytes = 1024)

    private fun target(directory: Path) = directory.resolve("Docker Desktop Installer.exe")

    private fun partialOf(target: Path): Path =
        target.resolveSibling(target.fileName.toString() + HttpFileDownloader.PARTIAL_SUFFIX)

    @Test
    fun a_complete_body_is_written_and_progress_is_reported() = runBlocking {
        val directory = createTempDirectory("hh-download")
        val target = target(directory)
        val payload = ByteArray(10_000) { it.toByte() }
        val progress = ArrayList<DownloadProgress>()

        val outcome = downloader.writeBody(
            body = ByteArrayInputStream(payload),
            target = target,
            maxBytes = 1_000_000,
            totalBytes = payload.size.toLong(),
            onProgress = { progress += it },
        )

        assertTrue(outcome is DownloadOutcome.Success, outcome.toString())
        assertEquals(payload.size.toLong(), outcome.sizeBytes)
        assertTrue(Files.isRegularFile(target))
        assertEquals(payload.size.toLong(), Files.size(target))
        assertFalse(Files.exists(partialOf(target)), "the partial file must not survive a success")

        assertTrue(progress.isNotEmpty())
        assertEquals(payload.size.toLong(), progress.last().downloadedBytes)
        assertEquals(payload.size.toLong(), progress.last().totalBytes)
    }

    @Test
    fun a_body_larger_than_the_limit_is_refused_and_leaves_nothing_behind() = runBlocking {
        val directory = createTempDirectory("hh-download-large")
        val target = target(directory)

        val outcome = downloader.writeBody(
            body = ByteArrayInputStream(ByteArray(50_000)),
            target = target,
            maxBytes = 4_096,
            totalBytes = null,
            onProgress = {},
        )

        assertEquals(DownloadOutcome.TooLarge, outcome)
        assertFalse(Files.exists(target), "an oversized response must not become the installer")
        assertFalse(Files.exists(partialOf(target)), "the partial file must be deleted")
    }

    @Test
    fun a_truncated_body_never_becomes_an_installer() = runBlocking {
        val directory = createTempDirectory("hh-download-truncated")
        val target = target(directory)

        val outcome = downloader.writeBody(
            body = ByteArrayInputStream(ByteArray(500)),
            target = target,
            maxBytes = 1_000_000,
            // The server announced more than it delivered.
            totalBytes = 5_000,
            onProgress = {},
        )

        assertEquals(DownloadOutcome.NetworkFailure, outcome)
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(partialOf(target)))
    }

    @Test
    fun a_broken_connection_removes_the_partial_file() = runBlocking {
        val directory = createTempDirectory("hh-download-broken")
        val target = target(directory)

        val outcome = downloader.writeBody(
            body = failingStream(bytesBeforeFailure = 2_048),
            target = target,
            maxBytes = 1_000_000,
            totalBytes = 100_000,
            onProgress = {},
        )

        assertEquals(DownloadOutcome.NetworkFailure, outcome)
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(partialOf(target)))
    }

    @Test
    fun the_target_directory_is_created_when_missing() = runBlocking {
        val directory = createTempDirectory("hh-download-nested").resolve("cache/docker")
        val target = target(directory)

        val outcome = downloader.writeBody(
            body = ByteArrayInputStream(ByteArray(64)),
            target = target,
            maxBytes = 1_000_000,
            totalBytes = 64,
            onProgress = {},
        )

        assertTrue(outcome is DownloadOutcome.Success)
        assertTrue(Files.isRegularFile(target))
    }

    /** A stream that delivers some bytes and then fails, the way a dropped connection does. */
    private fun failingStream(bytesBeforeFailure: Int): InputStream = object : InputStream() {
        private var delivered = 0

        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delivered >= bytesBeforeFailure) throw IOException("connection reset")
            val chunk = minOf(length, bytesBeforeFailure - delivered)
            delivered += chunk
            return chunk
        }
    }
}
