package org.hhclicker.launcher.preflight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.HostPlatform

class LinuxDockerProbeTest {
    @Test
    fun uses_linux_docker_cli_without_querying_windows_installation() {
        val commands = mutableListOf<List<String>>()
        val probe = object : CommandProbe() {
            override fun execute(arguments: List<String>): CommandProbeResult {
                commands += arguments
                val output = when (arguments.drop(1)) {
                    listOf("--version") -> "Docker version 29.9.3, build test"
                    listOf("compose", "version") -> "Docker Compose version v5.7.0"
                    else -> "linux|29.9.3"
                }
                return CommandProbeResult(started = true, exitCode = 0, output = output)
            }
        }
        val docker = DockerProbe(
            commandProbe = probe,
            engineProbe = probe,
            environment = { null },
            platform = HostPlatform.LINUX,
        ).probe()

        assertEquals(DockerBackend.NATIVE_LINUX, docker.backend)
        assertTrue(docker.ready)
        assertEquals(
            listOf(
                listOf("docker", "--version"),
                listOf("docker", "compose", "version"),
                DockerProbe.INFO_COMMAND,
            ),
            commands,
        )
    }
}
