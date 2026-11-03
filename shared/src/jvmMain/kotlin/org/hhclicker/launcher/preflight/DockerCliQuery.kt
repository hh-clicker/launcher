package org.hhclicker.launcher.preflight

/**
 * Builds the read-only `docker ps` invocations the launcher uses to observe its own containers.
 *
 * These argument lists exist as a separate, verified unit because of a Windows process creation rule
 * that silently destroyed every Docker query the launcher made. `ProcessBuilder` does not pass an
 * argument vector to `CreateProcess`: it joins the arguments into one command line and, whenever an
 * argument contains a space, wraps it in double quotes **without escaping the double quotes already
 * inside it**. A Go template written as `{{.Label "com.docker.compose.service"}}` therefore reaches
 * docker.exe as `{{.Label com.docker.compose.service}}` and every call fails with
 * `failed to parse template: function "com" not defined` and exit code 1 — while the very same command
 * typed into a shell works, because the shell quotes it correctly.
 *
 * Go templates accept raw strings in backquotes, which mean exactly the same thing and contain no
 * double quote at all. Every argument produced here is verified to be free of double quotes so the
 * defect cannot come back through a new query.
 */
internal object DockerCliQuery {
    const val INSTANCE_LABEL = "com.hh-clicker.instance-id"
    const val PROFILE_LABEL = "com.hh-clicker.profile"
    const val COMPOSE_SERVICE_LABEL = "com.docker.compose.service"

    /** Separates the fields of [publishedContainer]; a container name can never contain it. */
    const val FIELD_SEPARATOR = '|'

    /**
     * Lists the Compose service label of every running container labelled with this exact instance id.
     *
     * The filter is what keeps a foreign container out: another profile, another installation or an
     * unrelated project can never enter the launcher's runtime picture through this query.
     */
    fun runningServices(docker: String, instanceId: String): List<String> = verified(
        listOf(
            docker,
            "ps",
            "--filter",
            "label=$INSTANCE_LABEL=$instanceId",
            "--format",
            label(COMPOSE_SERVICE_LABEL),
        ),
    )

    /**
     * Asks Docker itself which container publishes a host port.
     *
     * The human readable `.Ports` column is never parsed; the published-port filter is Docker's own.
     */
    fun publishedContainer(docker: String, port: Int): List<String> = verified(
        listOf(
            docker,
            "ps",
            "--filter",
            "publish=$port",
            "--format",
            listOf("{{.Names}}", label(INSTANCE_LABEL), label(PROFILE_LABEL)).joinToString(FIELD_SEPARATOR.toString()),
        ),
    )

    private fun label(name: String): String = "{{.Label `" + name + "`}}"

    private fun verified(arguments: List<String>): List<String> {
        require(arguments.none { it.contains('"') }) {
            "Аргумент docker не должен содержать двойных кавычек: Windows искажает их при запуске процесса"
        }
        return arguments
    }
}
