package org.hhclicker.launcher.setup

import org.hhclicker.launcher.model.ContainerMode
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.DockerCliState
import org.hhclicker.launcher.model.DockerComposeState
import org.hhclicker.launcher.model.DockerDesktopProcessState
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.DockerDiagnostics
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.DockerInstallScope
import org.hhclicker.launcher.model.HostArchitecture
import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.model.SetupTarget
import org.hhclicker.launcher.model.WslDiagnostics

/** The single Docker operation the launcher may offer for the current diagnostics. */
enum class DockerSetupAction {
    /** Nothing to do, or nothing decided yet. */
    NONE,

    /** Download, verify and install Docker Desktop per user with the WSL 2 backend. */
    INSTALL,

    /** Install over an existing installation that is below the minimum supported version. */
    UPDATE,

    /** Docker Desktop is installed but not running. */
    START_DESKTOP,

    /** Docker Desktop is alive; only the engine is still coming up. */
    WAIT_FOR_ENGINE,

    /** WSL 2 is not fully ready yet. Docker must not be installed before it is. */
    BLOCKED_BY_WSL,

    /** Automatic installation is implemented for x64 only. */
    BLOCKED_BY_ARCHITECTURE,

    /** The engine serves Windows containers. HH Clicker needs Linux containers. */
    BLOCKED_BY_CONTAINER_MODE,

    /** The engine runs on a backend that is explicitly not WSL 2. */
    BLOCKED_BY_BACKEND,

    /** The backend could not be confirmed. Unconfirmed is never treated as ready. */
    BACKEND_UNCONFIRMED,

    /** A docker.exe exists without Docker Desktop. Never silently reinstalled. */
    CLI_WITHOUT_DESKTOP,

    /** The launcher could not determine the Docker state and must not guess. */
    DIAGNOSTICS_FAILED,
    ;

    /** True when the launcher itself may run something for this action. */
    val executable: Boolean
        get() = this == INSTALL || this == UPDATE || this == START_DESKTOP || this == WAIT_FOR_ENGINE

    /**
     * True when the action installs Docker Desktop and therefore requires the user to accept the Docker
     * Subscription Service Agreement with an explicit click first.
     */
    val requiresLicenseAcceptance: Boolean
        get() = this == INSTALL || this == UPDATE
}

/** Terminal result of one Docker preparation attempt. */
enum class DockerPreparationOutcome {
    /** Docker Desktop was installed and the engine reached a ready state. */
    INSTALLED_AND_READY,

    /** Docker Desktop was already installed and is now running and ready. */
    STARTED_AND_READY,

    /** Nothing needed doing. */
    ALREADY_READY,

    /** The installer could not be downloaded. */
    DOWNLOAD_FAILED,

    /** The download was interrupted. */
    DOWNLOAD_CANCELLED,

    /** The response exceeded the accepted installer size. */
    DOWNLOAD_TOO_LARGE,

    /** Authenticode verification refused the file. The installer was deleted. */
    SIGNATURE_REJECTED,

    /** The installer ran and returned a non-zero exit code. */
    INSTALLER_FAILED,
    INSTALLER_TIMED_OUT,

    /** Docker Desktop could not be started. */
    START_FAILED,

    /** Docker Desktop is up but the engine did not answer in time. */
    ENGINE_TIMEOUT,

    /**
     * The wait was abandoned because Docker Desktop is not running.
     *
     * Distinct from [ENGINE_TIMEOUT] on purpose: telling the user that a closed application "did not
     * answer in time" sends them to look at a window that is not there.
     */
    DESKTOP_NOT_RUNNING,

    /** The engine came up on a backend that is not WSL 2. */
    BACKEND_NOT_WSL_2,

    /** The engine came up but the backend could not be confirmed. */
    BACKEND_UNCONFIRMED,

    /** The engine came up serving Windows containers. */
    CONTAINER_MODE_WINDOWS,

    /** Compose v2 is still unavailable after the operation. */
    COMPOSE_UNAVAILABLE,

    /** The Docker agreement was not accepted by an explicit click. */
    LICENSE_NOT_ACCEPTED,

    /** Nothing to execute: wrong platform, wrong architecture, or a refused action. */
    NOT_APPLICABLE,
}

data class DockerPreparationResult(
    val outcome: DockerPreparationOutcome,
    val exitCode: Int? = null,
    val httpStatus: Int? = null,
)

/**
 * Pure decision and state transition rules for the Docker Desktop preparation step.
 *
 * Everything here is deterministic and free of system calls. The screen never chooses a command: it
 * renders whatever single action this planner returns.
 */
object DockerSetupPlanner {

    /** The Docker Subscription Service Agreement the user must accept before an installation. */
    const val LICENSE_URL = "https://www.docker.com/legal/docker-subscription-service-agreement/"

    const val LICENSE_NOTICE =
        "Нажимая «Принять и установить», вы принимаете Docker Subscription Service Agreement"

    const val INSTALL_BUTTON_LABEL = "Принять и установить Docker"

    fun decide(wsl: WslDiagnostics?, docker: DockerDiagnostics?): DockerSetupAction {
        if (docker == null) return DockerSetupAction.NONE

        // WSL 2 must be completely ready before Docker Desktop is offered at all.
        if (wsl == null || !wsl.ready) return DockerSetupAction.BLOCKED_BY_WSL

        return when (docker.desktop) {
            DockerDesktopState.CHECK_FAILED -> DockerSetupAction.DIAGNOSTICS_FAILED

            DockerDesktopState.NOT_INSTALLED -> when {
                // A stray docker.exe without Docker Desktop is a distinct problem, not a reinstall trigger.
                docker.cli == DockerCliState.AVAILABLE -> DockerSetupAction.CLI_WITHOUT_DESKTOP
                else -> installationAction(docker, DockerSetupAction.INSTALL)
            }

            DockerDesktopState.OUTDATED -> updateAction(docker)

            DockerDesktopState.INSTALLED -> installedAction(docker)
        }
    }

    private fun installationAction(docker: DockerDiagnostics, action: DockerSetupAction): DockerSetupAction =
        if (docker.architecture == HostArchitecture.X64_SUPPORTED) {
            action
        } else {
            DockerSetupAction.BLOCKED_BY_ARCHITECTURE
        }

    private fun installedAction(docker: DockerDiagnostics): DockerSetupAction = when {
        docker.engine == DockerEngineState.STARTING -> DockerSetupAction.WAIT_FOR_ENGINE

        docker.engine != DockerEngineState.RUNNING ->
            if (docker.desktopProcess == DockerDesktopProcessState.RUNNING) {
                DockerSetupAction.WAIT_FOR_ENGINE
            } else {
                DockerSetupAction.START_DESKTOP
            }

        docker.containerMode == ContainerMode.WINDOWS -> DockerSetupAction.BLOCKED_BY_CONTAINER_MODE
        docker.containerMode == ContainerMode.UNKNOWN -> DockerSetupAction.DIAGNOSTICS_FAILED

        docker.backend == DockerBackend.NOT_WSL_2 -> DockerSetupAction.BLOCKED_BY_BACKEND
        docker.backend == DockerBackend.UNKNOWN -> DockerSetupAction.BACKEND_UNCONFIRMED

        docker.cli != DockerCliState.AVAILABLE -> DockerSetupAction.DIAGNOSTICS_FAILED
        docker.compose == DockerComposeState.FAILED -> DockerSetupAction.DIAGNOSTICS_FAILED
        docker.compose == DockerComposeState.MISSING -> updateAction(docker)

        else -> DockerSetupAction.NONE
    }

    /** Updating must preserve the existing per-user/all-users installation mode. */
    private fun updateAction(docker: DockerDiagnostics): DockerSetupAction = when {
        docker.architecture != HostArchitecture.X64_SUPPORTED -> DockerSetupAction.BLOCKED_BY_ARCHITECTURE
        docker.installScope == DockerInstallScope.UNKNOWN -> DockerSetupAction.DIAGNOSTICS_FAILED
        else -> DockerSetupAction.UPDATE
    }

    fun stepFor(action: DockerSetupAction): SetupStep = when (action) {
        DockerSetupAction.INSTALL, DockerSetupAction.UPDATE -> SetupStep.DOWNLOADING_DOCKER
        DockerSetupAction.START_DESKTOP -> SetupStep.STARTING_DOCKER
        DockerSetupAction.WAIT_FOR_ENGINE -> SetupStep.WAITING_FOR_DOCKER_ENGINE
        else -> SetupStep.NONE
    }

    /** State published when the operation begins, before anything is downloaded or started. */
    fun preparingState(action: DockerSetupAction): SetupState = stageState(stepFor(action))

    /** State published when a stage changes inside a running operation. */
    fun stageState(step: SetupStep): SetupState = SetupState(
        status = SetupStatus.PREPARING,
        target = SetupTarget.DOCKER,
        step = step,
        message = messageFor(step),
    )

    /** State published for each download progress update. */
    fun downloadState(downloadedBytes: Long, totalBytes: Long?): SetupState = SetupState(
        status = SetupStatus.PREPARING,
        target = SetupTarget.DOCKER,
        step = SetupStep.DOWNLOADING_DOCKER,
        message = messageFor(SetupStep.DOWNLOADING_DOCKER),
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
    )

    fun messageFor(step: SetupStep): String = when (step) {
        SetupStep.DOWNLOADING_DOCKER -> "Скачиваем официальный установщик Docker Desktop"
        SetupStep.VERIFYING_DOCKER_INSTALLER -> "Проверяем цифровую подпись установщика"
        SetupStep.INSTALLING_DOCKER -> "Устанавливаем Docker Desktop"
        SetupStep.STARTING_DOCKER -> "Запускаем Docker Desktop"
        SetupStep.WAITING_FOR_DOCKER_ENGINE -> "Ожидаем Docker Engine"
        SetupStep.VERIFYING_DOCKER_BACKEND -> "Проверяем режим Linux и backend WSL 2"
        SetupStep.RECHECKING_ENVIRONMENT -> "Повторяем проверку окружения"
        else -> "Подготовка Docker"
    }

    /** State published while the diagnostics are repeated after a finished operation. */
    fun rechecking(): SetupState = stageState(SetupStep.RECHECKING_ENVIRONMENT)

    /** State published when the screen asks for an action the launcher must not perform. */
    fun refusalState(action: DockerSetupAction): SetupState = failed(
        when (action) {
            DockerSetupAction.BLOCKED_BY_WSL ->
                "Сначала завершите настройку WSL 2 — Docker Desktop работает только на нём"

            DockerSetupAction.BLOCKED_BY_ARCHITECTURE ->
                "Автоматическая установка Docker Desktop поддерживается только на x64"

            DockerSetupAction.BLOCKED_BY_CONTAINER_MODE ->
                "Docker переключён на Windows containers. Нужен режим Linux containers"

            DockerSetupAction.BLOCKED_BY_BACKEND ->
                "Docker работает не на WSL 2. Включите Settings → General → Use WSL 2 based engine"

            DockerSetupAction.BACKEND_UNCONFIRMED ->
                "Не удалось подтвердить, что Docker использует WSL 2. Проверьте Settings → General → Use WSL 2 based engine"

            DockerSetupAction.CLI_WITHOUT_DESKTOP ->
                "Найден docker.exe, но сам Docker Desktop не найден. Автоматическая переустановка не выполняется"

            DockerSetupAction.DIAGNOSTICS_FAILED ->
                "Не удалось определить состояние Docker. Запустите проверку снова"

            else -> "Подготовка Docker не требуется"
        },
    )

    fun stateFor(result: DockerPreparationResult): SetupState = when (result.outcome) {
        DockerPreparationOutcome.INSTALLED_AND_READY -> succeeded("Docker Desktop установлен и работает на WSL 2")
        DockerPreparationOutcome.STARTED_AND_READY -> succeeded("Docker Desktop запущен и работает на WSL 2")
        DockerPreparationOutcome.ALREADY_READY -> succeeded("Docker Desktop уже готов к работе")

        DockerPreparationOutcome.DOWNLOAD_FAILED -> failed(
            result.httpStatus?.let { "Не удалось скачать установщик Docker: сервер ответил $it" }
                ?: "Не удалось скачать установщик Docker: проверьте интернет-соединение",
        )

        DockerPreparationOutcome.DOWNLOAD_CANCELLED -> failed("Загрузка установщика Docker прервана")

        DockerPreparationOutcome.DOWNLOAD_TOO_LARGE -> failed(
            "Загрузка прервана: файл больше допустимого размера установщика Docker",
        )

        DockerPreparationOutcome.SIGNATURE_REJECTED -> failed(
            "Подпись установщика не принадлежит Docker Inc. Файл удалён, установка отменена",
        )

        DockerPreparationOutcome.INSTALLER_FAILED -> failed(
            result.exitCode?.let { "Установщик Docker завершился с кодом $it" }
                ?: "Установщик Docker завершился с неизвестным кодом",
        )

        DockerPreparationOutcome.INSTALLER_TIMED_OUT -> failed(
            "Установщик Docker не завершился за отведённое время",
        )

        DockerPreparationOutcome.START_FAILED -> failed("Не удалось запустить Docker Desktop")

        DockerPreparationOutcome.ENGINE_TIMEOUT -> failed(
            "Docker Desktop запущен, но Engine не ответил за отведённое время. Проверьте окно Docker и повторите",
        )

        DockerPreparationOutcome.DESKTOP_NOT_RUNNING -> failed(
            "Docker Desktop не запущен, ждать Engine не от кого. Запустите Docker Desktop и повторите",
        )

        DockerPreparationOutcome.BACKEND_NOT_WSL_2 -> failed(
            "Docker запустился не на WSL 2. Включите Settings → General → Use WSL 2 based engine",
        )

        DockerPreparationOutcome.BACKEND_UNCONFIRMED -> failed(
            "Docker запустился, но backend WSL 2 не подтверждён. Проверьте Settings → General → Use WSL 2 based engine",
        )

        DockerPreparationOutcome.CONTAINER_MODE_WINDOWS -> failed(
            "Docker работает в режиме Windows containers. Переключите его на Linux containers",
        )

        DockerPreparationOutcome.COMPOSE_UNAVAILABLE -> failed(
            "Docker запущен, но команда docker compose недоступна. Обновите Docker Desktop",
        )

        DockerPreparationOutcome.LICENSE_NOT_ACCEPTED -> failed(
            "Установка не начата: соглашение Docker не принято",
        )

        DockerPreparationOutcome.NOT_APPLICABLE -> failed(
            "Подготовка Docker Desktop доступна только в Windows на x64",
        )
    }

    private fun succeeded(message: String) =
        SetupState(SetupStatus.SUCCEEDED, SetupStep.NONE, message, SetupTarget.DOCKER)

    private fun failed(message: String) =
        SetupState(SetupStatus.FAILED, SetupStep.NONE, message, SetupTarget.DOCKER)
}
