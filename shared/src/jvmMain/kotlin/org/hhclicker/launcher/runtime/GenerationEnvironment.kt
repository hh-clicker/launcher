package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.preflight.DockerCliResolver

class GenerationEnvironment(
    private val commandRunner: CommandRunner = CommandRunner(),
    private val readinessProbe: HttpReadinessProbe = HttpReadinessProbe(),
    private val dockerCli: DockerCliResolver = DockerCliResolver(),
) {
    private val buildPolicy = ComposeBuildPolicy(commandRunner, dockerCli::executable)

    suspend fun start(workspace: Path, configuration: LauncherRuntimeConfiguration): String? {
        val endpoints = configuration.endpoints
        val directory = workspace.resolve("generation-agent")
        val cliVersion = runCatching {
            Files.readAllLines(configuration.generationEnvironmentFile)
                .firstOrNull { it.startsWith("CODEX_CLI_VERSION=") }
                ?.substringAfter('=')?.trim() ?: "0.163.0"
        }.getOrNull()
        val build = if (cliVersion == null) {
            ComposeBuildDecision(true, null)
        } else {
            buildPolicy.decide(
                workspace,
                configuration,
                configuration.generationProjectName,
                listOf("generation-agent"),
                buildInputs = mapOf("CODEX_CLI_VERSION" to cliVersion),
            )
        }
        val command = commandRunner.execute(
            arguments = DockerComposeCommands.up(
                dockerCli.executable(),
                configuration.generationProjectName,
                configuration.generationEnvironmentFile,
                build.build,
                linuxComposeFiles(directory),
            ),
            workingDirectory = directory,
            timeoutMs = COMPOSE_START_TIMEOUT_MS,
        )
        if (!command.successful) {
            return when {
                command.timedOut -> "Generation Agent не запустился за 10 минут"
                command.output.isNotBlank() -> command.output.lineSequence().last()
                else -> "Generation Agent: docker compose завершился с кодом ${command.exitCode ?: "?"}"
            }
        }
        buildPolicy.rememberSuccessfulBuild(configuration, configuration.generationProjectName, build)

        return if (readinessProbe.await("${endpoints.generationUrl}/health", READY_TIMEOUT_MS)) {
            null
        } else {
            "Generation Agent не стал доступен на ${endpoints.generationUrl}"
        }
    }

    suspend fun stop(workspace: Path, configuration: LauncherRuntimeConfiguration): String? {
        val directory = workspace.resolve("generation-agent")
        val result = commandRunner.execute(
            arguments = DockerComposeCommands.down(
                dockerCli.executable(),
                configuration.generationProjectName,
                configuration.generationEnvironmentFile,
                linuxComposeFiles(directory),
            ),
            workingDirectory = directory,
            timeoutMs = COMPOSE_STOP_TIMEOUT_MS,
        )
        if (result.successful) return null
        return if (result.timedOut) {
            "Остановка Generation Agent превысила две минуты"
        } else {
            result.output.lineSequence().lastOrNull() ?: "Не удалось остановить Generation Agent"
        }
    }

    private fun linuxComposeFiles(directory: Path): List<Path> =
        if (HostPlatformDetector.detect() == HostPlatform.LINUX) {
            listOf(directory.resolve("compose.yaml"), directory.resolve("compose.linux.yaml"))
        } else emptyList()

    private companion object {
        const val COMPOSE_START_TIMEOUT_MS = 600_000L
        const val COMPOSE_STOP_TIMEOUT_MS = 120_000L
        const val READY_TIMEOUT_MS = 120_000L
    }
}
