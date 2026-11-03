package org.hhclicker.launcher.preflight

import org.hhclicker.launcher.model.WslDiagnostics
import org.hhclicker.launcher.setup.CommandOutcome
import org.hhclicker.launcher.setup.WslDiagnosticsParser

/**
 * Read-only probe for the WSL 2 prerequisite.
 *
 * It reuses [CommandProbe] instead of starting processes on its own and only runs fixed argument lists.
 * Nothing here changes the machine.
 */
class WslProbe(private val commandProbe: CommandProbe = CommandProbe()) {

    fun probe(): WslDiagnostics {
        val installation = WslDiagnosticsParser.installation(run(WSL_VERSION_COMMAND))
        return WslDiagnostics(
            virtualization = WslDiagnosticsParser.virtualization(run(VIRTUALIZATION_COMMAND)),
            installation = installation.state,
            defaultVersion = WslDiagnosticsParser.defaultVersion(run(DEFAULT_VERSION_COMMAND)),
            installedVersion = installation.version,
        )
    }

    private fun run(arguments: List<String>): CommandOutcome {
        val result = commandProbe.execute(arguments)
        return CommandOutcome(
            started = result.started,
            exitCode = result.exitCode,
            output = result.output,
            timedOut = result.timedOut,
        )
    }

    private companion object {
        /**
         * Reports `enabled`, `disabled` or `unknown`. A running hypervisor is accepted as proof, because
         * Windows reports `VirtualizationFirmwareEnabled` as false once Hyper-V owns the CPU.
         */
        val VIRTUALIZATION_COMMAND = listOf(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            """
            ${'$'}system = Get-CimInstance Win32_ComputerSystem
            ${'$'}cpus = @(Get-CimInstance Win32_Processor)
            if (${'$'}system.HypervisorPresent) { 'enabled' }
            elseif (${'$'}cpus.Count -eq 0) { 'unknown' }
            elseif (@(${'$'}cpus | Where-Object { ${'$'}_.VirtualizationFirmwareEnabled -eq ${'$'}false }).Count -gt 0) { 'disabled' }
            elseif (@(${'$'}cpus | Where-Object { ${'$'}_.VirtualizationFirmwareEnabled -eq ${'$'}true -and ${'$'}_.SecondLevelAddressTranslationExtensions -eq ${'$'}true }).Count -eq ${'$'}cpus.Count) { 'enabled' }
            else { 'unknown' }
            """.trimIndent(),
        )

        val WSL_VERSION_COMMAND = listOf("wsl.exe", "--version")

        /**
         * `wsl --status` is fully localized, the registry value is not. The key is absent until WSL writes
         * a default version, which is reported as an unknown default rather than as WSL 1.
         */
        val DEFAULT_VERSION_COMMAND = listOf(
            "reg.exe",
            "query",
            """HKCU\Software\Microsoft\Windows\CurrentVersion\Lxss""",
            "/v",
            "DefaultVersion",
        )
    }
}
