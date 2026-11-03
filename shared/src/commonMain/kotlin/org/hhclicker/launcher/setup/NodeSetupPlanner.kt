package org.hhclicker.launcher.setup

import org.hhclicker.launcher.model.NodeRuntimeDiagnostics
import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.model.SetupTarget

enum class NodeSetupAction { NONE, DOWNLOAD_PORTABLE, ALREADY_IN_PROGRESS }

enum class NodePreparationOutcome {
    INSTALLED_AND_READY, ALREADY_READY, DOWNLOAD_FAILED, DOWNLOAD_CANCELLED,
    DOWNLOAD_TOO_LARGE, CHECKSUM_NOT_FOUND, CHECKSUM_MISMATCH, ARCHIVE_INVALID, NOT_APPLICABLE,
}

data class NodePreparationResult(val outcome: NodePreparationOutcome, val httpStatus: Int? = null)

object NodeSetupPlanner {
    fun decide(node: NodeRuntimeDiagnostics?, preparationInProgress: Boolean = false): NodeSetupAction = when {
        node == null || node.ready -> NodeSetupAction.NONE
        preparationInProgress -> NodeSetupAction.ALREADY_IN_PROGRESS
        else -> NodeSetupAction.DOWNLOAD_PORTABLE
    }

    fun preparingState() = stageState(SetupStep.DOWNLOADING_NODE_CHECKSUMS)

    fun stageState(step: SetupStep) = SetupState(
        status = SetupStatus.PREPARING,
        target = SetupTarget.NODE,
        step = step,
        message = messageFor(step),
    )

    fun downloadState(downloaded: Long, total: Long?) = stageState(SetupStep.DOWNLOADING_NODE).copy(
        downloadedBytes = downloaded,
        totalBytes = total,
    )

    fun rechecking() = stageState(SetupStep.RECHECKING_ENVIRONMENT)

    fun messageFor(step: SetupStep): String = when (step) {
        SetupStep.DOWNLOADING_NODE_CHECKSUMS -> "Получаем официальную контрольную сумму Node.js"
        SetupStep.DOWNLOADING_NODE -> "Скачиваем portable Node.js"
        SetupStep.VERIFYING_NODE_ARCHIVE -> "Проверяем SHA-256 архива Node.js"
        SetupStep.INSTALLING_NODE_RUNTIME -> "Распаковываем локальный Node.js"
        SetupStep.RECHECKING_ENVIRONMENT -> "Повторяем проверку окружения"
        else -> "Подготовка Node.js"
    }

    fun stateFor(result: NodePreparationResult): SetupState = when (result.outcome) {
        NodePreparationOutcome.INSTALLED_AND_READY -> succeeded("Локальный portable Node.js готов")
        NodePreparationOutcome.ALREADY_READY -> succeeded("Совместимый Node.js уже доступен")
        NodePreparationOutcome.DOWNLOAD_FAILED -> failed(
            result.httpStatus?.let { "Сервер Node.js ответил кодом $it" }
                ?: "Не удалось скачать Node.js: проверьте интернет-соединение",
        )
        NodePreparationOutcome.DOWNLOAD_CANCELLED -> failed("Загрузка Node.js прервана")
        NodePreparationOutcome.DOWNLOAD_TOO_LARGE -> failed("Архив Node.js больше допустимого размера")
        NodePreparationOutcome.CHECKSUM_NOT_FOUND -> failed("Официальная контрольная сумма Node.js не найдена")
        NodePreparationOutcome.CHECKSUM_MISMATCH -> failed("SHA-256 архива Node.js не совпадает с официальным")
        NodePreparationOutcome.ARCHIVE_INVALID -> failed("Архив Node.js повреждён или не содержит node.exe")
        NodePreparationOutcome.NOT_APPLICABLE -> failed("Не удалось подготовить локальный Node.js")
    }

    fun refusalState() = failed("Подготовка Node.js уже выполняется")

    private fun succeeded(message: String) = SetupState(SetupStatus.SUCCEEDED, SetupStep.NONE, message, SetupTarget.NODE)
    private fun failed(message: String) = SetupState(SetupStatus.FAILED, SetupStep.NONE, message, SetupTarget.NODE)
}
