package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.model.SetupStep

/** Installs the signed Docker Desktop DMG using Docker's own installer and macOS authorization dialog. */
class MacDockerPreparer(
    private val downloader: FileDownloader = HttpFileDownloader(),
    private val commands: CommandRunner = CommandRunner(),
    private val dataRoot: Path? = LauncherDataPaths.dataRoot(),
    private val application: Path = Path.of("/Applications/Docker.app"),
    private val platform: HostPlatform = HostPlatformDetector.detect(),
    private val architecture: String = System.getProperty("os.arch").orEmpty(),
    private val expectedHashOverride: String? = null,
) {
    data class Result(val success: Boolean, val message: String)
    data class Distribution(val architecture: String, val url: String, val sha256: String)

    suspend fun prepare(
        onStage: (SetupStep) -> Unit,
        onDownloadProgress: (Long, Long?) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val distribution = distributionFor(architecture)
        if (platform != HostPlatform.MACOS || distribution == null)
            return@withContext Result(false, "Установка Docker Desktop поддерживается на Intel и Apple Silicon Mac")
        val expectedHash = expectedHashOverride ?: distribution.sha256
        val root = dataRoot ?: return@withContext Result(false, "Недоступен каталог данных лаунчера")
        val cache = root.resolve("downloads")
        if (runCatching { Files.createDirectories(cache) }.isFailure) {
            return@withContext Result(false, "Не удалось создать каталог загрузок лаунчера")
        }
        if (Files.isDirectory(application)) return@withContext startDesktop(cache, onStage)

        val dmg = cache.resolve("Docker-$DOCKER_VERSION-${distribution.architecture}.dmg")
        if (!Files.isRegularFile(dmg) || sha256(dmg) != expectedHash) {
            Files.deleteIfExists(dmg)
            onStage(SetupStep.DOWNLOADING_DOCKER)
            val download = downloader.download(distribution.url, dmg, MAX_DMG_BYTES) {
                onDownloadProgress(it.downloadedBytes, it.totalBytes)
            }
            if (download !is DownloadOutcome.Success) {
                return@withContext Result(false, when (download) {
                    is DownloadOutcome.HttpFailure -> "Сервер Docker ответил ${download.status} при загрузке установщика"
                    DownloadOutcome.TooLarge -> "Загрузка Docker прервана: файл слишком большой"
                    DownloadOutcome.Cancelled -> "Загрузка Docker прервана"
                    else -> "Не удалось скачать Docker Desktop; проверьте соединение"
                })
            }
        }
        onStage(SetupStep.VERIFYING_DOCKER_INSTALLER)
        if (sha256(dmg) != expectedHash) {
            Files.deleteIfExists(dmg)
            return@withContext Result(false, "Контрольная сумма Docker Desktop не совпала; файл удалён")
        }
        val verified = commands.execute(listOf("/usr/bin/hdiutil", "verify", dmg.toString()), cache, VERIFY_TIMEOUT_MS)
        if (!verified.successful) {
            Files.deleteIfExists(dmg)
            return@withContext Result(false, "macOS не подтвердила целостность образа Docker Desktop")
        }

        val mount = Files.createTempDirectory(cache, "docker-mount-")
        var attached = false
        try {
            val attach = commands.execute(
                listOf("/usr/bin/hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount.toString(), dmg.toString()),
                cache, MOUNT_TIMEOUT_MS,
            )
            if (!attach.successful) return@withContext Result(false, "Не удалось открыть образ Docker Desktop")
            attached = true
            val bundle = mount.resolve("Docker.app")
            val identifier = commands.execute(
                listOf("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleIdentifier", bundle.resolve("Contents/Info.plist").toString()),
                cache, VERIFY_TIMEOUT_MS,
            )
            val signature = commands.execute(
                listOf("/usr/bin/codesign", "--verify", "--deep", "--strict", bundle.toString()),
                cache, VERIFY_TIMEOUT_MS,
            )
            val gatekeeper = commands.execute(
                listOf("/usr/sbin/spctl", "--assess", "--type", "execute", bundle.toString()),
                cache, VERIFY_TIMEOUT_MS,
            )
            if (!Files.isDirectory(bundle) || !identifier.successful || identifier.output.trim() != "com.docker.docker" ||
                !signature.successful || !gatekeeper.successful
            ) {
                Files.deleteIfExists(dmg)
                return@withContext Result(false, "macOS не подтвердила подпись Docker Desktop; файл удалён")
            }

            onStage(SetupStep.INSTALLING_DOCKER)
            val installer = bundle.resolve("Contents/MacOS/install")
            if (!Files.isExecutable(installer)) return@withContext Result(false, "В образе Docker Desktop не найден штатный установщик")
            val install = commands.execute(
                listOf(
                    "/usr/bin/osascript",
                    "-e", "on run argv",
                    "-e", "do shell script quoted form of (item 1 of argv) with administrator privileges",
                    "-e", "end run",
                    installer.toString(),
                ),
                cache, INSTALL_TIMEOUT_MS,
            )
            if (!install.successful) return@withContext Result(
                false,
                if (install.timedOut) "Установка Docker Desktop не завершилась за 15 минут"
                else "Установка Docker Desktop отменена или завершилась ошибкой",
            )
        } finally {
            if (attached) commands.execute(listOf("/usr/bin/hdiutil", "detach", mount.toString()), cache, MOUNT_TIMEOUT_MS)
            runCatching { Files.deleteIfExists(mount) }
        }

        if (!Files.isDirectory(application)) return@withContext Result(false, "Установщик завершился, но Docker.app не найден в Applications")
        startDesktop(cache, onStage)
    }

    private suspend fun startDesktop(cache: Path, onStage: (SetupStep) -> Unit): Result {
        onStage(SetupStep.STARTING_DOCKER)
        val started = commands.execute(listOf("/usr/bin/open", "-a", application.toString()), cache, START_TIMEOUT_MS)
        return if (started.successful) {
            Result(true, "Docker Desktop открыт. Примите условия в его окне и дождитесь запуска Engine")
        } else {
            Result(false, "Не удалось открыть Docker Desktop; запустите его из Applications")
        }
    }

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

    companion object {
        const val DOCKER_VERSION = "4.98.0"
        const val DOCKER_INTEL_DMG_SHA256 = "96b53f9a4a807b6be6207d18f360b9778ff481e1d94b5027bdf1ae2a22d837cb"
        const val DOCKER_ARM_DMG_SHA256 = "9c6f13ce4edda2abf20d801552b6acb96acd3d64b3e91b50c4484353802758b5"
        fun distributionFor(architecture: String): Distribution? = when (architecture.lowercase()) {
            "amd64", "x86_64" -> Distribution("amd64", "https://desktop.docker.com/mac/main/amd64/245422/Docker.dmg", DOCKER_INTEL_DMG_SHA256)
            "aarch64", "arm64" -> Distribution("arm64", "https://desktop.docker.com/mac/main/arm64/245422/Docker.dmg", DOCKER_ARM_DMG_SHA256)
            else -> null
        }
        const val MAX_DMG_BYTES = 2L * 1024 * 1024 * 1024
        private const val VERIFY_TIMEOUT_MS = 120_000L
        private const val MOUNT_TIMEOUT_MS = 120_000L
        private const val INSTALL_TIMEOUT_MS = 900_000L
        private const val START_TIMEOUT_MS = 30_000L
    }
}
