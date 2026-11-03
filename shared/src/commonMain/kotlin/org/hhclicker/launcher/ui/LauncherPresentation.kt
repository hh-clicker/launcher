package org.hhclicker.launcher.ui

import org.hhclicker.launcher.model.ComponentStatus
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.LauncherStatus
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.model.SetupStatus

/**
 * The sentences the main screen shows, kept apart from the Compose tree so they can be unit tested.
 *
 * They are the only place that decides what a user is told about a failure, which is why they live in
 * one file: a reason that is computed and then never rendered is the same as no reason at all.
 */

internal val LauncherState.headline: String
    get() = when (preflight.status) {
        PreflightStatus.NOT_CHECKED, PreflightStatus.CHECKING -> "Проверяем окружение"
        PreflightStatus.BLOCKED -> "Нужна настройка"
        PreflightStatus.READY -> when (status) {
            LauncherStatus.STOPPED -> if (components.any { it.status == ComponentStatus.RUNNING }) {
                "Часть сервисов работает"
            } else {
                "Готов к запуску"
            }
            LauncherStatus.STARTING -> "Запускаем сервисы"
            LauncherStatus.RUNNING -> "Сервисы работают"
            LauncherStatus.STOPPING -> "Останавливаем сервисы"
            LauncherStatus.FAILED -> "Нужна проверка"
        }
    }

/**
 * The line under the headline.
 *
 * A blocked environment and a failed operation are independent facts and can hold at the same time — a
 * start fails, and the check that follows finds the engine gone. The blocking check stayed visible while
 * the failure was dropped, so the user saw what to fix but never what had actually gone wrong. Both are
 * shown now, in that order, and neither replaces the other.
 */
internal val LauncherState.supportingText: String
    get() = when (preflight.status) {
        PreflightStatus.NOT_CHECKED, PreflightStatus.CHECKING -> "Ищем Docker, Node.js, Chrome и конфигурацию"
        PreflightStatus.BLOCKED -> when {
            setup.status == SetupStatus.RESTART_REQUIRED -> setup.message ?: "Перезагрузите компьютер и запустите лаунчер снова"
            else -> listOfNotNull(
                preflight.blockingCheck?.message ?: "Не все обязательные зависимости доступны",
                error?.takeIf { status == LauncherStatus.FAILED },
            ).joinToString(REASON_SEPARATOR)
        }
        PreflightStatus.READY -> when (status) {
            LauncherStatus.STOPPED -> if (components.any { it.status == ComponentStatus.RUNNING }) {
                "${components.count { it.status == ComponentStatus.RUNNING }} из ${components.size} компонентов уже работают"
            } else {
                "Все компоненты сейчас остановлены"
            }
            LauncherStatus.STARTING -> "Подготавливаем и запускаем Backend, Frontend и PostgreSQL"
            LauncherStatus.RUNNING -> "Все компоненты доступны и готовы к работе"
            LauncherStatus.STOPPING -> "Корректно завершаем Docker Compose project"
            LauncherStatus.FAILED -> error ?: "Один из компонентов не удалось запустить"
        }
    }

internal val LauncherState.primaryActionLabel: String
    get() = when (preflight.status) {
        PreflightStatus.NOT_CHECKED, PreflightStatus.CHECKING -> "Проверяем…"
        PreflightStatus.BLOCKED -> "Проверить снова"
        PreflightStatus.READY -> when (status) {
            LauncherStatus.STOPPED -> "Запустить"
            LauncherStatus.FAILED -> "Остановить"
            LauncherStatus.STARTING -> "Запускаем…"
            LauncherStatus.RUNNING -> "Открыть HH Clicker"
            LauncherStatus.STOPPING -> "Остановка…"
        }
    }

internal val LauncherState.summaryLabel: String
    get() = when (preflight.status) {
        PreflightStatus.NOT_CHECKED, PreflightStatus.CHECKING -> "Проверка окружения"
        PreflightStatus.BLOCKED -> "Окружение не готово"
        PreflightStatus.READY -> when (status) {
            LauncherStatus.STOPPED -> if (components.any { it.status == ComponentStatus.RUNNING }) {
                "${components.count { it.status == ComponentStatus.RUNNING }} из ${components.size} работают"
            } else {
                "Все остановлены"
            }
            LauncherStatus.STARTING -> "Запуск компонентов"
            LauncherStatus.RUNNING -> "${components.count { it.status == ComponentStatus.RUNNING }} из ${components.size} работают"
            LauncherStatus.STOPPING -> "Остановка компонентов"
            LauncherStatus.FAILED -> "Есть ошибка"
        }
    }

/** Joins two independent reasons on one line without either of them looking like a continuation. */
internal const val REASON_SEPARATOR = " · "
