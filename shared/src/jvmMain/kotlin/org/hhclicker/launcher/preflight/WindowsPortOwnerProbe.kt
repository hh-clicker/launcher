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
        val output = listeningSockets() ?: return null
        val processId = listeningProcessId(output, port) ?: return null
        val processName = processCommand(processId)
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
        return LocalPortOwner(processId, processName)
    }

    /** Null means the query failed; true means a completed query found no listener. */
    fun isAvailable(port: Int): Boolean? = listeningSockets()?.let { listeningProcessId(it, port) == null }

    private fun listeningSockets(): String? {
        val netstat = environment("SystemRoot")
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it, "System32", "netstat.exe").toString() }
            ?: "netstat.exe"
        // Include both IPv4 and IPv6 listeners; `-p tcp` can omit TCPv6 on Windows.
        val result = commandProbe.execute(listOf(netstat, "-ano"))
        if (!result.successful || result.output.length >= NETSTAT_OUTPUT_LIMIT) return null
        return result.output
    }

    internal fun listeningProcessId(output: String, port: Int): Long? = output.lineSequence().firstNotNullOfOrNull { line ->
        val columns = line.trim().split(WHITESPACE)
        if (columns.size < 5 || !columns[0].equals("TCP", ignoreCase = true)) return@firstNotNullOfOrNull null
        val localPort = columns[1].substringAfterLast(':').toIntOrNull()
        val foreignPort = columns[2].substringAfterLast(':').toIntOrNull()
        if (localPort == port && foreignPort == 0 && columns[3].equals("LISTENING", ignoreCase = true)) {
            columns.last().toLongOrNull()
        } else null
    }

    private companion object {
        const val NETSTAT_OUTPUT_LIMIT = 1_000_000
        val WHITESPACE = Regex("\\s+")

        fun commandForProcess(processId: Long): String? = runCatching {
            ProcessHandle.of(processId).orElse(null)?.info()?.command()?.orElse(null)
        }.getOrNull()
    }
}

