package org.hhclicker.launcher.runtime

import java.nio.file.Path

/**
 * Runs one PowerShell script and returns its raw result.
 *
 * The abstraction exists so that preparation logic can be unit tested without executing a system
 * command; production code always uses [SystemPowerShellExecutor].
 */
fun interface PowerShellExecutor {
    suspend fun execute(script: String, timeoutMs: Long): CommandResult
}

/**
 * Default executor. It reuses [CommandRunner] instead of starting processes on its own, passes the script
 * as a single argument (never a composed shell line) and works from the system temp directory, which is
 * the only path involved.
 */
class SystemPowerShellExecutor(
    private val commandRunner: CommandRunner = CommandRunner(),
    private val workingDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
) : PowerShellExecutor {
    override suspend fun execute(script: String, timeoutMs: Long): CommandResult = commandRunner.execute(
        arguments = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script),
        workingDirectory = workingDirectory,
        timeoutMs = timeoutMs,
    )
}
