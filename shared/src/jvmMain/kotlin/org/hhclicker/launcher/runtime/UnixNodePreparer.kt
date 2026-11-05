package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.setup.NodePreparationOutcome
import org.hhclicker.launcher.setup.NodePreparationResult
import org.hhclicker.launcher.setup.NodeSetupAction

/** Official pinned Node archive, installed only in the launcher's user data on Unix hosts. */
class UnixNodePreparer(
    private val downloader: FileDownloader = HttpFileDownloader(),
    private val platform: HostPlatform = HostPlatformDetector.detect(),
) {
    suspend fun prepare(action: NodeSetupAction, context: NodePreparationContext): NodePreparationResult =
        withContext(Dispatchers.IO) {
            if (action != NodeSetupAction.DOWNLOAD_PORTABLE) return@withContext NodePreparationResult(NodePreparationOutcome.NOT_APPLICABLE)
            if (context.nodeReady()) return@withContext NodePreparationResult(NodePreparationOutcome.ALREADY_READY)
            val architecture = System.getProperty("os.arch").lowercase()
            val nodeArchitecture = when {
                architecture in setOf("amd64", "x86_64") -> "x64"
                platform == HostPlatform.MACOS && architecture in setOf("aarch64", "arm64") -> "arm64"
                else -> null
            }
            if (nodeArchitecture == null) {
                return@withContext NodePreparationResult(NodePreparationOutcome.NOT_APPLICABLE)
            }
            if (platform != HostPlatform.LINUX && platform != HostPlatform.MACOS) {
                return@withContext NodePreparationResult(NodePreparationOutcome.NOT_APPLICABLE)
            }
            val root = LauncherDataPaths.dataRoot() ?: return@withContext NodePreparationResult(NodePreparationOutcome.NOT_APPLICABLE)
            val cache = root.resolve("downloads")
            if (runCatching { Files.createDirectories(cache) }.isFailure) {
                return@withContext NodePreparationResult(NodePreparationOutcome.NOT_APPLICABLE)
            }
            val distribution = "node-v${NodePortableRuntime.VERSION}-${if (platform == HostPlatform.MACOS) "darwin" else "linux"}-$nodeArchitecture"
            val archiveName = "$distribution.${if (platform == HostPlatform.MACOS) "tar.gz" else "tar.xz"}"
            val archive = cache.resolve(archiveName)
            val sums = cache.resolve("node-shasums-${NodePortableRuntime.VERSION}.txt")
            context.onStage(SetupStep.DOWNLOADING_NODE_CHECKSUMS)
            val sumsResult = downloader.download(NodePortableRuntime.CHECKSUM_URL, sums, NodePortableRuntime.MAX_CHECKSUM_BYTES) { }
            if (sumsResult !is DownloadOutcome.Success) return@withContext downloadFailure(sumsResult)
            val expected = Files.readAllLines(sums).firstNotNullOfOrNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                parts.takeIf { it.size == 2 && it[1].trimStart('*') == archiveName }
                    ?.get(0)?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            } ?: return@withContext NodePreparationResult(NodePreparationOutcome.CHECKSUM_NOT_FOUND)
            if (!Files.isRegularFile(archive) || hash(archive) != expected) {
                Files.deleteIfExists(archive)
                context.onStage(SetupStep.DOWNLOADING_NODE)
                val result = downloader.download(
                    "https://nodejs.org/dist/v${NodePortableRuntime.VERSION}/$archiveName",
                    archive, NodePortableRuntime.MAX_ARCHIVE_BYTES,
                ) { context.onDownloadProgress(it.downloadedBytes, it.totalBytes) }
                if (result !is DownloadOutcome.Success) return@withContext downloadFailure(result)
            }
            context.onStage(SetupStep.VERIFYING_NODE_ARCHIVE)
            if (hash(archive) != expected) {
                Files.deleteIfExists(archive)
                return@withContext NodePreparationResult(NodePreparationOutcome.CHECKSUM_MISMATCH)
            }
            context.onStage(SetupStep.INSTALLING_NODE_RUNTIME)
            val directory = root.resolve("runtime/node-${NodePortableRuntime.VERSION}")
            val temporary = directory.resolve("node.installing")
            val target = directory.resolve("node")
            val installed = runCatching {
                Files.createDirectories(directory)
                val process = ProcessBuilder(
                    "tar", if (platform == HostPlatform.MACOS) "-xzOf" else "-xJOf",
                    archive.toString(), "$distribution/bin/node",
                ).redirectOutput(temporary.toFile()).start()
                if (process.waitFor() != 0 || Files.size(temporary) == 0L) return@runCatching false
                temporary.toFile().setExecutable(true, true)
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                context.nodeReady()
            }.getOrDefault(false)
            if (!installed) Files.deleteIfExists(temporary)
            NodePreparationResult(if (installed) NodePreparationOutcome.INSTALLED_AND_READY else NodePreparationOutcome.ARCHIVE_INVALID)
        }

    private fun downloadFailure(result: DownloadOutcome): NodePreparationResult = when (result) {
        is DownloadOutcome.HttpFailure -> NodePreparationResult(NodePreparationOutcome.DOWNLOAD_FAILED, result.status)
        DownloadOutcome.Cancelled -> NodePreparationResult(NodePreparationOutcome.DOWNLOAD_CANCELLED)
        DownloadOutcome.TooLarge -> NodePreparationResult(NodePreparationOutcome.DOWNLOAD_TOO_LARGE)
        else -> NodePreparationResult(NodePreparationOutcome.DOWNLOAD_FAILED)
    }

    private fun hash(path: Path): String? = runCatching {
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
}
