package org.hhclicker.launcher.runtime

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Compose is the executing half of the launcher, and it used to disagree with the diagnosing half about
 * which `docker` it meant. These tests pin the executable down for both `up` and `down`.
 */
class DockerComposeCommandsTest {
    private val docker = "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe"
    private val environmentFile = Path.of("config", "infra.env")

    @Test
    fun compose_up_runs_the_resolved_docker_cli() {
        val arguments = DockerComposeCommands.up(docker, "hh-clicker", environmentFile)

        assertEquals(docker, arguments.first())
        assertEquals("compose", arguments[1])
        assertEquals(listOf("--project-name", "hh-clicker"), arguments.subList(2, 4))
        assertEquals("--env-file", arguments[4])
        assertEquals(environmentFile.toAbsolutePath().normalize().toString(), arguments[5])
        assertEquals(listOf("up", "-d", "--build"), arguments.drop(6))
        assertFalse(arguments.drop(1).contains("docker"), arguments.toString())
    }

    @Test
    fun compose_up_can_reuse_an_existing_image_without_rebuilding() {
        val arguments = DockerComposeCommands.up(docker, "hh-clicker", environmentFile, build = false)

        assertEquals(listOf("up", "-d"), arguments.drop(6))
    }

    @Test
    fun compose_down_runs_the_resolved_docker_cli() {
        val arguments = DockerComposeCommands.down(docker, "hh-clicker-generation", environmentFile)

        assertEquals(docker, arguments.first())
        assertEquals(listOf("compose", "--project-name", "hh-clicker-generation"), arguments.subList(1, 4))
        assertEquals("down", arguments.last())
    }

    @Test
    fun linux_compose_override_is_used_for_both_start_and_stop() {
        val files = listOf(Path.of("compose.yaml"), Path.of("compose.linux.yaml"))
        val expected = files.flatMap { listOf("-f", it.toAbsolutePath().normalize().toString()) }

        val up = DockerComposeCommands.up(docker, "hh-clicker", environmentFile, build = false, composeFiles = files)
        val down = DockerComposeCommands.down(docker, "hh-clicker", environmentFile, composeFiles = files)

        assertEquals(expected, up.subList(6, 10))
        assertEquals(expected, down.subList(6, 10))
        assertEquals(listOf("up", "-d"), up.drop(10))
        assertEquals(listOf("down"), down.drop(10))
    }

    @Test
    fun an_unusable_executable_or_project_name_is_refused_instead_of_guessed() {
        assertFailsWith<IllegalArgumentException> {
            DockerComposeCommands.up("  ", "hh-clicker", environmentFile)
        }
        assertFailsWith<IllegalArgumentException> {
            DockerComposeCommands.down(docker, "HH Clicker", environmentFile)
        }
    }
}
