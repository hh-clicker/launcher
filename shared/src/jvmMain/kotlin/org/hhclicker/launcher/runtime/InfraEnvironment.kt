package org.hhclicker.launcher.runtime

import java.nio.file.Path
import org.hhclicker.launcher.preflight.DockerCliResolver

data class InfraStartResult(
    val backendReady: Boolean,
    val frontendReady: Boolean,
    val error: String? = null,
) {
    val successful: Boolean
        get() = backendReady && frontendReady && error == null
}

class InfraEnvironment(
    private val commandRunner: CommandRunner = CommandRunner(),
    private val readinessProbe: HttpReadinessProbe = HttpReadinessProbe(),
    private val dockerCli: DockerCliResolver = DockerCliResolver(),
) {
    private val buildPolicy = ComposeBuildPolicy(commandRunner, dockerCli::executable)

    suspend fun start(workspace: Path, configuration: LauncherRuntimeConfiguration): InfraStartResult {
        val endpoints = configuration.endpoints
        val infraDirectory = workspace.resolve("infra")
        val build = buildPolicy.decide(workspace, configuration, configuration.infraProjectName, listOf("backend", "frontend"))
        val command = composeUp(infraDirectory, configuration, build.build)
        if (!command.successful) {
            val reason = when {
                command.timedOut -> "Запуск инфраструктуры не завершился за 10 минут"
                command.output.isNotBlank() -> command.output.lineSequence().last()
                else -> "docker compose завершился с кодом ${command.exitCode ?: "?"}"
            }
            return InfraStartResult(false, false, reason)
        }
        buildPolicy.rememberSuccessfulBuild(configuration, configuration.infraProjectName, build)

        val backendReady = readinessProbe.await("${endpoints.backendUrl}/actuator/health", BACKEND_READY_TIMEOUT_MS)
        if (!backendReady) return InfraStartResult(false, false, "Backend не ответил на проверку здоровья")

        val frontendReady = readinessProbe.await("${endpoints.frontendUrl}/", FRONTEND_READY_TIMEOUT_MS)
        return InfraStartResult(
            backendReady = true,
            frontendReady = frontendReady,
            error = if (frontendReady) null else "Frontend не стал доступен на ${endpoints.frontendUrl}",
        )
    }

    suspend fun stop(workspace: Path, configuration: LauncherRuntimeConfiguration): String? {
        val result = commandRunner.execute(
            arguments = DockerComposeCommands.down(
                dockerCli.executable(),
                configuration.infraProjectName,
                configuration.infraEnvironmentFile,
            ),
            workingDirectory = workspace.resolve("infra"),
            timeoutMs = COMPOSE_STOP_TIMEOUT_MS,
        )
        if (result.successful) return null
        return if (result.timedOut) {
            "Остановка инфраструктуры превысила две минуты"
        } else {
            result.output.lineSequence().lastOrNull() ?: "Не удалось остановить инфраструктуру"
        }
    }

    private suspend fun composeUp(
        infraDirectory: Path,
        configuration: LauncherRuntimeConfiguration,
        build: Boolean,
    ): CommandResult = commandRunner.execute(
        arguments = DockerComposeCommands.up(
            dockerCli.executable(),
            configuration.infraProjectName,
            configuration.infraEnvironmentFile,
            build,
        ),
        workingDirectory = infraDirectory,
        timeoutMs = COMPOSE_START_TIMEOUT_MS,
    )

    private companion object {
        const val COMPOSE_START_TIMEOUT_MS = 600_000L
        const val COMPOSE_STOP_TIMEOUT_MS = 120_000L
        const val BACKEND_READY_TIMEOUT_MS = 180_000L
        const val FRONTEND_READY_TIMEOUT_MS = 60_000L
    }
}
