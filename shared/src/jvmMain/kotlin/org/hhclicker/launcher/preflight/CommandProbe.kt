package org.hhclicker.launcher.preflight

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class CommandProbeResult(
    val started: Boolean,
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
) {
    val successful: Boolean
        get() = started && !timedOut && exitCode == 0
}

/** Open so path-selection logic can be tested without starting a real process. */
open class CommandProbe(
    private val timeoutMs: Long = 8_000,
    private val maxOutputCharacters: Int = DEFAULT_MAX_OUTPUT_CHARACTERS,
) {
    open fun execute(arguments: List<String>): CommandProbeResult {
        require(arguments.isNotEmpty()) { "Command arguments must not be empty" }

        val process = try {
            ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return CommandProbeResult(started = false, exitCode = null, output = "")
        }

        // The reader thread can still be appending when the join below gives up, so the buffer is only
        // ever touched under its own monitor. A StringBuilder shared unsynchronised between two threads
        // can throw out of the middle of a read, and here that would surface as an exception raised at a
        // caller that only asked a diagnostic question.
        val output = StringBuilder()
        val reader = thread(name = "launcher-command-probe", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < maxOutputCharacters) output.appendLine(line)
                        }
                    }
                }
            }
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
        reader.join(500)

        return CommandProbeResult(
            started = true,
            exitCode = if (finished) process.exitValue() else null,
            output = synchronized(output) { output.toString() }.trim().take(maxOutputCharacters),
            timedOut = !finished,
        )
    }

    private companion object {
        const val DEFAULT_MAX_OUTPUT_CHARACTERS = 2_000
    }
}
