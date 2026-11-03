package org.hhclicker.launcher.preflight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the defect that made every Docker observation fail on Windows.
 *
 * `ProcessBuilder` joins arguments into a single command line and wraps any argument containing a space
 * in double quotes without escaping the double quotes inside it, so `{{.Label "x"}}` arrived at docker.exe
 * as `{{.Label x}}` and the query died with `failed to parse template: function "com" not defined`. The
 * same command typed into a shell worked, which is what made the defect look intermittent.
 */
class DockerCliQueryTest {

    @Test
    fun no_docker_argument_carries_a_double_quote() {
        val queries = listOf(
            DockerCliQuery.runningServices("docker", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
            DockerCliQuery.publishedContainer("docker", 80),
        )

        queries.forEach { arguments ->
            arguments.forEach { argument ->
                assertFalse(
                    argument.contains('"'),
                    "Windows corrupts a double quote inside an argument: $argument",
                )
            }
        }
    }

    @Test
    fun the_ownership_query_filters_by_this_exact_instance_and_reads_only_the_compose_service_label() {
        val arguments = DockerCliQuery.runningServices(
            "C:\\Users\\me\\AppData\\Local\\Programs\\DockerDesktop\\resources\\bin\\docker.exe",
            "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        )

        assertEquals(
            listOf(
                "C:\\Users\\me\\AppData\\Local\\Programs\\DockerDesktop\\resources\\bin\\docker.exe",
                "ps",
                "--filter",
                "label=com.hh-clicker.instance-id=aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                "--format",
                "{{.Label `com.docker.compose.service`}}",
            ),
            arguments,
        )
    }

    @Test
    fun the_published_port_query_asks_docker_and_never_parses_the_ports_column() {
        val arguments = DockerCliQuery.publishedContainer("docker", 17374)

        assertEquals(
            listOf(
                "docker",
                "ps",
                "--filter",
                "publish=17374",
                "--format",
                "{{.Names}}|{{.Label `com.hh-clicker.instance-id`}}|{{.Label `com.hh-clicker.profile`}}",
            ),
            arguments,
        )
        assertTrue(arguments.none { it.contains(".Ports") })
    }

    @Test
    fun every_query_is_read_only() {
        val mutating = setOf("rm", "stop", "kill", "run", "exec", "create", "start", "restart", "prune")
        listOf(
            DockerCliQuery.runningServices("docker", "id"),
            DockerCliQuery.publishedContainer("docker", 80),
        ).forEach { arguments ->
            assertEquals("ps", arguments[1])
            assertTrue(arguments.drop(1).none { it in mutating }, arguments.toString())
        }
    }
}
