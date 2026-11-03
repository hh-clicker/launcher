package org.hhclicker.launcher.preflight

import java.nio.file.Path

data class LocalPortOwner(val processId: Long, val processName: String?)

/** Read-only lookup of the Windows process listening on a TCP host port. */
class WindowsPortOwnerProbe(
    private val commandProbe: CommandProbe = CommandProbe(maxOutputCharacters = NETSTAT_OUTPUT_LIMIT),
    private val environment: (String) -> String? = System::getenv,
    private val processCommand: (Long) -> String? = ::commandForProcess,
) {
    fun owner(port: Int): LocalPortOwner? {
        val netstat = environment("SystemRoot")
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it, "System32", "netstat.exe").toString() }
            ?: "netstat.exe"
        val result = commandProbe.execute(listOf(netstat, "-ano", "-p", "tcp"))
        if (!result.successful) return null
        val processId = listeningProcessId(result.output, port) ?: return null
        val processName = processCommand(processId)
            ?.let { command -> runCatching { Path.of(command).fileName.toString() }.getOrNull() }
            ?.takeIf(String::isNotBlank)
        return LocalPortOwner(processId, processName)
    }

    internal fun listeningProcessId(output: String, port: Int): Long? = output.lineSequence().firstNotNullOfOrNull { line ->
        val columns = line.trim().split(WHITESPACE)
        if (columns.size < 5 || !columns[0].equals("TCP", ignoreCase = true)) return@firstNotNullOfOrNull null
        val localPort = columns[1].substringAfterLast(':').toIntOrNull()
        val foreignPort = columns[2].substringAfterLast(':').toIntOrNull()
        if (localPort == port && foreignPort == 0) columns.last().toLongOrNull() else null
    }

    private companion object {
        const val NETSTAT_OUTPUT_LIMIT = 128_000
        val WHITESPACE = Regex("\\s+")

        fun commandForProcess(processId: Long): String? = runCatching {
            ProcessHandle.of(processId).orElse(null)?.info()?.command()?.orElse(null)
        }.getOrNull()
    }
}

