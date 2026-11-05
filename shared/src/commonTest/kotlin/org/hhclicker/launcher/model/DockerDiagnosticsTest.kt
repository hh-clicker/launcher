package org.hhclicker.launcher.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerDiagnosticsTest {
    @Test
    fun native_linux_engine_is_ready_without_docker_desktop_or_wsl() {
        val docker = DockerDiagnostics(
            cli = DockerCliState.AVAILABLE,
            compose = DockerComposeState.AVAILABLE,
            engine = DockerEngineState.RUNNING,
            containerMode = ContainerMode.LINUX,
            backend = DockerBackend.NATIVE_LINUX,
        )
        assertTrue(docker.ready)
        assertFalse(docker.copy(containerMode = ContainerMode.WINDOWS).ready)
        assertFalse(docker.copy(backend = DockerBackend.UNKNOWN).ready)
    }
}
