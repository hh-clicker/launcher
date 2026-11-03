package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.model.VirtualizationState
import org.hhclicker.launcher.model.WslDefaultVersionState
import org.hhclicker.launcher.model.WslDiagnostics
import org.hhclicker.launcher.model.WslInstallationState

class WslSetupPlannerTest {

    private fun diagnostics(
        virtualization: VirtualizationState = VirtualizationState.ENABLED,
        installation: WslInstallationState = WslInstallationState.COMPATIBLE,
        defaultVersion: WslDefaultVersionState = WslDefaultVersionState.VERSION_2,
        version: String? = "2.3.24.0",
    ) = WslDiagnostics(virtualization, installation, defaultVersion, version)

    @Test
    fun nothing_is_decided_before_the_first_check() {
        assertEquals(WslSetupAction.NONE, WslSetupPlanner.decide(null))
    }

    @Test
    fun disabled_virtualization_blocks_every_installation() {
        val blocked = WslSetupPlanner.decide(
            diagnostics(virtualization = VirtualizationState.DISABLED_IN_FIRMWARE, installation = WslInstallationState.NOT_INSTALLED),
        )
        assertEquals(WslSetupAction.BLOCKED_BY_FIRMWARE, blocked)
        assertFalse(blocked.executable)
    }

    @Test
    fun undetermined_virtualization_does_not_block_installation() {
        assertEquals(
            WslSetupAction.INSTALL,
            WslSetupPlanner.decide(
                diagnostics(virtualization = VirtualizationState.UNKNOWN, installation = WslInstallationState.NOT_INSTALLED),
            ),
        )
    }

    @Test
    fun missing_wsl_is_installed_and_outdated_wsl_is_updated() {
        assertEquals(
            WslSetupAction.INSTALL,
            WslSetupPlanner.decide(diagnostics(installation = WslInstallationState.NOT_INSTALLED, version = null)),
        )
        assertEquals(
            WslSetupAction.UPDATE,
            WslSetupPlanner.decide(diagnostics(installation = WslInstallationState.OUTDATED, version = "2.0.9.0")),
        )
    }

    @Test
    fun compatible_wsl_is_never_reinstalled() {
        val action = WslSetupPlanner.decide(diagnostics())
        assertEquals(WslSetupAction.NONE, action)
        assertFalse(action.executable)
    }

    @Test
    fun compatible_wsl_with_unknown_default_version_is_explicitly_switched_to_two() {
        assertEquals(
            WslSetupAction.SET_DEFAULT_VERSION,
            WslSetupPlanner.decide(diagnostics(defaultVersion = WslDefaultVersionState.UNKNOWN)),
        )
    }

    @Test
    fun default_version_one_is_switched_to_two() {
        val action = WslSetupPlanner.decide(diagnostics(defaultVersion = WslDefaultVersionState.VERSION_1))
        assertEquals(WslSetupAction.SET_DEFAULT_VERSION, action)
        assertTrue(action.executable)
    }

    @Test
    fun failed_diagnostics_never_turn_into_an_installation() {
        val action = WslSetupPlanner.decide(diagnostics(installation = WslInstallationState.CHECK_FAILED, version = null))
        assertEquals(WslSetupAction.DIAGNOSTICS_FAILED, action)
        assertFalse(action.executable)
    }

    @Test
    fun steps_match_the_executable_actions() {
        assertEquals(SetupStep.INSTALLING_WSL, WslSetupPlanner.stepFor(WslSetupAction.INSTALL))
        assertEquals(SetupStep.UPDATING_WSL, WslSetupPlanner.stepFor(WslSetupAction.UPDATE))
        assertEquals(SetupStep.SETTING_DEFAULT_VERSION, WslSetupPlanner.stepFor(WslSetupAction.SET_DEFAULT_VERSION))
        assertEquals(SetupStep.NONE, WslSetupPlanner.stepFor(WslSetupAction.BLOCKED_BY_FIRMWARE))
        assertEquals(SetupStep.NONE, WslSetupPlanner.stepFor(WslSetupAction.DIAGNOSTICS_FAILED))
        assertEquals(SetupStep.NONE, WslSetupPlanner.stepFor(WslSetupAction.NONE))
    }

    @Test
    fun preparing_state_carries_the_step_and_a_message() {
        val preparing = WslSetupPlanner.preparingState(WslSetupAction.INSTALL)
        assertEquals(SetupStatus.PREPARING, preparing.status)
        assertEquals(SetupStep.INSTALLING_WSL, preparing.step)
        assertTrue(preparing.inProgress)
        assertNotNull(preparing.message)
    }

    @Test
    fun rechecking_keeps_the_operation_in_progress() {
        val finished = WslSetupPlanner.stateFor(WslPreparationResult(WslPreparationOutcome.COMPLETED))
        val rechecking = WslSetupPlanner.rechecking(finished)
        assertEquals(SetupStatus.PREPARING, rechecking.status)
        assertEquals(SetupStep.RECHECKING_ENVIRONMENT, rechecking.step)
    }

    @Test
    fun changing_windows_components_requires_a_manual_restart_and_is_not_a_success() {
        val state = WslSetupPlanner.stateFor(
            WslPreparationResult(WslPreparationOutcome.COMPONENTS_CHANGED_RESTART_REQUIRED),
        )
        assertEquals(SetupStatus.RESTART_REQUIRED, state.status)
        assertTrue(state.message.orEmpty().contains("Перезагрузите компьютер"))
    }

    @Test
    fun setting_the_default_version_succeeds_without_a_restart() {
        val state = WslSetupPlanner.stateFor(WslPreparationResult(WslPreparationOutcome.COMPLETED))
        assertEquals(SetupStatus.SUCCEEDED, state.status)
        assertEquals(SetupStep.NONE, state.step)
    }

    @Test
    fun declined_elevation_is_reported_as_such() {
        val state = WslSetupPlanner.stateFor(WslPreparationResult(WslPreparationOutcome.ELEVATION_DECLINED))
        assertEquals(SetupStatus.FAILED, state.status)
        assertTrue(state.message.orEmpty().contains("администратора"))
    }

    @Test
    fun a_failing_command_keeps_its_exit_code_in_the_message() {
        val state = WslSetupPlanner.stateFor(WslPreparationResult(WslPreparationOutcome.COMMAND_FAILED, exitCode = 4294967295.toInt()))
        assertEquals(SetupStatus.FAILED, state.status)
        assertTrue(state.message.orEmpty().contains("-1"))
    }

    @Test
    fun every_outcome_produces_a_user_message() {
        WslPreparationOutcome.entries.forEach { outcome ->
            val state = WslSetupPlanner.stateFor(WslPreparationResult(outcome))
            assertNotNull(state.message, "outcome $outcome has no message")
            assertFalse(state.inProgress, "outcome $outcome must be terminal")
        }
    }

    @Test
    fun refusals_explain_the_manual_blocker() {
        assertTrue(
            WslSetupPlanner.refusalState(WslSetupAction.BLOCKED_BY_FIRMWARE).message.orEmpty().contains("BIOS/UEFI"),
        )
        assertEquals(SetupStatus.FAILED, WslSetupPlanner.refusalState(WslSetupAction.DIAGNOSTICS_FAILED).status)
    }
}
