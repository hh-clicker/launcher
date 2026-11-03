package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hhclicker.launcher.model.ContainerMode
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.DockerCliState
import org.hhclicker.launcher.model.DockerComposeState
import org.hhclicker.launcher.model.DockerDesktopProcessState
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.HostArchitecture

class DockerDiagnosticsParserTest {

    private fun succeeded(output: String) = CommandOutcome(started = true, exitCode = 0, output = output)

    @Test
    fun architecture_prefers_the_wow64_variable_on_emulated_x64() {
        // An x64 process on ARM64 Windows reports AMD64 in PROCESSOR_ARCHITECTURE.
        assertEquals(
            HostArchitecture.ARM_UNSUPPORTED,
            DockerDiagnosticsParser.architecture("ARM64", "AMD64", "amd64"),
        )
    }

    @Test
    fun architecture_recognises_x64_and_refuses_to_guess() {
        assertEquals(HostArchitecture.X64_SUPPORTED, DockerDiagnosticsParser.architecture(null, "AMD64", "amd64"))
        assertEquals(HostArchitecture.X64_SUPPORTED, DockerDiagnosticsParser.architecture(null, null, "x86_64"))
        assertEquals(HostArchitecture.ARM_UNSUPPORTED, DockerDiagnosticsParser.architecture(null, "ARM64", null))
        assertEquals(HostArchitecture.UNKNOWN, DockerDiagnosticsParser.architecture(null, null, null))
        assertEquals(HostArchitecture.UNKNOWN, DockerDiagnosticsParser.architecture(null, "x86", null))
    }

    @Test
    fun cli_version_is_read_from_the_docker_banner() {
        val probe = DockerDiagnosticsParser.cli(succeeded("Docker version 27.3.1, build ce12230"))
        assertEquals(DockerCliState.AVAILABLE, probe.state)
        assertEquals("27.3.1", probe.version)
    }

    @Test
    fun a_missing_cli_and_a_failing_cli_are_different() {
        assertEquals(DockerCliState.MISSING, DockerDiagnosticsParser.cli(CommandOutcome.failedToStart()).state)
        assertEquals(
            DockerCliState.FAILED,
            DockerDiagnosticsParser.cli(CommandOutcome(started = true, exitCode = 1, output = "error")).state,
        )
        assertEquals(
            DockerCliState.FAILED,
            DockerDiagnosticsParser.cli(CommandOutcome(started = true, exitCode = null, output = "", timedOut = true)).state,
        )
    }

    @Test
    fun compose_v2_is_accepted() {
        val probe = DockerDiagnosticsParser.compose(succeeded("Docker Compose version v2.29.7"))
        assertEquals(DockerComposeState.AVAILABLE, probe.state)
        assertEquals("2.29.7", probe.version)
    }

    @Test
    fun compose_v1_is_not_accepted_as_compose_v2() {
        val probe = DockerDiagnosticsParser.compose(succeeded("docker-compose version 1.29.2, build 5becea4c"))
        assertEquals(DockerComposeState.MISSING, probe.state)
    }

    @Test
    fun an_unavailable_compose_subcommand_is_missing_not_failed() {
        val outcome = CommandOutcome(started = true, exitCode = 1, output = "docker: 'compose' is not a docker command")
        assertEquals(DockerComposeState.MISSING, DockerDiagnosticsParser.compose(outcome).state)
    }

    @Test
    fun engine_reports_linux_containers_and_the_server_version() {
        val probe = DockerDiagnosticsParser.engine(succeeded("linux|27.3.1"), DockerDesktopProcessState.RUNNING)
        assertEquals(DockerEngineState.RUNNING, probe.state)
        assertEquals(ContainerMode.LINUX, probe.containerMode)
        assertEquals("27.3.1", probe.serverVersion)
    }

    @Test
    fun engine_reports_windows_containers_as_such() {
        val probe = DockerDiagnosticsParser.engine(succeeded("windows|27.3.1"), DockerDesktopProcessState.RUNNING)
        assertEquals(DockerEngineState.RUNNING, probe.state)
        assertEquals(ContainerMode.WINDOWS, probe.containerMode)
    }

    @Test
    fun a_failing_info_while_desktop_runs_means_the_engine_is_starting() {
        val failing = CommandOutcome(started = true, exitCode = 1, output = "cannot connect to the Docker daemon")
        assertEquals(
            DockerEngineState.STARTING,
            DockerDiagnosticsParser.engine(failing, DockerDesktopProcessState.RUNNING).state,
        )
        assertEquals(
            DockerEngineState.UNAVAILABLE,
            DockerDiagnosticsParser.engine(failing, DockerDesktopProcessState.STOPPED).state,
        )
    }

    @Test
    fun an_unreadable_info_call_is_a_failed_check() {
        assertEquals(
            DockerEngineState.FAILED,
            DockerDiagnosticsParser.engine(CommandOutcome.failedToStart(), DockerDesktopProcessState.UNKNOWN).state,
        )
        assertEquals(
            DockerEngineState.FAILED,
            DockerDiagnosticsParser.engine(succeeded("no separator here"), DockerDesktopProcessState.RUNNING).state,
        )
    }

    @Test
    fun backend_is_read_from_the_current_settings_store() {
        val store = """{"wslEngineEnabled":true,"autoStart":false}"""
        assertEquals(DockerBackend.WSL_2, DockerDiagnosticsParser.backend(store, null))
    }

    @Test
    fun the_legacy_settings_file_is_used_only_as_a_fallback() {
        val legacy = """{"wslEngineEnabled":true}"""
        assertEquals(DockerBackend.WSL_2, DockerDiagnosticsParser.backend(null, legacy))

        // The current file wins when both are present.
        val store = """{"wslEngineEnabled":false}"""
        assertEquals(DockerBackend.NOT_WSL_2, DockerDiagnosticsParser.backend(store, legacy))
    }

    @Test
    fun a_disabled_wsl_engine_is_not_wsl2() {
        assertEquals(DockerBackend.NOT_WSL_2, DockerDiagnosticsParser.backend("""{"wslEngineEnabled":false}""", null))
    }

    @Test
    fun a_missing_key_or_file_is_unknown_and_never_wsl2() {
        assertEquals(DockerBackend.UNKNOWN, DockerDiagnosticsParser.backend(null, null))
        assertEquals(DockerBackend.UNKNOWN, DockerDiagnosticsParser.backend("{}", null))
        assertEquals(DockerBackend.UNKNOWN, DockerDiagnosticsParser.backend("not json at all", null))
        // A non-boolean value must not be coerced into a yes.
        assertEquals(DockerBackend.UNKNOWN, DockerDiagnosticsParser.backend("""{"wslEngineEnabled":"true"}""", null))
    }

    @Test
    fun a_valid_settings_file_can_use_the_callers_versioned_default() {
        assertEquals(
            DockerBackend.WSL_2,
            DockerDiagnosticsParser.backend(
                settingsStoreJson = "{}",
                legacySettingsJson = null,
                defaultWhenAbsent = DockerBackend.WSL_2,
            ),
        )
        assertEquals(
            DockerBackend.UNKNOWN,
            DockerDiagnosticsParser.backend(
                settingsStoreJson = "not json",
                legacySettingsJson = null,
                defaultWhenAbsent = DockerBackend.WSL_2,
            ),
        )
        assertEquals(
            DockerBackend.UNKNOWN,
            DockerDiagnosticsParser.backend(
                settingsStoreJson = """{"wslEngineEnabled":"true"}""",
                legacySettingsJson = null,
                defaultWhenAbsent = DockerBackend.WSL_2,
            ),
        )
    }

    @Test
    fun the_key_is_only_honoured_as_json_not_as_text() {
        // The literal name appears inside an unrelated string value; a textual match would answer wrongly.
        val misleading = """{"note":"wslEngineEnabled: true is what we want"}"""
        assertEquals(DockerBackend.UNKNOWN, DockerDiagnosticsParser.backend(misleading, null))
    }

    @Test
    fun desktop_state_combines_presence_and_version() {
        assertEquals(DockerDesktopState.NOT_INSTALLED, DockerDiagnosticsParser.desktopState(false, null))
        assertEquals(DockerDesktopState.OUTDATED, DockerDiagnosticsParser.desktopState(true, "3.6.0"))
        assertEquals(DockerDesktopState.INSTALLED, DockerDiagnosticsParser.desktopState(true, "4.34.2"))
        // An unreadable version must not be turned into "outdated" by guessing.
        assertEquals(DockerDesktopState.INSTALLED, DockerDiagnosticsParser.desktopState(true, null))
    }

    @Test
    fun compose_without_a_version_number_is_a_failed_check() {
        val probe = DockerDiagnosticsParser.compose(succeeded("Docker Compose version"))
        assertEquals(DockerComposeState.FAILED, probe.state)
        assertNull(probe.version)
    }
}
