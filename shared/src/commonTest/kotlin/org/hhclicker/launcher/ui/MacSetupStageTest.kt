package org.hhclicker.launcher.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hhclicker.launcher.model.EnvironmentCheck
import org.hhclicker.launcher.model.DockerDiagnostics
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.PreflightState
import org.hhclicker.launcher.model.SetupTarget
import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.ChromeDiagnostics
import org.hhclicker.launcher.model.ChromeInstallationState
import org.hhclicker.launcher.model.HostArchitecture
import org.hhclicker.launcher.setup.ChromeSetupAction

class MacSetupStageTest {
    @Test
    fun mac_chrome_installation_accepts_arm_but_refuses_unknown_installation_state() {
        val state = LauncherState.stopped().copy(preflight = PreflightState(
            platform = HostPlatform.MACOS,
            chrome = ChromeDiagnostics(installation = ChromeInstallationState.NOT_INSTALLED,
                architecture = HostArchitecture.ARM_UNSUPPORTED),
        ))
        assertEquals(ChromeSetupAction.INSTALL, macChromeSetupAction(state))
        assertEquals(ChromeSetupAction.DIAGNOSTICS_FAILED, macChromeSetupAction(state.copy(
            preflight = state.preflight.copy(chrome = ChromeDiagnostics(installation = ChromeInstallationState.CHECK_FAILED)),
        )))
    }

    @Test
    fun installed_desktop_is_not_offered_for_installation_while_cli_is_still_missing() {
        val state = LauncherState.stopped().copy(preflight = PreflightState(
            platform = HostPlatform.MACOS,
            docker = DockerDiagnostics(desktop = DockerDesktopState.INSTALLED),
            checks = listOf(EnvironmentCheck("docker-cli", "Docker CLI", false, "Требуется")),
        ))

        val presentation = macSetupPresentation(state, SetupTarget.DOCKER, {}) {}

        assertEquals("Открыть Docker", presentation.buttonLabel)
        assertEquals(true, presentation.description.contains("автоматически"))
    }

    @Test
    fun node_can_be_prepared_before_docker_and_chrome() {
        fun state(vararg failed: String) = LauncherState.stopped().copy(
            preflight = PreflightState(
                platform = HostPlatform.MACOS,
                checks = failed.map { EnvironmentCheck(it, it, false, "Требуется") },
            ),
        )

        assertEquals(SetupTarget.NODE, resolveMacSetupStage(state("docker-cli", "node", "chrome")))
        assertEquals(SetupTarget.DOCKER, resolveMacSetupStage(state("docker-cli", "chrome")))
        assertEquals(SetupTarget.CHROME, resolveMacSetupStage(state("chrome")))
    }

    @Test
    fun mac_docker_button_starts_launcher_preparation() {
        val state = LauncherState.stopped().copy(preflight = PreflightState(platform = HostPlatform.MACOS))
        var prepared = false
        val presentation = macSetupPresentation(state, SetupTarget.DOCKER, { error("Unexpected link") }) { prepared = true }

        presentation.onClick()

        assertEquals("Установить", presentation.buttonLabel)
        assertEquals(true, prepared)
    }
    @Test
    fun docker_open_card_disappears_when_background_checks_confirm_readiness() {
        val opening = LauncherState.stopped().copy(
            setup = SetupState(status = SetupStatus.SUCCEEDED, target = SetupTarget.DOCKER),
            preflight = PreflightState(
                platform = HostPlatform.MACOS,
                docker = DockerDiagnostics(
                    desktop = DockerDesktopState.INSTALLED,
                    engine = DockerEngineState.STARTING,
                ),
                checks = listOf(
                    EnvironmentCheck("docker-cli", "Docker CLI", true, "OK"),
                    EnvironmentCheck("docker-compose", "Compose", true, "OK"),
                    EnvironmentCheck("docker-server", "Docker Engine", false, "Starting"),
                ),
            ),
        )

        assertEquals(SetupTarget.DOCKER, resolveMacSetupStage(opening))

        // The monitor updates diagnostics, keeping the result of the explicit Open action.
        val ready = opening.copy(preflight = opening.preflight.copy(
            docker = opening.preflight.docker!!.copy(engine = DockerEngineState.RUNNING),
            checks = opening.preflight.checks.map { it.copy(passed = true, message = "OK") },
        ))

        assertNull(resolveMacSetupStage(ready))
        assertEquals(SetupTarget.CHROME, resolveMacSetupStage(ready.copy(
            preflight = ready.preflight.copy(
                checks = ready.preflight.checks + EnvironmentCheck("chrome", "Chrome", false, "Required"),
            ),
        )))
    }

    @Test
    fun docker_preparation_and_failure_remain_visible_when_checks_are_green() {
        val ready = LauncherState.stopped().copy(preflight = PreflightState(
            platform = HostPlatform.MACOS,
            checks = listOf(EnvironmentCheck("docker-server", "Docker Engine", true, "OK")),
        ))

        for (status in listOf(SetupStatus.PREPARING, SetupStatus.FAILED)) {
            assertEquals(SetupTarget.DOCKER, resolveMacSetupStage(ready.copy(
                setup = SetupState(status = status, target = SetupTarget.DOCKER),
            )))
        }
    }

}
