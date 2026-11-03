package org.hhclicker.launcher.controller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.model.ChromeDiagnostics
import org.hhclicker.launcher.model.ChromeInstallationState
import org.hhclicker.launcher.model.HostArchitecture
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.PreflightState
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupTarget

class FakeLauncherControllerChromeTest {
    private fun controller(installation: ChromeInstallationState) = FakeLauncherController(
        initialState = LauncherState.stopped().copy(
            preflight = PreflightState(
                status = PreflightStatus.BLOCKED,
                chrome = ChromeDiagnostics(installation, HostArchitecture.X64_SUPPORTED),
            ),
        ),
        transitionDelayMs = 0,
    )

    @Test
    fun installation_requires_its_own_explicit_google_terms_click() = runBlocking {
        val controller = controller(ChromeInstallationState.NOT_INSTALLED)

        controller.prepareChrome(acceptGoogleTerms = false)

        assertEquals(SetupTarget.CHROME, controller.state.value.setup.target)
        assertEquals(SetupStatus.FAILED, controller.state.value.setup.status)
        assertTrue(controller.state.value.setup.message.orEmpty().contains("Google"))
    }

    @Test
    fun accepted_preview_flow_reaches_a_terminal_chrome_state() = runBlocking {
        val controller = controller(ChromeInstallationState.NOT_INSTALLED)

        controller.prepareChrome(acceptGoogleTerms = true)

        assertEquals(SetupTarget.CHROME, controller.state.value.setup.target)
        assertEquals(SetupStatus.SUCCEEDED, controller.state.value.setup.status)
    }

    @Test
    fun an_existing_chrome_never_enters_a_preparation_flow() = runBlocking {
        val controller = controller(ChromeInstallationState.INSTALLED)

        controller.prepareChrome(acceptGoogleTerms = true)

        assertEquals(SetupTarget.CHROME, controller.state.value.setup.target)
        assertEquals(SetupStatus.FAILED, controller.state.value.setup.status)
        assertTrue(controller.state.value.setup.message.orEmpty().contains("установлен"))
    }
}
