package org.hhclicker.launcher.controller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.model.ContainerMode
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.DockerCliState
import org.hhclicker.launcher.model.DockerComposeState
import org.hhclicker.launcher.model.DockerDesktopProcessState
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.DockerDiagnostics
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.HostArchitecture
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.PreflightState
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupTarget
import org.hhclicker.launcher.model.VirtualizationState
import org.hhclicker.launcher.model.WslDefaultVersionState
import org.hhclicker.launcher.model.WslDiagnostics
import org.hhclicker.launcher.model.WslInstallationState

/**
 * The preview controller must satisfy the extended contract without performing a single system action.
 */
class FakeLauncherControllerDockerTest {

    private val readyWsl = WslDiagnostics(
        virtualization = VirtualizationState.ENABLED,
        installation = WslInstallationState.COMPATIBLE,
        defaultVersion = WslDefaultVersionState.VERSION_2,
        installedVersion = "2.3.24.0",
    )

    private fun controllerWith(docker: DockerDiagnostics?, wsl: WslDiagnostics? = readyWsl) = FakeLauncherController(
        initialState = LauncherState.stopped().copy(
            preflight = PreflightState(status = PreflightStatus.BLOCKED, wsl = wsl, docker = docker),
        ),
        transitionDelayMs = 0,
    )

    @Test
    fun a_startable_docker_walks_through_preparing_to_a_terminal_state() = runBlocking {
        val controller = controllerWith(
            DockerDiagnostics(
                architecture = HostArchitecture.X64_SUPPORTED,
                desktop = DockerDesktopState.INSTALLED,
                desktopVersion = "4.45.0",
                cli = DockerCliState.AVAILABLE,
                compose = DockerComposeState.AVAILABLE,
                desktopProcess = DockerDesktopProcessState.STOPPED,
                engine = DockerEngineState.UNAVAILABLE,
                containerMode = ContainerMode.UNKNOWN,
                backend = DockerBackend.UNKNOWN,
            ),
        )

        controller.prepareDocker()

        val setup = controller.state.value.setup
        assertEquals(SetupTarget.DOCKER, setup.target)
        assertEquals(SetupStatus.SUCCEEDED, setup.status)
    }

    @Test
    fun an_installation_without_an_explicit_acceptance_is_refused() = runBlocking {
        val controller = controllerWith(
            DockerDiagnostics(
                architecture = HostArchitecture.X64_SUPPORTED,
                desktop = DockerDesktopState.NOT_INSTALLED,
                cli = DockerCliState.MISSING,
                compose = DockerComposeState.MISSING,
                desktopProcess = DockerDesktopProcessState.STOPPED,
                engine = DockerEngineState.UNAVAILABLE,
            ),
        )

        controller.prepareDocker(acceptDockerLicense = false)

        val setup = controller.state.value.setup
        assertEquals(SetupStatus.FAILED, setup.status)
        assertEquals(SetupTarget.DOCKER, setup.target)
        assertTrue(setup.message.orEmpty().contains("соглашение"), setup.message.orEmpty())
    }

    @Test
    fun docker_preparation_is_refused_while_wsl_is_not_ready() = runBlocking {
        val controller = controllerWith(
            docker = DockerDiagnostics(
                architecture = HostArchitecture.X64_SUPPORTED,
                desktop = DockerDesktopState.NOT_INSTALLED,
                cli = DockerCliState.MISSING,
            ),
            wsl = readyWsl.copy(installation = WslInstallationState.NOT_INSTALLED),
        )

        controller.prepareDocker(acceptDockerLicense = true)

        val setup = controller.state.value.setup
        assertEquals(SetupStatus.FAILED, setup.status)
        assertTrue(setup.message.orEmpty().contains("WSL 2"), setup.message.orEmpty())
    }

    @Test
    fun a_ready_docker_offers_nothing_to_do() = runBlocking {
        val controller = controllerWith(
            DockerDiagnostics(
                architecture = HostArchitecture.X64_SUPPORTED,
                desktop = DockerDesktopState.INSTALLED,
                desktopVersion = "4.45.0",
                cli = DockerCliState.AVAILABLE,
                compose = DockerComposeState.AVAILABLE,
                desktopProcess = DockerDesktopProcessState.RUNNING,
                engine = DockerEngineState.RUNNING,
                containerMode = ContainerMode.LINUX,
                backend = DockerBackend.WSL_2,
            ),
        )

        controller.prepareDocker(acceptDockerLicense = true)

        assertEquals(SetupStatus.FAILED, controller.state.value.setup.status)
        assertTrue(controller.state.value.setup.message.orEmpty().contains("не требуется"))
    }
}
