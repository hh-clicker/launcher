package org.hhclicker.launcher.controller

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.model.ComponentStatus
import org.hhclicker.launcher.model.LauncherStatus
import org.hhclicker.launcher.preflight.ServiceEndpoints
import org.hhclicker.launcher.runtime.BrowserAgentProcess
import org.hhclicker.launcher.runtime.WindowsDockerPreparer

/**
 * The Browser Agent is a child process of this launcher and its lifetime has nothing to do with the
 * workspace. `stopAll` used to return before touching it whenever the workspace or the durable
 * configuration could not be resolved, which left a browser automation process running with nobody in
 * control of it.
 */
class StopAllTest {

    private class FakeBrowserAgent(private var running: Boolean = true) : BrowserAgentProcess() {
        var stopCalls = 0
        var failure: String? = null

        override fun isRunning(): Boolean = running

        override suspend fun start(workspace: Path, endpoints: ServiceEndpoints): String? = null

        override suspend fun stop(): String? {
            stopCalls++
            if (failure == null) running = false
            return failure
        }
    }

    /** No installation is performed here, so the preparer gets no cache directory to create. */
    private fun launcher(agent: FakeBrowserAgent) = PreflightLauncherController(
        browserAgent = agent,
        dockerPreparer = WindowsDockerPreparer(cacheDirectory = null),
    )

    @Test
    fun the_browser_agent_is_stopped_even_without_a_workspace_or_configuration() = runBlocking {
        val agent = FakeBrowserAgent()
        val controller = launcher(agent)

        // No refreshEnvironment has run, so neither the workspace nor the configuration is known.
        controller.stopAll()

        assertEquals(1, agent.stopCalls)
        val state = controller.state.value
        assertEquals(
            ComponentStatus.STOPPED,
            state.components.single { it.id == ComponentId.BROWSER_AGENT }.status,
        )
    }

    @Test
    fun compose_components_report_why_they_could_not_be_stopped() = runBlocking {
        val agent = FakeBrowserAgent()
        val controller = launcher(agent)

        controller.stopAll()

        val state = controller.state.value
        assertEquals(LauncherStatus.FAILED, state.status)
        listOf(ComponentId.BACKEND, ComponentId.FRONTEND, ComponentId.GENERATION_AGENT).forEach { id ->
            val component = state.components.single { it.id == id }
            assertEquals(ComponentStatus.FAILED, component.status, "$id")
            // The reason is carried on the component, which is where the screen now shows it.
            assertTrue(component.details.orEmpty().isNotBlank(), "$id без причины")
        }
        assertTrue(state.error.orEmpty().isNotBlank())
    }

    @Test
    fun a_browser_agent_that_refuses_to_stop_is_reported_and_does_not_hide_the_other_failures() = runBlocking {
        val agent = FakeBrowserAgent().apply { failure = "Не удалось завершить Browser Agent" }
        val controller = launcher(agent)

        controller.stopAll()

        val state = controller.state.value
        assertEquals(
            ComponentStatus.FAILED,
            state.components.single { it.id == ComponentId.BROWSER_AGENT }.status,
        )
        assertEquals(
            "Не удалось завершить Browser Agent",
            state.components.single { it.id == ComponentId.BROWSER_AGENT }.details,
        )
        assertTrue(state.error.orEmpty().contains("Browser Agent"), state.error.orEmpty())
    }

    @Test
    fun an_idle_launcher_that_owns_nothing_stops_nothing() = runBlocking {
        val agent = FakeBrowserAgent(running = false)
        val controller = launcher(agent)

        controller.stopAll()

        assertEquals(0, agent.stopCalls)
        assertEquals(LauncherStatus.STOPPED, controller.state.value.status)
    }
}
