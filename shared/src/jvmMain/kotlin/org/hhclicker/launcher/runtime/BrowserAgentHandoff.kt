package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.hhclicker.launcher.setup.JsonValue
import org.hhclicker.launcher.setup.MiniJson

/**
 * What the launcher recorded about the Browser Agent process it started.
 *
 * A process id alone proves nothing: Windows reuses process ids, and by the time a new launcher reads
 * this file the number may belong to something else entirely. The start instant pins the identity — a
 * different process cannot have been created at the same millisecond as the one that owned this id — and
 * the executable path is a second, independent check.
 */
data class BrowserAgentRecord(
    val processId: Long,
    val startedAtEpochMilli: Long,
    val command: String,
    val agentId: String,
)

/** What the operating system currently says about the process holding a recorded id. */
data class RunningProcessFacts(
    val processId: Long,
    val startedAtEpochMilli: Long?,
    val command: String?,
)

/** Whether a recorded process may be terminated by this launcher. */
sealed interface BrowserAgentOwnership {
    /** No previous instance left anything behind. */
    data object NothingRecorded : BrowserAgentOwnership

    /** The recorded process is gone; only the stale record has to be removed. */
    data object ProcessGone : BrowserAgentOwnership

    /** Proven to be the Browser Agent of a previous launcher run. */
    data class Owned(val record: BrowserAgentRecord) : BrowserAgentOwnership

    /**
     * The process holding the recorded id is not ours, or ownership could not be proven.
     *
     * Either way it is left completely alone. Absence of evidence is never treated as permission: this
     * is the difference between reaping an abandoned agent and killing a stranger's `node.exe`.
     */
    data class NotOurs(val reason: String) : BrowserAgentOwnership
}

/**
 * The ownership decision, kept pure so every refusal path is unit tested without starting a process.
 */
object BrowserAgentOwnershipCheck {
    fun verdict(record: BrowserAgentRecord?, facts: RunningProcessFacts?): BrowserAgentOwnership = when {
        record == null -> BrowserAgentOwnership.NothingRecorded
        facts == null -> BrowserAgentOwnership.ProcessGone
        facts.processId != record.processId ->
            BrowserAgentOwnership.NotOurs("Проверен не тот процесс")

        facts.startedAtEpochMilli == null ->
            BrowserAgentOwnership.NotOurs("Время запуска процесса недоступно")

        facts.startedAtEpochMilli != record.startedAtEpochMilli ->
            BrowserAgentOwnership.NotOurs("Идентификатор процесса переиспользован другим процессом")

        facts.command == null ->
            BrowserAgentOwnership.NotOurs("Исполняемый файл процесса недоступен")

        !facts.command.equals(record.command, ignoreCase = true) ->
            BrowserAgentOwnership.NotOurs("Идентификатор процесса занят другим исполняемым файлом")

        else -> BrowserAgentOwnership.Owned(record)
    }
}

/** Outcome of the startup handover, surfaced for logging and tests rather than for the screen. */
enum class BrowserAgentReclaim {
    NOTHING_TO_DO,
    STALE_RECORD_REMOVED,
    TERMINATED,
    TERMINATION_FAILED,
    LEFT_ALONE,
}

/**
 * Stores the Browser Agent ownership record outside the launcher process.
 *
 * It lives next to the other launcher-owned state under LOCALAPPDATA, so a launcher that was killed
 * without running any shutdown code still leaves behind enough to identify what it owned.
 */
class BrowserAgentHandleStore(
    private val environment: (String) -> String? = System::getenv,
    private val fileOverride: Path? = null,
) {
    fun file(): Path? = fileOverride ?: LauncherDataPaths.applicationRoot(environment)?.let { root ->
        val current = root.resolve("data/runtime/${LauncherDataPaths.BROWSER_AGENT_HANDLE_FILE}")
        val legacy = root.resolve("runtime/${LauncherDataPaths.BROWSER_AGENT_HANDLE_FILE}")
        if (!Files.exists(current) && Files.isRegularFile(legacy)) legacy else current
    }

    fun read(): BrowserAgentRecord? {
        val path = file() ?: return null
        return runCatching {
            if (!Files.isRegularFile(path)) return@runCatching null
            if (Files.size(path) > MAX_HANDLE_BYTES) return@runCatching null
            val entries = (MiniJson.parseOrNull(Files.readString(path)) as? JsonValue.JsonObject)?.entries
                ?: return@runCatching null
            BrowserAgentRecord(
                processId = entries.number("processId") ?: return@runCatching null,
                startedAtEpochMilli = entries.number("startedAtEpochMilli") ?: return@runCatching null,
                command = entries.text("command") ?: return@runCatching null,
                agentId = entries.text("agentId") ?: return@runCatching null,
            )
        }.getOrNull()
    }

    fun write(record: BrowserAgentRecord): Boolean = runCatching {
        val path = file() ?: return false
        Files.createDirectories(path.parent)
        val document = JsonValue.JsonObject(
            linkedMapOf(
                "processId" to JsonValue.JsonNumber(record.processId.toString()),
                "startedAtEpochMilli" to JsonValue.JsonNumber(record.startedAtEpochMilli.toString()),
                "command" to JsonValue.JsonString(record.command),
                "agentId" to JsonValue.JsonString(record.agentId),
            ),
        )
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, MiniJson.stringify(document))
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    fun clear() {
        val path = file() ?: return
        runCatching { Files.deleteIfExists(path) }
    }

    private fun Map<String, JsonValue>.text(name: String): String? =
        (this[name] as? JsonValue.JsonString)?.value?.takeIf(String::isNotEmpty)

    private fun Map<String, JsonValue>.number(name: String): Long? =
        (this[name] as? JsonValue.JsonNumber)?.value?.toLongOrNull()

    private companion object {
        const val MAX_HANDLE_BYTES = 8_192L
    }
}
