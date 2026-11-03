package org.hhclicker.launcher.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.CheckSeverity
import org.hhclicker.launcher.model.ComponentStatus
import org.hhclicker.launcher.model.EnvironmentCheck
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.LauncherStatus
import org.hhclicker.launcher.model.PreflightState
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus

/**
 * A reason that is computed and never rendered is the same as no reason at all. These pin down what the
 * main screen actually tells the user when something has gone wrong.
 */
class LauncherPresentationTest {

    private val engineGone = EnvironmentCheck(
        id = "docker-server",
        label = "Docker Engine",
        passed = false,
        message = "Engine недоступен; запустите Docker Desktop",
        severity = CheckSeverity.REQUIRED,
    )

    private fun blocked() = LauncherState.stopped().copy(
        preflight = PreflightState(status = PreflightStatus.BLOCKED, checks = listOf(engineGone)),
    )

    @Test
    fun a_blocked_environment_and_a_failed_operation_are_both_reported() {
        val state = blocked().copy(
            status = LauncherStatus.FAILED,
            error = "failed to connect to the docker API",
        )

        val text = state.supportingText

        assertTrue(text.contains(engineGone.message), text)
        assertTrue(text.contains("failed to connect to the docker API"), text)
        assertEquals("Нужна настройка", state.headline)
        assertEquals("Проверить снова", state.primaryActionLabel)
    }

    @Test
    fun a_blocked_environment_without_a_failure_shows_only_the_blocking_check() {
        val text = blocked().supportingText

        assertEquals(engineGone.message, text)
    }

    @Test
    fun a_stale_error_from_an_earlier_state_is_not_shown_next_to_the_blocking_check() {
        // The launcher is not reporting a failure right now, so only the environment reason is relevant.
        val text = blocked().copy(status = LauncherStatus.STOPPED, error = "старая ошибка").supportingText

        assertEquals(engineGone.message, text)
    }

    @Test
    fun a_required_restart_still_wins_over_everything_else() {
        val state = blocked().copy(
            status = LauncherStatus.FAILED,
            error = "не важно",
            setup = SetupState(status = SetupStatus.RESTART_REQUIRED, message = "Перезагрузите компьютер"),
        )

        assertEquals("Перезагрузите компьютер", state.supportingText)
    }

    @Test
    fun a_ready_environment_with_a_failed_start_still_shows_the_error() {
        val state = LauncherState.stopped().copy(
            preflight = PreflightState.ready,
            status = LauncherStatus.FAILED,
            error = "Backend не ответил на проверку здоровья",
        )

        assertEquals("Backend не ответил на проверку здоровья", state.supportingText)
        assertEquals("Нужна проверка", state.headline)
        assertEquals("Остановить", state.primaryActionLabel)
        assertEquals("Есть ошибка", state.summaryLabel)
    }

    @Test
    fun a_running_stack_reports_how_many_components_are_up() {
        val running = LauncherState.stopped().let { base ->
            base.copy(
                preflight = PreflightState.ready,
                status = LauncherStatus.RUNNING,
                components = base.components.map { it.copy(status = ComponentStatus.RUNNING) },
            )
        }

        assertEquals("Сервисы работают", running.headline)
        assertEquals("Открыть HH Clicker", running.primaryActionLabel)
        assertEquals("4 из 4 работают", running.summaryLabel)
    }
}
