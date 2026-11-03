package org.hhclicker.launcher.setup

import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.model.SetupTarget
import org.hhclicker.launcher.model.VirtualizationState
import org.hhclicker.launcher.model.WslDefaultVersionState
import org.hhclicker.launcher.model.WslDiagnostics
import org.hhclicker.launcher.model.WslInstallationState

/** The single operation the launcher may offer for the current diagnostics. */
enum class WslSetupAction {
    /** Nothing to do, or nothing decided yet. */
    NONE,
    INSTALL,
    UPDATE,
    SET_DEFAULT_VERSION,

    /** Hardware virtualization is off. Only the user can fix this, in BIOS/UEFI. */
    BLOCKED_BY_FIRMWARE,

    /** The launcher could not determine the WSL state and must not guess. */
    DIAGNOSTICS_FAILED,
    ;

    /** True when the launcher itself is allowed to run an elevated command for this action. */
    val executable: Boolean
        get() = this == INSTALL || this == UPDATE || this == SET_DEFAULT_VERSION
}

/** Terminal result of one elevated preparation attempt. */
enum class WslPreparationOutcome {
    /** Windows components changed. A manual restart is mandatory before anything else. */
    COMPONENTS_CHANGED_RESTART_REQUIRED,

    /** The operation finished and did not touch Windows components. */
    COMPLETED,

    /** The user dismissed the UAC prompt. */
    ELEVATION_DECLINED,

    /** Windows refused to start the elevated process for another reason. */
    ELEVATION_FAILED,

    /** The elevated command ran and returned a non-zero exit code. */
    COMMAND_FAILED,
    TIMED_OUT,

    /** The launcher could not start the elevation helper at all. */
    LAUNCH_FAILED,

    /** Nothing to execute: wrong platform, or an action the launcher must not perform. */
    NOT_APPLICABLE,
}

data class WslPreparationResult(
    val outcome: WslPreparationOutcome,
    val exitCode: Int? = null,
)

/**
 * Pure decision and state transition rules for the WSL 2 preparation step.
 *
 * Everything here is deterministic and free of system calls so that it can be unit tested directly.
 */
object WslSetupPlanner {

    fun decide(diagnostics: WslDiagnostics?): WslSetupAction = when {
        diagnostics == null -> WslSetupAction.NONE
        diagnostics.virtualization == VirtualizationState.DISABLED_IN_FIRMWARE -> WslSetupAction.BLOCKED_BY_FIRMWARE
        diagnostics.installation == WslInstallationState.CHECK_FAILED -> WslSetupAction.DIAGNOSTICS_FAILED
        diagnostics.installation == WslInstallationState.NOT_INSTALLED -> WslSetupAction.INSTALL
        diagnostics.installation == WslInstallationState.OUTDATED -> WslSetupAction.UPDATE
        diagnostics.defaultVersion != WslDefaultVersionState.VERSION_2 -> WslSetupAction.SET_DEFAULT_VERSION
        else -> WslSetupAction.NONE
    }

    fun stepFor(action: WslSetupAction): SetupStep = when (action) {
        WslSetupAction.INSTALL -> SetupStep.INSTALLING_WSL
        WslSetupAction.UPDATE -> SetupStep.UPDATING_WSL
        WslSetupAction.SET_DEFAULT_VERSION -> SetupStep.SETTING_DEFAULT_VERSION
        else -> SetupStep.NONE
    }

    /** State published while the elevated command is running. */
    fun preparingState(action: WslSetupAction): SetupState = SetupState(
        status = SetupStatus.PREPARING,
        target = SetupTarget.WSL,
        step = stepFor(action),
        message = when (action) {
            WslSetupAction.INSTALL -> "Устанавливаем WSL 2 без дистрибутива Linux. Подтвердите запрос Windows"
            WslSetupAction.UPDATE -> "Обновляем WSL. Подтвердите запрос Windows"
            WslSetupAction.SET_DEFAULT_VERSION -> "Делаем WSL 2 версией по умолчанию. Подтвердите запрос Windows"
            else -> "Подготовка недоступна"
        },
    )

    /** State published while the diagnostics are repeated after a finished operation. */
    fun rechecking(previous: SetupState): SetupState = previous.copy(
        status = SetupStatus.PREPARING,
        step = SetupStep.RECHECKING_ENVIRONMENT,
        message = "Повторяем проверку окружения",
    )

    /** State published when the screen asks for an action the launcher must not perform. */
    fun refusalState(action: WslSetupAction): SetupState = SetupState(
        status = SetupStatus.FAILED,
        target = SetupTarget.WSL,
        step = SetupStep.NONE,
        message = when (action) {
            WslSetupAction.BLOCKED_BY_FIRMWARE ->
                "Сначала включите Intel VT-x или AMD-V (SVM) в BIOS/UEFI"
            WslSetupAction.DIAGNOSTICS_FAILED ->
                "Не удалось определить состояние WSL: команда wsl --version завершилась ошибкой"
            else -> "Подготовка WSL 2 не требуется"
        },
    )

    fun stateFor(result: WslPreparationResult): SetupState = when (result.outcome) {
        WslPreparationOutcome.COMPONENTS_CHANGED_RESTART_REQUIRED -> SetupState(
            status = SetupStatus.RESTART_REQUIRED,
            target = SetupTarget.WSL,
            step = SetupStep.NONE,
            message = "Компоненты Windows изменены. Перезагрузите компьютер и запустите лаунчер снова",
        )

        WslPreparationOutcome.COMPLETED -> SetupState(
            status = SetupStatus.SUCCEEDED,
            target = SetupTarget.WSL,
            step = SetupStep.NONE,
            message = "WSL 2 выбран версией по умолчанию",
        )

        WslPreparationOutcome.ELEVATION_DECLINED -> failed(
            "Запрос прав администратора отклонён. Разрешите его, чтобы установить WSL 2",
        )

        WslPreparationOutcome.ELEVATION_FAILED -> failed(
            "Windows не выдала права администратора для установки WSL 2",
        )

        WslPreparationOutcome.COMMAND_FAILED -> failed(
            result.exitCode?.let { "Команда WSL завершилась с кодом $it" }
                ?: "Команда WSL завершилась с неизвестным кодом",
        )

        WslPreparationOutcome.TIMED_OUT -> failed(
            "Команда WSL не завершилась за отведённое время и была прервана",
        )

        WslPreparationOutcome.LAUNCH_FAILED -> failed(
            "Не удалось запустить powershell.exe для установки WSL 2",
        )

        WslPreparationOutcome.NOT_APPLICABLE -> failed(
            "Подготовка WSL 2 доступна только в Windows",
        )
    }

    private fun failed(message: String) =
        SetupState(SetupStatus.FAILED, SetupStep.NONE, message, SetupTarget.WSL)
}
