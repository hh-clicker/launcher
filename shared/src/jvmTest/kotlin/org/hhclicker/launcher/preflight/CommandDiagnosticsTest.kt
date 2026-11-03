package org.hhclicker.launcher.preflight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandDiagnosticsTest {

    @Test
    fun a_failed_command_keeps_its_reason() {
        val description = CommandDiagnostics.describe(
            "docker ps",
            CommandProbeResult(
                started = true,
                exitCode = 1,
                output = "failed to parse template: template: :1: function not defined",
            ),
        )

        assertTrue(description.contains("docker ps"), description)
        assertTrue(description.contains("1"), description)
        assertTrue(description.contains("failed to parse template"), description)
    }

    @Test
    fun a_command_that_did_not_start_and_one_that_timed_out_are_distinguishable() {
        assertEquals(
            "docker ps не удалось запустить",
            CommandDiagnostics.describe("docker ps", CommandProbeResult(false, null, "")),
        )
        assertEquals(
            "docker ps не ответил вовремя",
            CommandDiagnostics.describe("docker ps", CommandProbeResult(true, null, "", timedOut = true)),
        )
    }

    @Test
    fun credentials_never_reach_the_diagnostic() {
        val reason = CommandDiagnostics.reason(
            "error while reading env: POSTGRES_PASSWORD=s3cr3t-value CODEX_API_KEY=sk-abcdef",
        )

        assertFalse(reason!!.contains("s3cr3t-value"), reason)
        assertFalse(reason.contains("sk-abcdef"), reason)
        assertTrue(reason.contains("POSTGRES_PASSWORD"), reason)
    }

    @Test
    fun file_system_paths_are_removed() {
        val reason = CommandDiagnostics.reason(
            "open C:\\Projects\\hh-clicker\\infra\\.env and /home/user/secrets/config.json failed",
        )!!

        assertFalse(reason.contains("Projects"), reason)
        assertFalse(reason.contains("/home/user/secrets"), reason)
    }

    @Test
    fun a_caller_supplied_value_is_masked() {
        val reason = CommandDiagnostics.reason(
            "no such container for label com.hh-clicker.instance-id=aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            listOf("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
        )!!

        assertFalse(reason.contains("aaaaaaaa-bbbb"), reason)
    }

    @Test
    fun a_long_output_is_truncated_to_one_short_line() {
        val reason = CommandDiagnostics.reason("first line\n" + "x".repeat(4_000))!!

        assertTrue(reason.length <= 160, reason.length.toString())
        assertFalse(reason.contains("first line"), reason)
    }

    @Test
    fun a_thrown_probe_is_described_without_its_stack() {
        val description = CommandDiagnostics.describe("Проверка контейнеров", IllegalStateException("boom"))

        assertTrue(description.contains("IllegalStateException"), description)
        assertTrue(description.contains("boom"), description)
    }
}
