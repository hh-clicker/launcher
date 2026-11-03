package org.hhclicker.launcher.preflight

import java.nio.file.Path
import org.hhclicker.launcher.model.ChromeDiagnostics
import org.hhclicker.launcher.model.ChromeInstallationState
import org.hhclicker.launcher.setup.DockerDiagnosticsParser

/**
 * Read-only probe for the Google Chrome prerequisite.
 *
 * It changes nothing, starts no browser and never reads a Chrome profile. A failure of the search
 * itself answers [ChromeInstallationState.CHECK_FAILED] rather than "not installed", so an unreadable
 * machine can never trigger an installation over an existing Chrome.
 */
class ChromeProbe(
    private val locator: ChromeInstallationLocator = ChromeInstallationLocator(),
    private val environment: (String) -> String? = System::getenv,
) {

    /** Finds chrome.exe again, for example right after an installation finished. */
    fun locateExecutable(): Path? = runCatching { locator.locate() }.getOrNull()

    fun probe(): ChromeDiagnostics {
        // Reused from the Docker diagnostics: an x64 process emulated on ARM64 reports AMD64 in
        // PROCESSOR_ARCHITECTURE, so PROCESSOR_ARCHITEW6432 has to be read first.
        val architecture = DockerDiagnosticsParser.architecture(
            wow64Architecture = environment("PROCESSOR_ARCHITEW6432"),
            processorArchitecture = environment("PROCESSOR_ARCHITECTURE"),
            jvmArchitecture = System.getProperty("os.arch"),
        )
        val located = runCatching { locator.locate() }
        val installation = when {
            located.isFailure -> ChromeInstallationState.CHECK_FAILED
            located.getOrNull() != null -> ChromeInstallationState.INSTALLED
            else -> ChromeInstallationState.NOT_INSTALLED
        }
        return ChromeDiagnostics(installation = installation, architecture = architecture)
    }
}
