package org.hhclicker.launcher.runtime

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.model.HostPlatform

class MacDockerPreparerTest {
    @Test
    fun selects_a_separately_verified_distribution_for_each_mac_architecture() {
        val intel = MacDockerPreparer.distributionFor("x86_64")!!
        val arm = MacDockerPreparer.distributionFor("aarch64")!!

        assertTrue(intel.url.contains("/amd64/"))
        assertTrue(arm.url.contains("/arm64/"))
        assertEquals(MacDockerPreparer.DOCKER_INTEL_DMG_SHA256, intel.sha256)
        assertEquals(MacDockerPreparer.DOCKER_ARM_DMG_SHA256, arm.sha256)
    }

    @Test
    fun a_bad_checksum_never_reaches_the_mounter_or_installer() = runBlocking {
        val root = createTempDirectory("mac-docker-checksum")
        val downloader = object : FileDownloader {
            override suspend fun download(
                url: String,
                target: java.nio.file.Path,
                maxBytes: Long,
                onProgress: (DownloadProgress) -> Unit,
            ): DownloadOutcome {
                Files.writeString(target, "not a Docker DMG")
                return DownloadOutcome.Success(target, Files.size(target))
            }
        }
        val commands = object : CommandRunner() {
            override suspend fun execute(
                arguments: List<String>,
                workingDirectory: java.nio.file.Path,
                timeoutMs: Long,
                environment: Map<String, String>,
            ): CommandResult = error("Unverified DMG reached ${arguments.first()}")
        }
        val result = MacDockerPreparer(
            downloader = downloader,
            commands = commands,
            dataRoot = root,
            application = root.resolve("not-installed.app"),
            platform = HostPlatform.MACOS,
            architecture = "x86_64",
        ).prepare(onStage = {}, onDownloadProgress = { _, _ -> })

        assertFalse(result.success)
        assertTrue(result.message.contains("Контрольная сумма"))
    }

    @Test
    fun an_existing_docker_app_is_opened_without_downloading_again() = runBlocking {
        val root = createTempDirectory("mac-docker-existing")
        val application = root.resolve("Docker.app")
        Files.createDirectories(application)
        var opened = false
        val commands = object : CommandRunner() {
            override suspend fun execute(
                arguments: List<String>,
                workingDirectory: java.nio.file.Path,
                timeoutMs: Long,
                environment: Map<String, String>,
            ): CommandResult {
                opened = arguments == listOf("/usr/bin/open", "-a", application.toString())
                return CommandResult(0, "")
            }
        }
        val result = MacDockerPreparer(
            downloader = object : FileDownloader {
                override suspend fun download(
                    url: String,
                    target: java.nio.file.Path,
                    maxBytes: Long,
                    onProgress: (DownloadProgress) -> Unit,
                ): DownloadOutcome = error("Docker is already installed")
            },
            commands = commands,
            dataRoot = root,
            application = application,
            platform = HostPlatform.MACOS,
            architecture = "arm64",
        ).prepare(onStage = {}, onDownloadProgress = { _, _ -> })

        assertTrue(result.success)
        assertTrue(opened)
    }
}
