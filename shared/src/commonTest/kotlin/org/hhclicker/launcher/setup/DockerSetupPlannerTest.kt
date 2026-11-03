package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.model.SetupTarget
import org.hhclicker.launcher.model.VirtualizationState
import org.hhclicker.launcher.model.WslDefaultVersionState
import org.hhclicker.launcher.model.WslDiagnostics
import org.hhclicker.launcher.model.WslInstallationState

class DockerSetupPlannerTest {

    private val readyWsl = WslDiagnostics(
        virtualization = VirtualizationState.ENABLED,
        installation = WslInstallationState.COMPATIBLE,
        defaultVersion = WslDefaultVersionState.VERSION_2,
        installedVersion = "2.3.24.0",
    )

    private val unreadyWsl = readyWsl.copy(installation = WslInstallationState.NOT_INSTALLED)

    private fun docker(
        architecture: HostArchitecture = HostArchitecture.X64_SUPPORTED,
        desktop: DockerDesktopState = DockerDesktopState.INSTALLED,
        desktopVersion: String? = "4.45.0",
        cli: DockerCliState = DockerCliState.AVAILABLE,
        compose: DockerComposeState = DockerComposeState.AVAILABLE,
        desktopProcess: DockerDesktopProcessState = DockerDesktopProcessState.RUNNING,
        engine: DockerEngineState = DockerEngineState.RUNNING,
        containerMode: ContainerMode = ContainerMode.LINUX,
        backend: DockerBackend = DockerBackend.WSL_2,
        installScope: DockerInstallScope = DockerInstallScope.PER_USER,
    ) = DockerDiagnostics(
        architecture = architecture,
        desktop = desktop,
        desktopVersion = desktopVersion,
        cli = cli,
        compose = compose,
        desktopProcess = desktopProcess,
        engine = engine,
        containerMode = containerMode,
        backend = backend,
        installScope = installScope,
    )

    // --- gating -----------------------------------------------------------------------------------

    @Test
    fun nothing_is_decided_before_the_first_check() {
        assertEquals(DockerSetupAction.NONE, DockerSetupPlanner.decide(readyWsl, null))
    }

    @Test
    fun docker_is_never_offered_before_wsl_is_completely_ready() {
        val action = DockerSetupPlanner.decide(unreadyWsl, docker(desktop = DockerDesktopState.NOT_INSTALLED, cli = DockerCliState.MISSING))
        assertEquals(DockerSetupAction.BLOCKED_BY_WSL, action)
        assertFalse(action.executable)

        assertEquals(
            DockerSetupAction.BLOCKED_BY_WSL,
            DockerSetupPlanner.decide(null, docker()),
        )
        assertEquals(
            DockerSetupAction.BLOCKED_BY_WSL,
            DockerSetupPlanner.decide(readyWsl.copy(defaultVersion = WslDefaultVersionState.UNKNOWN), docker()),
        )
    }

    @Test
    fun automatic_installation_is_refused_outside_x64() {
        listOf(HostArchitecture.ARM_UNSUPPORTED, HostArchitecture.UNKNOWN).forEach { architecture ->
            val action = DockerSetupPlanner.decide(
                readyWsl,
                docker(architecture = architecture, desktop = DockerDesktopState.NOT_INSTALLED, cli = DockerCliState.MISSING),
            )
            assertEquals(DockerSetupAction.BLOCKED_BY_ARCHITECTURE, action, "architecture $architecture")
            assertFalse(action.executable)
        }
    }

    // --- installation and update ------------------------------------------------------------------

    @Test
    fun a_clean_machine_with_ready_wsl_is_offered_one_installation() {
        val action = DockerSetupPlanner.decide(
            readyWsl,
            docker(desktop = DockerDesktopState.NOT_INSTALLED, cli = DockerCliState.MISSING, engine = DockerEngineState.UNAVAILABLE),
        )
        assertEquals(DockerSetupAction.INSTALL, action)
        assertTrue(action.executable)
        assertTrue(action.requiresLicenseAcceptance)
    }

    @Test
    fun a_version_below_the_minimum_is_offered_an_update() {
        val action = DockerSetupPlanner.decide(readyWsl, docker(desktop = DockerDesktopState.OUTDATED, desktopVersion = "3.6.0"))
        assertEquals(DockerSetupAction.UPDATE, action)
        assertTrue(action.requiresLicenseAcceptance)
    }

    @Test
    fun an_update_is_refused_when_the_existing_installation_scope_is_unknown() {
        val action = DockerSetupPlanner.decide(
            readyWsl,
            docker(
                desktop = DockerDesktopState.OUTDATED,
                desktopVersion = "3.6.0",
                installScope = DockerInstallScope.UNKNOWN,
            ),
        )
        assertEquals(DockerSetupAction.DIAGNOSTICS_FAILED, action)
        assertFalse(action.executable)
    }

    @Test
    fun a_cli_without_docker_desktop_is_its_own_error_and_never_a_reinstall() {
        val action = DockerSetupPlanner.decide(
            readyWsl,
            docker(desktop = DockerDesktopState.NOT_INSTALLED, cli = DockerCliState.AVAILABLE),
        )
        assertEquals(DockerSetupAction.CLI_WITHOUT_DESKTOP, action)
        assertFalse(action.executable)
    }

    @Test
    fun a_compatible_docker_is_never_reinstalled() {
        val action = DockerSetupPlanner.decide(readyWsl, docker())
        assertEquals(DockerSetupAction.NONE, action)
        assertFalse(action.executable)
    }

    @Test
    fun an_old_but_working_docker_is_left_alone() {
        // 4.20 is below the recommendation and above the minimum: a warning, not an action.
        val action = DockerSetupPlanner.decide(readyWsl, docker(desktopVersion = "4.20.0"))
        assertEquals(DockerSetupAction.NONE, action)
    }

    // --- existing installation --------------------------------------------------------------------

    @Test
    fun a_stopped_desktop_is_only_started() {
        val action = DockerSetupPlanner.decide(
            readyWsl,
            docker(
                desktopProcess = DockerDesktopProcessState.STOPPED,
                engine = DockerEngineState.UNAVAILABLE,
                containerMode = ContainerMode.UNKNOWN,
                backend = DockerBackend.UNKNOWN,
            ),
        )
        assertEquals(DockerSetupAction.START_DESKTOP, action)
        assertFalse(action.requiresLicenseAcceptance)
    }

    @Test
    fun a_booting_engine_is_waited_for_rather_than_restarted() {
        assertEquals(
            DockerSetupAction.WAIT_FOR_ENGINE,
            DockerSetupPlanner.decide(readyWsl, docker(engine = DockerEngineState.STARTING)),
        )
        assertEquals(
            DockerSetupAction.WAIT_FOR_ENGINE,
            DockerSetupPlanner.decide(
                readyWsl,
                docker(engine = DockerEngineState.UNAVAILABLE, desktopProcess = DockerDesktopProcessState.RUNNING),
            ),
        )
    }

    @Test
    fun windows_containers_are_never_treated_as_readiness() {
        val action = DockerSetupPlanner.decide(readyWsl, docker(containerMode = ContainerMode.WINDOWS))
        assertEquals(DockerSetupAction.BLOCKED_BY_CONTAINER_MODE, action)
        assertFalse(action.executable)
    }

    @Test
    fun a_non_wsl2_backend_is_a_blocker_with_a_precise_instruction() {
        val action = DockerSetupPlanner.decide(readyWsl, docker(backend = DockerBackend.NOT_WSL_2))
        assertEquals(DockerSetupAction.BLOCKED_BY_BACKEND, action)
        val message = DockerSetupPlanner.refusalState(action).message.orEmpty()
        assertTrue(message.contains("WSL 2"), message)
        assertTrue(message.contains("Use WSL 2 based engine"), message)
    }

    @Test
    fun an_unconfirmed_backend_is_never_reported_as_ready() {
        val diagnostics = docker(backend = DockerBackend.UNKNOWN)
        assertFalse(diagnostics.ready)
        val action = DockerSetupPlanner.decide(readyWsl, diagnostics)
        assertEquals(DockerSetupAction.BACKEND_UNCONFIRMED, action)
        assertFalse(action.executable)
    }

    @Test
    fun a_missing_compose_v2_is_offered_an_update() {
        assertEquals(
            DockerSetupAction.UPDATE,
            DockerSetupPlanner.decide(readyWsl, docker(compose = DockerComposeState.MISSING)),
        )
        assertEquals(
            DockerSetupAction.DIAGNOSTICS_FAILED,
            DockerSetupPlanner.decide(readyWsl, docker(compose = DockerComposeState.FAILED)),
        )
    }

    @Test
    fun a_failed_docker_check_never_turns_into_an_installation() {
        val action = DockerSetupPlanner.decide(readyWsl, docker(desktop = DockerDesktopState.CHECK_FAILED))
        assertEquals(DockerSetupAction.DIAGNOSTICS_FAILED, action)
        assertFalse(action.executable)
    }

    // --- state transitions ------------------------------------------------------------------------

    @Test
    fun steps_match_the_executable_actions() {
        assertEquals(SetupStep.DOWNLOADING_DOCKER, DockerSetupPlanner.stepFor(DockerSetupAction.INSTALL))
        assertEquals(SetupStep.DOWNLOADING_DOCKER, DockerSetupPlanner.stepFor(DockerSetupAction.UPDATE))
        assertEquals(SetupStep.STARTING_DOCKER, DockerSetupPlanner.stepFor(DockerSetupAction.START_DESKTOP))
        assertEquals(SetupStep.WAITING_FOR_DOCKER_ENGINE, DockerSetupPlanner.stepFor(DockerSetupAction.WAIT_FOR_ENGINE))
        assertEquals(SetupStep.NONE, DockerSetupPlanner.stepFor(DockerSetupAction.BLOCKED_BY_WSL))
    }

    @Test
    fun every_docker_state_is_tagged_with_the_docker_target() {
        assertEquals(SetupTarget.DOCKER, DockerSetupPlanner.preparingState(DockerSetupAction.INSTALL).target)
        assertEquals(SetupTarget.DOCKER, DockerSetupPlanner.rechecking().target)
        assertEquals(SetupTarget.DOCKER, DockerSetupPlanner.refusalState(DockerSetupAction.BLOCKED_BY_WSL).target)
        DockerPreparationOutcome.entries.forEach { outcome ->
            assertEquals(
                SetupTarget.DOCKER,
                DockerSetupPlanner.stateFor(DockerPreparationResult(outcome)).target,
                "outcome $outcome",
            )
        }
    }

    @Test
    fun download_progress_is_carried_in_the_state() {
        val state = DockerSetupPlanner.downloadState(downloadedBytes = 50_000_000, totalBytes = 200_000_000)
        assertEquals(SetupStatus.PREPARING, state.status)
        assertEquals(SetupStep.DOWNLOADING_DOCKER, state.step)
        assertEquals(25, state.progressPercent)
    }

    @Test
    fun progress_without_a_content_length_reports_no_percentage() {
        val state = DockerSetupPlanner.downloadState(downloadedBytes = 50_000_000, totalBytes = null)
        assertEquals(null, state.progressPercent)
        assertEquals(50_000_000L, state.downloadedBytes)
    }

    @Test
    fun every_outcome_produces_a_terminal_user_message() {
        DockerPreparationOutcome.entries.forEach { outcome ->
            val state = DockerSetupPlanner.stateFor(DockerPreparationResult(outcome))
            assertNotNull(state.message, "outcome $outcome has no message")
            assertFalse(state.inProgress, "outcome $outcome must be terminal")
        }
    }

    @Test
    fun readiness_outcomes_succeed_and_every_other_outcome_fails() {
        val ready = setOf(
            DockerPreparationOutcome.INSTALLED_AND_READY,
            DockerPreparationOutcome.STARTED_AND_READY,
            DockerPreparationOutcome.ALREADY_READY,
        )
        DockerPreparationOutcome.entries.forEach { outcome ->
            val expected = if (outcome in ready) SetupStatus.SUCCEEDED else SetupStatus.FAILED
            assertEquals(expected, DockerSetupPlanner.stateFor(DockerPreparationResult(outcome)).status, "outcome $outcome")
        }
    }

    @Test
    fun failures_are_distinguishable_in_the_message() {
        val http = DockerSetupPlanner.stateFor(
            DockerPreparationResult(DockerPreparationOutcome.DOWNLOAD_FAILED, httpStatus = 503),
        )
        assertTrue(http.message.orEmpty().contains("503"), http.message.orEmpty())

        val installer = DockerSetupPlanner.stateFor(
            DockerPreparationResult(DockerPreparationOutcome.INSTALLER_FAILED, exitCode = 4),
        )
        assertTrue(installer.message.orEmpty().contains("4"), installer.message.orEmpty())

        val signature = DockerSetupPlanner.stateFor(DockerPreparationResult(DockerPreparationOutcome.SIGNATURE_REJECTED))
        assertTrue(signature.message.orEmpty().contains("Docker Inc"), signature.message.orEmpty())

        val timeout = DockerSetupPlanner.stateFor(DockerPreparationResult(DockerPreparationOutcome.ENGINE_TIMEOUT))
        assertTrue(timeout.message.orEmpty().contains("Engine"), timeout.message.orEmpty())
    }

    @Test
    fun the_licence_is_named_and_linked_before_any_installation() {
        assertTrue(DockerSetupPlanner.LICENSE_NOTICE.contains("Docker Subscription Service Agreement"))
        assertEquals(
            "https://www.docker.com/legal/docker-subscription-service-agreement/",
            DockerSetupPlanner.LICENSE_URL,
        )
        assertTrue(DockerSetupPlanner.INSTALL_BUTTON_LABEL.contains("Принять"))
        assertTrue(DockerSetupAction.INSTALL.requiresLicenseAcceptance)
        assertTrue(DockerSetupAction.UPDATE.requiresLicenseAcceptance)
        assertFalse(DockerSetupAction.START_DESKTOP.requiresLicenseAcceptance)
        assertFalse(DockerSetupAction.WAIT_FOR_ENGINE.requiresLicenseAcceptance)
    }
}
