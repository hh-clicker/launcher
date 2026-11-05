package org.hhclicker.launcher.runtime

import java.nio.file.Path

/**
 * Builds shell-free Compose invocations with an explicit project identity and durable env file.
 *
 * The executable is an argument rather than a constant: it comes from
 * [org.hhclicker.launcher.preflight.DockerCliResolver], so a Docker Desktop the launcher has just
 * installed is used by its absolute path instead of a `docker` that is not yet on this process's PATH.
 */
internal object DockerComposeCommands {
    fun up(
        docker: String,
        projectName: String,
        environmentFile: Path,
        build: Boolean = true,
        composeFiles: List<Path> = emptyList(),
    ): List<String> =
        withProjectAndEnvironment(
            docker,
            if (build) listOf("up", "-d", "--build") else listOf("up", "-d"),
            projectName,
            environmentFile,
            composeFiles,
        )

    fun down(docker: String, projectName: String, environmentFile: Path, composeFiles: List<Path> = emptyList()): List<String> =
        withProjectAndEnvironment(docker, listOf("down"), projectName, environmentFile, composeFiles)

    /** [subcommand] is the Compose verb and its options only; `docker compose` is prepended here. */
    private fun withProjectAndEnvironment(
        docker: String,
        subcommand: List<String>,
        projectName: String,
        environmentFile: Path,
        composeFiles: List<Path>,
    ): List<String> {
        require(docker.isNotBlank()) { "Не определён исполняемый файл Docker" }
        require(subcommand.isNotEmpty()) { "Не указана команда Compose" }
        require(projectName.matches(PROJECT_NAME)) { "Некорректное имя Compose project" }
        return buildList {
            add(docker)
            add("compose")
            add("--project-name")
            add(projectName)
            add("--env-file")
            add(environmentFile.toAbsolutePath().normalize().toString())
            composeFiles.forEach { file ->
                add("-f")
                add(file.toAbsolutePath().normalize().toString())
            }
            addAll(subcommand)
        }
    }

    private val PROJECT_NAME = Regex("[a-z0-9][a-z0-9_-]*")
}
