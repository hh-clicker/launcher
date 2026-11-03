package org.hhclicker.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.hhclicker.launcher.controller.FakeLauncherController
import org.hhclicker.launcher.controller.LauncherController
import org.hhclicker.launcher.design.LauncherTheme
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.model.CodexAuthStatus
import org.hhclicker.launcher.model.LauncherStatus
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.ui.LauncherScreen
import org.hhclicker.launcher.ui.LauncherPage

private const val RUNTIME_REFRESH_INTERVAL_MS = 4_000L

@Composable
@Preview
fun App() {
    LauncherTheme {
        LauncherContent()
    }
}

@Composable
fun LauncherContent(
    controller: LauncherController = remember { FakeLauncherController() },
    /** Opening a browser is a platform capability; the desktop entry point supplies it. */
    onOpenUrl: (String) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(LauncherPage.MAIN) }

    LaunchedEffect(controller) {
        controller.refreshEnvironment()
        if (controller.state.value.preflight.status == PreflightStatus.BLOCKED) {
            page = LauncherPage.SETTINGS
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            delay(RUNTIME_REFRESH_INTERVAL_MS)
            try {
                controller.refreshRuntimeState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Runtime observation is best-effort. One unexpected probe failure must not kill
                // the long-lived polling coroutine; the next tick retries from a clean snapshot.
            }
        }
    }

    // Codex authorization polls on its own timeline and shares nothing with the runtime monitor above: a
    // failing authentication request must not cancel this effect, and it can never pause container
    // observation, which runs in its own coroutine and holds no lock while it waits for Docker.
    LaunchedEffect(state.codexAuth.status) {
        while (controller.state.value.codexAuth.status == CodexAuthStatus.AUTHORIZING) {
            delay(1_500)
            try {
                controller.refreshCodexAuth()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The next tick retries; one failed request is not a reason to abandon the login.
            }
        }
    }

    LauncherScreen(
        state = state,
        page = page,
        onNavigate = {
            page = it
        },
        onPrimaryAction = {
            if (state.status == LauncherStatus.RUNNING) {
                state.components
                    .firstOrNull { it.id == ComponentId.FRONTEND }
                    ?.location
                    ?.let(onOpenUrl)
            } else {
                scope.launch {
                    when {
                        state.preflight.status != PreflightStatus.READY -> controller.refreshEnvironment()
                        state.status == LauncherStatus.FAILED -> controller.stopAll()
                        else -> {
                            controller.startAll()
                            val current = controller.state.value
                            if (current.status == LauncherStatus.RUNNING && current.codexAuth.status == CodexAuthStatus.SIGNED_OUT) {
                                page = LauncherPage.SETTINGS
                            }
                        }
                    }
                }
            }
        },
        onStopAction = { scope.launch { controller.stopAll() } },
        onSelectFreePorts = { scope.launch { controller.selectFreePorts() } },
        onRefreshEnvironment = {
            scope.launch {
                controller.refreshEnvironment()
                if (controller.state.value.preflight.status == PreflightStatus.READY) {
                    page = LauncherPage.MAIN
                }
            }
        },
        // Preparation always keeps the user on the setup screen: its result, including a required
        // restart, is the answer they are waiting for.
        onPrepareWsl = { scope.launch { controller.prepareWsl() } },
        // The click on the install button is the explicit acceptance of the Docker agreement; the flag
        // reaches the controller only from that button and never from startup or diagnostics.
        onPrepareDocker = { acceptLicense -> scope.launch { controller.prepareDocker(acceptLicense) } },
        onPrepareNode = { scope.launch { controller.prepareNode() } },
        // Same contract for Chrome: the flag is set by the button that states Google terms are accepted,
        // and an acceptance given for Docker is never reused here.
        onPrepareChrome = { acceptTerms -> scope.launch { controller.prepareChrome(acceptTerms) } },
        onRefreshCodexAuth = { scope.launch { controller.refreshCodexAuth() } },
        onStartCodexLogin = {
            scope.launch {
                controller.startCodexLogin()
                controller.state.value.codexAuth.verificationUrl?.let(onOpenUrl)
            }
        },
        onCancelCodexLogin = { scope.launch { controller.cancelCodexLogin() } },
        onLogoutCodex = { scope.launch { controller.logoutCodex() } },
        onOpenUrl = onOpenUrl,
    )
}
