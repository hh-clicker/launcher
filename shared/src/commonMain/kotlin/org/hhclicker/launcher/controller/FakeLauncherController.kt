package org.hhclicker.launcher.controller

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.model.ComponentStatus
import org.hhclicker.launcher.model.CodexAuthState
import org.hhclicker.launcher.model.CodexAuthStatus
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.LauncherStatus
import org.hhclicker.launcher.setup.ChromePreparationOutcome
import org.hhclicker.launcher.setup.ChromePreparationResult
import org.hhclicker.launcher.setup.ChromeSetupPlanner
import org.hhclicker.launcher.setup.DockerPreparationOutcome
import org.hhclicker.launcher.setup.DockerPreparationResult
import org.hhclicker.launcher.setup.DockerSetupPlanner
import org.hhclicker.launcher.setup.WslPreparationOutcome
import org.hhclicker.launcher.setup.WslPreparationResult
import org.hhclicker.launcher.setup.WslSetupPlanner
import org.hhclicker.launcher.setup.NodePreparationOutcome
import org.hhclicker.launcher.setup.NodePreparationResult
import org.hhclicker.launcher.setup.NodeSetupPlanner

class FakeLauncherController(
    initialState: LauncherState = LauncherState.stopped().copy(preflight = org.hhclicker.launcher.model.PreflightState.ready),
    private val transitionDelayMs: Long = 350,
) : LauncherController {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(initialState)

    override val state: StateFlow<LauncherState> = mutableState.asStateFlow()

    override suspend fun refreshEnvironment() = Unit

    override suspend fun refreshRuntimeState() = Unit

    override suspend fun selectFreePorts() = Unit

    /**
     * Preview implementation: it walks the same preparation states as the desktop controller but never
     * touches Windows, WSL or any elevated command.
     */
    override suspend fun prepareWsl() = operation.withLock {
        val action = WslSetupPlanner.decide(mutableState.value.preflight.wsl)
        if (!action.executable) {
            mutableState.value = mutableState.value.copy(setup = WslSetupPlanner.refusalState(action))
            return@withLock
        }
        mutableState.value = mutableState.value.copy(setup = WslSetupPlanner.preparingState(action))
        delay(transitionDelayMs)
        mutableState.value = mutableState.value.copy(
            setup = WslSetupPlanner.stateFor(WslPreparationResult(WslPreparationOutcome.COMPONENTS_CHANGED_RESTART_REQUIRED)),
        )
    }

    /**
     * Preview implementation: it walks the same Docker preparation states as the desktop controller but
     * never downloads, verifies, installs or starts anything, and never accepts the Docker agreement.
     */
    override suspend fun prepareDocker(acceptDockerLicense: Boolean) = operation.withLock {
        val action = DockerSetupPlanner.decide(
            wsl = mutableState.value.preflight.wsl,
            docker = mutableState.value.preflight.docker,
        )
        if (!action.executable) {
            mutableState.value = mutableState.value.copy(setup = DockerSetupPlanner.refusalState(action))
            return@withLock
        }
        if (action.requiresLicenseAcceptance && !acceptDockerLicense) {
            mutableState.value = mutableState.value.copy(
                setup = DockerSetupPlanner.stateFor(
                    DockerPreparationResult(DockerPreparationOutcome.LICENSE_NOT_ACCEPTED),
                ),
            )
            return@withLock
        }
        mutableState.value = mutableState.value.copy(setup = DockerSetupPlanner.preparingState(action))
        delay(transitionDelayMs)
        mutableState.value = mutableState.value.copy(
            setup = DockerSetupPlanner.stateFor(
                DockerPreparationResult(DockerPreparationOutcome.ALREADY_READY),
            ),
        )
    }

    override suspend fun prepareNode() = operation.withLock {
        val action = NodeSetupPlanner.decide(mutableState.value.preflight.node)
        if (action != org.hhclicker.launcher.setup.NodeSetupAction.DOWNLOAD_PORTABLE) return@withLock
        mutableState.value = mutableState.value.copy(setup = NodeSetupPlanner.preparingState())
        delay(transitionDelayMs)
        mutableState.value = mutableState.value.copy(
            setup = NodeSetupPlanner.stateFor(NodePreparationResult(NodePreparationOutcome.INSTALLED_AND_READY)),
        )
    }

    /**
     * Preview implementation: it walks the same Chrome preparation states as the desktop controller but
     * never downloads, verifies or installs anything, and never accepts the Google terms.
     */
    override suspend fun prepareChrome(acceptGoogleTerms: Boolean) = operation.withLock {
        val action = ChromeSetupPlanner.decide(mutableState.value.preflight.chrome)
        if (!action.executable) {
            mutableState.value = mutableState.value.copy(setup = ChromeSetupPlanner.refusalState(action))
            return@withLock
        }
        if (action.requiresTermsAcceptance && !acceptGoogleTerms) {
            mutableState.value = mutableState.value.copy(
                setup = ChromeSetupPlanner.stateFor(
                    ChromePreparationResult(ChromePreparationOutcome.TERMS_NOT_ACCEPTED),
                ),
            )
            return@withLock
        }
        mutableState.value = mutableState.value.copy(setup = ChromeSetupPlanner.preparingState(action))
        delay(transitionDelayMs)
        mutableState.value = mutableState.value.copy(
            setup = ChromeSetupPlanner.stateFor(
                ChromePreparationResult(ChromePreparationOutcome.ALREADY_INSTALLED),
            ),
        )
    }

    override suspend fun refreshCodexAuth() = operation.withLock {
        val running = mutableState.value.components.any {
            it.id == ComponentId.GENERATION_AGENT && it.status == ComponentStatus.RUNNING
        }
        mutableState.value = mutableState.value.copy(
            codexAuth = if (running) {
                CodexAuthState(CodexAuthStatus.SIGNED_OUT, message = "Требуется вход через ChatGPT")
            } else {
                CodexAuthState(CodexAuthStatus.UNAVAILABLE, message = "Сначала запустите Generation Agent")
            },
        )
    }

    override suspend fun startCodexLogin() = operation.withLock {
        mutableState.value = mutableState.value.copy(
            codexAuth = CodexAuthState(
                CodexAuthStatus.AUTHORIZING,
                verificationUrl = "https://auth.openai.com/codex/device",
                userCode = "ABCD-1234",
            ),
        )
    }

    override suspend fun cancelCodexLogin() = operation.withLock {
        mutableState.value = mutableState.value.copy(
            codexAuth = CodexAuthState(CodexAuthStatus.SIGNED_OUT, message = "Требуется вход через ChatGPT"),
        )
    }

    override suspend fun logoutCodex() = operation.withLock {
        mutableState.value = mutableState.value.copy(
            codexAuth = CodexAuthState(CodexAuthStatus.SIGNED_OUT, message = "Вы вышли из Codex"),
        )
    }

    override suspend fun startAll() = operation.withLock {
        if (mutableState.value.status == LauncherStatus.RUNNING) return@withLock

        mutableState.value = mutableState.value.copy(status = LauncherStatus.STARTING, error = null)
        launchOrder.forEach { componentId ->
            updateComponent(componentId, ComponentStatus.STARTING)
            delay(transitionDelayMs)
            updateComponent(componentId, ComponentStatus.RUNNING)
        }
        mutableState.value = mutableState.value.copy(status = LauncherStatus.RUNNING)
    }

    override suspend fun stopAll() = operation.withLock {
        if (mutableState.value.status == LauncherStatus.STOPPED) return@withLock

        mutableState.value = mutableState.value.copy(status = LauncherStatus.STOPPING, error = null)
        launchOrder.asReversed().forEach { componentId ->
            updateComponent(componentId, ComponentStatus.STOPPING)
            delay(transitionDelayMs / 2)
            updateComponent(componentId, ComponentStatus.STOPPED)
        }
        mutableState.value = mutableState.value.copy(
            status = LauncherStatus.STOPPED,
            codexAuth = CodexAuthState(CodexAuthStatus.UNAVAILABLE, message = "Сначала запустите Generation Agent"),
        )
    }

    private fun updateComponent(id: ComponentId, status: ComponentStatus) {
        mutableState.value = mutableState.value.copy(
            components = mutableState.value.components.map { component ->
                if (component.id == id) component.copy(status = status, details = null) else component
            },
        )
    }

    private companion object {
        val launchOrder = listOf(
            ComponentId.POSTGRES,
            ComponentId.BACKEND,
            ComponentId.FRONTEND,
            ComponentId.GENERATION_AGENT,
            ComponentId.BROWSER_AGENT,
        )
    }
}
