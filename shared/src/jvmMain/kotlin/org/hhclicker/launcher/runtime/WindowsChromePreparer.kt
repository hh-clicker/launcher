package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.setup.ChromePreparationOutcome
import org.hhclicker.launcher.setup.ChromePreparationResult
import org.hhclicker.launcher.setup.ChromeSetupAction
import org.hhclicker.launcher.setup.ExpectedPublisher
import org.hhclicker.launcher.setup.SignatureVerdict

/**
 * Everything the Chrome preparer needs from the rest of the launcher.
 *
 * [locateChrome] is the same search the preflight row uses. It is called twice: once before anything is
 * fetched, so an existing Chrome ends the operation without a download, and once after the installer
 * reported success, because an MSI success code alone is not proof that chrome.exe exists.
 */
class ChromePreparationContext(
    val locateChrome: suspend () -> Path?,
    val onStage: (SetupStep) -> Unit,
    val onDownloadProgress: (Long, Long?) -> Unit,
)

/**
 * The only component allowed to install Google Chrome on this machine.
 *
 * It downloads exactly one official HTTPS URL, refuses to run an installer whose Authenticode signature
 * is not a valid Google LLC signature, and installs silently through msiexec with a fixed argument
 * list.
 *
 * It never touches an existing Chrome: an installation found before the download ends the operation
 * immediately. It never updates, repairs, reinstalls or uninstalls Chrome, never terminates a running
 * chrome.exe, never starts the browser afterwards, never changes the default browser and never creates
 * shortcuts of its own. A failed attempt rolls back nothing, because nothing on the machine was
 * changed by anything except msiexec itself.
 */
class WindowsChromePreparer(
    private val downloader: FileDownloader = HttpFileDownloader(),
    private val verifier: AuthenticodeVerifier = WindowsAuthenticodeVerifier(),
    private val commandRunner: CommandRunner = CommandRunner(),
    private val cacheDirectory: Path? = defaultCacheDirectory(),
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
    private val msiexec: Path? = ChromeInstallerCommands.msiexecPath(),
    private val installTimeoutMs: Long = ChromeInstallerCommands.INSTALL_TIMEOUT_MS,
) {
    /**
     * Full technical result of the last attempt. It stays inside this component: the screen only shows
     * the short message derived from the returned outcome. It never contains an environment variable,
     * a user path or any credential.
     */
    @Volatile
    var lastTechnicalReport: String? = null
        private set

    suspend fun prepare(
        action: ChromeSetupAction,
        termsAccepted: Boolean,
        context: ChromePreparationContext,
    ): ChromePreparationResult {
        val report = StringBuilder()
        val result = runPreparation(action, termsAccepted, context, report)
        report.appendLine("outcome=${result.outcome}")
        result.exitCode?.let { report.appendLine("exitCode=$it") }
        result.httpStatus?.let { report.appendLine("httpStatus=$it") }
        lastTechnicalReport = report.toString()
        return result
    }

    private suspend fun runPreparation(
        action: ChromeSetupAction,
        termsAccepted: Boolean,
        context: ChromePreparationContext,
        report: StringBuilder,
    ): ChromePreparationResult {
        report.appendLine("action=$action")
        if (!action.executable) return ChromePreparationResult(ChromePreparationOutcome.NOT_APPLICABLE)
        if (!windows) {
            report.appendLine("skipped=not Windows")
            return ChromePreparationResult(ChromePreparationOutcome.NOT_APPLICABLE)
        }
        if (!termsAccepted) {
            report.appendLine("skipped=terms not accepted")
            return ChromePreparationResult(ChromePreparationOutcome.TERMS_NOT_ACCEPTED)
        }

        // An installed Chrome is never touched. This is the last gate before anything leaves the machine.
        if (context.locateChrome() != null) {
            report.appendLine("skipped=chrome already installed")
            return ChromePreparationResult(ChromePreparationOutcome.ALREADY_INSTALLED)
        }

        // Never fall back to a same-named executable from PATH: only Windows' own absolute path is
        // acceptable for running an MSI with elevation.
        val installerEngine = msiexec
            ?: return ChromePreparationResult(ChromePreparationOutcome.INSTALLER_ENGINE_NOT_FOUND)

        val cache = cacheDirectory ?: return ChromePreparationResult(ChromePreparationOutcome.NOT_APPLICABLE)
        val installer = cache.resolve(ChromeInstallerCommands.INSTALLER_FILE_NAME)

        obtainVerifiedInstaller(installer, context, report)?.let { return it }

        context.onStage(SetupStep.INSTALLING_CHROME)
        val arguments = ChromeInstallerCommands.elevatedArguments()
        report.appendLine("installer=elevated msiexec /i <installer> /qn /norestart")
        val run = commandRunner.execute(
            arguments = arguments,
            workingDirectory = cache,
            timeoutMs = installTimeoutMs,
            environment = ChromeInstallerCommands.elevatedEnvironment(installerEngine, installer),
        )
        val elevatedExitCode = ChromeInstallerCommands.reportedExitCode(run.output)
        report.appendLine("wrapperExit=${run.exitCode ?: "none"} elevatedExit=${elevatedExitCode ?: "none"} timedOut=${run.timedOut}")

        val installed = when {
            run.timedOut -> return ChromePreparationResult(ChromePreparationOutcome.INSTALLER_TIMED_OUT)
            run.exitCode == null || run.exitCode != 0 ->
                return ChromePreparationResult(ChromePreparationOutcome.INSTALLER_FAILED)

            run.output.contains(ChromeInstallerCommands.ELEVATION_DECLINED_MARKER) ->
                return ChromePreparationResult(ChromePreparationOutcome.INSTALLER_CANCELLED)

            run.output.contains(ChromeInstallerCommands.ELEVATION_FAILED_MARKER) ->
                return ChromePreparationResult(ChromePreparationOutcome.ELEVATION_FAILED)

            elevatedExitCode == ChromeInstallerCommands.EXIT_SUCCESS ->
                ChromePreparationOutcome.INSTALLED_AND_READY

            elevatedExitCode == ChromeInstallerCommands.EXIT_SUCCESS_REBOOT_REQUIRED ->
                ChromePreparationOutcome.INSTALLED_RESTART_RECOMMENDED

            elevatedExitCode == ChromeInstallerCommands.EXIT_USER_CANCELLED ->
                return ChromePreparationResult(ChromePreparationOutcome.INSTALLER_CANCELLED, elevatedExitCode)

            elevatedExitCode == ChromeInstallerCommands.EXIT_ANOTHER_INSTALLATION_RUNNING ->
                return ChromePreparationResult(
                    ChromePreparationOutcome.ANOTHER_INSTALLATION_RUNNING,
                    elevatedExitCode,
                )

            else -> return ChromePreparationResult(ChromePreparationOutcome.INSTALLER_FAILED, elevatedExitCode)
        }

        // A success code is a claim, not a fact: the same locator has to find chrome.exe afterwards.
        val located = context.locateChrome()
        report.appendLine("chromeFoundAfterInstall=${located != null}")
        if (located == null) {
            return ChromePreparationResult(
                ChromePreparationOutcome.CHROME_NOT_FOUND_AFTER_INSTALL,
                elevatedExitCode,
            )
        }
        return ChromePreparationResult(installed, elevatedExitCode)
    }

    /**
     * Produces a signature-verified MSI in the cache, or a failure result.
     *
     * A cached file is never trusted on its own: it is verified again before every run, and a file that
     * fails verification is deleted and downloaded once more before the attempt is abandoned.
     */
    private suspend fun obtainVerifiedInstaller(
        installer: Path,
        context: ChromePreparationContext,
        report: StringBuilder,
    ): ChromePreparationResult? {
        if (Files.isRegularFile(installer)) {
            context.onStage(SetupStep.VERIFYING_CHROME_INSTALLER)
            val cachedVerdict = verifier.verify(installer, ExpectedPublisher.GOOGLE)
            report.appendLine("cachedSignature=${cachedVerdict.verdict}")
            if (cachedVerdict.trusted) return null
            runCatching { Files.deleteIfExists(installer) }
        }

        context.onStage(SetupStep.DOWNLOADING_CHROME)
        val download = downloader.download(
            url = ChromeInstallerCommands.INSTALLER_URL,
            target = installer,
            maxBytes = ChromeInstallerCommands.MAX_INSTALLER_BYTES,
            onProgress = { progress -> context.onDownloadProgress(progress.downloadedBytes, progress.totalBytes) },
        )
        report.appendLine("download=${download::class.simpleName}")
        when (download) {
            is DownloadOutcome.Success -> report.appendLine("downloadedBytes=${download.sizeBytes}")
            is DownloadOutcome.HttpFailure ->
                return ChromePreparationResult(
                    ChromePreparationOutcome.DOWNLOAD_FAILED,
                    httpStatus = download.status,
                )

            DownloadOutcome.TooLarge -> return ChromePreparationResult(ChromePreparationOutcome.DOWNLOAD_TOO_LARGE)
            DownloadOutcome.Cancelled -> return ChromePreparationResult(ChromePreparationOutcome.DOWNLOAD_CANCELLED)
            DownloadOutcome.NetworkFailure,
            DownloadOutcome.InsecureUrl,
            DownloadOutcome.TooManyRedirects,
            -> return ChromePreparationResult(ChromePreparationOutcome.DOWNLOAD_FAILED)
        }

        context.onStage(SetupStep.VERIFYING_CHROME_INSTALLER)
        val verdict = verifier.verify(installer, ExpectedPublisher.GOOGLE)
        report.appendLine("signature=${verdict.verdict} status=${verdict.status ?: "none"}")
        if (verdict.verdict != SignatureVerdict.VALID) {
            // An installer the launcher cannot trust is removed instead of being kept for a later run.
            runCatching { Files.deleteIfExists(installer) }
            return ChromePreparationResult(ChromePreparationOutcome.SIGNATURE_REJECTED)
        }
        return null
    }

    companion object {
        /**
         * The MSI lives in a launcher-owned cache under LOCALAPPDATA, never next to the portable
         * executable, so a read-only or shared application directory is never written to.
         */
        fun defaultCacheDirectory(environment: (String) -> String? = System::getenv): Path? {
            val localAppData = environment("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: return null
            return runCatching {
                val directory = Path.of(localAppData).resolve(ChromeInstallerCommands.CACHE_DIRECTORY)
                Files.createDirectories(directory)
                directory
            }.getOrNull()
        }
    }
}
