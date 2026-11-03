package org.hhclicker.launcher.model

/**
 * Presence of a system Google Chrome.
 *
 * The Browser Agent drives Playwright with `channel: "chrome"`, so a bundled Chromium is not a
 * substitute: only a real Google Chrome installation counts.
 */
enum class ChromeInstallationState {
    NOT_INSTALLED,
    INSTALLED,

    /** The search itself failed. An undetermined state is never treated as "absent" and reinstalled. */
    CHECK_FAILED,
}

/**
 * Independent facts behind the Google Chrome row.
 *
 * The executable path stays out of [LauncherState] on purpose: the screen shows short sentences, never
 * a full path or an environment value.
 */
data class ChromeDiagnostics(
    val installation: ChromeInstallationState = ChromeInstallationState.CHECK_FAILED,
    val architecture: HostArchitecture = HostArchitecture.UNKNOWN,
) {
    val ready: Boolean
        get() = installation == ChromeInstallationState.INSTALLED
}
