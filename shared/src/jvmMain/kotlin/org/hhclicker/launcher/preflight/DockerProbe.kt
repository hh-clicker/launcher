package org.hhclicker.launcher.preflight

import java.nio.file.Path
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.DockerCliState
import org.hhclicker.launcher.model.DockerDesktopProcessState
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.DockerDiagnostics
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.DockerInstallScope
import org.hhclicker.launcher.setup.CommandOutcome
import org.hhclicker.launcher.setup.DockerDiagnosticsParser
import org.hhclicker.launcher.setup.DockerEngineProbe
import org.hhclicker.launcher.setup.DockerVersionPolicy

/**
 * Read-only probe for the Docker prerequisite.
 *
 * It reuses [CommandProbe] instead of starting processes on its own and only runs fixed argument lists.
 * Nothing here changes the machine, and the engine check is a real `docker info` with its own bound.
 */
class DockerProbe(
    private val commandProbe: CommandProbe = CommandProbe(),
    private val engineProbe: CommandProbe = CommandProbe(ENGINE_TIMEOUT_MS),
    private val locator: DockerInstallationLocator = DockerInstallationLocator(commandProbe),
    private val settingsReader: DockerSettingsReader = DockerSettingsReader(),
    private val environment: (String) -> String? = System::getenv,
    private val processes: () -> List<String> = ::currentUserProcessCommands,
) {

    /**
     * Finds the Docker Desktop executable again.
     *
     * Called after an installation, because a freshly installed Docker Desktop does not appear in the
     * PATH this process inherited and its location must be re-read rather than remembered.
     */
    fun locateDesktopExecutable(): Path? = runCatching { locator.locate()?.desktopExecutable }.getOrNull()

    /**
     * Finds Docker Desktop's own CLI without relying on the PATH inherited when the launcher started.
     * This matters immediately after installation: Windows updates PATH for future processes only.
     */
    fun locateCliExecutable(): Path? = runCatching { locator.locate()?.cliExecutable }.getOrNull()

    fun probe(): DockerDiagnostics {
        val architecture = DockerDiagnosticsParser.architecture(
            wow64Architecture = environment("PROCESSOR_ARCHITEW6432"),
            processorArchitecture = environment("PROCESSOR_ARCHITECTURE"),
            jvmArchitecture = System.getProperty("os.arch"),
        )

        val installation = runCatching { locator.locate() }

        val desktopState = if (installation.isFailure) {
            DockerDesktopState.CHECK_FAILED
        } else {
            DockerDiagnosticsParser.desktopState(
                installed = installation.getOrNull() != null,
                version = installation.getOrNull()?.version,
            )
        }
        val desktopVersion = installation.getOrNull()?.version

        val dockerCommand = installation.getOrNull()?.cliExecutable?.toAbsolutePath()?.toString() ?: DOCKER_ON_PATH
        val cli = DockerDiagnosticsParser.cli(run(commandProbe, cliVersionCommand(dockerCommand)))

        // Compose and the engine are only meaningful once a CLI answered; skipping them keeps a clean
        // machine's preflight fast and avoids reporting a derived failure as an independent one.
        val compose = if (cli.state == DockerCliState.AVAILABLE) {
            DockerDiagnosticsParser.compose(run(commandProbe, composeVersionCommand(dockerCommand)))
        } else {
            DockerDiagnosticsParser.compose(CommandOutcome.failedToStart())
        }

        val desktopProcess = desktopProcessState()

        val engine = if (cli.state == DockerCliState.AVAILABLE) {
            DockerDiagnosticsParser.engine(run(engineProbe, infoCommand(dockerCommand)), desktopProcess)
        } else {
            DockerDiagnosticsParser.engine(CommandOutcome.failedToStart(), desktopProcess)
        }

        val defaultBackend = if (DockerVersionPolicy.wslIsDefault(desktopVersion)) {
            DockerBackend.WSL_2
        } else {
            DockerBackend.UNKNOWN
        }
        val backend = runCatching { settingsReader.backend(defaultBackend) }.getOrNull() ?: DockerBackend.UNKNOWN

        return DockerDiagnostics(
            architecture = architecture,
            desktop = desktopState,
            desktopVersion = desktopVersion,
            installScope = installation.getOrNull()?.scope ?: DockerInstallScope.UNKNOWN,
            cli = cli.state,
            cliVersion = cli.version,
            compose = compose.state,
            composeVersion = compose.version,
            desktopProcess = desktopProcess,
            engine = engine.state,
            engineVersion = engine.serverVersion,
            containerMode = engine.containerMode,
            backend = backend,
            updateRecommended = desktopState == DockerDesktopState.INSTALLED &&
                DockerVersionPolicy.desktopUpdateRecommended(desktopVersion),
        )
    }

    /**
     * Cheap engine-only probe used while waiting for Docker Desktop to come up.
     *
     * Polling the full diagnostics would re-read the registry on every tick; this asks the engine only.
     */
    fun probeEngine(): DockerEngineProbe {
        val dockerCommand = locateCliExecutable()?.toAbsolutePath()?.toString() ?: DOCKER_ON_PATH
        return DockerDiagnosticsParser.engine(run(engineProbe, infoCommand(dockerCommand)), desktopProcessState())
    }

    /**
     * Detects the Docker Desktop application process through the JVM process API.
     *
     * This avoids spawning `tasklist` for a fact the runtime already knows. Commands of processes owned
     * by other users are not readable, which is reported as unknown rather than as stopped.
     */
    private fun desktopProcessState(): DockerDesktopProcessState {
        val commands = runCatching { processes() }.getOrNull() ?: return DockerDesktopProcessState.UNKNOWN
        if (commands.isEmpty()) return DockerDesktopProcessState.UNKNOWN
        val running = commands.any { it.endsWith(DESKTOP_PROCESS_NAME, ignoreCase = true) }
        return if (running) DockerDesktopProcessState.RUNNING else DockerDesktopProcessState.STOPPED
    }

    private fun run(probe: CommandProbe, arguments: List<String>): CommandOutcome {
        val result = probe.execute(arguments)
        return CommandOutcome(
            started = result.started,
            exitCode = result.exitCode,
            output = result.output,
            timedOut = result.timedOut,
        )
    }

    companion object {
        /** `docker info` can be slow while the engine boots, so it gets a bound of its own. */
        const val ENGINE_TIMEOUT_MS = 20_000L

        const val DESKTOP_PROCESS_NAME = "Docker Desktop.exe"

        const val DOCKER_ON_PATH = "docker"

        val CLI_VERSION_COMMAND = cliVersionCommand(DOCKER_ON_PATH)
        val COMPOSE_VERSION_COMMAND = composeVersionCommand(DOCKER_ON_PATH)

        /** One read-only call that answers both the container platform and the server version. */
        val INFO_COMMAND = infoCommand(DOCKER_ON_PATH)

        fun cliVersionCommand(executable: String): List<String> = listOf(executable, "--version")

        fun composeVersionCommand(executable: String): List<String> = listOf(executable, "compose", "version")

        fun infoCommand(executable: String): List<String> = listOf(
            executable,
            "info",
            "--format",
            "{{.OSType}}${DockerDiagnosticsParser.INFO_SEPARATOR}{{.ServerVersion}}",
        )
    }
}

/**
 * Command lines of the processes this user can see.
 *
 * Uses the JVM process API instead of spawning `tasklist`: the launcher already knows how to look at
 * processes and must not start one more just to answer a boolean.
 */
internal fun currentUserProcessCommands(): List<String> {
    val commands = ArrayList<String>()
    ProcessHandle.allProcesses().forEach { handle ->
        handle.info().command().ifPresent { command -> if (command.isNotEmpty()) commands += command }
    }
    return commands
}
