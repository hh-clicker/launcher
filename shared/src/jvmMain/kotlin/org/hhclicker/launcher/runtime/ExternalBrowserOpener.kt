package org.hhclicker.launcher.runtime

import java.awt.Desktop
import java.net.URI
import org.hhclicker.launcher.model.HostPlatform

/** Opens launcher links with the host's default browser. */
object ExternalBrowserOpener {
    fun open(url: String, onFailure: (String) -> Unit) {
        runCatching {
            val uri = URI(url)
            require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true))
            require(!uri.host.isNullOrBlank())

            val command = commandFor(HostPlatformDetector.detect(), uri)
            if (command == null) {
                val desktop = Desktop.getDesktop()
                check(desktop.isSupported(Desktop.Action.BROWSE))
                desktop.browse(uri)
            } else {
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .onExit()
                    .thenAccept { process ->
                        if (process.exitValue() != 0) onFailure(url)
                    }
            }
        }.onFailure { onFailure(url) }
    }

    internal fun commandFor(platform: HostPlatform, uri: URI): List<String>? = when (platform) {
        HostPlatform.LINUX -> listOf("xdg-open", uri.toString())
        HostPlatform.MACOS -> listOf("open", uri.toString())
        HostPlatform.WINDOWS, HostPlatform.OTHER -> null
    }
}
