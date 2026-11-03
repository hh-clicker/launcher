package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.VirtualizationState
import org.hhclicker.launcher.model.WslDefaultVersionState
import org.hhclicker.launcher.model.WslInstallationState

class WslDiagnosticsParserTest {

    private fun succeeded(output: String) = CommandOutcome(started = true, exitCode = 0, output = output)

    @Test
    fun compatible_version_is_accepted() {
        val outcome = succeeded(
            """
            WSL version: 2.3.24.0
            Kernel version: 5.15.153.1-2
            WSLg version: 1.0.61
            """.trimIndent(),
        )
        val installation = WslDiagnosticsParser.installation(outcome)
        assertEquals(WslInstallationState.COMPATIBLE, installation.state)
        assertEquals("2.3.24.0", installation.version)
    }

    @Test
    fun localized_first_line_is_still_parsed() {
        val installation = WslDiagnosticsParser.installation(succeeded("Версия WSL: 2.1.5.0\nВерсия ядра: 5.15.146.1-2"))
        assertEquals(WslInstallationState.COMPATIBLE, installation.state)
        assertEquals("2.1.5.0", installation.version)
    }

    @Test
    fun utf16_padding_does_not_break_parsing() {
        val installation = WslDiagnosticsParser.installation(succeeded("W\u0000S\u0000L\u0000 version: 2.4.0.0"))
        assertEquals(WslInstallationState.COMPATIBLE, installation.state)
    }

    @Test
    fun older_package_is_outdated() {
        val installation = WslDiagnosticsParser.installation(succeeded("WSL version: 2.0.9.0"))
        assertEquals(WslInstallationState.OUTDATED, installation.state)
        assertEquals("2.0.9.0", installation.version)
    }

    @Test
    fun only_the_first_line_is_considered_the_package_version() {
        // The kernel version alone must never be accepted as proof of a modern WSL package.
        val installation = WslDiagnosticsParser.installation(succeeded("wsl: unknown option\nKernel version: 5.15.153.1-2"))
        assertEquals(WslInstallationState.CHECK_FAILED, installation.state)
        assertNull(installation.version)
    }

    @Test
    fun non_zero_exit_code_means_the_package_is_missing() {
        val outcome = CommandOutcome(started = true, exitCode = 1, output = "Подсистема Windows для Linux не установлена")
        assertEquals(WslInstallationState.NOT_INSTALLED, WslDiagnosticsParser.installation(outcome).state)
    }

    @Test
    fun unreachable_or_hanging_command_is_a_failed_check() {
        assertEquals(
            WslInstallationState.CHECK_FAILED,
            WslDiagnosticsParser.installation(CommandOutcome.failedToStart()).state,
        )
        assertEquals(
            WslInstallationState.CHECK_FAILED,
            WslDiagnosticsParser.installation(CommandOutcome(started = true, exitCode = null, output = "", timedOut = true)).state,
        )
    }

    @Test
    fun minimum_version_boundary() {
        assertTrue(WslDiagnosticsParser.isSupportedVersion("2.1.5"))
        assertTrue(WslDiagnosticsParser.isSupportedVersion("2.1.6.0"))
        assertTrue(WslDiagnosticsParser.isSupportedVersion("2.2.0.0"))
        assertTrue(WslDiagnosticsParser.isSupportedVersion("3.0.0.0"))
        assertFalse(WslDiagnosticsParser.isSupportedVersion("2.1.4.0"))
        assertFalse(WslDiagnosticsParser.isSupportedVersion("2.0.14.0"))
        assertFalse(WslDiagnosticsParser.isSupportedVersion("1.9.9.9"))
        assertFalse(WslDiagnosticsParser.isSupportedVersion("2.1"))
        assertFalse(WslDiagnosticsParser.isSupportedVersion("not.a.version"))
    }

    @Test
    fun virtualization_states() {
        assertEquals(VirtualizationState.ENABLED, WslDiagnosticsParser.virtualization(succeeded("enabled")))
        assertEquals(VirtualizationState.DISABLED_IN_FIRMWARE, WslDiagnosticsParser.virtualization(succeeded("disabled")))
        assertEquals(VirtualizationState.UNKNOWN, WslDiagnosticsParser.virtualization(succeeded("unknown")))
        assertEquals(VirtualizationState.UNKNOWN, WslDiagnosticsParser.virtualization(CommandOutcome.failedToStart()))
        assertEquals(
            VirtualizationState.UNKNOWN,
            WslDiagnosticsParser.virtualization(CommandOutcome(started = true, exitCode = 1, output = "enabled")),
        )
    }

    @Test
    fun default_version_is_read_from_the_registry_value() {
        val two = succeeded(
            """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Lxss
                DefaultVersion    REG_DWORD    0x2
            """.trimIndent(),
        )
        val one = succeeded("    DefaultVersion    REG_DWORD    0x1")
        assertEquals(WslDefaultVersionState.VERSION_2, WslDiagnosticsParser.defaultVersion(two))
        assertEquals(WslDefaultVersionState.VERSION_1, WslDiagnosticsParser.defaultVersion(one))
    }

    @Test
    fun missing_registry_value_is_unknown_not_wsl1() {
        val missing = CommandOutcome(started = true, exitCode = 1, output = "ERROR: The system was unable to find the specified registry key or value.")
        assertEquals(WslDefaultVersionState.UNKNOWN, WslDiagnosticsParser.defaultVersion(missing))
        assertEquals(WslDefaultVersionState.UNKNOWN, WslDiagnosticsParser.defaultVersion(succeeded("")))
    }
}
