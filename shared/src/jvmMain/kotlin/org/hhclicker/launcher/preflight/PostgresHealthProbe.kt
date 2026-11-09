package org.hhclicker.launcher.preflight

enum class PostgresHealth { HEALTHY, STARTING, UNHEALTHY, UNKNOWN }

/** Reads Docker's pg_isready healthcheck for this installation only. No credentials or SQL are needed. */
class PostgresHealthProbe(
    private val commandProbe: CommandProbe = CommandProbe(timeoutMs = 3_000),
    private val dockerExecutable: () -> String = DockerCliResolver()::executable,
) {
    fun probe(instanceId: String): PostgresHealth = runCatching {
        val result = commandProbe.execute(listOf(
            dockerExecutable(), "ps", "--filter", "label=${DockerCliQuery.INSTANCE_LABEL}=$instanceId",
            "--filter", "label=${DockerCliQuery.COMPOSE_SERVICE_LABEL}=postgres", "--format", "{{.Status}}",
        ))
        if (!result.successful) return@runCatching PostgresHealth.UNKNOWN
        val rows = result.output.lineSequence().filter(String::isNotBlank).toList()
        if (rows.size != 1) return@runCatching PostgresHealth.UNKNOWN
        when {
            rows.single().endsWith("(healthy)") -> PostgresHealth.HEALTHY
            rows.single().endsWith("(unhealthy)") -> PostgresHealth.UNHEALTHY
            rows.single().endsWith("(health: starting)") -> PostgresHealth.STARTING
            else -> PostgresHealth.UNKNOWN
        }
    }.getOrDefault(PostgresHealth.UNKNOWN)
}
