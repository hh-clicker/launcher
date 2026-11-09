package org.hhclicker.launcher.preflight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresHealthProbeTest {
    @Test
    fun health_is_scoped_to_owned_postgres_and_does_not_confuse_unhealthy_with_healthy() {
        for ((output, expected) in listOf(
            "Up 2 minutes (healthy)" to PostgresHealth.HEALTHY,
            "Up 2 minutes (unhealthy)" to PostgresHealth.UNHEALTHY,
            "Up 2 seconds (health: starting)" to PostgresHealth.STARTING,
            "Up 2 minutes" to PostgresHealth.UNKNOWN,
            "" to PostgresHealth.UNKNOWN,
            "Up (healthy)\nUp (healthy)" to PostgresHealth.UNKNOWN,
        )) {
            val probe = PostgresHealthProbe(object : CommandProbe() {
                override fun execute(arguments: List<String>): CommandProbeResult {
                    assertEquals("test-docker", arguments.first())
                    assertTrue(arguments.contains("label=com.hh-clicker.instance-id=test-instance"))
                    assertTrue(arguments.contains("label=com.docker.compose.service=postgres"))
                    assertFalse(arguments.any { it.contains('"') })
                    return CommandProbeResult(true, 0, output)
                }
            }, { "test-docker" })
            assertEquals(expected, probe.probe("test-instance"))
        }
    }

    @Test
    fun failed_docker_query_does_not_report_database_as_healthy() {
        val probe = PostgresHealthProbe(object : CommandProbe() {
            override fun execute(arguments: List<String>) = CommandProbeResult(true, 1, "Up (healthy)")
        }, { "docker" })
        assertEquals(PostgresHealth.UNKNOWN, probe.probe("test-instance"))
    }
}
