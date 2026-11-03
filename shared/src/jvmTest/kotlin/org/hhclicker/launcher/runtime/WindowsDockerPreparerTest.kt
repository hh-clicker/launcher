package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.model.ContainerMode
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.DockerCliState
import org.hhclicker.launcher.model.DockerComposeState
import org.hhclicker.launcher.model.DockerDesktopProcessState
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.DockerDiagnostics
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.DockerInstallScope
import org.hhclicker.launcher.model.HostArchitecture
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.setup.AuthenticodeResult
import org.hhclicker.launcher.setup.DockerPreparationOutcome
import org.hhclicker.launcher.setup.DockerSetupAction
import org.hhclicker.launcher.setup.SignatureVerdict

/**
 * The preparer is exercised entirely through substituted collaborators.
 *
 * No installer is downloaded, no signature is checked against Windows, no process is started and no
 * Docker command is executed.
 */
class WindowsDockerPreparerTest {

    // --- test doubles -----------------------------------------------------------------------------

    private class FakeDownloader(
        private val outcome: (Path) -> DownloadOutcome,
    ) : FileDownloader {
        var calls = 0
        var lastUrl: String? = null

        override suspend fun download(
            url: String,
            target: Path,
            maxBytes: Long,
            onProgress: (DownloadProgress) -> Unit,
        ): DownloadOutcome {
            calls++
            lastUrl = url
            onProgress(DownloadProgress(1_000, 2_000))
            onProgress(DownloadProgress(2_000, 2_000))
            return outcome(target)
        }
    }

    private class FakeRunner(private val exitCode: Int = 0, private val timedOut: Boolean = false) : CommandRunner() {
        val invocations = ArrayList<List<String>>()

        override suspend fun execute(
            arguments: List<String>,
            workingDirectory: Path,
            timeoutMs: Long,
            environment: Map<String, String>,
        ): CommandResult {
            invocations += arguments
            return CommandResult(if (timedOut) null else exitCode, "", timedOut)
        }
    }

    private class FakeLauncher(private val succeeds: Boolean = true) : ProcessLauncher {
        var calls = 0
        override fun launch(arguments: List<String>, workingDirectory: Path): Boolean {
            calls++
            return succeeds
        }
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun writingDownloader() = FakeDownloader { target ->
        Files.createDirectories(target.parent)
        Files.write(target, ByteArray(2_000))
        DownloadOutcome.Success(target, 2_000)
    }

    private fun trustedVerifier() = AuthenticodeVerifier { _, _ ->
        AuthenticodeResult(SignatureVerdict.VALID, "Valid", "O=Docker Inc")
    }

    private fun readyDiagnostics() = DockerDiagnostics(
        architecture = HostArchitecture.X64_SUPPORTED,
        desktop = DockerDesktopState.INSTALLED,
        desktopVersion = "4.45.0",
        cli = DockerCliState.AVAILABLE,
        compose = DockerComposeState.AVAILABLE,
        desktopProcess = DockerDesktopProcessState.RUNNING,
        engine = DockerEngineState.RUNNING,
        containerMode = ContainerMode.LINUX,
        backend = DockerBackend.WSL_2,
        installScope = DockerInstallScope.PER_USER,
    )

    private fun context(
        diagnostics: DockerDiagnostics = readyDiagnostics(),
        engine: DockerEngineState = DockerEngineState.RUNNING,
        finalDiagnostics: DockerDiagnostics = readyDiagnostics(),
        desktop: Path? = Path.of("C:\\Docker\\Docker Desktop.exe"),
        cli: Path? = null,
        prepareUnattendedStart: suspend () -> Boolean = { true },
        stages: MutableList<SetupStep> = ArrayList(),
        progress: MutableList<Pair<Long, Long?>> = ArrayList(),
    ) = DockerPreparationContext(
        diagnostics = diagnostics,
        engineState = { engine },
        refreshDiagnostics = { finalDiagnostics },
        locateDesktop = { desktop },
        locateCli = { cli },
        prepareUnattendedStart = prepareUnattendedStart,
        onStage = { stages += it },
        onDownloadProgress = { done, total -> progress += done to total },
    )

    private fun preparer(
        downloader: FileDownloader,
        verifier: AuthenticodeVerifier = trustedVerifier(),
        runner: CommandRunner = FakeRunner(),
        launcher: ProcessLauncher = FakeLauncher(),
        cache: Path = createTempDirectory("hh-docker-cache"),
        windows: Boolean = true,
        engineTimeoutMs: Long = 50,
    ) = WindowsDockerPreparer(
        downloader = downloader,
        verifier = verifier,
        commandRunner = runner,
        processLauncher = launcher,
        cacheDirectory = cache,
        windows = windows,
        enginePollIntervalMs = 1,
        engineTimeoutMs = engineTimeoutMs,
    )

    // --- refusals ---------------------------------------------------------------------------------

    @Test
    fun an_action_the_launcher_must_not_perform_executes_nothing() = runBlocking {
        val downloader = writingDownloader()
        val runner = FakeRunner()
        val launcher = FakeLauncher()
        val preparer = preparer(downloader, runner = runner, launcher = launcher)

        listOf(
            DockerSetupAction.NONE,
            DockerSetupAction.BLOCKED_BY_WSL,
            DockerSetupAction.BLOCKED_BY_ARCHITECTURE,
            DockerSetupAction.BLOCKED_BY_BACKEND,
            DockerSetupAction.BACKEND_UNCONFIRMED,
            DockerSetupAction.BLOCKED_BY_CONTAINER_MODE,
            DockerSetupAction.CLI_WITHOUT_DESKTOP,
            DockerSetupAction.DIAGNOSTICS_FAILED,
        ).forEach { action ->
            val result = preparer.prepare(action, licenseAccepted = true, context = context())
            assertEquals(DockerPreparationOutcome.NOT_APPLICABLE, result.outcome, "action $action")
        }

        assertEquals(0, downloader.calls, "nothing may be downloaded for a refused action")
        assertEquals(0, runner.invocations.size)
        assertEquals(0, launcher.calls)
    }

    @Test
    fun an_installation_without_an_explicit_acceptance_downloads_nothing() = runBlocking {
        val downloader = writingDownloader()
        val runner = FakeRunner()
        val result = preparer(downloader, runner = runner)
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = false, context = context())

        assertEquals(DockerPreparationOutcome.LICENSE_NOT_ACCEPTED, result.outcome)
        assertEquals(0, downloader.calls)
        assertEquals(0, runner.invocations.size)
    }

    @Test
    fun nothing_is_executed_outside_windows() = runBlocking {
        val downloader = writingDownloader()
        val result = preparer(downloader, windows = false)
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())

        assertEquals(DockerPreparationOutcome.NOT_APPLICABLE, result.outcome)
        assertEquals(0, downloader.calls)
    }

    // --- signature --------------------------------------------------------------------------------

    @Test
    fun an_untrusted_installer_is_deleted_and_never_executed() = runBlocking {
        val cache = createTempDirectory("hh-docker-untrusted")
        val downloader = writingDownloader()
        val runner = FakeRunner()
        val verifier = AuthenticodeVerifier { _, _ -> AuthenticodeResult(SignatureVerdict.WRONG_PUBLISHER, "Valid", "O=Evil Corp") }

        val result = preparer(downloader, verifier = verifier, runner = runner, cache = cache)
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())

        assertEquals(DockerPreparationOutcome.SIGNATURE_REJECTED, result.outcome)
        assertFalse(
            Files.exists(cache.resolve(DockerDesktopCommands.INSTALLER_FILE_NAME)),
            "a rejected installer must not stay in the cache",
        )
        assertTrue(runner.invocations.isEmpty(), "the installer must never run: ${runner.invocations}")
    }

    @Test
    fun an_invalid_signature_status_is_also_rejected() = runBlocking {
        val downloader = writingDownloader()
        val verifier = AuthenticodeVerifier { _, _ -> AuthenticodeResult(SignatureVerdict.INVALID_STATUS, "HashMismatch", null) }
        val result = preparer(downloader, verifier = verifier)
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())

        assertEquals(DockerPreparationOutcome.SIGNATURE_REJECTED, result.outcome)
    }

    @Test
    fun a_cached_installer_is_verified_again_before_it_is_trusted() = runBlocking {
        val cache = createTempDirectory("hh-docker-cached")
        Files.write(cache.resolve(DockerDesktopCommands.INSTALLER_FILE_NAME), ByteArray(2_000))
        val downloader = writingDownloader()
        var verifications = 0
        val verifier = AuthenticodeVerifier { _, _ ->
            verifications++
            AuthenticodeResult(SignatureVerdict.VALID, "Valid", "O=Docker Inc")
        }

        val result = preparer(downloader, verifier = verifier, cache = cache)
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())

        assertEquals(DockerPreparationOutcome.INSTALLED_AND_READY, result.outcome)
        assertEquals(1, verifications, "a cached installer must be verified on every run")
        assertEquals(0, downloader.calls, "a verified cached installer must not be downloaded again")
    }

    @Test
    fun a_cached_installer_that_fails_verification_is_replaced() = runBlocking {
        val cache = createTempDirectory("hh-docker-stale")
        Files.write(cache.resolve(DockerDesktopCommands.INSTALLER_FILE_NAME), ByteArray(10))
        val downloader = writingDownloader()
        var verifications = 0
        val verifier = AuthenticodeVerifier { _, _ ->
            verifications++
            if (verifications == 1) {
                AuthenticodeResult(SignatureVerdict.INVALID_STATUS, "NotSigned", null)
            } else {
                AuthenticodeResult(SignatureVerdict.VALID, "Valid", "O=Docker Inc")
            }
        }

        val result = preparer(downloader, verifier = verifier, cache = cache)
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())

        assertEquals(DockerPreparationOutcome.INSTALLED_AND_READY, result.outcome)
        assertEquals(1, downloader.calls, "an untrusted cached file must be downloaded again")
        assertEquals(2, verifications)
    }

    // --- installation -----------------------------------------------------------------------------

    @Test
    fun the_installation_uses_the_official_url_and_the_expected_arguments() = runBlocking {
        val downloader = writingDownloader()
        val runner = FakeRunner()
        val stages = ArrayList<SetupStep>()
        val progress = ArrayList<Pair<Long, Long?>>()

        val result = preparer(downloader, runner = runner)
            .prepare(
                DockerSetupAction.INSTALL,
                licenseAccepted = true,
                context = context(stages = stages, progress = progress),
            )

        assertEquals(DockerPreparationOutcome.INSTALLED_AND_READY, result.outcome)
        assertEquals(DockerDesktopCommands.INSTALLER_URL, downloader.lastUrl)

        val install = assertNotNull(runner.invocations.firstOrNull { it.contains("install") })
        assertTrue(install.contains("--user"), install.toString())
        assertTrue(install.contains("--backend=wsl-2"), install.toString())
        assertTrue(install.contains("--no-windows-containers"), install.toString())
        assertTrue(install.contains("--accept-license"), install.toString())

        assertTrue(stages.contains(SetupStep.DOWNLOADING_DOCKER))
        assertTrue(stages.contains(SetupStep.VERIFYING_DOCKER_INSTALLER))
        assertTrue(stages.contains(SetupStep.INSTALLING_DOCKER))
        assertTrue(stages.contains(SetupStep.WAITING_FOR_DOCKER_ENGINE))
        assertTrue(stages.contains(SetupStep.VERIFYING_DOCKER_BACKEND))
        val expectedProgress: List<Pair<Long, Long?>> = listOf(1_000L to 2_000L, 2_000L to 2_000L)
        assertEquals(expectedProgress, progress.toList())
    }

    @Test
    fun an_all_users_update_does_not_change_the_installation_scope() = runBlocking {
        val runner = FakeRunner()
        val diagnostics = readyDiagnostics().copy(
            desktop = DockerDesktopState.OUTDATED,
            desktopVersion = "3.6.0",
            installScope = DockerInstallScope.ALL_USERS,
        )

        val result = preparer(writingDownloader(), runner = runner).prepare(
            DockerSetupAction.UPDATE,
            licenseAccepted = true,
            context = context(diagnostics = diagnostics),
        )

        assertEquals(DockerPreparationOutcome.INSTALLED_AND_READY, result.outcome)
        val install = assertNotNull(runner.invocations.firstOrNull { it.contains("install") })
        assertFalse(install.contains("--user"), install.toString())
        assertFalse(install.contains("--backend=wsl-2"), install.toString())
        assertFalse(install.contains("--no-windows-containers"), install.toString())
    }

    @Test
    fun a_failing_installer_reports_its_exit_code() = runBlocking {
        val downloader = writingDownloader()
        val result = preparer(downloader, runner = FakeRunner(exitCode = 4))
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())

        assertEquals(DockerPreparationOutcome.INSTALLER_FAILED, result.outcome)
        assertEquals(4, result.exitCode)
    }

    @Test
    fun an_installer_that_never_finishes_is_a_timeout() = runBlocking {
        val downloader = writingDownloader()
        val result = preparer(downloader, runner = FakeRunner(timedOut = true))
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())

        assertEquals(DockerPreparationOutcome.INSTALLER_TIMED_OUT, result.outcome)
    }

    @Test
    fun a_download_failure_keeps_its_http_status() = runBlocking {
        val downloader = FakeDownloader { DownloadOutcome.HttpFailure(503) }
        val runner = FakeRunner()
        val result = preparer(downloader, runner = runner)
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())

        assertEquals(DockerPreparationOutcome.DOWNLOAD_FAILED, result.outcome)
        assertEquals(503, result.httpStatus)
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun an_oversized_download_is_its_own_failure() = runBlocking {
        val result = preparer(FakeDownloader { DownloadOutcome.TooLarge })
            .prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())
        assertEquals(DockerPreparationOutcome.DOWNLOAD_TOO_LARGE, result.outcome)
    }

    // --- start and wait ---------------------------------------------------------------------------

    @Test
    fun a_fresh_install_uses_the_located_cli_and_does_not_open_the_dashboard_executable() = runBlocking {
        val runner = FakeRunner()
        val launcher = FakeLauncher()
        val cli = Path.of("C:\\Users\\test\\AppData\\Local\\Programs\\DockerDesktop\\resources\\bin\\docker.exe")

        val result = preparer(writingDownloader(), runner = runner, launcher = launcher).prepare(
            DockerSetupAction.INSTALL,
            licenseAccepted = true,
            context = context(cli = cli),
        )

        assertEquals(DockerPreparationOutcome.INSTALLED_AND_READY, result.outcome)
        assertTrue(
            runner.invocations.any { it == DockerDesktopCommands.desktopCliStartArguments(cli) },
            runner.invocations.toString(),
        )
        assertEquals(0, launcher.calls)
    }

    @Test
    fun a_stopped_desktop_is_only_started_and_never_reinstalled() = runBlocking {
        val downloader = writingDownloader()
        val runner = FakeRunner()
        val launcher = FakeLauncher()
        val stages = ArrayList<SetupStep>()
        var unattendedStartPrepared = false

        val result = preparer(downloader, runner = runner, launcher = launcher)
            .prepare(
                DockerSetupAction.START_DESKTOP,
                licenseAccepted = false,
                context = context(
                    stages = stages,
                    prepareUnattendedStart = {
                        unattendedStartPrepared = true
                        true
                    },
                ),
            )

        assertEquals(DockerPreparationOutcome.STARTED_AND_READY, result.outcome)
        assertEquals(0, downloader.calls, "starting must never download an installer")
        assertTrue(runner.invocations.none { it.contains("install") })
        assertTrue(stages.contains(SetupStep.STARTING_DOCKER))
        assertFalse(stages.contains(SetupStep.INSTALLING_DOCKER))
        assertTrue(unattendedStartPrepared, "an existing installation must receive the quiet-start preference too")
    }

    @Test
    fun waiting_for_the_engine_does_not_start_docker_again() = runBlocking {
        val launcher = FakeLauncher()
        val runner = FakeRunner()
        val stages = ArrayList<SetupStep>()

        val result = preparer(writingDownloader(), runner = runner, launcher = launcher)
            .prepare(DockerSetupAction.WAIT_FOR_ENGINE, licenseAccepted = false, context = context(stages = stages))

        assertEquals(DockerPreparationOutcome.STARTED_AND_READY, result.outcome)
        assertEquals(0, launcher.calls)
        assertFalse(stages.contains(SetupStep.STARTING_DOCKER))
        assertTrue(stages.contains(SetupStep.WAITING_FOR_DOCKER_ENGINE))
    }

    @Test
    fun an_engine_that_never_answers_is_reported_as_a_timeout() = runBlocking {
        val result = preparer(writingDownloader(), engineTimeoutMs = 30)
            .prepare(
                DockerSetupAction.WAIT_FOR_ENGINE,
                licenseAccepted = false,
                // Docker Desktop is alive and the engine is genuinely still coming up.
                context = context(engine = DockerEngineState.STARTING),
            )

        assertEquals(DockerPreparationOutcome.ENGINE_TIMEOUT, result.outcome)
    }

    /**
     * [DockerEngineState.UNAVAILABLE] is only reported when `docker info` really answered and Docker
     * Desktop is not coming up, so nobody is going to start the engine. Sitting out the remaining minutes
     * held the whole screen in "Подготовка…" waiting for an application that had been closed.
     */
    @Test
    fun waiting_is_abandoned_when_docker_desktop_is_not_running() = runBlocking {
        val result = preparer(writingDownloader(), engineTimeoutMs = 30_000)
            .prepare(
                DockerSetupAction.WAIT_FOR_ENGINE,
                licenseAccepted = false,
                context = context(engine = DockerEngineState.UNAVAILABLE),
            )

        assertEquals(DockerPreparationOutcome.DESKTOP_NOT_RUNNING, result.outcome)
    }

    /**
     * The launcher started Docker Desktop itself, so an engine that is not there yet is expected: the
     * application needs a moment to become visible and the wait must run to its bound.
     */
    @Test
    fun a_wait_after_starting_docker_ourselves_is_never_abandoned_early() = runBlocking {
        val result = preparer(writingDownloader(), engineTimeoutMs = 30)
            .prepare(
                DockerSetupAction.START_DESKTOP,
                licenseAccepted = false,
                context = context(engine = DockerEngineState.UNAVAILABLE),
            )

        assertEquals(DockerPreparationOutcome.ENGINE_TIMEOUT, result.outcome)
    }

    @Test
    fun a_started_desktop_that_cannot_be_found_fails_to_start() = runBlocking {
        // The Desktop CLI is unavailable on this version and no executable was located.
        val diagnostics = readyDiagnostics().copy(desktopVersion = "4.20.0")
        val result = preparer(writingDownloader(), runner = FakeRunner(exitCode = 1), launcher = FakeLauncher(false))
            .prepare(
                DockerSetupAction.START_DESKTOP,
                licenseAccepted = false,
                context = context(diagnostics = diagnostics, desktop = null),
            )

        assertEquals(DockerPreparationOutcome.START_FAILED, result.outcome)
    }

    // --- readiness contract -----------------------------------------------------------------------

    @Test
    fun windows_containers_are_never_accepted_as_readiness() = runBlocking {
        val result = preparer(writingDownloader()).prepare(
            DockerSetupAction.WAIT_FOR_ENGINE,
            licenseAccepted = false,
            context = context(finalDiagnostics = readyDiagnostics().copy(containerMode = ContainerMode.WINDOWS)),
        )
        assertEquals(DockerPreparationOutcome.CONTAINER_MODE_WINDOWS, result.outcome)
    }

    @Test
    fun an_unconfirmed_backend_is_never_accepted_as_readiness() = runBlocking {
        val unconfirmed = preparer(writingDownloader()).prepare(
            DockerSetupAction.WAIT_FOR_ENGINE,
            licenseAccepted = false,
            context = context(finalDiagnostics = readyDiagnostics().copy(backend = DockerBackend.UNKNOWN)),
        )
        assertEquals(DockerPreparationOutcome.BACKEND_UNCONFIRMED, unconfirmed.outcome)

        val wrong = preparer(writingDownloader()).prepare(
            DockerSetupAction.WAIT_FOR_ENGINE,
            licenseAccepted = false,
            context = context(finalDiagnostics = readyDiagnostics().copy(backend = DockerBackend.NOT_WSL_2)),
        )
        assertEquals(DockerPreparationOutcome.BACKEND_NOT_WSL_2, wrong.outcome)
    }

    @Test
    fun a_missing_compose_after_the_operation_is_reported() = runBlocking {
        val result = preparer(writingDownloader()).prepare(
            DockerSetupAction.WAIT_FOR_ENGINE,
            licenseAccepted = false,
            context = context(finalDiagnostics = readyDiagnostics().copy(compose = DockerComposeState.MISSING)),
        )
        assertEquals(DockerPreparationOutcome.COMPOSE_UNAVAILABLE, result.outcome)
    }

    @Test
    fun the_full_technical_result_stays_inside_the_component() = runBlocking {
        val preparer = preparer(writingDownloader())
        preparer.prepare(DockerSetupAction.INSTALL, licenseAccepted = true, context = context())

        val report = assertNotNull(preparer.lastTechnicalReport)
        assertTrue(report.contains("--backend=wsl-2"), report)
        assertTrue(report.contains("outcome=INSTALLED_AND_READY"), report)
        // The report must not carry the environment or any setting value.
        assertFalse(report.contains("PROCESSOR_ARCHITECTURE"), report)
        assertFalse(report.contains("wslEngineEnabled"), report)
    }
}
