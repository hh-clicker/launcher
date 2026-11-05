package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import org.hhclicker.launcher.model.HostPlatform

/** Stable per-user paths kept outside the replaceable application payload. */
object LauncherDataPaths {
    const val APPLICATION_DIRECTORY = "HH Clicker"
    const val DATA_DIRECTORY = "data"
    const val BROWSER_AGENT_HANDLE_FILE = "browser-agent.json"

    fun applicationRoot(
        environment: (String) -> String? = System::getenv,
        platform: HostPlatform = HostPlatformDetector.detect(),
        userHome: () -> String? = { System.getProperty("user.home") },
    ): Path? = runCatching {
        when (platform) {
            HostPlatform.WINDOWS -> environment("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)?.let { Path.of(it, APPLICATION_DIRECTORY) }
            HostPlatform.LINUX -> {
                val xdg = environment("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
                    ?.let(Path::of)?.takeIf(Path::isAbsolute)
                val base = xdg ?: (environment("HOME") ?: userHome())
                    ?.takeIf(String::isNotBlank)?.let { Path.of(it, ".local", "share") }
                base?.resolve("hh-clicker")
            }
            HostPlatform.MACOS -> (environment("HOME") ?: userHome())
                ?.takeIf(String::isNotBlank)?.let { Path.of(it, "Library", "Application Support", APPLICATION_DIRECTORY) }
            HostPlatform.OTHER -> null
        }
    }.getOrNull()

    fun dataRoot(environment: (String) -> String? = System::getenv): Path? =
        applicationRoot(environment)?.resolve(DATA_DIRECTORY)

    /**
     * Moves only launcher-created state from the legacy mixed layout into `data`.
     *
     * Every move is best-effort and refuses to replace an existing destination. This keeps startup
     * safe when an old browser still has a profile locked or a previous migration was interrupted.
     */
    fun migrateLegacyLayout(
        environment: (String) -> String? = System::getenv,
        platform: HostPlatform = HostPlatformDetector.detect(),
    ) {
        if (platform != HostPlatform.WINDOWS) return
        val legacyRoot = applicationRoot(environment, platform) ?: return
        val targetRoot = legacyRoot.resolve(DATA_DIRECTORY)

        listOf("config", "browser-agent", "downloads", "launcher-cache").forEach { name ->
            moveIfAbsent(legacyRoot.resolve(name), targetRoot.resolve(name))
        }

        val legacyRuntime = legacyRoot.resolve("runtime")
        val targetRuntime = targetRoot.resolve("runtime")
        moveIfAbsent(
            legacyRuntime.resolve(BROWSER_AGENT_HANDLE_FILE),
            targetRuntime.resolve(BROWSER_AGENT_HANDLE_FILE),
        )
        runCatching {
            if (!Files.isDirectory(legacyRuntime)) return@runCatching
            Files.list(legacyRuntime).use { children ->
                children
                    .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("node-") }
                    .forEach { child -> moveIfAbsent(child, targetRuntime.resolve(child.fileName)) }
            }
        }
    }

    private fun moveIfAbsent(source: Path, destination: Path): Boolean = runCatching {
        if (!Files.exists(source) || Files.exists(destination)) return false
        Files.createDirectories(destination.parent)
        Files.move(source, destination)
        true
    }.getOrDefault(false)
}
