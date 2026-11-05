package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import org.hhclicker.launcher.model.HostPlatform

class HostPlatformDetectorTest {
    @Test
    fun identifies_supported_hosts_without_confusing_mac_with_linux() {
        assertEquals(HostPlatform.WINDOWS, HostPlatformDetector.detect("Windows 11"))
        assertEquals(HostPlatform.LINUX, HostPlatformDetector.detect("Linux"))
        assertEquals(HostPlatform.MACOS, HostPlatformDetector.detect("Mac OS X"))
        assertEquals(HostPlatform.OTHER, HostPlatformDetector.detect("FreeBSD"))
    }
}
