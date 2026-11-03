package org.hhclicker.launcher.controller

import kotlinx.coroutines.flow.StateFlow
import org.hhclicker.launcher.model.LauncherState

interface LauncherController {
    val state: StateFlow<LauncherState>

    suspend fun refreshEnvironment()

    /** Refreshes live service state without repeating the full machine preflight. */
    suspend fun refreshRuntimeState()

    /** Replaces only currently conflicting configured ports with free profile-local alternatives. */
    suspend fun selectFreePorts()

    /**
     * Prepares the WSL 2 prerequisite for the current diagnostics: installs the WSL package without a
     * Linux distribution, updates an outdated package, or selects WSL 2 as the default version.
     *
     * The implementation decides the single action from the last diagnostics; the screen never chooses a
     * command. It must be a no-op when the action is not executable, for example while hardware
     * virtualization is disabled in BIOS/UEFI.
     */
    suspend fun prepareWsl()

    /**
     * Prepares the Docker Desktop prerequisite for the current diagnostics: installs it per user with the
     * WSL 2 backend, installs a newer version over one below the supported minimum, starts an installed
     * Docker Desktop, or waits for an engine that is already coming up.
     *
     * The implementation decides the single action; the screen never chooses a command and never passes a
     * path or a flag. [acceptDockerLicense] must only be true when the user pressed the button that
     * explicitly states the Docker Subscription Service Agreement is being accepted; without it an
     * installation is refused rather than performed silently.
     */
    suspend fun prepareDocker(acceptDockerLicense: Boolean = false)

    /** Downloads an isolated portable Node.js only when no compatible system runtime is available. */
    suspend fun prepareNode()

    /**
     * Installs the system Google Chrome the Browser Agent needs, and nothing else.
     *
     * The implementation decides the single action; the screen never chooses a command and never passes
     * a URL or a path. [acceptGoogleTerms] must only be true when the user pressed the button that
     * explicitly states the Google terms are being accepted — an acceptance given earlier for Docker is
     * never reused here. Without it nothing is downloaded.
     *
     * An existing Chrome is never updated, repaired, reinstalled, closed or started, and the installed
     * browser is never opened afterwards.
     */
    suspend fun prepareChrome(acceptGoogleTerms: Boolean = false)

    suspend fun refreshCodexAuth()

    suspend fun startCodexLogin()

    suspend fun cancelCodexLogin()

    suspend fun logoutCodex()

    suspend fun startAll()

    suspend fun stopAll()
}
