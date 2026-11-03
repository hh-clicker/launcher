package org.hhclicker.launcher.runtime

import java.net.InetSocketAddress
import java.net.ServerSocket

internal object LocalPortAvailability {
    fun isAvailable(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(port))
        }
        true
    }.getOrDefault(false)
}
