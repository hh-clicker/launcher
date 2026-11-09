package org.hhclicker.launcher.runtime

import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

/**
 * Starts a process without waiting for it.
 *
 * Docker Desktop is a long lived application with its own window: the launcher must start it and return
 * immediately instead of blocking on it. [CommandRunner] always waits, so this is a separate, deliberately
 * tiny contract rather than an option on it.
 */
fun interface ProcessLauncher {
    /** Returns true when the process was started. The launcher never waits for or owns it afterwards. */
    fun launch(arguments: List<String>, workingDirectory: Path): Boolean
}

class SystemProcessLauncher : ProcessLauncher {
    override fun launch(arguments: List<String>, workingDirectory: Path): Boolean {
        require(arguments.isNotEmpty()) { "Command arguments must not be empty" }
        return runCatching {
            ProcessBuilder(arguments)
                .directory(workingDirectory.toFile())
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .also { WindowsJavaDllIsolation.cleanChildEnvironment(it.environment()) }
                .start()
            true
        }.getOrDefault(false)
    }
}
