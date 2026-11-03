package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.setup.NodePreparationOutcome
import org.hhclicker.launcher.setup.NodeSetupAction

class WindowsNodePreparerTest {
    @Test
    fun verified_archive_installs_only_private_node_executable() = runBlocking {
        val root = createTempDirectory("hh-node")
        val sourceArchive = root.resolve("source.zip")
        ZipOutputStream(Files.newOutputStream(sourceArchive)).use { zip ->
            zip.putNextEntry(ZipEntry("${NodePortableRuntime.DISTRIBUTION}/node.exe"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("${NodePortableRuntime.DISTRIBUTION}/LICENSE"))
            zip.write("MIT".toByteArray())
            zip.closeEntry()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sourceArchive))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val sourceChecksums = root.resolve("checksums.txt")
        Files.writeString(sourceChecksums, "$hash  ${NodePortableRuntime.ARCHIVE_NAME}\n")

        val downloader = object : FileDownloader {
            override suspend fun download(
                url: String,
                target: Path,
                maxBytes: Long,
                onProgress: (DownloadProgress) -> Unit,
            ): DownloadOutcome {
                val source = if (url == NodePortableRuntime.CHECKSUM_URL) sourceChecksums else sourceArchive
                Files.createDirectories(target.parent)
                Files.copy(source, target)
                val size = Files.size(target)
                onProgress(DownloadProgress(size, size))
                return DownloadOutcome.Success(target, size)
            }
        }
        val installed = root.resolve("HH Clicker/data/runtime/node-${NodePortableRuntime.VERSION}/node.exe")
        val result = WindowsNodePreparer(downloader, environment = { if (it == "LOCALAPPDATA") root.toString() else null })
            .prepare(
                NodeSetupAction.DOWNLOAD_PORTABLE,
                NodePreparationContext(
                    nodeReady = { Files.isRegularFile(installed) },
                    onStage = {},
                    onDownloadProgress = { _, _ -> },
                ),
            )

        assertEquals(NodePreparationOutcome.INSTALLED_AND_READY, result.outcome)
        assertTrue(Files.isRegularFile(installed))
        assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(installed).toList())
    }
}
