package org.hhclicker.launcher.runtime

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.HostPlatform

class LinuxDesktopIntegrationTest {
    @Test
    fun adds_menu_entry_and_replaces_the_appimage_without_changing_user_data() {
        val root = createTempDirectory("hh-desktop-integration")
        val home = root.resolve("home")
        val dataHome = root.resolve("xdg data")
        Files.createDirectories(home)
        val source = root.resolve("download.AppImage")
        Files.writeString(source, "first version")
        val environment = mapOf(
            "APPIMAGE" to source.toString(),
            "HOME" to home.toString(),
            "XDG_DATA_HOME" to dataHome.toString(),
        )
        val integration = LinuxDesktopIntegration(environment::get, { HostPlatform.LINUX }, { byteArrayOf(1, 2, 3) })
        val application = dataHome.resolve("hh-clicker/app/HH-Clicker.AppImage")
        val desktop = dataHome.resolve("applications/org.hhclicker.Launcher.desktop")
        val icon = dataHome.resolve("icons/hicolor/512x512/apps/hh-clicker.png")
        val userData = dataHome.resolve("hh-clicker/data/config/production/settings")
        Files.createDirectories(userData.parent)
        Files.writeString(userData, "keep")

        assertFalse(integration.status()!!.installed)
        integration.install()
        assertTrue(integration.status()!!.installed)
        assertEquals("first version", Files.readString(application))
        assertTrue(Files.isExecutable(application))
        assertContains(Files.readString(desktop), "Exec=\"${application}\"")
        assertContains(Files.readString(desktop), "Icon=hh-clicker")
        assertTrue(Files.readAllBytes(icon).contentEquals(byteArrayOf(1, 2, 3)))

        Files.writeString(source, "second version")
        integration.install()
        assertEquals("second version", Files.readString(application))
        assertEquals("keep", Files.readString(userData))
    }

    @Test
    fun integration_is_hidden_outside_an_appimage_on_linux_and_on_windows() {
        val environment = mapOf("APPIMAGE" to "/tmp/launcher.AppImage")
        assertNull(LinuxDesktopIntegration(emptyMap<String, String>()::get, { HostPlatform.LINUX }).status())
        assertNull(LinuxDesktopIntegration(environment::get, { HostPlatform.WINDOWS }).status())
    }
}
