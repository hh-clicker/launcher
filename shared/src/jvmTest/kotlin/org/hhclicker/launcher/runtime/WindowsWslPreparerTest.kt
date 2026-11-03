package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.setup.WslPreparationOutcome
import org.hhclicker.launcher.setup.WslSetupAction

/**
 * The preparer is exercised through a substituted executor. No system command is ever started here.
 */
class WindowsWslPreparerTest {

    private class RecordingExecutor(private val result: CommandResult) : PowerShellExecutor {
        var script: String? = null
        var timeoutMs: Long? = null
        var calls: Int = 0

        override suspend fun execute(script: String, timeoutMs: Long): CommandResult {
            calls++
            this.script = script
            this.timeoutMs = timeoutMs
            return result
        }
    }

    private fun wrapperOutput(text: String) = CommandResult(exitCode = 0, output = text)

    private fun prepare(
        action: WslSetupAction,
        result: CommandResult,
        windows: Boolean = true,
    ): Pair<WslPreparationOutcome, RecordingExecutor> {
        val executor = RecordingExecutor(result)
        val preparer = WindowsWslPreparer(powerShell = executor, windows = windows)
        val outcome = runBlocking { preparer.prepare(action) }
        return outcome.outcome to executor
    }

    @Test
    fun install_runs_the_documented_command_without_a_distribution() {
        val (outcome, executor) = prepare(WslSetupAction.INSTALL, wrapperOutput("${EXIT_CODE_MARKER}0"))
        val script = assertNotNull(executor.script)

        assertEquals(WslPreparationOutcome.COMPONENTS_CHANGED_RESTART_REQUIRED, outcome)
        assertTrue(script.contains("'--install','--no-distribution','--web-download'"), script)
        assertTrue(script.contains("-FilePath 'wsl.exe'"), script)
        assertTrue(script.contains("-Verb RunAs"), script)
        assertTrue(script.contains("-Wait"), script)
        assertFalseContains(script, "Ubuntu")
        assertFalseContains(script, "--distribution")
        assertEquals(COMPONENT_CHANGE_TIMEOUT_MS, executor.timeoutMs)
    }

    @Test
    fun update_runs_the_web_download_update() {
        val (outcome, executor) = prepare(WslSetupAction.UPDATE, wrapperOutput("${EXIT_CODE_MARKER}0"))
        assertEquals(WslPreparationOutcome.COMPONENTS_CHANGED_RESTART_REQUIRED, outcome)
        assertTrue(assertNotNull(executor.script).contains("'--update','--web-download'"))
    }

    @Test
    fun setting_the_default_version_completes_without_a_restart() {
        val (outcome, executor) = prepare(WslSetupAction.SET_DEFAULT_VERSION, wrapperOutput("${EXIT_CODE_MARKER}0"))
        assertEquals(WslPreparationOutcome.COMPLETED, outcome)
        assertTrue(assertNotNull(executor.script).contains("'--set-default-version','2'"))
        assertEquals(DEFAULT_VERSION_TIMEOUT_MS, executor.timeoutMs)
    }

    @Test
    fun a_dismissed_uac_prompt_is_not_a_command_failure() {
        val (outcome, _) = prepare(WslSetupAction.INSTALL, wrapperOutput(ELEVATION_DECLINED_MARKER))
        assertEquals(WslPreparationOutcome.ELEVATION_DECLINED, outcome)
    }

    @Test
    fun a_refused_elevation_is_distinguished_from_a_dismissed_prompt() {
        val (outcome, _) = prepare(WslSetupAction.INSTALL, wrapperOutput(ELEVATION_FAILED_MARKER))
        assertEquals(WslPreparationOutcome.ELEVATION_FAILED, outcome)
    }

    @Test
    fun a_non_zero_exit_code_is_reported_with_its_value() {
        val executor = RecordingExecutor(wrapperOutput("${EXIT_CODE_MARKER}4294967295"))
        val preparer = WindowsWslPreparer(powerShell = executor, windows = true)
        val result = runBlocking { preparer.prepare(WslSetupAction.INSTALL) }
        assertEquals(WslPreparationOutcome.COMMAND_FAILED, result.outcome)

        val negative = RecordingExecutor(wrapperOutput("${EXIT_CODE_MARKER}-1"))
        val second = runBlocking { WindowsWslPreparer(powerShell = negative, windows = true).prepare(WslSetupAction.INSTALL) }
        assertEquals(WslPreparationOutcome.COMMAND_FAILED, second.outcome)
        assertEquals(-1, second.exitCode)
    }

    @Test
    fun a_timeout_is_reported_as_a_timeout() {
        val (outcome, _) = prepare(
            WslSetupAction.INSTALL,
            CommandResult(exitCode = null, output = "", timedOut = true),
        )
        assertEquals(WslPreparationOutcome.TIMED_OUT, outcome)
    }

    @Test
    fun an_unusable_helper_is_reported_as_a_launch_failure() {
        val (missingPowerShell, _) = prepare(WslSetupAction.INSTALL, CommandResult(exitCode = null, output = "Cannot run program"))
        assertEquals(WslPreparationOutcome.LAUNCH_FAILED, missingPowerShell)

        val (noMarker, _) = prepare(WslSetupAction.INSTALL, wrapperOutput("unexpected output"))
        assertEquals(WslPreparationOutcome.LAUNCH_FAILED, noMarker)
    }

    @Test
    fun nothing_is_executed_outside_windows() {
        val (outcome, executor) = prepare(WslSetupAction.INSTALL, wrapperOutput("${EXIT_CODE_MARKER}0"), windows = false)
        assertEquals(WslPreparationOutcome.NOT_APPLICABLE, outcome)
        assertEquals(0, executor.calls)
        assertNull(executor.script)
    }

    @Test
    fun actions_the_launcher_must_not_perform_execute_nothing() {
        listOf(WslSetupAction.NONE, WslSetupAction.BLOCKED_BY_FIRMWARE, WslSetupAction.DIAGNOSTICS_FAILED).forEach { action ->
            val (outcome, executor) = prepare(action, wrapperOutput("${EXIT_CODE_MARKER}0"))
            assertEquals(WslPreparationOutcome.NOT_APPLICABLE, outcome, "action $action")
            assertEquals(0, executor.calls, "action $action")
        }
    }

    @Test
    fun the_full_technical_result_stays_inside_the_component() {
        val executor = RecordingExecutor(wrapperOutput("${EXIT_CODE_MARKER}1\nWSL detailed diagnostic text"))
        val preparer = WindowsWslPreparer(powerShell = executor, windows = true)
        runBlocking { preparer.prepare(WslSetupAction.UPDATE) }
        val report = assertNotNull(preparer.lastTechnicalReport)
        assertTrue(report.contains("wsl.exe --update --web-download"), report)
        assertTrue(report.contains("WSL detailed diagnostic text"), report)
    }

    @Test
    fun every_elevated_command_is_bounded_in_time() {
        ElevatedWslCommand.entries.forEach { command ->
            assertTrue(command.timeoutMs > 0, "${command.name} has no timeout")
            assertTrue(command.arguments.none { it.contains("Ubuntu", ignoreCase = true) }, "${command.name} installs a distribution")
        }
    }

    private fun assertFalseContains(haystack: String, needle: String) {
        assertTrue(!haystack.contains(needle), "script must not contain $needle")
    }
}
