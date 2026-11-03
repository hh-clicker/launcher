package org.hhclicker.launcher.setup

import org.hhclicker.launcher.model.VirtualizationState
import org.hhclicker.launcher.model.WslDefaultVersionState
import org.hhclicker.launcher.model.WslInstallationState

/** Installation state together with the version string that proved it. */
data class WslInstallation(
    val state: WslInstallationState,
    val version: String? = null,
)

/**
 * Turns raw command output into the independent WSL facts.
 *
 * The rules are intentionally strict: only a parsed version number from `wsl --version` is accepted as
 * proof that the modern WSL package is present. Any other output is either "not installed" (the command
 * itself reported a failure, which is what a clean Windows does) or "check failed" (the launcher could
 * not run or could not understand the command). Neither is silently treated as success.
 */
object WslDiagnosticsParser {
    /** Minimal WSL package version supported by the launcher. */
    val minimumVersion: List<Int> = listOf(2, 1, 5)

    val minimumVersionText: String = minimumVersion.joinToString(".")

    fun virtualization(outcome: CommandOutcome): VirtualizationState {
        if (!outcome.started || outcome.timedOut || outcome.exitCode != 0) return VirtualizationState.UNKNOWN
        return when (lastMeaningfulLine(outcome.output)?.lowercase()) {
            "enabled" -> VirtualizationState.ENABLED
            "disabled" -> VirtualizationState.DISABLED_IN_FIRMWARE
            else -> VirtualizationState.UNKNOWN
        }
    }

    fun installation(outcome: CommandOutcome): WslInstallation {
        if (!outcome.started) return WslInstallation(WslInstallationState.CHECK_FAILED)
        if (outcome.timedOut) return WslInstallation(WslInstallationState.CHECK_FAILED)
        // A non-zero exit code is what `wsl --version` returns when the modern WSL package is missing,
        // including on Windows builds that only carry the legacy inbox component.
        if (outcome.exitCode != 0) return WslInstallation(WslInstallationState.NOT_INSTALLED)

        val version = parseVersion(outcome.output) ?: return WslInstallation(WslInstallationState.CHECK_FAILED)
        val state = if (isSupportedVersion(version)) WslInstallationState.COMPATIBLE else WslInstallationState.OUTDATED
        return WslInstallation(state, version)
    }

    fun defaultVersion(outcome: CommandOutcome): WslDefaultVersionState {
        if (!outcome.started || outcome.timedOut || outcome.exitCode != 0) return WslDefaultVersionState.UNKNOWN
        val raw = defaultVersionPattern.find(sanitize(outcome.output))?.groupValues?.get(1) ?: return WslDefaultVersionState.UNKNOWN
        return when (raw.toIntOrNull(16)) {
            1 -> WslDefaultVersionState.VERSION_1
            2 -> WslDefaultVersionState.VERSION_2
            else -> WslDefaultVersionState.UNKNOWN
        }
    }

    /**
     * `wsl --version` always prints the WSL package version on its first line. Only that line is
     * inspected, otherwise the kernel or WSLg version could be mistaken for the package version.
     */
    fun parseVersion(output: String): String? {
        val firstLine = sanitize(output).lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) ?: return null
        return versionPattern.find(firstLine)?.value
    }

    fun isSupportedVersion(version: String): Boolean {
        val parts = version.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size < minimumVersion.size) return false
        minimumVersion.forEachIndexed { index, required ->
            val actual = parts[index]
            if (actual > required) return true
            if (actual < required) return false
        }
        return true
    }

    private fun sanitize(output: String): String = output.replace("\u0000", "")

    private fun lastMeaningfulLine(output: String): String? =
        sanitize(output).lineSequence().map(String::trim).lastOrNull(String::isNotEmpty)

    private val versionPattern = Regex("""\d+\.\d+\.\d+(?:\.\d+)?""")

    private val defaultVersionPattern = Regex("""DefaultVersion\s+REG_DWORD\s+0x([0-9a-fA-F]+)""", RegexOption.IGNORE_CASE)
}
