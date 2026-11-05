package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.model.SetupStep

/** Installs Google's universal signed PKG, only after explicit acceptance of Google's terms. */
class MacChromePreparer(
    private val downloader: FileDownloader = HttpFileDownloader(),
    private val commands: CommandRunner = CommandRunner(),
    private val dataRoot: Path? = LauncherDataPaths.dataRoot(),
    private val platform: HostPlatform = HostPlatformDetector.detect(),
    private val architecture: String = System.getProperty("os.arch").orEmpty(),
) {
    data class Result(val success: Boolean, val message: String)

    suspend fun prepare(termsAccepted: Boolean, context: ChromePreparationContext): Result = withContext(Dispatchers.IO) {
        if (platform != HostPlatform.MACOS || architecture.lowercase() !in setOf("amd64", "x86_64", "arm64", "aarch64"))
            return@withContext Result(false, "Установка Chrome поддерживается на Intel и Apple Silicon Mac")
        if (!termsAccepted) return@withContext Result(false, "Установка не начата: условия Google не приняты")
        if (context.locateChrome() != null) return@withContext Result(true, "Google Chrome уже установлен")
        val root = dataRoot ?: return@withContext Result(false, "Недоступен каталог данных лаунчера")
        val cache = Files.createDirectories(root.resolve("downloads"))
        // A unique directory prevents concurrent attempts from replacing each other's package.
        val attempt = Files.createTempDirectory(cache, "chrome-")
        val pkg = attempt.resolve("GoogleChrome.pkg")
        try {
            context.onStage(SetupStep.DOWNLOADING_CHROME)
            val download = downloader.download(DOWNLOAD_URL, pkg, MAX_PKG_BYTES) {
                context.onDownloadProgress(it.downloadedBytes, it.totalBytes)
            }
            if (download !is DownloadOutcome.Success) return@withContext Result(false, when (download) {
                is DownloadOutcome.HttpFailure -> "Сервер Google ответил ${download.status} при загрузке Chrome"
                DownloadOutcome.TooLarge -> "Загрузка Chrome прервана: файл слишком большой"
                DownloadOutcome.Cancelled -> "Загрузка Chrome прервана"
                else -> "Не удалось скачать Chrome; проверьте соединение"
            })
            context.onStage(SetupStep.VERIFYING_CHROME_INSTALLER)
            val signature = commands.execute(listOf("/usr/sbin/pkgutil", "--check-signature", pkg.toString()), attempt, VERIFY_TIMEOUT_MS,
                environment = mapOf("LC_ALL" to "C", "LANG" to "C"))
            val gatekeeper = commands.execute(listOf("/usr/sbin/spctl", "--assess", "--type", "install", pkg.toString()), attempt, VERIFY_TIMEOUT_MS)
            if (!signature.successful || !isGoogleInstallerSignature(signature.output) || !gatekeeper.successful)
                return@withContext Result(false, "Не удалось подтвердить подпись Google. Установка отменена, файл удалён")
            // An installation completed elsewhere while the package downloaded must not be overwritten.
            if (context.locateChrome() != null) return@withContext Result(true, "Google Chrome уже установлен")
            context.onStage(SetupStep.INSTALLING_CHROME)
            val install = commands.execute(listOf(
                "/usr/bin/osascript",
                "-e", "on run argv",
                "-e", "do shell script \"/usr/sbin/installer -pkg \" & quoted form of (item 1 of argv) & \" -target /\" with administrator privileges",
                "-e", "end run",
                pkg.toString(),
            ), attempt, INSTALL_TIMEOUT_MS)
            if (!install.successful) return@withContext Result(false, when {
                install.timedOut -> "Установка Chrome не завершилась за 15 минут"
                install.output.contains("-128") -> "Установка Chrome отменена"
                else -> "Установка Chrome завершилась ошибкой"
            })
            if (context.locateChrome() == null) return@withContext Result(false, "Установщик завершился, но Chrome не найден в Applications")
            Result(true, "Google Chrome установлен")
        } finally {
            Files.deleteIfExists(pkg)
            Files.deleteIfExists(attempt)
        }
    }

    companion object {
        const val DOWNLOAD_URL = "https://dl.google.com/chrome/mac/universal/stable/gcem/GoogleChrome.pkg"
        const val MAX_PKG_BYTES = 1024L * 1024 * 1024
        private const val VERIFY_TIMEOUT_MS = 120_000L
        private const val INSTALL_TIMEOUT_MS = 900_000L

        internal fun isGoogleInstallerSignature(output: String): Boolean = output.lineSequence().any {
            it.trim() == "1. Developer ID Installer: Google LLC (EQHXZ8M8AV)"
        }
    }
}
