package org.hhclicker.launcher.runtime

import java.nio.file.Path
import org.hhclicker.launcher.model.DockerInstallScope

/**
 * The closed set of Docker Desktop operations the launcher may perform, and the only download URL it
 * will ever use.
 *
 * Every argument below is a compile time constant. The only runtime value is the launcher-owned path of
 * the cached installer, which is passed as `argv[0]` of a process — never through a shell, never
 * concatenated into a command line.
 */
object DockerDesktopCommands {

    /** Official Docker Desktop installer for Windows on x86_64. HTTPS only. */
    const val INSTALLER_URL = "https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe"

    const val INSTALLER_FILE_NAME = "Docker Desktop Installer.exe"

    /** Directory name of the launcher-owned cache under LOCALAPPDATA. */
    const val CACHE_DIRECTORY = "HH Clicker/data/launcher-cache/docker"

    /** The published installer is a few hundred megabytes; anything beyond this is not it. */
    const val MAX_INSTALLER_BYTES = 1_500L * 1024 * 1024

    const val INSTALL_TIMEOUT_MS = 1_800_000L
    const val DESKTOP_CLI_START_TIMEOUT_MS = 120_000L

    /** Docker Desktop can take minutes on a first run; the wait is bounded and reports its own state. */
    const val ENGINE_READY_TIMEOUT_MS = 300_000L
    const val ENGINE_POLL_INTERVAL_MS = 3_000L

    /**
     * Arguments for installing or updating Docker Desktop.
     *
     * `--accept-license` is appended only when [licenseAccepted] is true, which happens exclusively after
     * the user pressed the button that states the Docker Subscription Service Agreement is accepted.
     * A fresh installation is per-user, uses WSL 2 and disables Windows-container integration. During an
     * update [scope] preserves the existing installation mode and [freshInstall] is false, so neither the
     * backend nor the container mode is reconfigured. Hyper-V, Docker VMM, `--always-run-service` and the
     * docker-users group are never requested.
     */
    fun installArguments(
        installer: Path,
        licenseAccepted: Boolean,
        scope: DockerInstallScope = DockerInstallScope.PER_USER,
        freshInstall: Boolean = true,
    ): List<String> = buildList {
        require(scope != DockerInstallScope.UNKNOWN) { "Docker installation scope must be known" }
        add(installer.toAbsolutePath().toString())
        add("install")
        add("--quiet")
        if (scope == DockerInstallScope.PER_USER) add("--user")
        if (licenseAccepted) add("--accept-license")
        if (freshInstall) {
            add("--backend=wsl-2")
            add("--no-windows-containers")
        }
    }

    /**
     * Detached start through the documented `docker desktop` CLI.
     *
     * Only the absolute-path form exists. A PATH-based variant used to sit next to it and was never
     * called in production, which is exactly how a bare `docker` survives a review: the executable is
     * always the one Docker Desktop ships, because a freshly installed Desktop is not on this process's
     * inherited PATH.
     */
    fun desktopCliStartArguments(cliExecutable: Path): List<String> =
        listOf(cliExecutable.toAbsolutePath().toString(), "desktop", "start", "--detach")

    /** Fallback start path: run the located application directly and do not wait for it. */
    fun desktopExecutableArguments(desktopExecutable: Path): List<String> =
        listOf(desktopExecutable.toAbsolutePath().toString())
}
