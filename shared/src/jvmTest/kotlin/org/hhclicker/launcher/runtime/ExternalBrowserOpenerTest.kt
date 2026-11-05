package org.hhclicker.launcher.runtime

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hhclicker.launcher.model.HostPlatform

class ExternalBrowserOpenerTest {
    private val url = URI("https://auth.openai.com/codex/device")

    @Test
    fun linux_uses_the_system_default_browser_opener() {
        assertEquals(listOf("xdg-open", url.toString()), ExternalBrowserOpener.commandFor(HostPlatform.LINUX, url))
    }

    @Test
    fun mac_uses_the_system_default_browser_opener() {
        assertEquals(listOf("open", url.toString()), ExternalBrowserOpener.commandFor(HostPlatform.MACOS, url))
    }

    @Test
    fun windows_keeps_the_existing_desktop_browser_integration() {
        assertNull(ExternalBrowserOpener.commandFor(HostPlatform.WINDOWS, url))
    }
}
