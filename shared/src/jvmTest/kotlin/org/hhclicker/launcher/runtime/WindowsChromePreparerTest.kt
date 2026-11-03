package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.setup.AuthenticodeResult
import org.hhclicker.launcher.setup.ChromePreparationOutcome
import org.hhclicker.launcher.setup.ChromeSetupAction
import org.hhclicker.launcher.setup.ExpectedPublisher
import org.hhclicker.launcher.setup.SignatureVerdict

/**
 * Exercises the complete Chrome preparation flow through test doubles only.
 *
 * No network request, Authenticode command or MSI process is started by this test class.
 */
class WindowsChromePreparerTest {
    private class FakeDownloader(
        private val outcome: (Path) -> DownloadOutcome,
    ) : FileDownloader {
        var calls = 0
        var lastUrl: String? = null
        var lastMaxBytes: Long? = null

        override suspend fun download(
            url: String,
            target: Path,
            maxBytes: Long,
            onProgress: (DownloadProgress) -> Unit,
        ): DownloadOutcome {
            calls++
            lastUrl = url
            lastMaxBytes = maxBytes
            onProgress(DownloadProgress(1_000, 2_000))
            onProgress(DownloadProgress(2_000, 2_000))
            return outcome(target)
        }
    }

    private class FakeRunner(
        private val elevatedExitCode: Int? = 0,
        private val timedOut: Boolean = false,
        private val output: String? = null,
    ) : CommandRunner() {
        val invocations = ArrayList<List<String>>()
        val environments = ArrayList<Map<String, String>>()

        override suspend fun execute(
            arguments: List<String>,
            workingDirectory: Path,
            timeoutMs: Long,
            environment: Map<String, String>,
        ): CommandResult {
            invocations += arguments
            environments += environment
            val reported = output ?: elevatedExitCode?.let { ChromeInstallerCommands.EXIT_CODE_MARKER + it }.orEmpty()
            return CommandResult(if (timedOut) null else 0, reported, timedOut)
        }
    }

    private class RecordingVerifier(
        private val verdicts: ArrayDeque<AuthenticodeResult>,
    ) : AuthenticodeVerifier {
        val publishers = ArrayList<ExpectedPublisher>()

        override suspend fun verify(file: Path, expected: ExpectedPublisher): AuthenticodeResult {
            publishers += expected
            return verdicts.removeFirst()
        }
    }

    private fun writingDownloader() = FakeDownloader { target ->
        Files.createDirectories(target.parent)
        Files.write(target, ByteArray(2_000))
        DownloadOutcome.Success(target, 2_000)
    }

    private fun trustedVerifier() = RecordingVerifier(
        ArrayDeque(listOf(AuthenticodeResult(SignatureVerdict.VALID, "Valid", "O=Google LLC"))),
    )

    private fun preparer(
        downloader: FileDownloader,
        verifier: AuthenticodeVerifier = trustedVerifier(),
        runner: CommandRunner = FakeRunner(),
        cache: Path = createTempDirectory("hh-chrome-cache"),
        windows: Boolean = true,
        msiexec: Path? = Path.of("C:\\Windows\\System32\\msiexec.exe"),
    ) = WindowsChromePreparer(
        downloader = downloader,
        verifier = verifier,
        commandRunner = runner,
        cacheDirectory = cache,
        windows = windows,
        msiexec = msiexec,
        installTimeoutMs = 50,
    )

    private fun context(
        locateChrome: suspend () -> Path? = { Path.of("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe") },
        stages: MutableList<SetupStep> = ArrayList(),
        progress: MutableList<Pair<Long, Long?>> = ArrayList(),
    ) = ChromePreparationContext(
        locateChrome = locateChrome,
        onStage = { stages += it },
        onDownloadProgress = { downloaded, total -> progress += downloaded to total },
    )

    private fun absentThenPresent(): suspend () -> Path? {
        var calls = 0
        return {
            calls++
            if (calls == 1) null else Path.of("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe")
        }
    }

    @Test
    fun refused_actions_and_non_windows_hosts_execute_nothing() = runBlocking {
        val downloader = writingDownloader()
        val runner = FakeRunner()
        val subject = preparer(downloader, runner = runner)

        listOf(
            ChromeSetupAction.NONE,
            ChromeSetupAction.BLOCKED_BY_ARCHITECTURE,
            ChromeSetupAction.DIAGNOSTICS_FAILED,
            ChromeSetupAction.ALREADY_IN_PROGRESS,
        ).forEach { action ->
            assertEquals(
                ChromePreparationOutcome.NOT_APPLICABLE,
                subject.prepare(action, termsAccepted = true, context = context()).outcome,
            )
        }

        val nonWindows = preparer(downloader, runner = runner, windows = false)
        assertEquals(
            ChromePreparationOutcome.NOT_APPLICABLE,
            nonWindows.prepare(ChromeSetupAction.INSTALL, true, context()).outcome,
        )
        assertEquals(0, downloader.calls)
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun explicit_terms_acceptance_is_required_before_download() = runBlocking {
        val downloader = writingDownloader()
        val runner = FakeRunner()

        val result = preparer(downloader, runner = runner)
            .prepare(ChromeSetupAction.INSTALL, termsAccepted = false, context = context())

        assertEquals(ChromePreparationOutcome.TERMS_NOT_ACCEPTED, result.outcome)
        assertEquals(0, downloader.calls)
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun an_existing_chrome_is_never_downloaded_repaired_or_reinstalled() = runBlocking {
        val downloader = writingDownloader()
        val runner = FakeRunner()

        val result = preparer(downloader, runner = runner)
            .prepare(ChromeSetupAction.INSTALL, true, context())

        assertEquals(ChromePreparationOutcome.ALREADY_INSTALLED, result.outcome)
        assertEquals(0, downloader.calls)
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun missing_system_installer_path_stops_before_download() = runBlocking {
        val downloader = writingDownloader()
        val runner = FakeRunner()

        val result = preparer(downloader, runner = runner, msiexec = null).prepare(
            ChromeSetupAction.INSTALL,
            true,
            context(locateChrome = { null }),
        )

        assertEquals(ChromePreparationOutcome.INSTALLER_ENGINE_NOT_FOUND, result.outcome)
        assertEquals(0, downloader.calls)
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun verified_google_msi_is_downloaded_from_the_fixed_url_and_installed_silently() = runBlocking {
        val downloader = writingDownloader()
        val runner = FakeRunner()
        val verifier = trustedVerifier()
        val stages = ArrayList<SetupStep>()
        val progress = ArrayList<Pair<Long, Long?>>()

        val result = preparer(downloader, verifier, runner).prepare(
            ChromeSetupAction.INSTALL,
            true,
            context(absentThenPresent(), stages, progress),
        )

        assertEquals(ChromePreparationOutcome.INSTALLED_AND_READY, result.outcome)
        assertEquals(ChromeInstallerCommands.INSTALLER_URL, downloader.lastUrl)
        assertEquals(ChromeInstallerCommands.MAX_INSTALLER_BYTES, downloader.lastMaxBytes)
        assertEquals(listOf(ExpectedPublisher.GOOGLE), verifier.publishers)
        assertEquals(
            listOf(
                SetupStep.DOWNLOADING_CHROME,
                SetupStep.VERIFYING_CHROME_INSTALLER,
                SetupStep.INSTALLING_CHROME,
            ),
            stages,
        )
        val expectedProgress: List<Pair<Long, Long?>> = listOf(
            1_000L to 2_000L,
            2_000L to 2_000L,
        )
        assertEquals(expectedProgress, progress)
        val arguments = runner.invocations.single()
        assertEquals("powershell.exe", arguments[0])
        assertEquals("-Command", arguments[3])
        assertTrue(arguments[4].contains("-Verb RunAs"))
        assertFalse(arguments[4].contains("GoogleChromeStandaloneEnterprise64.msi"))
        val environment = runner.environments.single()
        assertTrue(environment.getValue(ChromeInstallerCommands.MSIEXEC_VARIABLE).endsWith("msiexec.exe"))
        assertTrue(environment.getValue(ChromeInstallerCommands.INSTALLER_VARIABLE).endsWith(".msi"))
    }

    @Test
    fun rejected_signature_is_deleted_and_never_executed() = runBlocking {
        val cache = createTempDirectory("hh-chrome-untrusted")
        val downloader = writingDownloader()
        val runner = FakeRunner()
        val verifier = RecordingVerifier(
            ArrayDeque(listOf(AuthenticodeResult(SignatureVerdict.WRONG_PUBLISHER, "Valid", "O=Shakaly LLC"))),
        )

        val result = preparer(downloader, verifier, runner, cache).prepare(
            ChromeSetupAction.INSTALL,
            true,
            context(locateChrome = { null }),
        )

        assertEquals(ChromePreparationOutcome.SIGNATURE_REJECTED, result.outcome)
        assertFalse(Files.exists(cache.resolve(ChromeInstallerCommands.INSTALLER_FILE_NAME)))
        assertTrue(runner.invocations.isEmpty())
        assertEquals(listOf(ExpectedPublisher.GOOGLE), verifier.publishers)
    }

    @Test
    fun a_valid_cached_installer_is_verified_on_every_attempt_without_redownload() = runBlocking {
        val cache = createTempDirectory("hh-chrome-cached")
        Files.write(cache.resolve(ChromeInstallerCommands.INSTALLER_FILE_NAME), ByteArray(2_000))
        val downloader = writingDownloader()
        val verifier = trustedVerifier()

        val result = preparer(downloader, verifier = verifier, cache = cache).prepare(
            ChromeSetupAction.INSTALL,
            true,
            context(absentThenPresent()),
        )

        assertEquals(ChromePreparationOutcome.INSTALLED_AND_READY, result.outcome)
        assertEquals(0, downloader.calls)
        assertEquals(listOf(ExpectedPublisher.GOOGLE), verifier.publishers)
    }

    @Test
    fun an_untrusted_cached_file_is_replaced_and_the_replacement_is_verified() = runBlocking {
        val cache = createTempDirectory("hh-chrome-stale")
        Files.write(cache.resolve(ChromeInstallerCommands.INSTALLER_FILE_NAME), ByteArray(10))
        val downloader = writingDownloader()
        val verifier = RecordingVerifier(
            ArrayDeque(
                listOf(
                    AuthenticodeResult(SignatureVerdict.INVALID_STATUS, "HashMismatch", null),
                    AuthenticodeResult(SignatureVerdict.VALID, "Valid", "O=Google LLC"),
                ),
            ),
        )

        val result = preparer(downloader, verifier, cache = cache).prepare(
            ChromeSetupAction.INSTALL,
            true,
            context(absentThenPresent()),
        )

        assertEquals(ChromePreparationOutcome.INSTALLED_AND_READY, result.outcome)
        assertEquals(1, downloader.calls)
        assertEquals(listOf(ExpectedPublisher.GOOGLE, ExpectedPublisher.GOOGLE), verifier.publishers)
    }

    @Test
    fun documented_windows_installer_exit_codes_have_distinct_results() = runBlocking {
        val cases = listOf(
            ChromeInstallerCommands.EXIT_SUCCESS_REBOOT_REQUIRED to ChromePreparationOutcome.INSTALLED_RESTART_RECOMMENDED,
            ChromeInstallerCommands.EXIT_USER_CANCELLED to ChromePreparationOutcome.INSTALLER_CANCELLED,
            ChromeInstallerCommands.EXIT_ANOTHER_INSTALLATION_RUNNING to ChromePreparationOutcome.ANOTHER_INSTALLATION_RUNNING,
            1603 to ChromePreparationOutcome.INSTALLER_FAILED,
        )

        cases.forEach { (exitCode, expected) ->
            val locator = if (exitCode == ChromeInstallerCommands.EXIT_SUCCESS_REBOOT_REQUIRED) {
                absentThenPresent()
            } else {
                suspend { null }
            }
            val result = preparer(writingDownloader(), runner = FakeRunner(exitCode)).prepare(
                ChromeSetupAction.INSTALL,
                true,
                context(locator),
            )
            assertEquals(expected, result.outcome, "exit code $exitCode")
            assertEquals(exitCode, result.exitCode)
        }
    }

    @Test
    fun dismissed_or_failed_uac_is_distinguished_without_running_an_msi_in_the_test() = runBlocking {
        val declined = preparer(
            writingDownloader(),
            runner = FakeRunner(output = ChromeInstallerCommands.ELEVATION_DECLINED_MARKER),
        ).prepare(ChromeSetupAction.INSTALL, true, context(locateChrome = { null }))
        assertEquals(ChromePreparationOutcome.INSTALLER_CANCELLED, declined.outcome)

        val failed = preparer(
            writingDownloader(),
            runner = FakeRunner(output = ChromeInstallerCommands.ELEVATION_FAILED_MARKER),
        ).prepare(ChromeSetupAction.INSTALL, true, context(locateChrome = { null }))
        assertEquals(ChromePreparationOutcome.ELEVATION_FAILED, failed.outcome)
    }

    @Test
    fun timeout_and_missing_browser_after_success_are_not_reported_as_success() = runBlocking {
        val timedOut = preparer(writingDownloader(), runner = FakeRunner(timedOut = true)).prepare(
            ChromeSetupAction.INSTALL,
            true,
            context(locateChrome = { null }),
        )
        assertEquals(ChromePreparationOutcome.INSTALLER_TIMED_OUT, timedOut.outcome)

        val missingAfterInstall = preparer(writingDownloader(), runner = FakeRunner()).prepare(
            ChromeSetupAction.INSTALL,
            true,
            context(locateChrome = { null }),
        )
        assertEquals(ChromePreparationOutcome.CHROME_NOT_FOUND_AFTER_INSTALL, missingAfterInstall.outcome)
    }

    @Test
    fun download_failures_preserve_safe_diagnostics_and_never_start_msi() = runBlocking {
        val runner = FakeRunner()
        val httpFailure = preparer(FakeDownloader { DownloadOutcome.HttpFailure(503) }, runner = runner).prepare(
            ChromeSetupAction.INSTALL,
            true,
            context(locateChrome = { null }),
        )
        assertEquals(ChromePreparationOutcome.DOWNLOAD_FAILED, httpFailure.outcome)
        assertEquals(503, httpFailure.httpStatus)

        val tooLarge = preparer(FakeDownloader { DownloadOutcome.TooLarge }, runner = runner).prepare(
            ChromeSetupAction.INSTALL,
            true,
            context(locateChrome = { null }),
        )
        assertEquals(ChromePreparationOutcome.DOWNLOAD_TOO_LARGE, tooLarge.outcome)
        assertTrue(runner.invocations.isEmpty())
    }
}
