package org.hhclicker.launcher.model

/**
 * Hardware virtualization state. This is a manual blocker only: the launcher never tries to change
 * BIOS/UEFI settings, it can only report them.
 */
enum class VirtualizationState {
    ENABLED,
    DISABLED_IN_FIRMWARE,
    UNKNOWN,
}

/** Installation state of the modern (Store/MSI) WSL package. */
enum class WslInstallationState {
    NOT_INSTALLED,
    OUTDATED,
    COMPATIBLE,
    CHECK_FAILED,
}

/** Default distribution version configured for WSL. WSL 1 cannot host the Docker backend. */
enum class WslDefaultVersionState {
    VERSION_2,
    VERSION_1,
    UNKNOWN,
}

/**
 * Independent facts about the WSL 2 prerequisite. Each field is decided by its own probe so that a
 * failed probe never masquerades as a negative answer.
 */
data class WslDiagnostics(
    val virtualization: VirtualizationState = VirtualizationState.UNKNOWN,
    val installation: WslInstallationState = WslInstallationState.CHECK_FAILED,
    val defaultVersion: WslDefaultVersionState = WslDefaultVersionState.UNKNOWN,
    val installedVersion: String? = null,
) {
    val ready: Boolean
        get() = installation == WslInstallationState.COMPATIBLE && defaultVersion == WslDefaultVersionState.VERSION_2
}
