package org.hhclicker.launcher.controller

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.BundleKind
import org.hhclicker.launcher.model.CheckSeverity
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.model.ComponentStatus
import org.hhclicker.launcher.model.CodexAuthState
import org.hhclicker.launcher.model.CodexAuthStatus
import org.hhclicker.launcher.model.ContainerMode
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.DockerCliState
import org.hhclicker.launcher.model.DockerComposeState
import org.hhclicker.launcher.model.DockerDesktopProcessState
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.DockerDiagnostics
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.DockerServiceSnapshot
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.model.LauncherComponentState
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.LauncherStatus
import org.hhclicker.launcher.model.PortConflict
import org.hhclicker.launcher.model.PreflightState
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.VirtualizationState
import org.hhclicker.launcher.model.WslDefaultVersionState
import org.hhclicker.launcher.model.WslDiagnostics
import org.hhclicker.launcher.model.WslInstallationState
import org.hhclicker.launcher.preflight.EnvironmentPreflight
import org.hhclicker.launcher.preflight.ServiceEndpoints
import org.hhclicker.launcher.preflight.ServicePortInspection
import org.hhclicker.launcher.runtime.LauncherRuntimeConfiguration
import org.hhclicker.launcher.setup.DockerEngineProbe
import org.hhclicker.launcher.setup.DockerSetupAction
import org.hhclicker.launcher.setup.DockerSetupPlanner

class RuntimeReconciliationTest {

    // 6. Codex authorization must never suspend container observation.
    @Test
    fun codex_authorization_does_not_pause_runtime_monitoring() {
        val state = allRunning().copy(
            preflight = PreflightState.ready,
            codexAuth = CodexAuthState(CodexAuthStatus.AUTHORIZING),
        )

        assertTrue(canRefreshRuntime(state))
        assertTrue(
            runtimeSnapshotIsCurrent(
                revisionAtInspection = 7,
                revisionNow = 7,
                configurationAtInspection = configuration,
                configurationNow = configuration,
                latest = state,
            ),
        )

        // And the tick that follows still reports a container that stopped in the meantime.
        val components = reconcileRuntimeComponents(
            current = state.components,
            endpoints = endpoints,
            dockerServices = DockerServiceSnapshot.Observed(setOf("backend", "generation-agent")),
            browserAgentRunning = true,
        )
        assertEquals(ComponentStatus.STOPPED, components.single { it.id == ComponentId.FRONTEND }.status)
    }

    @Test
    fun docker_services_and_browser_process_are_reconciled_independently() {
        val components = reconcileRuntimeComponents(
            current = LauncherState.stopped().components,
            endpoints = ServiceEndpoints(
                backendPort = 18080,
                frontendPort = 3000,
                generationPort = 17375,
                backendUrl = "http://localhost:18080",
                frontendUrl = "http://localhost:3000",
                generationUrl = "http://localhost:17375",
            ),
            dockerServices = DockerServiceSnapshot.Observed(setOf("backend", "frontend")),
            browserAgentRunning = true,
        )

        assertEquals(ComponentStatus.RUNNING, components.single { it.id == ComponentId.BACKEND }.status)
        assertEquals(ComponentStatus.RUNNING, components.single { it.id == ComponentId.FRONTEND }.status)
        assertEquals(ComponentStatus.STOPPED, components.single { it.id == ComponentId.GENERATION_AGENT }.status)
        assertEquals(ComponentStatus.RUNNING, components.single { it.id == ComponentId.BROWSER_AGENT }.status)
        assertEquals("http://localhost:3000", components.single { it.id == ComponentId.FRONTEND }.location)
        assertEquals(LauncherStatus.STOPPED, reconciledLauncherStatus(components))
    }

    // 2. A container stopped by hand turns grey on the very next tick.
    @Test
    fun a_service_missing_from_a_trustworthy_snapshot_becomes_stopped() {
        val components = reconcileRuntimeComponents(
            current = allRunning().components,
            endpoints = endpoints,
            dockerServices = DockerServiceSnapshot.Observed(setOf("backend", "generation-agent")),
            browserAgentRunning = true,
        )

        assertEquals(ComponentStatus.STOPPED, components.single { it.id == ComponentId.FRONTEND }.status)
        assertEquals(ComponentStatus.RUNNING, components.single { it.id == ComponentId.BACKEND }.status)
        assertEquals(LauncherStatus.STOPPED, reconciledLauncherStatus(components))
    }

    // 3. Starting it again turns it green again, without any launcher operation.
    @Test
    fun a_service_that_appears_again_becomes_running_again() {
        val stoppedFrontend = allRunning().copy(
            components = allRunning().components.map { component ->
                if (component.id == ComponentId.FRONTEND) {
                    component.copy(status = ComponentStatus.STOPPED)
                } else {
                    component
                }
            },
        )

        val components = reconcileRuntimeComponents(
            current = stoppedFrontend.components,
            endpoints = endpoints,
            dockerServices = DockerServiceSnapshot.Observed(setOf("backend", "frontend", "generation-agent")),
            browserAgentRunning = true,
        )

        assertEquals(ComponentStatus.RUNNING, components.single { it.id == ComponentId.FRONTEND }.status)
        assertEquals(LauncherStatus.RUNNING, reconciledLauncherStatus(components))
    }

    // 4. A Docker query that could not be answered keeps the last trustworthy state.
    @Test
    fun an_unknown_snapshot_keeps_the_last_observed_docker_state() {
        val components = reconcileRuntimeComponents(
            current = allRunning().components,
            endpoints = endpoints,
            dockerServices = DockerServiceSnapshot.Unknown("docker ps завершился с кодом 1"),
            browserAgentRunning = false,
        )

        assertEquals(ComponentStatus.RUNNING, components.single { it.id == ComponentId.BACKEND }.status)
        assertEquals(ComponentStatus.RUNNING, components.single { it.id == ComponentId.FRONTEND }.status)
        assertEquals(ComponentStatus.RUNNING, components.single { it.id == ComponentId.GENERATION_AGENT }.status)
        // The Browser Agent is this launcher's own child process, so its answer is never undetermined.
        assertEquals(ComponentStatus.STOPPED, components.single { it.id == ComponentId.BROWSER_AGENT }.status)
    }

    // 5. A manual full re-check must not turn UNKNOWN into STOPPED either.
    @Test
    fun a_full_recheck_with_an_unknown_snapshot_does_not_grey_out_the_components() {
        val stopped = reconcileRuntimeComponents(
            current = allRunning().components,
            endpoints = endpoints,
            // What a full re-check produced before the fix: no Docker answer, reported as "nothing runs".
            dockerServices = DockerServiceSnapshot.Observed(emptySet()),
            browserAgentRunning = true,
        )
        assertEquals(ComponentStatus.STOPPED, stopped.single { it.id == ComponentId.FRONTEND }.status)

        val preserved = reconcileRuntimeComponents(
            current = allRunning().components,
            endpoints = endpoints,
            dockerServices = DockerServiceSnapshot.Unknown("docker ps не ответил вовремя"),
            browserAgentRunning = true,
        )
        assertEquals(ComponentStatus.RUNNING, preserved.single { it.id == ComponentId.FRONTEND }.status)
        assertEquals(LauncherStatus.RUNNING, reconciledLauncherStatus(preserved))
    }

    @Test
    fun an_undetermined_ports_row_reports_the_reason_and_never_blocks_the_start() {
        val state = EnvironmentPreflight(platform = HostPlatform.WINDOWS).withServiceInspection(
            state = PreflightState(status = PreflightStatus.READY, checks = listOf(portsCheckPlaceholder())),
            configuration = configuration,
            inspection = ServicePortInspection.unknown("docker ps завершился с кодом 1: connection refused"),
        )

        val row = state.checks.single { it.id == "service-ports" }
        assertFalse(row.passed)
        assertEquals(CheckSeverity.OPTIONAL, row.severity)
        assertEquals(PreflightStatus.READY, state.status)
        assertTrue(row.message.contains("connection refused"), row.message)
    }

    // A confirmed conflict is a completed query, so it keeps blocking the start.
    @Test
    fun a_confirmed_port_conflict_still_blocks_the_start() {
        val state = EnvironmentPreflight(platform = HostPlatform.WINDOWS).withServiceInspection(
            state = PreflightState(status = PreflightStatus.READY, checks = listOf(portsCheckPlaceholder())),
            configuration = configuration,
            inspection = ServicePortInspection(
                conflicts = listOf(
                    PortConflict(
                        component = ComponentId.FRONTEND,
                        label = "Frontend",
                        port = 80,
                        dockerContainerName = "other-frontend",
                        hhClickerProfile = "development",
                    ),
                ),
                services = DockerServiceSnapshot.Unknown("docker ps не ответил вовремя"),
            ),
        )

        val row = state.checks.single { it.id == "service-ports" }
        assertFalse(row.passed)
        assertEquals(CheckSeverity.REQUIRED, row.severity)
        assertEquals(PreflightStatus.BLOCKED, state.status)
    }

    // 7. A snapshot taken before a start, a stop or a preparation is discarded.
    @Test
    fun a_snapshot_taken_before_an_operation_is_discarded() {
        val idle = allRunning().copy(preflight = PreflightState.ready)

        assertTrue(
            runtimeSnapshotIsCurrent(
                revisionAtInspection = 3,
                revisionNow = 3,
                configurationAtInspection = configuration,
                configurationNow = configuration,
                latest = idle,
            ),
        )
        // startAll and stopAll bump the revision before they touch the runtime.
        assertFalse(
            runtimeSnapshotIsCurrent(
                revisionAtInspection = 3,
                revisionNow = 4,
                configurationAtInspection = configuration,
                configurationNow = configuration,
                latest = idle,
            ),
        )
        // selectFreePorts replaces the configuration the snapshot was taken against.
        assertFalse(
            runtimeSnapshotIsCurrent(
                revisionAtInspection = 3,
                revisionNow = 3,
                configurationAtInspection = configuration,
                configurationNow = configuration.copy(endpoints = endpoints.copy(frontendPort = 3001)),
                latest = idle,
            ),
        )
        // A start that is still running.
        assertFalse(
            runtimeSnapshotIsCurrent(
                revisionAtInspection = 3,
                revisionNow = 3,
                configurationAtInspection = configuration,
                configurationNow = configuration,
                latest = idle.copy(status = LauncherStatus.STARTING),
            ),
        )
        // A machine preparation that is still running.
        assertFalse(
            runtimeSnapshotIsCurrent(
                revisionAtInspection = 3,
                revisionNow = 3,
                configurationAtInspection = configuration,
                configurationNow = configuration,
                latest = idle.copy(setup = SetupState(status = SetupStatus.PREPARING)),
            ),
        )
    }

    // Closing Docker Desktop must reach the environment rows on its own, without a manual re-check.
    @Test
    fun a_lost_engine_turns_the_docker_row_red_and_blocks_the_preflight() {
        val healthy = PreflightState(
            status = PreflightStatus.READY,
            checks = listOf(engineCheckPlaceholder()),
            docker = readyDocker,
        )

        val lost = EnvironmentPreflight(platform = HostPlatform.WINDOWS).withEngineState(
            healthy,
            DockerEngineProbe(DockerEngineState.UNAVAILABLE),
        )

        val row = lost.checks.single { it.id == "docker-server" }
        assertFalse(row.passed)
        assertEquals(PreflightStatus.BLOCKED, lost.status)
        assertTrue(row.message.contains("Docker Desktop"), row.message)
        // Facts an engine probe never observed are not invented away.
        assertEquals(readyDocker.compose, lost.docker?.compose)
        assertEquals(readyDocker.backend, lost.docker?.backend)
    }

    @Test
    fun a_recovered_engine_makes_the_preflight_ready_again() {
        val blocked = PreflightState(
            status = PreflightStatus.BLOCKED,
            checks = listOf(engineCheckPlaceholder().copy(passed = false, message = "Engine недоступен")),
            docker = readyDocker.copy(
                engine = DockerEngineState.UNAVAILABLE,
                containerMode = ContainerMode.UNKNOWN,
                engineVersion = null,
            ),
        )

        val recovered = EnvironmentPreflight(platform = HostPlatform.WINDOWS).withEngineState(
            blocked,
            DockerEngineProbe(DockerEngineState.RUNNING, ContainerMode.LINUX, "27.0.3"),
        )

        assertTrue(recovered.checks.single { it.id == "docker-server" }.passed)
        assertEquals(PreflightStatus.READY, recovered.status)
    }

    @Test
    fun a_state_without_the_engine_row_is_left_untouched() {
        val state = PreflightState(status = PreflightStatus.READY, checks = listOf(portsCheckPlaceholder()))

        assertEquals(
            state,
            EnvironmentPreflight(platform = HostPlatform.WINDOWS).withEngineState(state, DockerEngineProbe(DockerEngineState.UNAVAILABLE)),
        )
    }

    @Test
    fun the_engine_is_asked_exactly_when_the_picture_does_not_add_up() {
        // Healthy path: the container query answered and the engine is known to be ready.
        assertFalse(
            shouldProbeDockerEngine(
                snapshot = DockerServiceSnapshot.Observed(setOf("backend")),
                docker = readyDocker,
                launcherFailed = false,
            ),
        )
        // The container query could not answer.
        assertTrue(
            shouldProbeDockerEngine(
                snapshot = DockerServiceSnapshot.Unknown("docker ps завершился с кодом 1"),
                docker = readyDocker,
                launcherFailed = false,
            ),
        )
        // The engine is not currently known to be ready, so recovery must be noticed automatically.
        assertTrue(
            shouldProbeDockerEngine(
                snapshot = DockerServiceSnapshot.Observed(emptySet()),
                docker = readyDocker.copy(engine = DockerEngineState.UNAVAILABLE),
                launcherFailed = false,
            ),
        )
        // A displayed failure that nothing on the settings screen explains.
        assertTrue(
            shouldProbeDockerEngine(snapshot = null, docker = readyDocker, launcherFailed = true),
        )
        assertFalse(
            shouldProbeDockerEngine(snapshot = null, docker = readyDocker, launcherFailed = false),
        )
    }

    // A failed operation stops component reconciliation but must not stop diagnosing the machine.
    @Test
    fun a_failed_launcher_still_gets_diagnosed() {
        val failed = allRunning().copy(status = LauncherStatus.FAILED, preflight = PreflightState.ready)

        assertFalse(canRefreshRuntime(failed))
        assertTrue(canRefreshDiagnostics(failed))

        // Neither runs while an operation or a machine preparation owns the runtime.
        val starting = failed.copy(status = LauncherStatus.STARTING)
        assertFalse(canRefreshDiagnostics(starting))
        assertFalse(canRefreshDiagnostics(failed.copy(setup = SetupState(status = SetupStatus.PREPARING))))
        assertFalse(canRefreshDiagnostics(failed.copy(preflight = PreflightState(PreflightStatus.CHECKING))))
    }

    /**
     * The screenshot this came from: Docker Desktop was killed, the engine row went red, and the setup
     * card still offered "Дождаться Docker Engine" — a wait for an application nobody was going to start.
     */
    @Test
    fun a_lost_engine_offers_to_start_docker_desktop_instead_of_waiting_for_it() {
        val healthy = PreflightState(
            status = PreflightStatus.READY,
            checks = listOf(engineCheckPlaceholder()),
            docker = readyDocker,
        )

        val lost = EnvironmentPreflight(platform = HostPlatform.WINDOWS).withEngineState(
            healthy,
            // What a real probe answers once Docker Desktop is gone.
            DockerEngineProbe(
                state = DockerEngineState.UNAVAILABLE,
                desktopProcess = DockerDesktopProcessState.STOPPED,
            ),
        )

        assertEquals(DockerDesktopProcessState.STOPPED, lost.docker?.desktopProcess)
        assertEquals(DockerSetupAction.START_DESKTOP, DockerSetupPlanner.decide(readyWsl, lost.docker))
    }

    @Test
    fun a_live_desktop_with_a_slow_engine_is_still_a_wait() {
        val state = PreflightState(
            status = PreflightStatus.READY,
            checks = listOf(engineCheckPlaceholder()),
            docker = readyDocker,
        )

        val starting = EnvironmentPreflight(platform = HostPlatform.WINDOWS).withEngineState(
            state,
            DockerEngineProbe(
                state = DockerEngineState.STARTING,
                desktopProcess = DockerDesktopProcessState.RUNNING,
            ),
        )

        assertEquals(DockerSetupAction.WAIT_FOR_ENGINE, DockerSetupPlanner.decide(readyWsl, starting.docker))
    }

    // A container cannot serve a port without the daemon, so a dead engine answers for all of them.
    @Test
    fun a_confirmed_dead_engine_reports_the_docker_components_as_stopped() {
        val services = foldEngineVerdict(
            snapshot = DockerServiceSnapshot.Unknown("docker ps завершился с кодом 1"),
            engine = DockerEngineProbe(DockerEngineState.UNAVAILABLE),
        )

        val components = reconcileRuntimeComponents(
            current = allRunning().components,
            endpoints = endpoints,
            dockerServices = services,
            browserAgentRunning = true,
        )

        assertEquals(ComponentStatus.STOPPED, components.single { it.id == ComponentId.BACKEND }.status)
        assertEquals(ComponentStatus.STOPPED, components.single { it.id == ComponentId.FRONTEND }.status)
        assertEquals(ComponentStatus.STOPPED, components.single { it.id == ComponentId.GENERATION_AGENT }.status)
        // The Browser Agent does not run on Docker and is unaffected.
        assertEquals(ComponentStatus.RUNNING, components.single { it.id == ComponentId.BROWSER_AGENT }.status)
    }

    @Test
    fun an_engine_that_was_not_probed_or_is_coming_up_decides_nothing() {
        val unknown = DockerServiceSnapshot.Unknown("docker ps не ответил вовремя")

        assertEquals(unknown, foldEngineVerdict(unknown, engine = null))
        assertEquals(unknown, foldEngineVerdict(unknown, DockerEngineProbe(DockerEngineState.STARTING)))
        assertEquals(unknown, foldEngineVerdict(unknown, DockerEngineProbe(DockerEngineState.FAILED)))
    }

    // The green "Docker Desktop запущен и работает на WSL 2" header over a red engine row.
    @Test
    fun a_success_message_the_machine_has_contradicted_is_dropped() {
        val succeeded = SetupState(
            status = SetupStatus.SUCCEEDED,
            message = "Docker Desktop запущен и работает на WSL 2",
        )

        assertEquals(
            SetupState(),
            setupAfterDiagnosis(succeeded, readyDocker.copy(engine = DockerEngineState.UNAVAILABLE)),
        )
        // While Docker really is ready the confirmation stays on screen.
        assertEquals(succeeded, setupAfterDiagnosis(succeeded, readyDocker))
        // A failure is the only place its own reason is shown, so it is never cleared by a diagnosis.
        val failed = SetupState(status = SetupStatus.FAILED, message = "Не удалось запустить Docker Desktop")
        assertEquals(failed, setupAfterDiagnosis(failed, readyDocker.copy(engine = DockerEngineState.UNAVAILABLE)))
        // A preparation that is still running owns the card.
        val preparing = SetupState(status = SetupStatus.PREPARING)
        assertEquals(preparing, setupAfterDiagnosis(preparing, null))
    }

    private val readyWsl = WslDiagnostics(
        virtualization = VirtualizationState.ENABLED,
        installation = WslInstallationState.COMPATIBLE,
        defaultVersion = WslDefaultVersionState.VERSION_2,
        installedVersion = "2.2.4.0",
    )

    private val readyDocker = DockerDiagnostics(
        desktop = DockerDesktopState.INSTALLED,
        desktopVersion = "4.34.0",
        desktopProcess = DockerDesktopProcessState.RUNNING,
        cli = DockerCliState.AVAILABLE,
        cliVersion = "27.0.3",
        compose = DockerComposeState.AVAILABLE,
        composeVersion = "2.29.0",
        engine = DockerEngineState.RUNNING,
        engineVersion = "27.0.3",
        containerMode = ContainerMode.LINUX,
        backend = DockerBackend.WSL_2,
    )

    private fun engineCheckPlaceholder() = org.hhclicker.launcher.model.EnvironmentCheck(
        id = "docker-server",
        label = "Docker Engine",
        passed = true,
        message = "Engine 27.0.3: Linux containers на WSL 2",
    )

    private fun portsCheckPlaceholder() = org.hhclicker.launcher.model.EnvironmentCheck(
        id = "service-ports",
        label = "Локальные порты",
        passed = true,
        message = "",
    )

    private fun allRunning(): LauncherState = LauncherState.stopped().let { base ->
        base.copy(
            status = LauncherStatus.RUNNING,
            components = base.components.map { component: LauncherComponentState ->
                component.copy(status = ComponentStatus.RUNNING)
            },
        )
    }

    private val endpoints = ServiceEndpoints(
        backendPort = 8080,
        frontendPort = 80,
        generationPort = 17374,
        backendUrl = "http://localhost:8080",
        frontendUrl = "http://localhost",
        generationUrl = "http://localhost:17374",
    )

    private val configuration = LauncherRuntimeConfiguration(
        bundleKind = BundleKind.PORTABLE,
        instanceId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        version = "0.1.0",
        directory = Path.of("config"),
        infraEnvironmentFile = Path.of("config/infra.env"),
        generationEnvironmentFile = Path.of("config/generation.env"),
        infraProjectName = "hh-clicker",
        generationProjectName = "hh-clicker-generation",
        endpoints = endpoints,
    )
}
