package org.hhclicker.launcher.model

/** Host CPU architecture. Automatic Docker Desktop installation is implemented for x64 only. */
enum class HostArchitecture {
    X64_SUPPORTED,
    ARM_UNSUPPORTED,
    UNKNOWN,
}

/** Presence of the Docker Desktop application itself, independent of the CLI on PATH. */
enum class DockerDesktopState {
    NOT_INSTALLED,
    INSTALLED,

    /** Installed but below the minimum supported version of [org.hhclicker.launcher.setup.DockerVersionPolicy]. */
    OUTDATED,
    CHECK_FAILED,
}

/** `docker` command line client. A `docker.exe` on PATH alone never proves Docker Desktop is installed. */
enum class DockerCliState {
    AVAILABLE,
    MISSING,
    FAILED,
}

/** Compose v2 as the `docker compose` subcommand. The legacy `docker-compose` binary is not accepted. */
enum class DockerComposeState {
    AVAILABLE,
    MISSING,
    FAILED,
}

/** Whether the Docker Desktop application process is currently alive. */
enum class DockerDesktopProcessState {
    STOPPED,
    STARTING,
    RUNNING,
    UNKNOWN,
}

/** Reachability of the Docker Engine, established with a real read only `docker info`. */
enum class DockerEngineState {
    UNAVAILABLE,
    STARTING,
    RUNNING,
    FAILED,
}

/** Container platform the engine currently serves. HH Clicker requires Linux containers. */
enum class ContainerMode {
    LINUX,
    WINDOWS,
    UNKNOWN,
}

/**
 * Virtualization backend behind the engine.
 *
 * On Windows, `docker info` reporting `OSType=linux` does not identify the backend: Linux containers
 * also run on Hyper-V and Docker VMM. Only an explicitly confirmed [WSL_2] counts as ready there.
 * On a Linux host, the native Docker probe records [NATIVE_LINUX]. [UNKNOWN] never counts as ready.
 */
enum class DockerBackend {
    WSL_2,
    NATIVE_LINUX,
    NOT_WSL_2,
    UNKNOWN,
}

/** Where Docker Desktop is installed. Recorded for diagnostics; the launcher never changes the mode. */
enum class DockerInstallScope {
    PER_USER,
    ALL_USERS,
    UNKNOWN,
}

/**
 * Independent facts about the Docker prerequisite.
 *
 * Every field is decided by its own probe, so a failed probe never masquerades as a negative answer and
 * an unknown value is never rounded up to success.
 */
data class DockerDiagnostics(
    val architecture: HostArchitecture = HostArchitecture.UNKNOWN,
    val desktop: DockerDesktopState = DockerDesktopState.CHECK_FAILED,
    val desktopVersion: String? = null,
    val installScope: DockerInstallScope = DockerInstallScope.UNKNOWN,
    val cli: DockerCliState = DockerCliState.MISSING,
    val cliVersion: String? = null,
    val compose: DockerComposeState = DockerComposeState.MISSING,
    val composeVersion: String? = null,
    val desktopProcess: DockerDesktopProcessState = DockerDesktopProcessState.UNKNOWN,
    val engine: DockerEngineState = DockerEngineState.UNAVAILABLE,
    val engineVersion: String? = null,
    val containerMode: ContainerMode = ContainerMode.UNKNOWN,
    val backend: DockerBackend = DockerBackend.UNKNOWN,
    /** True when the version is supported but old enough to be worth updating. Never a blocker. */
    val updateRecommended: Boolean = false,
) {
    /**
     * A reachable Linux engine, CLI and Compose v2 are required. Windows additionally confirms WSL 2;
     * a native Linux probe records its own backend explicitly.
     */
    val ready: Boolean
        get() = cli == DockerCliState.AVAILABLE &&
            compose == DockerComposeState.AVAILABLE &&
            engine == DockerEngineState.RUNNING &&
            containerMode == ContainerMode.LINUX &&
            (backend == DockerBackend.WSL_2 || backend == DockerBackend.NATIVE_LINUX)
}
