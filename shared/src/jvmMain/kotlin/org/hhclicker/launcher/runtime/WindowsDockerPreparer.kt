package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import org.hhclicker.launcher.model.ContainerMode
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.DockerComposeState
import org.hhclicker.launcher.model.DockerDiagnostics
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.DockerInstallScope
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.setup.DockerPreparationOutcome
import org.hhclicker.launcher.setup.DockerPreparationResult
import org.hhclicker.launcher.setup.DockerSetupAction
import org.hhclicker.launcher.setup.ExpectedPublisher
import org.hhclicker.launcher.setup.SignatureVerdict

/**
 * Everything the preparer needs from the rest of the launcher, without constructing probes itself.
 *
 * [engineState] is the cheap engine-only poll, [refreshDiagnostics] is the full re-read performed once at
 * the end, and [locateDesktop] finds Docker Desktop again after an installation, because the path does
 * not appear in the PATH this process inherited.
 */
class DockerPreparationContext(
    val diagnostics: DockerDiagnostics,
    val engineState: suspend () -> DockerEngineState,
    val refreshDiagnostics: suspend () -> DockerDiagnostics,
    val locateDesktop: suspend () -> Path?,
    val locateCli: suspend () -> Path?,
    val prepareUnattendedStart: suspend () -> Boolean,
    val onStage: (SetupStep) -> Unit,
    val onDownloadProgress: (Long, Long?) -> Unit,
)

/**
 * The only component allowed to install or start Docker Desktop on this machine.
 *
 * It downloads exactly one official HTTPS URL, refuses to run an installer whose Authenticode signature
 * is not a valid Docker Inc. signature, installs per user with the WSL 2 backend and Linux containers
 * only, starts the application without waiting for it and then polls the engine within a bound.
 *
 * It never uninstalls anything, never changes the installation mode, never touches the Docker data root,
 * never enables Kubernetes, never logs in to Docker Hub, never installs a Linux distribution and never
 * accepts the license on its own. On a fresh install it only marks Docker's optional onboarding survey as
 * already displayed, so the detached first start remains unattended.
 */
class WindowsDockerPreparer(
    private val downloader: FileDownloader = HttpFileDownloader(),
    private val verifier: AuthenticodeVerifier = WindowsAuthenticodeVerifier(),
    private val commandRunner: CommandRunner = CommandRunner(),
    private val processLauncher: ProcessLauncher = SystemProcessLauncher(),
    private val cacheDirectory: Path? = defaultCacheDirectory(),
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
    private val enginePollIntervalMs: Long = DockerDesktopCommands.ENGINE_POLL_INTERVAL_MS,
    private val engineTimeoutMs: Long = DockerDesktopCommands.ENGINE_READY_TIMEOUT_MS,
) {
    /**
     * Full technical result of the last attempt. It stays inside this component: the screen only shows the
     * short message derived from the returned outcome. It never contains an environment variable, a
     * setting value or any credential.
     */
    @Volatile
    var lastTechnicalReport: String? = null
        private set

    suspend fun prepare(
        action: DockerSetupAction,
        licenseAccepted: Boolean,
        context: DockerPreparationContext,
    ): DockerPreparationResult {
        val report = StringBuilder()
        val result = runPreparation(action, licenseAccepted, context, report)
        report.appendLine("outcome=${result.outcome}")
        result.exitCode?.let { report.appendLine("exitCode=$it") }
        result.httpStatus?.let { report.appendLine("httpStatus=$it") }
        lastTechnicalReport = report.toString()
        return result
    }

    private suspend fun runPreparation(
        action: DockerSetupAction,
        licenseAccepted: Boolean,
        context: DockerPreparationContext,
        report: StringBuilder,
    ): DockerPreparationResult {
        report.appendLine("action=$action")
        if (!action.executable) return DockerPreparationResult(DockerPreparationOutcome.NOT_APPLICABLE)
        if (!windows) {
            report.appendLine("skipped=not Windows")
            return DockerPreparationResult(DockerPreparationOutcome.NOT_APPLICABLE)
        }

        val installed = action == DockerSetupAction.INSTALL || action == DockerSetupAction.UPDATE
        if (installed) {
            if (!licenseAccepted) {
                report.appendLine("skipped=license not accepted")
                return DockerPreparationResult(DockerPreparationOutcome.LICENSE_NOT_ACCEPTED)
            }
            val installOutcome = downloadVerifyAndInstall(action, context, report)
            if (installOutcome != null) return installOutcome
        }

        if (action != DockerSetupAction.WAIT_FOR_ENGINE) {
            report.appendLine("unattendedStartPrepared=${context.prepareUnattendedStart()}")
            val startOutcome = startDesktop(context, report)
            if (startOutcome != null) return startOutcome
        }

        context.onStage(SetupStep.WAITING_FOR_DOCKER_ENGINE)
        // Only a wait the launcher did not start may be abandoned early: after starting Docker Desktop
        // ourselves the application takes a moment to become visible, and an early answer means nothing.
        val waited = awaitEngine(
            context,
            report,
            abandonWhenDesktopIsGone = action == DockerSetupAction.WAIT_FOR_ENGINE,
        )
        when (waited) {
            EngineWait.READY -> Unit
            EngineWait.DESKTOP_GONE ->
                return DockerPreparationResult(DockerPreparationOutcome.DESKTOP_NOT_RUNNING)

            EngineWait.TIMED_OUT -> return DockerPreparationResult(DockerPreparationOutcome.ENGINE_TIMEOUT)
        }

        context.onStage(SetupStep.VERIFYING_DOCKER_BACKEND)
        val fresh = context.refreshDiagnostics()
        report.appendLine("engine=${fresh.engine} mode=${fresh.containerMode} backend=${fresh.backend} compose=${fresh.compose}")
        verifyReadiness(fresh)?.let { return it }

        return DockerPreparationResult(
            if (installed) DockerPreparationOutcome.INSTALLED_AND_READY else DockerPreparationOutcome.STARTED_AND_READY,
        )
    }

    /** Returns a failure result, or null when the installation succeeded. */
    private suspend fun downloadVerifyAndInstall(
        action: DockerSetupAction,
        context: DockerPreparationContext,
        report: StringBuilder,
    ): DockerPreparationResult? {
        val cache = cacheDirectory ?: return DockerPreparationResult(DockerPreparationOutcome.NOT_APPLICABLE)
        val installer = cache.resolve(DockerDesktopCommands.INSTALLER_FILE_NAME)

        val prepared = obtainVerifiedInstaller(installer, context, report)
        if (prepared != null) return prepared

        context.onStage(SetupStep.INSTALLING_DOCKER)
        val scope = when (action) {
            DockerSetupAction.INSTALL -> DockerInstallScope.PER_USER
            DockerSetupAction.UPDATE -> context.diagnostics.installScope
            else -> DockerInstallScope.UNKNOWN
        }
        if (scope == DockerInstallScope.UNKNOWN) {
            report.appendLine("installer=skipped unknown installation scope")
            return DockerPreparationResult(DockerPreparationOutcome.NOT_APPLICABLE)
        }
        val arguments = DockerDesktopCommands.installArguments(
            installer,
            licenseAccepted = true,
            scope = scope,
            freshInstall = action == DockerSetupAction.INSTALL,
        )
        report.appendLine("installer=${arguments.drop(1).joinToString(" ")}")
        val run = commandRunner.execute(
            arguments = arguments,
            workingDirectory = cache,
            timeoutMs = DockerDesktopCommands.INSTALL_TIMEOUT_MS,
        )
        return when {
            run.timedOut -> DockerPreparationResult(DockerPreparationOutcome.INSTALLER_TIMED_OUT)
            run.exitCode == null -> DockerPreparationResult(DockerPreparationOutcome.INSTALLER_FAILED)
            run.exitCode != 0 -> DockerPreparationResult(DockerPreparationOutcome.INSTALLER_FAILED, run.exitCode)
            else -> null
        }
    }

    /**
     * Produces a signature-verified installer in the cache, or a failure result.
     *
     * A cached file is never trusted on its own: it is verified again on every run, and a file that fails
     * verification is deleted and downloaded once more before the attempt is abandoned.
     */
    private suspend fun obtainVerifiedInstaller(
        installer: Path,
        context: DockerPreparationContext,
        report: StringBuilder,
    ): DockerPreparationResult? {
        if (Files.isRegularFile(installer)) {
            context.onStage(SetupStep.VERIFYING_DOCKER_INSTALLER)
            val cachedVerdict = verifier.verify(installer, ExpectedPublisher.DOCKER)
            report.appendLine("cachedSignature=${cachedVerdict.verdict}")
            if (cachedVerdict.trusted) return null
            runCatching { Files.deleteIfExists(installer) }
        }

        context.onStage(SetupStep.DOWNLOADING_DOCKER)
        val download = downloader.download(
            url = DockerDesktopCommands.INSTALLER_URL,
            target = installer,
            maxBytes = DockerDesktopCommands.MAX_INSTALLER_BYTES,
            onProgress = { progress -> context.onDownloadProgress(progress.downloadedBytes, progress.totalBytes) },
        )
        report.appendLine("download=${download::class.simpleName}")
        when (download) {
            is DownloadOutcome.Success -> report.appendLine("downloadedBytes=${download.sizeBytes}")
            is DownloadOutcome.HttpFailure ->
                return DockerPreparationResult(DockerPreparationOutcome.DOWNLOAD_FAILED, httpStatus = download.status)
            DownloadOutcome.TooLarge -> return DockerPreparationResult(DockerPreparationOutcome.DOWNLOAD_TOO_LARGE)
            DownloadOutcome.Cancelled -> return DockerPreparationResult(DockerPreparationOutcome.DOWNLOAD_CANCELLED)
            DownloadOutcome.NetworkFailure,
            DownloadOutcome.InsecureUrl,
            DownloadOutcome.TooManyRedirects,
            -> return DockerPreparationResult(DockerPreparationOutcome.DOWNLOAD_FAILED)
        }

        context.onStage(SetupStep.VERIFYING_DOCKER_INSTALLER)
        val verdict = verifier.verify(installer, ExpectedPublisher.DOCKER)
        report.appendLine("signature=${verdict.verdict} status=${verdict.status ?: "none"}")
        if (verdict.verdict != SignatureVerdict.VALID) {
            // An installer the launcher cannot trust is removed instead of being kept for a later attempt.
            runCatching { Files.deleteIfExists(installer) }
            return DockerPreparationResult(DockerPreparationOutcome.SIGNATURE_REJECTED)
        }
        return null
    }

    /** Returns a failure result, or null when Docker Desktop was started. */
    private suspend fun startDesktop(
        context: DockerPreparationContext,
        report: StringBuilder,
    ): DockerPreparationResult? {
        context.onStage(SetupStep.STARTING_DOCKER)
        val executable = context.locateDesktop()
        val cliExecutable = context.locateCli()
        report.appendLine("desktopFound=${executable != null}")
        report.appendLine("desktopCliFound=${cliExecutable != null}")

        // Prefer the located CLI whenever it exists. On older Desktop versions the subcommand simply
        // returns an error and the executable fallback below remains available. This capability probe is
        // essential after a fresh install, where the old diagnostics did not yet know a Desktop version.
        if (cliExecutable != null) {
            val cli = commandRunner.execute(
                arguments = DockerDesktopCommands.desktopCliStartArguments(cliExecutable),
                workingDirectory = executable?.parent ?: cacheDirectory ?: Path.of(System.getProperty("user.dir")),
                timeoutMs = DockerDesktopCommands.DESKTOP_CLI_START_TIMEOUT_MS,
            )
            report.appendLine("desktopCliStart=${cli.exitCode ?: "none"}")
            if (cli.successful) return null
        }

        if (executable == null) return DockerPreparationResult(DockerPreparationOutcome.START_FAILED)
        val started = processLauncher.launch(
            arguments = DockerDesktopCommands.desktopExecutableArguments(executable),
            workingDirectory = executable.parent ?: Path.of(System.getProperty("user.dir")),
        )
        report.appendLine("desktopExecutableStart=$started")
        return if (started) null else DockerPreparationResult(DockerPreparationOutcome.START_FAILED)
    }

    /**
     * Polls the engine within a bound.
     *
     * [abandonWhenDesktopIsGone] ends the wait as soon as the engine answers [DockerEngineState.UNAVAILABLE],
     * which the diagnostics only report when `docker info` really answered and Docker Desktop is not
     * coming up. Waiting out the remaining minutes then means waiting for an application nobody is going
     * to start, with the whole screen held in "Подготовка…" the entire time.
     */
    private suspend fun awaitEngine(
        context: DockerPreparationContext,
        report: StringBuilder,
        abandonWhenDesktopIsGone: Boolean,
    ): EngineWait {
        val deadline = System.currentTimeMillis() + engineTimeoutMs
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            polls++
            val state = context.engineState()
            if (state == DockerEngineState.RUNNING) {
                report.appendLine("enginePolls=$polls")
                return EngineWait.READY
            }
            if (abandonWhenDesktopIsGone && state == DockerEngineState.UNAVAILABLE) {
                report.appendLine("enginePolls=$polls abandoned=Docker Desktop is not running")
                return EngineWait.DESKTOP_GONE
            }
            delay(enginePollIntervalMs)
        }
        report.appendLine("enginePolls=$polls timeoutMs=$engineTimeoutMs")
        return EngineWait.TIMED_OUT
    }

    /** How a bounded wait for the engine ended. */
    private enum class EngineWait { READY, DESKTOP_GONE, TIMED_OUT }

    /** The complete readiness contract. An unconfirmed backend is a failure, never a success. */
    private fun verifyReadiness(diagnostics: DockerDiagnostics): DockerPreparationResult? = when {
        diagnostics.engine != DockerEngineState.RUNNING ->
            DockerPreparationResult(DockerPreparationOutcome.ENGINE_TIMEOUT)

        diagnostics.containerMode == ContainerMode.WINDOWS ->
            DockerPreparationResult(DockerPreparationOutcome.CONTAINER_MODE_WINDOWS)

        diagnostics.backend == DockerBackend.NOT_WSL_2 ->
            DockerPreparationResult(DockerPreparationOutcome.BACKEND_NOT_WSL_2)

        diagnostics.backend != DockerBackend.WSL_2 ->
            DockerPreparationResult(DockerPreparationOutcome.BACKEND_UNCONFIRMED)

        diagnostics.compose != DockerComposeState.AVAILABLE ->
            DockerPreparationResult(DockerPreparationOutcome.COMPOSE_UNAVAILABLE)

        else -> null
    }

    companion object {
        /**
         * The installer lives in a launcher-owned cache under LOCALAPPDATA, never next to the portable
         * executable, so a read-only or shared application directory is never written to.
         */
        fun defaultCacheDirectory(environment: (String) -> String? = System::getenv): Path? {
            val localAppData = environment("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: return null
            return runCatching {
                val directory = Path.of(localAppData).resolve(DockerDesktopCommands.CACHE_DIRECTORY)
                Files.createDirectories(directory)
                directory
            }.getOrNull()
        }
    }
}
