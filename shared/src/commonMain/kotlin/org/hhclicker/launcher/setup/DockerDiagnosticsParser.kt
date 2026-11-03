package org.hhclicker.launcher.setup

import org.hhclicker.launcher.model.ContainerMode
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.DockerCliState
import org.hhclicker.launcher.model.DockerComposeState
import org.hhclicker.launcher.model.DockerDesktopProcessState
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.HostArchitecture

data class DockerCliProbe(val state: DockerCliState, val version: String? = null)

data class DockerComposeProbe(val state: DockerComposeState, val version: String? = null)

data class DockerEngineProbe(
    val state: DockerEngineState,
    val containerMode: ContainerMode = ContainerMode.UNKNOWN,
    val serverVersion: String? = null,
    /**
     * Whether the Docker Desktop application was alive at the moment of this probe.
     *
     * It is carried out of the probe rather than left behind, because it decides which single action the
     * launcher may offer. Reading it from an older full check is how a closed Docker Desktop ended up
     * offering "wait for the engine": nobody was going to start it, so the wait could only time out.
     */
    val desktopProcess: DockerDesktopProcessState = DockerDesktopProcessState.UNKNOWN,
)

/**
 * Turns raw command output and configuration text into the independent Docker facts.
 *
 * Every rule is pure and deterministic. Nothing here starts a process, reads a file or touches the
 * network, so the whole classification is unit tested directly.
 */
object DockerDiagnosticsParser {

    /**
     * The literal separator requested from `docker info --format`. A pipe cannot appear in an OSType or
     * in a server version, so splitting on it is unambiguous.
     */
    const val INFO_SEPARATOR = "|"

    /**
     * Resolves the host architecture.
     *
     * `PROCESSOR_ARCHITEW6432` is checked first: an x64 process emulated on ARM64 Windows reports
     * `AMD64` in `PROCESSOR_ARCHITECTURE` and the real `ARM64` only in the WOW64 variable. The JVM's own
     * `os.arch` is the last resort. Values are classified, never logged.
     */
    fun architecture(wow64Architecture: String?, processorArchitecture: String?, jvmArchitecture: String?): HostArchitecture {
        val candidates = listOf(wow64Architecture, processorArchitecture, jvmArchitecture)
            .mapNotNull { it?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
        if (candidates.any { it == "arm64" || it == "aarch64" }) return HostArchitecture.ARM_UNSUPPORTED
        val first = candidates.firstOrNull() ?: return HostArchitecture.UNKNOWN
        return when (first) {
            "amd64", "x86_64", "x64" -> HostArchitecture.X64_SUPPORTED
            else -> HostArchitecture.UNKNOWN
        }
    }

    /** `docker --version` prints `Docker version 27.3.1, build ce12230`. */
    fun cli(outcome: CommandOutcome): DockerCliProbe = when {
        !outcome.started -> DockerCliProbe(DockerCliState.MISSING)
        outcome.timedOut -> DockerCliProbe(DockerCliState.FAILED)
        outcome.exitCode != 0 -> DockerCliProbe(DockerCliState.FAILED)
        else -> {
            val version = SemanticVersion.parseOrNull(outcome.output)
            if (version == null) {
                DockerCliProbe(DockerCliState.FAILED)
            } else {
                DockerCliProbe(DockerCliState.AVAILABLE, version.toString())
            }
        }
    }

    /**
     * `docker compose version` prints `Docker Compose version v2.29.7`.
     *
     * A successful call that reports a major version below 2 is treated as missing: the launcher only
     * supports the Compose v2 subcommand, never the legacy `docker-compose` binary.
     */
    fun compose(outcome: CommandOutcome): DockerComposeProbe = when {
        !outcome.started -> DockerComposeProbe(DockerComposeState.MISSING)
        outcome.timedOut -> DockerComposeProbe(DockerComposeState.FAILED)
        outcome.exitCode != 0 -> DockerComposeProbe(DockerComposeState.MISSING)
        else -> {
            val version = SemanticVersion.parseOrNull(outcome.output)
            when {
                version == null -> DockerComposeProbe(DockerComposeState.FAILED)
                DockerVersionPolicy.classifyCompose(version.toString()) == VersionCompatibility.BELOW_MINIMUM ->
                    DockerComposeProbe(DockerComposeState.MISSING, version.toString())
                else -> DockerComposeProbe(DockerComposeState.AVAILABLE, version.toString())
            }
        }
    }

    /**
     * Reads `docker info --format {{.OSType}}|{{.ServerVersion}}`.
     *
     * A failing call while the Docker Desktop process is alive means the engine is still coming up, which
     * is a different situation from an engine that is simply not there.
     */
    fun engine(outcome: CommandOutcome, desktopProcess: DockerDesktopProcessState): DockerEngineProbe {
        val comingUp = desktopProcess == DockerDesktopProcessState.RUNNING ||
            desktopProcess == DockerDesktopProcessState.STARTING
        return when {
            !outcome.started -> DockerEngineProbe(DockerEngineState.FAILED, desktopProcess = desktopProcess)
            outcome.timedOut -> DockerEngineProbe(
                if (comingUp) DockerEngineState.STARTING else DockerEngineState.FAILED,
                desktopProcess = desktopProcess,
            )
            outcome.exitCode != 0 -> DockerEngineProbe(
                if (comingUp) DockerEngineState.STARTING else DockerEngineState.UNAVAILABLE,
                desktopProcess = desktopProcess,
            )
            else -> {
                val line = outcome.output.lineSequence().map(String::trim)
                    .lastOrNull { it.contains(INFO_SEPARATOR) }
                    ?: return DockerEngineProbe(DockerEngineState.FAILED, desktopProcess = desktopProcess)
                val parts = line.split(INFO_SEPARATOR)
                val osType = parts.getOrNull(0)?.trim()?.lowercase().orEmpty()
                val serverVersion = parts.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
                DockerEngineProbe(
                    state = DockerEngineState.RUNNING,
                    desktopProcess = desktopProcess,
                    containerMode = when (osType) {
                        "linux" -> ContainerMode.LINUX
                        "windows" -> ContainerMode.WINDOWS
                        else -> ContainerMode.UNKNOWN
                    },
                    serverVersion = serverVersion,
                )
            }
        }
    }

    /**
     * Reads the documented `wslEngineEnabled` setting.
     *
     * The current `settings-store.json` wins over the legacy `settings.json`. Both are parsed as JSON. A
     * missing file, unreadable JSON or an absent key answers [DockerBackend.UNKNOWN] — never [DockerBackend.WSL_2].
     */
    fun backend(
        settingsStoreJson: String?,
        legacySettingsJson: String?,
        defaultWhenAbsent: DockerBackend = DockerBackend.UNKNOWN,
    ): DockerBackend {
        listOf(settingsStoreJson, legacySettingsJson).forEach { text ->
            if (text == null) return@forEach
            val document = MiniJson.parseOrNull(text) as? JsonValue.JsonObject ?: return@forEach
            val enabled = MiniJson.booleanField(document, WSL_ENGINE_SETTING)
            if (enabled != null) return if (enabled) DockerBackend.WSL_2 else DockerBackend.NOT_WSL_2
            if (document.entries.containsKey(WSL_ENGINE_SETTING)) return DockerBackend.UNKNOWN

            // New Docker Desktop versions omit settings that keep their default value. A valid current
            // settings document therefore supplies evidence that the documented default applies; a
            // missing or malformed document does not.
            return defaultWhenAbsent
        }
        return DockerBackend.UNKNOWN
    }

    /** Combines presence and version into the Docker Desktop application state. */
    fun desktopState(installed: Boolean, version: String?): DockerDesktopState = when {
        !installed -> DockerDesktopState.NOT_INSTALLED
        DockerVersionPolicy.desktopUpdateRequired(version) -> DockerDesktopState.OUTDATED
        else -> DockerDesktopState.INSTALLED
    }

    /** The documented Docker Desktop setting that selects the WSL 2 backend. */
    const val WSL_ENGINE_SETTING = "wslEngineEnabled"
}
