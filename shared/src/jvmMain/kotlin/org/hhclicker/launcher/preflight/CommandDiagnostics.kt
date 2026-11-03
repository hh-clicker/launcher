package org.hhclicker.launcher.preflight

/**
 * Turns a failed probe into one short sentence that is safe to show on screen.
 *
 * A completely swallowed failure is what let a permanently broken Docker query look like a temporary
 * hiccup for as long as it did, so the reason is kept. It is never kept verbatim: only the last output
 * line survives, assignments whose name looks like a credential are masked, file system paths are
 * replaced, and any caller supplied value — an instance id, a profile — is removed, because a Docker or
 * Compose error can quote a line of the environment file that also holds the database password.
 */
internal object CommandDiagnostics {
    private const val MAX_REASON_CHARACTERS = 160
    private const val MASK = "<скрыто>"
    private const val PATH_MASK = "<путь>"

    private val SECRET_ASSIGNMENT =
        Regex("""([A-Za-z0-9_]*(?:PASSWORD|SECRET|TOKEN|CREDENTIAL|APIKEY|API_KEY)[A-Za-z0-9_]*)\s*[=:]\s*\S+""", RegexOption.IGNORE_CASE)
    private val WINDOWS_PATH = Regex("""[A-Za-z]:[\\/][^\s'"]*""")
    /** A drive letter is lost as soon as the path contains a space, so any backslash token follows it. */
    private val WINDOWS_PATH_TAIL = Regex("""[^\s'"]*\\[^\s'"]*""")
    private val UNIX_PATH = Regex("""(?<![\w.])/[\w.+-]+(?:/[\w.+-]+)+""")

    /**
     * Describes a probe that did not succeed. [redactions] are values the caller knows are private, such
     * as the instance id; anything shorter than four characters is ignored so a short value cannot mask
     * unrelated text into nonsense.
     */
    fun describe(
        command: String,
        result: CommandProbeResult,
        redactions: Collection<String> = emptyList(),
    ): String = when {
        !result.started -> "$command не удалось запустить"
        result.timedOut -> "$command не ответил вовремя"
        else -> {
            val reason = reason(result.output, redactions)
            "$command завершился с кодом ${result.exitCode ?: "?"}" + (reason?.let { ": $it" } ?: "")
        }
    }

    /** Describes a probe that threw instead of returning; the exception message is redacted the same way. */
    fun describe(command: String, failure: Throwable, redactions: Collection<String> = emptyList()): String {
        val reason = failure.message?.let { reason(it, redactions) }
        return "$command прерван (${failure::class.simpleName ?: "ошибка"})" + (reason?.let { ": $it" } ?: "")
    }

    internal fun reason(output: String, redactions: Collection<String> = emptyList()): String? {
        val line = output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty) ?: return null
        var safe = line
        redactions.asSequence().filter { it.length >= 4 }.forEach { secret -> safe = safe.replace(secret, MASK) }
        safe = SECRET_ASSIGNMENT.replace(safe) { match -> "${match.groupValues[1]}=$MASK" }
        safe = WINDOWS_PATH.replace(safe, PATH_MASK)
        safe = WINDOWS_PATH_TAIL.replace(safe, PATH_MASK)
        safe = UNIX_PATH.replace(safe, PATH_MASK)
        return safe.trim().take(MAX_REASON_CHARACTERS).takeIf(String::isNotEmpty)
    }
}
