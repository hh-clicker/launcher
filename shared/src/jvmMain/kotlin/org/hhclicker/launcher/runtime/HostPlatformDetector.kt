package org.hhclicker.launcher.runtime

import org.hhclicker.launcher.model.HostPlatform

object HostPlatformDetector {
    fun detect(osName: String = System.getProperty("os.name").orEmpty()): HostPlatform = when {
        osName.startsWith("Windows", ignoreCase = true) -> HostPlatform.WINDOWS
        osName.startsWith("Linux", ignoreCase = true) -> HostPlatform.LINUX
        osName.startsWith("Mac", ignoreCase = true) -> HostPlatform.MACOS
        else -> HostPlatform.OTHER
    }
}
