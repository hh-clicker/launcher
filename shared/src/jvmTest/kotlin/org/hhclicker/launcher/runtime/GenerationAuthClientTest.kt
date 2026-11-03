package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.CodexAuthStatus

class GenerationAuthClientTest {
    private val client = GenerationAuthClient()

    @Test
    fun limits_are_presented_as_remaining_and_check_failure_is_not_logout() {
        val state = client.parseAccountStatus("""{"authenticated":true,"method":"chatgpt","limits":{"primary":{"usedPercent":36,"windowDurationMins":300,"resetsAt":1789464317},"secondary":{"usedPercent":60,"windowDurationMins":10080,"resetsAt":1789829961}}}""")
        assertEquals("5 часов: осталось 64% · Неделя: осталось 40%", state?.limitsSummary)
        assertTrue(state?.limitsResetSummary?.contains("5 часов:") == true)
        val failure = client.parseAccountStatus("""{"authenticated":false,"error":{"code":"AUTH_CHECK_FAILED"}}""")
        assertEquals(CodexAuthStatus.FAILED, failure?.status)
        assertNull(failure?.limitsSummary)
    }

    @Test
    fun account_status_distinguishes_chatgpt_login_from_signed_out() {
        val signedIn = client.parseAccountStatus("""{"authenticated":true,"method":"chatgpt","error":null}""")
        val signedOut = client.parseAccountStatus("""{"authenticated":false,"method":null,"error":null}""")

        assertEquals(CodexAuthStatus.SIGNED_IN, signedIn?.status)
        assertEquals("chatgpt", signedIn?.method)
        assertEquals(CodexAuthStatus.SIGNED_OUT, signedOut?.status)
    }

    @Test
    fun rejected_credentials_require_a_new_login() {
        val state = client.parseAccountStatus(
            """{"authenticated":false,"method":null,"error":{"code":"AUTH_REQUIRED","message":"safe"}}""",
        )
        assertEquals(CodexAuthStatus.SIGNED_OUT, state?.status)
        assertEquals("Требуется повторный вход в Codex", state?.message)
    }

    @Test
    fun device_login_accepts_only_the_official_verification_address_and_safe_code() {
        val state = client.parseLoginState(
            """{"status":"pending","verificationUrl":"https://auth.openai.com/codex/device","userCode":"ABCD-1234"}""",
        )
        assertEquals(CodexAuthStatus.AUTHORIZING, state?.status)
        assertEquals("ABCD-1234", state?.userCode)
        assertTrue(client.trustedVerificationUrl("https://auth.openai.com/codex/device"))
        assertFalse(client.trustedVerificationUrl("https://evil.example/codex/device"))

        val rejected = client.parseLoginState(
            """{"status":"pending","verificationUrl":"https://evil.example/codex/device","userCode":"ABCD-1234"}""",
        )
        assertEquals(CodexAuthStatus.FAILED, rejected?.status)
    }

    @Test
    fun malformed_auth_json_is_not_treated_as_a_success() {
        assertNull(client.parseAccountStatus("{}"))
        assertNull(client.parseLoginState("not-json"))
    }

    @Test
    fun health_reports_the_running_codex_model_and_cli_version() {
        val runtime = client.parseRuntimeInfo(
            """{"ok":true,"model":"gpt-6-luna","reasoningEffort":"low","cliVersion":"0.163.0"}""",
        )
        assertEquals("gpt-6-luna", runtime?.model)
        assertEquals("low", runtime?.reasoningEffort)
        assertEquals("0.163.0", runtime?.cliVersion)
        assertNull(client.parseRuntimeInfo("""{"ok":false,"model":"gpt-6-luna"}"""))
        assertNull(client.parseRuntimeInfo("""{"ok":true,"model":" "}"""))
    }
}
