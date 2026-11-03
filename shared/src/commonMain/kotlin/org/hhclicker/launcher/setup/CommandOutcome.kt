package org.hhclicker.launcher.setup

/**
 * Platform neutral description of a finished console command.
 *
 * The JVM probes map their own result types onto this class so that every classification rule stays
 * pure and unit testable without executing a single system command.
 */
data class CommandOutcome(
    val started: Boolean,
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
) {
    companion object {
        fun failedToStart(): CommandOutcome = CommandOutcome(started = false, exitCode = null, output = "")
    }
}
