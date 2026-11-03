package org.hhclicker.launcher.setup

import org.hhclicker.launcher.model.CodexAuthState
import org.hhclicker.launcher.model.CodexAuthStatus

/**
 * One poll of the Codex device-login endpoint.
 *
 * The two cases must stay apart. Only the Generation Agent may end an authorization; a request that
 * never reached it, a non-2xx answer or a body that does not parse says nothing about the login the user
 * is completing in their browser, and treating those as a failure aborted a perfectly healthy login on
 * the first network hiccup of a five-minute wait.
 */
sealed interface CodexLoginPoll {
    /** The Generation Agent answered and the answer was understood. This includes a real `failed`. */
    data class Answered(val state: CodexAuthState) : CodexLoginPoll

    /** The answer did not arrive or could not be read. Nothing may be concluded from it. */
    data class Unavailable(val diagnostics: String? = null) : CodexLoginPoll
}

/** New authentication state together with the running count of consecutive unanswered polls. */
data class CodexPollDecision(val state: CodexAuthState, val consecutiveFailures: Int)

/**
 * Keeps a device-code authorization alive across transient failures.
 *
 * The user cannot influence the outcome of one HTTP request; they are looking at a browser tab. The
 * launcher therefore keeps showing [CodexAuthStatus.AUTHORIZING] and retries, and only gives up after
 * enough consecutive silent polls that the Generation Agent is plausibly gone. Any answered poll resets
 * the count, so an unstable connection cannot accumulate its way to a false failure.
 */
object CodexLoginTolerance {

    /** At the current 1.5 second poll interval this is roughly ten seconds of silence. */
    const val MAX_CONSECUTIVE_FAILURES = 6

    const val LOST_CONTACT_MESSAGE =
        "Generation Agent перестал отвечать во время авторизации. Проверьте, что он запущен, и повторите"

    fun next(
        current: CodexAuthState,
        poll: CodexLoginPoll,
        consecutiveFailures: Int,
        maxConsecutiveFailures: Int = MAX_CONSECUTIVE_FAILURES,
    ): CodexPollDecision = when (poll) {
        is CodexLoginPoll.Answered -> CodexPollDecision(poll.state, consecutiveFailures = 0)

        is CodexLoginPoll.Unavailable -> {
            val failures = consecutiveFailures + 1
            if (failures >= maxConsecutiveFailures) {
                CodexPollDecision(
                    CodexAuthState(CodexAuthStatus.FAILED, message = LOST_CONTACT_MESSAGE),
                    consecutiveFailures = failures,
                )
            } else {
                // The visible state is left exactly as it was, so the polling loop keeps running.
                CodexPollDecision(current, consecutiveFailures = failures)
            }
        }
    }
}
