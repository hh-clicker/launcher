package org.hhclicker.launcher.preflight

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hhclicker.launcher.model.HostPlatform

class ChromeInstallationLocatorTest {
    @Test
    fun fixed_machine_and_user_locations_are_searched_in_order_without_path() {
        val root = createTempDirectory("hh-chrome-locations")
        val programFiles = root.resolve("Program Files")
        val programFilesX86 = root.resolve("Program Files x86")
        val localAppData = root.resolve("Local AppData")
        val environment = mapOf(
            "PROGRAMFILES" to programFiles.toString(),
            "PROGRAMFILES(X86)" to programFilesX86.toString(),
            "LOCALAPPDATA" to localAppData.toString(),
            "PATH" to root.resolve("untrusted-path").toString(),
        )
        val locator = ChromeInstallationLocator(environment::get, HostPlatform.WINDOWS)
        val relative = "Google/Chrome/Application/chrome.exe"

        assertEquals(
            listOf(
                programFiles.resolve(relative),
                programFilesX86.resolve(relative),
                localAppData.resolve(relative),
            ),
            locator.candidates(),
        )

        val perUserChrome = localAppData.resolve(relative)
        Files.createDirectories(perUserChrome.parent)
        Files.write(perUserChrome, byteArrayOf(1))
        assertEquals(perUserChrome, locator.locate())
    }

    @Test
    fun absent_or_invalid_environment_locations_produce_no_candidate() {
        val locator = ChromeInstallationLocator(environment = { null }, platform = HostPlatform.WINDOWS)
        assertEquals(emptyList(), locator.candidates())
        assertNull(locator.locate())
    }

    @Test
    fun linux_uses_known_google_chrome_locations() {
        val locator = ChromeInstallationLocator(environment = { null }, platform = HostPlatform.LINUX)
        assertEquals(
            listOf(
                java.nio.file.Path.of("/opt/google/chrome/chrome"),
                java.nio.file.Path.of("/usr/bin/google-chrome"),
                java.nio.file.Path.of("/usr/bin/google-chrome-stable"),
            ),
            locator.candidates(),
        )
    }
}
