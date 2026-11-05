package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hhclicker.launcher.model.SetupStep

/** Explicit Debian 13 package actions. Nothing runs during read-only preflight. */
class LinuxPackagePreparer(
    private val downloader: FileDownloader = HttpFileDownloader(),
    private val userName: () -> String = { System.getProperty("user.name") },
) {
    companion object {
        fun supportsAutoInstall(
            osRelease: String = runCatching { Files.readString(Path.of("/etc/os-release")) }.getOrDefault(""),
            architecture: String = System.getProperty("os.arch").orEmpty(),
        ): Boolean = osRelease.lineSequence().any { it == "ID=debian" } &&
            osRelease.lineSequence().any { it == "VERSION_ID=13" || it == "VERSION_ID=\"13\"" } &&
            architecture in setOf("amd64", "x86_64")
    }

    data class Result(val success: Boolean, val message: String, val restartSession: Boolean = false)

    suspend fun prepareDocker(onStage: (SetupStep) -> Unit): Result = withContext(Dispatchers.IO) {
        prerequisites()?.let { return@withContext it }
        val log = logFile("docker") ?: return@withContext Result(false, "Не удалось открыть журнал установки")
        onStage(SetupStep.INSTALLING_DOCKER)
        val name = userName()
        if (!name.matches(Regex("[a-z_][a-z0-9_-]*"))) return@withContext Result(false, "Не удалось определить имя пользователя для группы docker")
        if (!run(log, "/usr/bin/pkexec", "/bin/sh", "-c",
                "/usr/bin/apt-get update && /usr/bin/apt-get install -y docker.io docker-cli docker-compose docker-buildx && /usr/bin/systemctl enable --now docker && /usr/sbin/usermod -aG docker $name")) {
            return@withContext failure(log)
        }
        Result(true, "Docker установлен. Выйдите из сеанса Debian и войдите снова, чтобы получить доступ к Docker без sudo.", true)
    }

    suspend fun prepareChrome(
        onStage: (SetupStep) -> Unit,
        onDownloadProgress: (Long, Long?) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        prerequisites()?.let { return@withContext it }
        val cache = LauncherDataPaths.dataRoot()?.resolve("downloads")
            ?: return@withContext Result(false, "Недоступен каталог загрузок лаунчера")
        Files.createDirectories(cache)
        val deb = cache.resolve("google-chrome-stable_current_amd64.deb")
        val log = logFile("chrome") ?: return@withContext Result(false, "Не удалось открыть журнал установки")
        onStage(SetupStep.DOWNLOADING_CHROME)
        val downloaded = downloader.download(
            "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb",
            deb, 250L * 1024 * 1024,
        ) { onDownloadProgress(it.downloadedBytes, it.totalBytes) }
        if (downloaded !is DownloadOutcome.Success) return@withContext Result(false, "Не удалось скачать Google Chrome; проверьте соединение")
        if (debField(deb, "Package") != "google-chrome-stable" || debField(deb, "Architecture") != "amd64") {
            Files.deleteIfExists(deb)
            return@withContext Result(false, "Загруженный файл не является пакетом Google Chrome для amd64")
        }
        onStage(SetupStep.INSTALLING_CHROME)
        if (!run(log, "/usr/bin/pkexec", "/usr/bin/apt-get", "install", "-y", deb.toAbsolutePath().toString())) {
            return@withContext failure(log)
        }
        Result(true, "Google Chrome установлен")
    }

    private fun prerequisites(): Result? {
        if (!supportsAutoInstall()) return Result(false, "Автоустановка поддерживается только на Debian 13 x86_64")
        if (!Files.isExecutable(Path.of("/usr/bin/pkexec"))) return Result(false, "Не найден pkexec. Установите пакет pkexec и повторите действие")
        return null
    }

    private fun logFile(component: String): Path? {
        val directory = LauncherDataPaths.dataRoot()?.resolve("logs") ?: return null
        return runCatching {
            Files.createDirectories(directory)
            directory.resolve("install-$component.log")
        }.getOrNull()
    }

    private fun debField(archive: Path, field: String): String? = runCatching {
        val process = ProcessBuilder("/usr/bin/dpkg-deb", "-f", archive.toString(), field)
            .redirectErrorStream(true)
            .start()
        val value = process.inputStream.bufferedReader().readText().trim()
        value.takeIf { process.waitFor() == 0 }
    }.getOrNull()

    private fun run(log: Path, vararg command: String): Boolean = runCatching {
        Files.writeString(log, "\n> ${command.joinToString(" ")}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .start()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun failure(log: Path) = Result(false, "Установка не завершилась. Подробности: $log")
}
