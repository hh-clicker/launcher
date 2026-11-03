package org.hhclicker.launcher.setup

import org.hhclicker.launcher.model.ChromeDiagnostics
import org.hhclicker.launcher.model.ChromeInstallationState
import org.hhclicker.launcher.model.HostArchitecture
import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.model.SetupTarget

/** The single Google Chrome operation the launcher may offer for the current diagnostics. */
enum class ChromeSetupAction {
    /** Chrome is installed, or nothing has been decided yet. Never an update or a repair. */
    NONE,

    /** Chrome is absent on a supported host: download, verify and install the official MSI. */
    INSTALL,

    /** The official MSI the launcher uses is x64 only. */
    BLOCKED_BY_ARCHITECTURE,

    /** The launcher could not determine whether Chrome exists and must not guess. */
    DIAGNOSTICS_FAILED,

    /** A preparation is already running. A second one must never be started. */
    ALREADY_IN_PROGRESS,
    ;

    /** True when the launcher itself may run something for this action. */
    val executable: Boolean
        get() = this == INSTALL

    /** True when the user must accept Google's terms with an explicit click before anything is fetched. */
    val requiresTermsAcceptance: Boolean
        get() = this == INSTALL
}

/** Terminal result of one Chrome preparation attempt. */
enum class ChromePreparationOutcome {
    /** The MSI reported success and a chrome.exe was found afterwards. */
    INSTALLED_AND_READY,

    /** MSI exit code 3010: installed, and Windows additionally asks for a restart at some point. */
    INSTALLED_RESTART_RECOMMENDED,

    /** Chrome was already there. Nothing was downloaded, installed or started. */
    ALREADY_INSTALLED,

    /** The installer could not be downloaded. */
    DOWNLOAD_FAILED,

    /** The download was interrupted. */
    DOWNLOAD_CANCELLED,

    /** The response exceeded the accepted installer size. */
    DOWNLOAD_TOO_LARGE,

    /** Authenticode verification refused the file. The MSI was deleted. */
    SIGNATURE_REJECTED,

    /** MSI exit code 1602: the user dismissed the UAC prompt or cancelled the installation. */
    INSTALLER_CANCELLED,

    /** MSI exit code 1618: another Windows Installer transaction is in progress. */
    ANOTHER_INSTALLATION_RUNNING,

    /** Any other non-zero exit code. */
    INSTALLER_FAILED,

    /** msiexec did not finish inside its bound. */
    INSTALLER_TIMED_OUT,

    /** Windows could not show or complete the administrator elevation request. */
    ELEVATION_FAILED,

    /** The absolute path to Windows' own msiexec.exe could not be determined safely. */
    INSTALLER_ENGINE_NOT_FOUND,

    /** The installer claimed success but no supported chrome.exe appeared. */
    CHROME_NOT_FOUND_AFTER_INSTALL,

    /** Google terms were not accepted by an explicit click. Nothing was downloaded. */
    TERMS_NOT_ACCEPTED,

    /** Nothing to execute: wrong platform, wrong architecture, or a refused action. */
    NOT_APPLICABLE,
}

data class ChromePreparationResult(
    val outcome: ChromePreparationOutcome,
    val exitCode: Int? = null,
    val httpStatus: Int? = null,
)

/**
 * Pure decision and state transition rules for the Google Chrome preparation step.
 *
 * Everything here is deterministic and free of system calls. An installed Chrome is never updated,
 * repaired, reinstalled, closed or launched: the only action this planner can ever return for an
 * existing installation is [ChromeSetupAction.NONE].
 */
object ChromeSetupPlanner {

    /** Google terms the user accepts by pressing the install button. */
    const val TERMS_URL = "https://www.google.com/chrome/terms/"

    const val TERMS_NOTICE = "Скачивая Chrome, вы принимаете условия использования Google Chrome"

    const val INSTALL_BUTTON_LABEL = "Принять и установить Chrome"

    const val INSTALL_TITLE = "Установить Google Chrome"

    const val INSTALL_DESCRIPTION =
        "Chrome нужен Browser Agent для работы с HH. Лаунчер скачает официальный установщик Google " +
            "и установит браузер для всех пользователей."

    /**
     * @param preparationInProgress true while any preparation operation is running. A second Chrome
     *   installation must never start on top of the first one.
     */
    fun decide(chrome: ChromeDiagnostics?, preparationInProgress: Boolean = false): ChromeSetupAction {
        if (chrome == null) return ChromeSetupAction.NONE
        // An existing installation ends the decision immediately: no update, no repair, no reinstall.
        if (chrome.installation == ChromeInstallationState.INSTALLED) return ChromeSetupAction.NONE
        if (preparationInProgress) return ChromeSetupAction.ALREADY_IN_PROGRESS
        return when {
            chrome.installation == ChromeInstallationState.CHECK_FAILED -> ChromeSetupAction.DIAGNOSTICS_FAILED
            chrome.architecture != HostArchitecture.X64_SUPPORTED -> ChromeSetupAction.BLOCKED_BY_ARCHITECTURE
            else -> ChromeSetupAction.INSTALL
        }
    }

    fun stepFor(action: ChromeSetupAction): SetupStep = when (action) {
        ChromeSetupAction.INSTALL -> SetupStep.DOWNLOADING_CHROME
        else -> SetupStep.NONE
    }

    /** State published when the operation begins, before anything is downloaded. */
    fun preparingState(action: ChromeSetupAction): SetupState = stageState(stepFor(action))

    /** State published when a stage changes inside a running operation. */
    fun stageState(step: SetupStep): SetupState = SetupState(
        status = SetupStatus.PREPARING,
        target = SetupTarget.CHROME,
        step = step,
        message = messageFor(step),
    )

    /** State published for each download progress update. */
    fun downloadState(downloadedBytes: Long, totalBytes: Long?): SetupState = SetupState(
        status = SetupStatus.PREPARING,
        target = SetupTarget.CHROME,
        step = SetupStep.DOWNLOADING_CHROME,
        message = messageFor(SetupStep.DOWNLOADING_CHROME),
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
    )

    fun messageFor(step: SetupStep): String = when (step) {
        SetupStep.DOWNLOADING_CHROME -> "Скачиваем официальный установщик Google Chrome"
        SetupStep.VERIFYING_CHROME_INSTALLER -> "Проверяем цифровую подпись установщика"
        SetupStep.INSTALLING_CHROME -> "Устанавливаем Google Chrome"
        SetupStep.RECHECKING_ENVIRONMENT -> "Повторяем проверку окружения"
        else -> "Подготовка Google Chrome"
    }

    /** State published while the diagnostics are repeated after a finished operation. */
    fun rechecking(): SetupState = stageState(SetupStep.RECHECKING_ENVIRONMENT)

    /** State published when the screen asks for an action the launcher must not perform. */
    fun refusalState(action: ChromeSetupAction): SetupState = failed(
        when (action) {
            ChromeSetupAction.BLOCKED_BY_ARCHITECTURE ->
                "Автоматическая установка Google Chrome поддерживается только на x64"

            ChromeSetupAction.DIAGNOSTICS_FAILED ->
                "Не удалось определить, установлен ли Google Chrome. Запустите проверку снова"

            ChromeSetupAction.ALREADY_IN_PROGRESS ->
                "Установка уже выполняется. Дождитесь её завершения"

            else -> "Google Chrome уже установлен"
        },
    )

    fun stateFor(result: ChromePreparationResult): SetupState = when (result.outcome) {
        ChromePreparationOutcome.INSTALLED_AND_READY -> succeeded("Google Chrome установлен")

        // The browser works immediately; the restart is Windows housekeeping, not a launcher restart.
        ChromePreparationOutcome.INSTALLED_RESTART_RECOMMENDED ->
            succeeded("Google Chrome установлен. Windows просит перезагрузить компьютер позже")

        ChromePreparationOutcome.ALREADY_INSTALLED -> succeeded("Google Chrome уже установлен")

        ChromePreparationOutcome.DOWNLOAD_FAILED -> failed(
            result.httpStatus?.let { "Не удалось скачать установщик Chrome: сервер ответил $it" }
                ?: "Не удалось скачать установщик Chrome: проверьте интернет-соединение",
        )

        ChromePreparationOutcome.DOWNLOAD_CANCELLED -> failed("Загрузка установщика Chrome прервана")

        ChromePreparationOutcome.DOWNLOAD_TOO_LARGE -> failed(
            "Загрузка прервана: файл больше допустимого размера установщика Chrome",
        )

        ChromePreparationOutcome.SIGNATURE_REJECTED -> failed(
            "Не удалось подтвердить издателя установщика. Файл удалён, установка отменена",
        )

        ChromePreparationOutcome.INSTALLER_CANCELLED -> failed("Установка была отменена")

        ChromePreparationOutcome.ANOTHER_INSTALLATION_RUNNING -> failed(
            "Сейчас выполняется другая установка Windows. Дождитесь её завершения и попробуйте снова",
        )

        ChromePreparationOutcome.INSTALLER_FAILED -> failed(
            result.exitCode?.let { "Установщик Chrome завершился с кодом $it" }
                ?: "Установщик Chrome завершился с неизвестным кодом",
        )

        ChromePreparationOutcome.INSTALLER_TIMED_OUT -> failed(
            "Установщик Chrome не завершился за отведённое время",
        )

        ChromePreparationOutcome.ELEVATION_FAILED -> failed(
            "Windows не удалось запросить права администратора. Попробуйте снова",
        )

        ChromePreparationOutcome.INSTALLER_ENGINE_NOT_FOUND -> failed(
            "Не найден системный установщик Windows. Перезапустите Windows и попробуйте снова",
        )

        ChromePreparationOutcome.CHROME_NOT_FOUND_AFTER_INSTALL -> failed(
            "Установщик сообщил об успехе, но Chrome не найден",
        )

        ChromePreparationOutcome.TERMS_NOT_ACCEPTED -> failed(
            "Установка не начата: условия Google не приняты",
        )

        ChromePreparationOutcome.NOT_APPLICABLE -> failed(
            "Установка Google Chrome доступна только в Windows на x64",
        )
    }

    private fun succeeded(message: String) =
        SetupState(SetupStatus.SUCCEEDED, SetupStep.NONE, message, SetupTarget.CHROME)

    private fun failed(message: String) =
        SetupState(SetupStatus.FAILED, SetupStep.NONE, message, SetupTarget.CHROME)
}
