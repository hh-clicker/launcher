package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.model.HostPlatform

class MacChromePreparerTest {
    @Test
    fun rejected_terms_or_existing_chrome_never_download_or_install() = runBlocking {
        val fixture = Fixture()
        assertFalse(fixture.prepare(accepted = false).success)
        assertEquals(0, fixture.downloads)
        fixture.installed = true
        assertTrue(fixture.prepare().success)
        assertEquals(0, fixture.downloads)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun a_trusted_package_from_another_publisher_never_reaches_the_installer() = runBlocking {
        val fixture = Fixture(signature = "1. Developer ID Installer: Someone Else (OTHERTEAM)")
        assertFalse(fixture.prepare().success)
        assertFalse(fixture.calls.any { it.first() == "/usr/bin/osascript" })
        assertFalse(Files.exists(fixture.downloaded))
    }

    @Test
    fun gatekeeper_rejection_blocks_a_google_signed_package() = runBlocking {
        val fixture = Fixture(gatekeeperAccepted = false)
        assertFalse(fixture.prepare().success)
        assertFalse(fixture.installed)
    }

    @Test
    fun universal_package_installs_on_arm_and_is_rechecked_then_removed() = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.prepare().success)
        assertTrue(fixture.installed)
        assertEquals(1, fixture.downloads)
        val installer = fixture.calls.single { it.first() == "/usr/bin/osascript" }
        assertEquals(fixture.downloaded.toString(), installer.last())
        assertFalse(Files.exists(fixture.downloaded))
    }

    @Test
    fun installer_success_without_chrome_is_not_reported_as_ready() = runBlocking {
        assertFalse(Fixture(createsChrome = false).prepare().success)
    }

    private class Fixture(
        private val signature: String = "1. Developer ID Installer: Google LLC (EQHXZ8M8AV)",
        private val gatekeeperAccepted: Boolean = true,
        private val createsChrome: Boolean = true,
    ) {
        val root = Files.createTempDirectory("mac-chrome-")
        var installed = false
        var downloads = 0
        lateinit var downloaded: Path
        val calls = mutableListOf<List<String>>()
        private val preparer = MacChromePreparer(
            platform = HostPlatform.MACOS, architecture = "aarch64", dataRoot = root,
            downloader = object : FileDownloader {
                override suspend fun download(url: String, target: Path, maxBytes: Long,
                    onProgress: (DownloadProgress) -> Unit): DownloadOutcome {
                    downloads++
                    downloaded = target
                    Files.writeString(target, "test package")
                    assertEquals(MacChromePreparer.DOWNLOAD_URL, url)
                    return DownloadOutcome.Success(target, Files.size(target))
                }
            },
            commands = object : CommandRunner() {
                override suspend fun execute(arguments: List<String>, workingDirectory: Path,
                    timeoutMs: Long, environment: Map<String, String>): CommandResult {
                    calls += arguments
                    return when (arguments.first()) {
                        "/usr/sbin/pkgutil" -> CommandResult(0, signature)
                        "/usr/sbin/spctl" -> CommandResult(if (gatekeeperAccepted) 0 else 1, "")
                        "/usr/bin/osascript" -> { installed = createsChrome; CommandResult(0, "") }
                        else -> error("Unexpected command: $arguments")
                    }
                }
            },
        )
        suspend fun prepare(accepted: Boolean = true) = preparer.prepare(accepted, ChromePreparationContext(
            locateChrome = { if (installed) root.resolve("Google Chrome.app") else null },
            onStage = {}, onDownloadProgress = { _, _ -> },
        ))
    }
}
