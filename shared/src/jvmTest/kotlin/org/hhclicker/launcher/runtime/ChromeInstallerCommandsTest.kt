package org.hhclicker.launcher.runtime

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChromeInstallerCommandsTest {
    @Test
    fun installer_url_is_the_official_google_https_msi() {
        assertEquals(
            "https://dl.google.com/dl/chrome/install/googlechromestandaloneenterprise64.msi",
            ChromeInstallerCommands.INSTALLER_URL,
        )
        assertTrue(ChromeInstallerCommands.INSTALLER_URL.startsWith("https://"))
    }

    @Test
    fun elevation_wrapper_is_static_and_paths_are_passed_only_in_the_child_environment() {
        val msiexec = Path.of("C:\\Windows\\System32\\msiexec.exe")
        val installer = Path.of("C:\\cache folder\\Google Chrome.msi")

        val arguments = ChromeInstallerCommands.elevatedArguments()
        val environment = ChromeInstallerCommands.elevatedEnvironment(msiexec, installer)

        assertEquals("powershell.exe", arguments[0])
        assertEquals("-Command", arguments[3])
        assertEquals(5, arguments.size)
        assertTrue(arguments[4].contains("-Verb RunAs"))
        assertTrue(arguments[4].contains("'/qn'"))
        assertTrue(arguments[4].contains("'/norestart'"))
        assertFalse(arguments.any { it.contains(installer.toString()) })
        assertFalse(arguments.any { it.contains(msiexec.toString()) })
        assertEquals(msiexec.toAbsolutePath().toString(), environment[ChromeInstallerCommands.MSIEXEC_VARIABLE])
        assertEquals(installer.toAbsolutePath().toString(), environment[ChromeInstallerCommands.INSTALLER_VARIABLE])
        assertFalse(arguments.any { it.contains("ALLUSERS", ignoreCase = true) })
    }

    @Test
    fun system_msiexec_is_resolved_without_using_path_when_windows_directory_is_known() {
        val resolved = ChromeInstallerCommands.msiexecPath { name ->
            if (name == "SYSTEMROOT") "C:\\Windows" else null
        }
        assertEquals(Path.of("C:\\Windows", "System32", "msiexec.exe"), resolved)
    }

    @Test
    fun missing_windows_directory_never_falls_back_to_path() {
        assertNull(ChromeInstallerCommands.msiexecPath { null })
    }

    @Test
    fun operation_is_bounded() {
        assertTrue(ChromeInstallerCommands.INSTALL_TIMEOUT_MS > 0)
        assertTrue(ChromeInstallerCommands.MAX_INSTALLER_BYTES in 1..(1024L * 1024 * 1024))
    }

    @Test
    fun elevated_exit_marker_is_parsed_without_confusing_wrapper_exit_code() {
        assertEquals(0, ChromeInstallerCommands.reportedExitCode("${ChromeInstallerCommands.EXIT_CODE_MARKER}0"))
        assertEquals(3010, ChromeInstallerCommands.reportedExitCode("before\n${ChromeInstallerCommands.EXIT_CODE_MARKER}3010\nafter"))
        assertEquals(null, ChromeInstallerCommands.reportedExitCode(ChromeInstallerCommands.ELEVATION_DECLINED_MARKER))
    }
}
