package org.hhclicker.launcher.runtime

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
) {
    val successful: Boolean
        get() = !timedOut && exitCode == 0
}

/** Open so that preparation logic can be unit tested against a substitute that starts no process. */
open class CommandRunner {
    /**
     * Starts a process from an argument list. A shell command string is never accepted.
     *
     * [environment] adds variables to the child process only. It exists so that a file path can reach a
     * helper without ever being concatenated into script text; the values are passed through and are
     * never written to the technical report.
     */
    open suspend fun execute(
        arguments: List<String>,
        workingDirectory: Path,
        timeoutMs: Long,
        environment: Map<String, String> = emptyMap(),
    ): CommandResult = withContext(Dispatchers.IO) {
        require(arguments.isNotEmpty()) { "Command arguments must not be empty" }

        val process = try {
            ProcessBuilder(arguments)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .also { builder -> builder.environment().putAll(environment) }
                .start()
        } catch (exception: Exception) {
            return@withContext CommandResult(null, exception.message ?: "Не удалось запустить команду")
        }

        val output = StringBuilder()
        val reader = thread(name = "launcher-command-output", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                        if (output.length > MAX_OUTPUT_CHARACTERS) {
                            output.delete(0, output.length - MAX_OUTPUT_CHARACTERS)
                        }
                    }
                }
            }
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(1_000)

        CommandResult(
            exitCode = if (finished) process.exitValue() else null,
            output = output.toString().trim(),
            timedOut = !finished,
        )
    }

    private companion object {
        const val MAX_OUTPUT_CHARACTERS = 8_000
    }
}
