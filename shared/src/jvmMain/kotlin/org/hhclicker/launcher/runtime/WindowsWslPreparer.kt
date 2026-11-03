package org.hhclicker.launcher.runtime

import org.hhclicker.launcher.setup.WslPreparationOutcome
import org.hhclicker.launcher.setup.WslPreparationResult
import org.hhclicker.launcher.setup.WslSetupAction

/** Markers printed by the elevation wrapper. They keep a cancelled UAC prompt apart from a failed command. */
internal const val ELEVATION_DECLINED_MARKER = "HHCLICKER_WSL_ELEVATION_DECLINED"
internal const val ELEVATION_FAILED_MARKER = "HHCLICKER_WSL_ELEVATION_FAILED"
internal const val EXIT_CODE_MARKER = "HHCLICKER_WSL_EXIT="

/** Windows reports a dismissed UAC prompt as ERROR_CANCELLED. */
internal const val ERROR_CANCELLED = 1223

/** Enabling Windows components downloads a package, so the elevated call gets a generous bound. */
internal const val COMPONENT_CHANGE_TIMEOUT_MS = 900_000L

/** Writing the default version is a local operation. */
internal const val DEFAULT_VERSION_TIMEOUT_MS = 120_000L

/**
 * The only component allowed to change WSL on this machine.
 *
 * It runs one of three predefined `wsl.exe` invocations through a static PowerShell elevation wrapper,
 * waits for the elevated process and reports a precise outcome. It never installs a Linux distribution,
 * never composes a command from user input or environment values, never logs environment variables and
 * never restarts Windows.
 */
class WindowsWslPreparer(
    private val powerShell: PowerShellExecutor = SystemPowerShellExecutor(),
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
) {
    /**
     * Full technical result of the last attempt. It stays inside this component: the screen only shows the
     * short message derived from the returned outcome.
     */
    @Volatile
    var lastTechnicalReport: String? = null
        private set

    suspend fun prepare(action: WslSetupAction): WslPreparationResult {
        val command = ElevatedWslCommand.forAction(action)
        if (command == null) {
            lastTechnicalReport = "action=$action has no elevated command"
            return WslPreparationResult(WslPreparationOutcome.NOT_APPLICABLE)
        }
        if (!windows) {
            lastTechnicalReport = "action=$action skipped: elevated WSL preparation requires Windows"
            return WslPreparationResult(WslPreparationOutcome.NOT_APPLICABLE)
        }

        val result = powerShell.execute(command.script, command.timeoutMs)
        val preparation = interpret(command, result)
        lastTechnicalReport = buildString {
            appendLine("command=wsl.exe ${command.arguments.joinToString(" ")}")
            appendLine("timeoutMs=${command.timeoutMs}")
            appendLine("wrapperExitCode=${result.exitCode ?: "none"}")
            appendLine("timedOut=${result.timedOut}")
            appendLine("outcome=${preparation.outcome}")
            appendLine("elevatedExitCode=${preparation.exitCode ?: "none"}")
            appendLine("output:")
            append(result.output)
        }
        return preparation
    }

    internal fun interpret(command: ElevatedWslCommand, result: CommandResult): WslPreparationResult {
        if (result.timedOut) return WslPreparationResult(WslPreparationOutcome.TIMED_OUT)
        if (result.exitCode == null) return WslPreparationResult(WslPreparationOutcome.LAUNCH_FAILED)

        val output = result.output
        if (output.contains(ELEVATION_DECLINED_MARKER)) {
            return WslPreparationResult(WslPreparationOutcome.ELEVATION_DECLINED)
        }
        if (output.contains(ELEVATION_FAILED_MARKER)) {
            return WslPreparationResult(WslPreparationOutcome.ELEVATION_FAILED)
        }

        val markerIndex = output.indexOf(EXIT_CODE_MARKER)
        if (markerIndex < 0) return WslPreparationResult(WslPreparationOutcome.LAUNCH_FAILED)

        val reported = output.substring(markerIndex + EXIT_CODE_MARKER.length)
            .takeWhile { it == '-' || it.isDigit() }
            .toIntOrNull()
        return if (reported == 0) {
            WslPreparationResult(command.successOutcome)
        } else {
            WslPreparationResult(WslPreparationOutcome.COMMAND_FAILED, reported)
        }
    }
}

/**
 * The closed set of elevated operations the launcher may perform.
 *
 * `--no-distribution` keeps Ubuntu and every other Linux distribution out of scope, and `--web-download`
 * avoids depending on the Microsoft Store being available on the machine.
 */
internal enum class ElevatedWslCommand(
    val arguments: List<String>,
    val timeoutMs: Long,
    val successOutcome: WslPreparationOutcome,
) {
    INSTALL(
        arguments = listOf("--install", "--no-distribution", "--web-download"),
        timeoutMs = COMPONENT_CHANGE_TIMEOUT_MS,
        successOutcome = WslPreparationOutcome.COMPONENTS_CHANGED_RESTART_REQUIRED,
    ),
    UPDATE(
        arguments = listOf("--update", "--web-download"),
        timeoutMs = COMPONENT_CHANGE_TIMEOUT_MS,
        successOutcome = WslPreparationOutcome.COMPONENTS_CHANGED_RESTART_REQUIRED,
    ),
    SET_DEFAULT_VERSION(
        arguments = listOf("--set-default-version", "2"),
        timeoutMs = DEFAULT_VERSION_TIMEOUT_MS,
        successOutcome = WslPreparationOutcome.COMPLETED,
    ),
    ;

    val script: String get() = elevationScript(arguments)

    companion object {
        fun forAction(action: WslSetupAction): ElevatedWslCommand? = when (action) {
            WslSetupAction.INSTALL -> INSTALL
            WslSetupAction.UPDATE -> UPDATE
            WslSetupAction.SET_DEFAULT_VERSION -> SET_DEFAULT_VERSION
            WslSetupAction.NONE,
            WslSetupAction.BLOCKED_BY_FIRMWARE,
            WslSetupAction.DIAGNOSTICS_FAILED,
            -> null
        }
    }
}

/**
 * Builds the elevation wrapper.
 *
 * [arguments] always comes from the compile time entries of [ElevatedWslCommand]; no path, environment
 * value or user input is ever interpolated here. The wrapper itself always exits with 0 and reports the
 * real result through markers, so an unreachable PowerShell, a dismissed UAC prompt and a failed WSL
 * command stay distinguishable.
 */
internal fun elevationScript(arguments: List<String>): String {
    val argumentList = arguments.joinToString(",") { "'$it'" }
    return "${'$'}ErrorActionPreference = 'Stop'; " +
        "${'$'}native = 0; " +
        "try { ${'$'}process = Start-Process -FilePath 'wsl.exe' -ArgumentList $argumentList -Verb RunAs -Wait -PassThru } " +
        "catch { " +
        "${'$'}failure = ${'$'}_.Exception; " +
        "if (${'$'}failure -is [System.ComponentModel.Win32Exception]) { ${'$'}native = ${'$'}failure.NativeErrorCode } " +
        "elseif (${'$'}failure.InnerException -is [System.ComponentModel.Win32Exception]) { ${'$'}native = ${'$'}failure.InnerException.NativeErrorCode }; " +
        "if (${'$'}native -eq $ERROR_CANCELLED) { Write-Output '$ELEVATION_DECLINED_MARKER' } " +
        "else { Write-Output '$ELEVATION_FAILED_MARKER' }; " +
        "exit 0 }; " +
        "Write-Output ('$EXIT_CODE_MARKER' + ${'$'}process.ExitCode); " +
        "exit 0"
}
