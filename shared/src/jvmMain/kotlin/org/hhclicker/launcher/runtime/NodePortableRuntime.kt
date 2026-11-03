package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.preflight.NodeRuntimeLocator
import org.hhclicker.launcher.setup.NodePreparationOutcome
import org.hhclicker.launcher.setup.NodePreparationResult
import org.hhclicker.launcher.setup.NodeSetupAction

object NodePortableRuntime {
    const val VERSION = "24.20.0"
    const val DISTRIBUTION = "node-v$VERSION-win-x64"
    const val ARCHIVE_NAME = "$DISTRIBUTION.zip"
    const val ARCHIVE_URL = "https://nodejs.org/dist/v$VERSION/$ARCHIVE_NAME"
    const val CHECKSUM_URL = "https://nodejs.org/dist/v$VERSION/SHASUMS256.txt"
    const val MAX_ARCHIVE_BYTES = 120L * 1024 * 1024
    const val MAX_CHECKSUM_BYTES = 2L * 1024 * 1024
}

class NodePreparationContext(
    val nodeReady: suspend () -> Boolean,
    val onStage: (SetupStep) -> Unit,
    val onDownloadProgress: (Long, Long?) -> Unit,
)

class WindowsNodePreparer(
    private val downloader: FileDownloader = HttpFileDownloader(),
    private val environment: (String) -> String? = System::getenv,
) {
    suspend fun prepare(action: NodeSetupAction, context: NodePreparationContext): NodePreparationResult {
        if (action != NodeSetupAction.DOWNLOAD_PORTABLE) return NodePreparationResult(NodePreparationOutcome.NOT_APPLICABLE)
        if (context.nodeReady()) return NodePreparationResult(NodePreparationOutcome.ALREADY_READY)
        val local = environment("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: return NodePreparationResult(NodePreparationOutcome.NOT_APPLICABLE)
        val dataRoot = local.resolve(LauncherDataPaths.APPLICATION_DIRECTORY).resolve(LauncherDataPaths.DATA_DIRECTORY)
        val cache = dataRoot.resolve("downloads")
        if (runCatching { Files.createDirectories(cache) }.isFailure) {
            return NodePreparationResult(NodePreparationOutcome.NOT_APPLICABLE)
        }
        val checksums = cache.resolve("node-shasums-${NodePortableRuntime.VERSION}.txt")
        val archive = cache.resolve(NodePortableRuntime.ARCHIVE_NAME)

        context.onStage(SetupStep.DOWNLOADING_NODE_CHECKSUMS)
        download(NodePortableRuntime.CHECKSUM_URL, checksums, NodePortableRuntime.MAX_CHECKSUM_BYTES) { _, _ -> }
            ?.let { return it }
        val expected = expectedHash(checksums) ?: return NodePreparationResult(NodePreparationOutcome.CHECKSUM_NOT_FOUND)

        if (!Files.isRegularFile(archive) || sha256(archive) != expected) {
            Files.deleteIfExists(archive)
            context.onStage(SetupStep.DOWNLOADING_NODE)
            download(NodePortableRuntime.ARCHIVE_URL, archive, NodePortableRuntime.MAX_ARCHIVE_BYTES, context.onDownloadProgress)
                ?.let { return it }
        }
        context.onStage(SetupStep.VERIFYING_NODE_ARCHIVE)
        if (sha256(archive) != expected) {
            Files.deleteIfExists(archive)
            return NodePreparationResult(NodePreparationOutcome.CHECKSUM_MISMATCH)
        }

        context.onStage(SetupStep.INSTALLING_NODE_RUNTIME)
        return if (extractNode(archive, local) && context.nodeReady()) {
            NodePreparationResult(NodePreparationOutcome.INSTALLED_AND_READY)
        } else {
            NodePreparationResult(NodePreparationOutcome.ARCHIVE_INVALID)
        }
    }

    private suspend fun download(
        url: String,
        target: Path,
        maxBytes: Long,
        progress: (Long, Long?) -> Unit,
    ): NodePreparationResult? = when (val outcome = downloader.download(url, target, maxBytes) { progress(it.downloadedBytes, it.totalBytes) }) {
        is DownloadOutcome.Success -> null
        is DownloadOutcome.HttpFailure -> NodePreparationResult(NodePreparationOutcome.DOWNLOAD_FAILED, outcome.status)
        DownloadOutcome.Cancelled -> NodePreparationResult(NodePreparationOutcome.DOWNLOAD_CANCELLED)
        DownloadOutcome.TooLarge -> NodePreparationResult(NodePreparationOutcome.DOWNLOAD_TOO_LARGE)
        else -> NodePreparationResult(NodePreparationOutcome.DOWNLOAD_FAILED)
    }

    private fun expectedHash(path: Path): String? = runCatching {
        Files.readAllLines(path).firstNotNullOfOrNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            parts.takeIf { it.size == 2 && it[1].trimStart('*') == NodePortableRuntime.ARCHIVE_NAME }
                ?.get(0)?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        }
    }.getOrNull()

    private fun sha256(path: Path): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()

    private suspend fun extractNode(archive: Path, local: Path): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val runtimeDirectory = local.resolve(LauncherDataPaths.APPLICATION_DIRECTORY)
                .resolve(LauncherDataPaths.DATA_DIRECTORY)
                .resolve("runtime")
                .resolve("node-${NodePortableRuntime.VERSION}")
            val target = runtimeDirectory.resolve("node.exe")
            Files.createDirectories(runtimeDirectory)
            val temporary = target.resolveSibling("node.exe.installing")
            ZipFile(archive.toFile()).use { zip ->
                val entry = zip.getEntry("${NodePortableRuntime.DISTRIBUTION}/node.exe")
                    ?: return@runCatching false
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.getEntry("${NodePortableRuntime.DISTRIBUTION}/LICENSE")?.let { license ->
                    zip.getInputStream(license).use { input ->
                        Files.copy(input, runtimeDirectory.resolve("LICENSE"), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            Files.isRegularFile(target)
        }.getOrDefault(false)
    }
}
