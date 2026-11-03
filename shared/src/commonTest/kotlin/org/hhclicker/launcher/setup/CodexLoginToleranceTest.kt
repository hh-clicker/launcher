package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.CodexAuthState
import org.hhclicker.launcher.model.CodexAuthStatus

/**
 * A device-code login lasts minutes and the user spends them in a browser tab. One request that never
 * came back says nothing about it, and used to end the whole authorization.
 */
class CodexLoginToleranceTest {

    private val authorizing = CodexAuthState(
        status = CodexAuthStatus.AUTHORIZING,
        verificationUrl = "https://auth.openai.com/codex/device",
        userCode = "ABCD-1234",
    )

    @Test
    fun one_transient_failure_keeps_the_authorization_on_screen() {
        val decision = CodexLoginTolerance.next(authorizing, CodexLoginPoll.Unavailable(), consecutiveFailures = 0)

        assertEquals(authorizing, decision.state)
        assertEquals(CodexAuthStatus.AUTHORIZING, decision.state.status)
        assertEquals(1, decision.consecutiveFailures)
    }

    @Test
    fun a_failure_followed_by_a_pending_answer_continues_the_normal_flow() {
        val afterFailure = CodexLoginTolerance.next(authorizing, CodexLoginPoll.Unavailable(), consecutiveFailures = 0)

        val pending = authorizing.copy(userCode = "EFGH-5678")
        val afterAnswer = CodexLoginTolerance.next(
            afterFailure.state,
            CodexLoginPoll.Answered(pending),
            consecutiveFailures = afterFailure.consecutiveFailures,
        )

        assertEquals(pending, afterAnswer.state)
        // The counter resets, so an unstable connection cannot accumulate its way to a false failure.
        assertEquals(0, afterAnswer.consecutiveFailures)
    }

    @Test
    fun a_real_server_failure_ends_the_authorization_immediately() {
        val serverFailure = CodexAuthState(CodexAuthStatus.FAILED, message = "Авторизация Codex не завершена")

        val decision = CodexLoginTolerance.next(
            authorizing,
            CodexLoginPoll.Answered(serverFailure),
            consecutiveFailures = 0,
        )

        assertEquals(serverFailure, decision.state)
        assertEquals(0, decision.consecutiveFailures)
    }

    @Test
    fun enough_consecutive_silence_is_finally_reported_as_a_failure() {
        var state = authorizing
        var failures = 0
        repeat(CodexLoginTolerance.MAX_CONSECUTIVE_FAILURES - 1) {
            val decision = CodexLoginTolerance.next(state, CodexLoginPoll.Unavailable(), failures)
            state = decision.state
            failures = decision.consecutiveFailures
            assertEquals(CodexAuthStatus.AUTHORIZING, state.status)
        }

        val last = CodexLoginTolerance.next(state, CodexLoginPoll.Unavailable(), failures)

        assertEquals(CodexAuthStatus.FAILED, last.state.status)
        assertEquals(CodexLoginTolerance.MAX_CONSECUTIVE_FAILURES, last.consecutiveFailures)
        assertTrue(last.state.message.orEmpty().isNotBlank())
    }

    @Test
    fun a_successful_answer_after_the_limit_would_still_be_accepted() {
        val signedIn = CodexAuthState(CodexAuthStatus.SIGNED_IN, message = "Codex подключён")

        val decision = CodexLoginTolerance.next(
            CodexAuthState(CodexAuthStatus.FAILED),
            CodexLoginPoll.Answered(signedIn),
            consecutiveFailures = CodexLoginTolerance.MAX_CONSECUTIVE_FAILURES,
        )

        assertEquals(signedIn, decision.state)
        assertEquals(0, decision.consecutiveFailures)
    }
}
