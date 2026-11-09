package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.ChromeDiagnostics
import org.hhclicker.launcher.model.ChromeInstallationState
import org.hhclicker.launcher.model.HostArchitecture
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.model.SetupTarget

class ChromeSetupPlannerTest {

    private fun diagnostics(
        installation: ChromeInstallationState,
        architecture: HostArchitecture = HostArchitecture.X64_SUPPORTED,
    ) = ChromeDiagnostics(installation = installation, architecture = architecture)

    // --- decisions --------------------------------------------------------------------------------

    @Test
    fun an_installed_chrome_produces_no_action_at_all() {
        val action = ChromeSetupPlanner.decide(diagnostics(ChromeInstallationState.INSTALLED))
        assertEquals(ChromeSetupAction.NONE, action)
        assertFalse(action.executable)
        assertFalse(action.requiresTermsAcceptance)
    }

    @Test
    fun a_missing_chrome_on_x64_is_offered_an_installation() {
        val action = ChromeSetupPlanner.decide(diagnostics(ChromeInstallationState.NOT_INSTALLED))
        assertEquals(ChromeSetupAction.INSTALL, action)
        assertTrue(action.executable)
        assertTrue(action.requiresTermsAcceptance)
        assertEquals(SetupStep.DOWNLOADING_CHROME, ChromeSetupPlanner.stepFor(action))
    }

    @Test
    fun an_unsupported_architecture_is_refused() {
        listOf(HostArchitecture.ARM_UNSUPPORTED, HostArchitecture.UNKNOWN).forEach { architecture ->
            val action = ChromeSetupPlanner.decide(
                diagnostics(ChromeInstallationState.NOT_INSTALLED, architecture),
            )
            assertEquals(ChromeSetupAction.BLOCKED_BY_ARCHITECTURE, action, architecture.toString())
            assertFalse(action.executable, architecture.toString())
        }
    }

    @Test
    fun a_failed_diagnosis_never_turns_into_an_installation() {
        val action = ChromeSetupPlanner.decide(diagnostics(ChromeInstallationState.CHECK_FAILED))
        assertEquals(ChromeSetupAction.DIAGNOSTICS_FAILED, action)
        assertFalse(action.executable)
    }

    @Test
    fun nothing_is_decided_before_the_first_check() {
        assertEquals(ChromeSetupAction.NONE, ChromeSetupPlanner.decide(null))
    }

    @Test
    fun a_running_preparation_blocks_a_second_installation() {
        val action = ChromeSetupPlanner.decide(
            diagnostics(ChromeInstallationState.NOT_INSTALLED),
            preparationInProgress = true,
        )
        assertEquals(ChromeSetupAction.ALREADY_IN_PROGRESS, action)
        assertFalse(action.executable)
        assertFalse(action.requiresTermsAcceptance)
    }

    /** An installed Chrome stays untouched even while something else is being prepared. */
    @Test
    fun an_installed_chrome_is_still_not_touched_during_a_preparation() {
        assertEquals(
            ChromeSetupAction.NONE,
            ChromeSetupPlanner.decide(diagnostics(ChromeInstallationState.INSTALLED), preparationInProgress = true),
        )
    }

    // --- states -----------------------------------------------------------------------------------

    @Test
    fun every_stage_has_its_own_russian_message_and_the_chrome_target() {
        listOf(
            SetupStep.DOWNLOADING_CHROME to "Скачиваем официальный установщик Google Chrome",
            SetupStep.VERIFYING_CHROME_INSTALLER to "Проверяем цифровую подпись установщика",
            SetupStep.INSTALLING_CHROME to "Устанавливаем Google Chrome",
            SetupStep.RECHECKING_ENVIRONMENT to "Повторяем проверку окружения",
        ).forEach { (step, expected) ->
            val state = ChromeSetupPlanner.stageState(step)
            assertEquals(expected, state.message)
            assertEquals(SetupTarget.CHROME, state.target)
            assertEquals(SetupStatus.PREPARING, state.status)
            assertTrue(state.inProgress)
        }
    }

    @Test
    fun the_download_state_carries_progress() {
        val state = ChromeSetupPlanner.downloadState(50, 200)
        assertEquals(SetupStep.DOWNLOADING_CHROME, state.step)
        assertEquals(25, state.progressPercent)
    }

    @Test
    fun a_successful_installation_is_reported_as_success() {
        val state = ChromeSetupPlanner.stateFor(
            ChromePreparationResult(ChromePreparationOutcome.INSTALLED_AND_READY, exitCode = 0),
        )
        assertEquals(SetupStatus.SUCCEEDED, state.status)
        assertEquals("Google Chrome установлен. ${ChromeSetupPlanner.FIRST_RUN_HINT}", state.message)
        assertEquals(SetupTarget.CHROME, state.target)
    }

    /** 3010 is a successful installation; the launcher never demands its own restart for it. */
    @Test
    fun a_reboot_flagged_installation_is_still_a_success() {
        val state = ChromeSetupPlanner.stateFor(
            ChromePreparationResult(ChromePreparationOutcome.INSTALLED_RESTART_RECOMMENDED, exitCode = 3010),
        )
        assertEquals(SetupStatus.SUCCEEDED, state.status)
        assertTrue(assertNotNull(state.message).contains("установлен"))
    }

    @Test
    fun each_failure_the_user_can_act_on_has_its_own_sentence() {
        assertEquals(
            "Установка была отменена",
            ChromeSetupPlanner.stateFor(
                ChromePreparationResult(ChromePreparationOutcome.INSTALLER_CANCELLED, exitCode = 1602),
            ).message,
        )
        assertEquals(
            "Сейчас выполняется другая установка Windows. Дождитесь её завершения и попробуйте снова",
            ChromeSetupPlanner.stateFor(
                ChromePreparationResult(ChromePreparationOutcome.ANOTHER_INSTALLATION_RUNNING, exitCode = 1618),
            ).message,
        )
        assertEquals(
            "Установщик сообщил об успехе, но Chrome не найден",
            ChromeSetupPlanner.stateFor(
                ChromePreparationResult(ChromePreparationOutcome.CHROME_NOT_FOUND_AFTER_INSTALL),
            ).message,
        )
        assertTrue(
            assertNotNull(
                ChromeSetupPlanner.stateFor(
                    ChromePreparationResult(ChromePreparationOutcome.SIGNATURE_REJECTED),
                ).message,
            ).startsWith("Не удалось подтвердить издателя установщика"),
        )
    }

    @Test
    fun every_terminal_state_is_failed_or_succeeded_and_never_stuck_in_progress() {
        ChromePreparationOutcome.entries.forEach { outcome ->
            val state = ChromeSetupPlanner.stateFor(ChromePreparationResult(outcome))
            assertTrue(
                state.status == SetupStatus.SUCCEEDED || state.status == SetupStatus.FAILED,
                "$outcome -> ${state.status}",
            )
            assertFalse(state.inProgress, outcome.toString())
            assertEquals(SetupTarget.CHROME, state.target, outcome.toString())
            assertTrue(assertNotNull(state.message).isNotBlank(), outcome.toString())
        }
    }

    /** No user-facing sentence may leak a path, an environment variable or a raw command. */
    @Test
    fun user_messages_stay_free_of_technical_detail() {
        val messages = ChromePreparationOutcome.entries.mapNotNull {
            ChromeSetupPlanner.stateFor(ChromePreparationResult(it)).message
        } + ChromeSetupAction.entries.mapNotNull { ChromeSetupPlanner.refusalState(it).message } +
            listOf(ChromeSetupPlanner.INSTALL_DESCRIPTION, ChromeSetupPlanner.TERMS_NOTICE)

        messages.forEach { message ->
            assertFalse(message.contains("\\"), message)
            assertFalse(message.contains("msiexec", ignoreCase = true), message)
            assertFalse(message.contains(".msi", ignoreCase = true), message)
            assertFalse(message.contains("LOCALAPPDATA", ignoreCase = true), message)
            assertFalse(message.contains("dl.google.com"), message)
            assertFalse(message.contains("http"), message)
        }
    }

    @Test
    fun the_consent_wording_names_chrome_and_links_google_terms() {
        assertEquals("Установить Google Chrome", ChromeSetupPlanner.INSTALL_TITLE)
        assertEquals("Принять и установить Chrome", ChromeSetupPlanner.INSTALL_BUTTON_LABEL)
        assertTrue(ChromeSetupPlanner.INSTALL_DESCRIPTION.contains("Browser Agent"))
        assertTrue(ChromeSetupPlanner.INSTALL_DESCRIPTION.contains(ChromeSetupPlanner.FIRST_RUN_HINT))
        assertTrue(ChromeSetupPlanner.TERMS_URL.startsWith("https://"))
        // Docker's agreement is never reused as the Chrome consent.
        assertFalse(ChromeSetupPlanner.TERMS_NOTICE.contains("Docker"))
        assertFalse(ChromeSetupPlanner.TERMS_URL.contains("docker"))
    }
}
