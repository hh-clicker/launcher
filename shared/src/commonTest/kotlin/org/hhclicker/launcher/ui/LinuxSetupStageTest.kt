package org.hhclicker.launcher.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hhclicker.launcher.model.EnvironmentCheck
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.PreflightState
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupTarget

class LinuxSetupStageTest {
    @Test
    fun unsupported_linux_distributions_offer_manual_instructions_instead_of_package_actions() {
        val state = LauncherState.stopped().copy(
            preflight = PreflightState(platform = HostPlatform.LINUX, linuxPackageAutoInstallSupported = false),
        )
        var opened: String? = null
        val docker = linuxPackagePresentation(state, SetupTarget.DOCKER, { opened = it }) {
            error("Debian installer must not be offered")
        }
        assertEquals("Инструкция", docker.buttonLabel)
        assertEquals("Установить Docker", docker.title)
        assertNull(opened)
        docker.onClick()
        assertEquals("https://docs.docker.com/engine/install/", opened)

        val chrome = linuxPackagePresentation(state, SetupTarget.CHROME, { opened = it }) {
            error("Debian installer must not be offered")
        }
        chrome.onClick()
        assertEquals("https://support.google.com/chrome/answer/95346", opened)
    }

    @Test
    fun debian_13_keeps_the_explicit_package_action() {
        val state = LauncherState.stopped().copy(
            preflight = PreflightState(platform = HostPlatform.LINUX, linuxPackageAutoInstallSupported = true),
        )
        var prepared = false
        val docker = linuxPackagePresentation(state, SetupTarget.DOCKER, { error("Unexpected documentation link") }) {
            prepared = true
        }
        assertEquals("Установить", docker.buttonLabel)
        docker.onClick()
        assertEquals(true, prepared)
    }

    @Test
    fun remaining_dependencies_can_be_prepared_before_the_single_docker_relogin() {
        val restart = SetupState(status = SetupStatus.RESTART_REQUIRED, target = SetupTarget.DOCKER)
        fun state(vararg failed: String) = LauncherState.stopped().copy(
            setup = restart,
            preflight = PreflightState(
                status = PreflightStatus.BLOCKED,
                platform = HostPlatform.LINUX,
                checks = failed.map { EnvironmentCheck(it, it, false, "Требуется") },
            ),
        )

        assertEquals(SetupTarget.NODE, resolveLinuxSetupStage(state("docker-server", "node", "chrome")))
        assertEquals(SetupTarget.CHROME, resolveLinuxSetupStage(state("docker-server", "chrome")))
        assertEquals(SetupTarget.DOCKER, resolveLinuxSetupStage(state("docker-server")))
    }
}
