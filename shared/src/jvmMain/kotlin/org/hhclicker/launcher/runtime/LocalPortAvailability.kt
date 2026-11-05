package org.hhclicker.launcher.runtime

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.preflight.CommandProbe
import org.hhclicker.launcher.preflight.WindowsPortOwnerProbe

internal object LocalPortAvailability {
    fun isAvailable(port: Int): Boolean {
        val platform = HostPlatformDetector.detect()
        if (platform == HostPlatform.LINUX) {
            // A bind can fail for a privileged port or a recently closed connection even when no
            // process listens on it. Docker needs the latter fact, not a test bind by the GUI user.
            return runCatching {
                val tables = listOf(Path.of("/proc/net/tcp"), Path.of("/proc/net/tcp6"))
                    .map(Files::readString)
                !linuxListeningPortPresent(port, tables)
            }.getOrDefault(false)
        }
        if (platform == HostPlatform.MACOS) {
            // Docker Desktop can publish port 80 through its privileged helper even though an ordinary
            // launcher process cannot bind that port. Ask whether anything is actually listening.
            val result = CommandProbe().execute(
                listOf("/usr/sbin/lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN"),
            )
            return result.started && result.exitCode == 1 && !result.timedOut
        }
        val bindSucceeded = runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(port))
            }
            true
        }.getOrDefault(false)
        if (bindSucceeded) return true

        // On Windows a socket can still be in TIME_WAIT after Compose exits. Only an active
        // listener means the port is occupied; an unsuccessful netstat query stays conservative.
        return platform == HostPlatform.WINDOWS && WindowsPortOwnerProbe().isAvailable(port) == true
    }

    internal fun linuxListeningPortPresent(port: Int, tables: List<String>): Boolean {
        val expected = port.toString(16).padStart(4, '0')
        return tables.any { table ->
            table.lineSequence().drop(1).any { line ->
                val columns = line.trim().split(Regex("\\s+"))
                columns.size >= 4 && columns[3] == "0A" &&
                    columns[1].substringAfterLast(':').equals(expected, ignoreCase = true)
            }
        }
    }
}
