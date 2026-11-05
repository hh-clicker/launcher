package org.hhclicker.launcher.runtime

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.hhclicker.launcher.model.DesktopIntegrationState
import org.hhclicker.launcher.model.HostPlatform

/** Per-user menu integration for an explicitly launched AppImage; never runs during startup. */
class LinuxDesktopIntegration(
    private val environment: (String) -> String? = System::getenv,
    private val platform: () -> HostPlatform = HostPlatformDetector::detect,
    private val iconBytes: () -> ByteArray = {
        checkNotNull(LinuxDesktopIntegration::class.java.classLoader.getResourceAsStream("hh-clicker.png")) {
            "Иконка лаунчера не найдена в AppImage"
        }.use { it.readAllBytes() }
    },
) {
    fun status(): DesktopIntegrationState? {
        if (platform() != HostPlatform.LINUX || source() == null) return null
        return DesktopIntegrationState(installed = Files.isRegularFile(desktopEntry()) && Files.isRegularFile(application()))
    }

    fun install() {
        check(platform() == HostPlatform.LINUX) { "Интеграция доступна только на Linux" }
        val source = checkNotNull(source()) { "Запустите лаунчер из AppImage" }
        check(Files.isRegularFile(source)) { "Файл AppImage не найден" }

        val application = application()
        val icon = dataHome().resolve("icons/hicolor/512x512/apps/hh-clicker.png")
        val desktop = desktopEntry()
        writeAtomically(application, executable = true) { Files.copy(source, it, REPLACE_EXISTING) }
        writeAtomically(icon) { Files.write(it, iconBytes()) }
        writeAtomically(desktop) { Files.writeString(it, desktopText(application)) }
    }

    private fun source(): Path? = environment("APPIMAGE")?.takeIf(String::isNotBlank)
        ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }

    private fun dataHome(): Path {
        val xdg = environment("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
            ?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf(Path::isAbsolute)
        if (xdg != null) return xdg
        val home = checkNotNull(environment("HOME")?.takeIf(String::isNotBlank)
            ?: System.getProperty("user.home")?.takeIf(String::isNotBlank)) { "Домашний каталог не найден" }
        return Path.of(home, ".local", "share")
    }

    private fun application(): Path = dataHome().resolve("hh-clicker/app/HH-Clicker.AppImage")
    private fun desktopEntry(): Path = dataHome().resolve("applications/org.hhclicker.Launcher.desktop")

    private fun desktopText(application: Path): String = """
        [Desktop Entry]
        Type=Application
        Name=HH Clicker
        Exec=${quoteExec(application)}
        Icon=hh-clicker
        Categories=Utility;
        Terminal=false
    """.trimIndent() + "\n"

    private fun quoteExec(path: Path): String = "\"" + path.toString()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("`", "\\`")
        .replace("%", "%%") + "\""

    private fun writeAtomically(target: Path, executable: Boolean = false, write: (Path) -> Unit) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".hh-clicker-", ".tmp")
        try {
            write(temporary)
            if (executable) check(temporary.toFile().setExecutable(true, true)) { "Не удалось разрешить запуск AppImage" }
            try {
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
