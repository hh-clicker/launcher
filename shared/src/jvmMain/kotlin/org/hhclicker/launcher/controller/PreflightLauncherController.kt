package org.hhclicker.launcher.controller

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.model.ComponentStatus
import org.hhclicker.launcher.model.CodexAuthState
import org.hhclicker.launcher.model.CodexAuthStatus
import org.hhclicker.launcher.model.DockerDiagnostics
import org.hhclicker.launcher.model.ChromeInstallationState
import org.hhclicker.launcher.model.DockerCliState
import org.hhclicker.launcher.model.DockerComposeState
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.DockerServiceSnapshot
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.LauncherStatus
import org.hhclicker.launcher.model.LauncherComponentState
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupStep
import org.hhclicker.launcher.model.SetupTarget
import org.hhclicker.launcher.preflight.ChromeProbe
import org.hhclicker.launcher.preflight.BundleLayout
import org.hhclicker.launcher.preflight.CommandDiagnostics
import org.hhclicker.launcher.preflight.DockerCliResolver
import org.hhclicker.launcher.preflight.DockerProbe
import org.hhclicker.launcher.preflight.EnvironmentPreflight
import org.hhclicker.launcher.preflight.ServiceEndpoints
import org.hhclicker.launcher.preflight.ServicePortInspection
import org.hhclicker.launcher.preflight.ServicePortProbe
import org.hhclicker.launcher.preflight.NodeRuntimeLocator
import org.hhclicker.launcher.runtime.BrowserAgentProcess
import org.hhclicker.launcher.runtime.ChromePreparationContext
import org.hhclicker.launcher.runtime.DockerPreparationContext
import org.hhclicker.launcher.runtime.DockerUserSettings
import org.hhclicker.launcher.runtime.GenerationEnvironment
import org.hhclicker.launcher.runtime.GenerationAuthClient
import org.hhclicker.launcher.runtime.InfraEnvironment
import org.hhclicker.launcher.runtime.WindowsChromePreparer
import org.hhclicker.launcher.runtime.WindowsDockerPreparer
import org.hhclicker.launcher.runtime.WindowsWslPreparer
import org.hhclicker.launcher.runtime.WindowsNodePreparer
import org.hhclicker.launcher.runtime.UnixNodePreparer
import org.hhclicker.launcher.runtime.LinuxPackagePreparer
import org.hhclicker.launcher.runtime.MacDockerPreparer
import org.hhclicker.launcher.runtime.MacChromePreparer
import org.hhclicker.launcher.runtime.NodePreparationContext
import org.hhclicker.launcher.runtime.LauncherRuntimeConfiguration
import org.hhclicker.launcher.runtime.LauncherRuntimeConfigurationStore
import org.hhclicker.launcher.setup.ChromePreparationOutcome
import org.hhclicker.launcher.setup.ChromePreparationResult
import org.hhclicker.launcher.setup.ChromeSetupPlanner
import org.hhclicker.launcher.setup.DockerPreparationOutcome
import org.hhclicker.launcher.setup.DockerPreparationResult
import org.hhclicker.launcher.setup.CodexLoginTolerance
import org.hhclicker.launcher.setup.DockerEngineProbe
import org.hhclicker.launcher.setup.DockerSetupPlanner
import org.hhclicker.launcher.setup.WslPreparationOutcome
import org.hhclicker.launcher.setup.WslSetupPlanner
import org.hhclicker.launcher.setup.NodePreparationOutcome
import org.hhclicker.launcher.setup.NodeSetupAction
import org.hhclicker.launcher.setup.NodeSetupPlanner

/**
 * Runs real environment checks, controls both Compose projects and owns one Browser Agent process.
 */
class PreflightLauncherController(
    private val dockerProbe: DockerProbe = DockerProbe(),
    private val chromeProbe: ChromeProbe = ChromeProbe(),
    private val nodeRuntimeLocator: NodeRuntimeLocator = NodeRuntimeLocator(),
    private val configurationStore: LauncherRuntimeConfigurationStore = LauncherRuntimeConfigurationStore(),
    /**
     * One resolver for the whole launcher: observation and execution must never disagree about which
     * docker.exe they mean, and a Docker Desktop installed during this run has to be picked up without
     * a restart.
     */
    dockerCli: DockerCliResolver = DockerCliResolver(),
    private val servicePortProbe: ServicePortProbe = ServicePortProbe(dockerExecutable = dockerCli::executable),
    private val preflight: EnvironmentPreflight = EnvironmentPreflight(
        dockerProbe = dockerProbe,
        chromeProbe = chromeProbe,
        nodeRuntimeLocator = nodeRuntimeLocator,
        configurationStore = configurationStore,
        servicePortProbe = servicePortProbe,
    ),
    private val infraEnvironment: InfraEnvironment = InfraEnvironment(dockerCli = dockerCli),
    private val generationEnvironment: GenerationEnvironment = GenerationEnvironment(dockerCli = dockerCli),
    private val generationAuthClient: GenerationAuthClient = GenerationAuthClient(),
    private val browserAgent: BrowserAgentProcess = BrowserAgentProcess(),
    private val wslPreparer: WindowsWslPreparer = WindowsWslPreparer(),
    private val dockerPreparer: WindowsDockerPreparer = WindowsDockerPreparer(),
    private val chromePreparer: WindowsChromePreparer = WindowsChromePreparer(),
    private val nodePreparer: WindowsNodePreparer = WindowsNodePreparer(),
    private val unixNodePreparer: UnixNodePreparer = UnixNodePreparer(),
    private val macDockerPreparer: MacDockerPreparer = MacDockerPreparer(),
    private val macChromePreparer: MacChromePreparer = MacChromePreparer(),
    private val linuxPackagePreparer: LinuxPackagePreparer = LinuxPackagePreparer(),
    private val dockerUserSettings: DockerUserSettings = DockerUserSettings(),
) : LauncherController {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(LauncherState.stopped(System.getProperty("user.dir")))
    // Written under [operation] and read by the background runtime poll without it, so publication must
    // be explicit; a half published configuration would make the poll ask about the wrong instance.
    @Volatile
    private var workspace: Path? = null

    @Volatile
    private var endpoints: ServiceEndpoints? = null

    @Volatile
    private var configuration: LauncherRuntimeConfiguration? = null
    /** Changes only when an operation can invalidate an in-flight runtime snapshot. */
    @Volatile
    private var runtimeRevision: Long = 0

    /** Consecutive device-login polls that never reached the Generation Agent. Reset by any answer. */
    @Volatile
    private var codexLoginFailures: Int = 0

    /** A Browser Agent abandoned by a previous run is dealt with once, on the first full check. */
    @Volatile
    private var previousInstanceReclaimed: Boolean = false

    override val state: StateFlow<LauncherState> = mutableState.asStateFlow()

    override suspend fun selectFreePorts() {
        var changed = false
        operation.withLock {
            if (mutableState.value.busy) return@withLock
            val current = configuration ?: return@withLock
            val conflicts = mutableState.value.preflight.portConflicts
            if (conflicts.isEmpty()) return@withLock
            val result = runCatching {
                configurationStore.reassignPorts(current, conflicts.mapTo(linkedSetOf()) { it.component })
            }
            if (result.isSuccess) {
                configuration = result.getOrThrow()
                runtimeRevision++
                changed = true
            } else {
                val message = result.exceptionOrNull()?.message ?: "Не удалось выбрать свободные порты"
                mutableState.value = mutableState.value.copy(
                    preflight = mutableState.value.preflight.copy(
                        checks = mutableState.value.preflight.checks.map { check ->
                            if (check.id == "service-ports") check.copy(message = message) else check
                        },
                    ),
                )
            }
        }
        if (changed) refreshEnvironment()
    }

    /**
     * One background observation tick: what the containers are doing, and — when that is in doubt —
     * whether the Docker engine is still there at all.
     *
     * The two questions are separate on purpose. Reconciling components is unsafe while the launcher is
     * reporting a failed operation, but diagnosing the machine is exactly what is needed then: closing
     * Docker Desktop used to leave every machine row green, so the screen kept offering a start that
     * could not work, and the reason only appeared as a Compose error once the user pressed it.
     *
     * The tick never throws. The coroutine driving it is long lived and a single failed probe must cost
     * one tick, not the monitor. An undetermined Docker answer is published as such: the reason reaches
     * the ports row while every Docker component keeps the state it was last observed in.
     */
    override suspend fun refreshRuntimeState() {
        val before = mutableState.value
        val revisionBeforeInspection = runtimeRevision
        if (!canRefreshDiagnostics(before)) return

        // Docker Desktop creates its CLI links after the installer exits. An engine-only observation
        // cannot discover them, so keep rechecking the whole Mac prerequisite until they appear.
        if (shouldRefreshMacDockerAfterInstall(before)) {
            refreshEnvironment()
            return
        }
        val currentConfiguration = configuration ?: return
        val currentEndpoints = endpoints ?: return

        val reconcile = canRefreshRuntime(before)
        val inspection = if (reconcile) {
            withContext(Dispatchers.IO) {
                runCatching { servicePortProbe.inspect(currentConfiguration) }
                    .getOrElse { failure ->
                        ServicePortInspection.unknown(
                            CommandDiagnostics.describe(
                                "Проверка контейнеров",
                                failure,
                                listOf(currentConfiguration.instanceId),
                            ),
                        )
                    }
            }
        } else {
            null
        }
        // Costs one `docker info` only while the picture does not add up, and keeps costing it until it
        // does, so a reopened Docker Desktop turns the engine row green again without a manual re-check.
        val engine = if (
            shouldProbeDockerEngine(
                snapshot = inspection?.services,
                docker = before.preflight.docker,
                launcherFailed = before.status == LauncherStatus.FAILED,
            )
        ) {
            withContext(Dispatchers.IO) { runCatching { dockerProbe.probeEngine() }.getOrNull() }
        } else {
            null
        }
        val generationRuntime = if (inspection != null && before.components.any {
                it.id == ComponentId.GENERATION_AGENT && it.status == ComponentStatus.RUNNING
            }) {
            generationAuthClient.runtimeInfo(currentEndpoints.generationUrl)
        } else {
            null
        }

        if (!operation.tryLock()) return
        try {
            val latest = mutableState.value
            if (!runtimeSnapshotIsCurrent(
                    revisionAtInspection = revisionBeforeInspection,
                    revisionNow = runtimeRevision,
                    configurationAtInspection = currentConfiguration,
                    configurationNow = configuration,
                    latest = latest,
                )
            ) {
                return
            }

            var next = latest
            if (inspection != null) {
                val services = foldEngineVerdict(inspection.services, engine)
                val components = reconcileRuntimeComponents(
                    latest.components,
                    currentEndpoints,
                    services,
                    browserAgent.isRunning(),
                )
                val generationRunning = components.any {
                    it.id == ComponentId.GENERATION_AGENT && it.status == ComponentStatus.RUNNING
                }
                next = next.copy(
                    status = reconciledLauncherStatus(components),
                    components = components,
                    preflight = preflight.withServiceInspection(
                        next.preflight,
                        currentConfiguration,
                        inspection,
                    ),
                    codexAuth = if (generationRunning) latest.codexAuth else CodexAuthState(
                        CodexAuthStatus.UNAVAILABLE,
                        message = "Сначала запустите Generation Agent",
                    ),
                    codexRuntime = if (generationRunning) generationRuntime else null,
                    // An undetermined observation is not proof that an already reported failure is gone.
                    error = if (services.trustworthy) null else latest.error,
                )
            }
            // Applied last so the machine verdict wins: a preflight that just found the engine gone must
            // stay BLOCKED even though the ports row of the same tick had nothing to complain about.
            if (engine != null) {
                val refreshed = preflight.withEngineState(next.preflight, engine)
                next = next.copy(
                    preflight = refreshed,
                    setup = setupAfterDiagnosis(next.setup, refreshed.docker),
                )
            }
            mutableState.value = next
        } finally {
            operation.unlock()
        }
    }

    override suspend fun refreshEnvironment() = operation.withLock {
        runtimeRevision++
        if (!previousInstanceReclaimed) {
            previousInstanceReclaimed = true
            withContext(Dispatchers.IO) { runCatching { browserAgent.reclaimPreviousInstance() } }
        }
        mutableState.value = mutableState.value.copy(
            preflight = mutableState.value.preflight.copy(status = PreflightStatus.CHECKING),
            setup = when (mutableState.value.setup.status) {
                SetupStatus.PREPARING, SetupStatus.RESTART_REQUIRED -> mutableState.value.setup
                else -> SetupState()
            },
        )
        val result = preflight.check()
        workspace = result.workspace
        endpoints = result.endpoints
        configuration = result.configuration
        // A manual full re-check observes the machine, it does not get to invent a Docker answer. While
        // the snapshot is UNKNOWN the Docker components keep the state of the last successful observation.
        val reconciledComponents = result.endpoints?.let { currentEndpoints ->
            reconcileRuntimeComponents(
                mutableState.value.components,
                currentEndpoints,
                result.dockerServices,
                browserAgent.isRunning(),
            )
        } ?: mutableState.value.components
        val reconciledStatus = reconciledLauncherStatus(reconciledComponents)
        mutableState.value = mutableState.value.copy(
            workspacePath = result.workspace?.toString() ?: mutableState.value.workspacePath,
            version = BundleLayout.displayVersion(result.workspace) ?: result.configuration?.version ?: "dev",
            preflight = result.state,
            status = reconciledStatus,
            components = reconciledComponents,
            error = null,
        )
    }

    /**
     * Runs the single WSL 2 action the last diagnostics allow, then repeats the diagnostics.
     *
     * The action is never taken from the screen. Enabling Windows components always ends in
     * [SetupStatus.RESTART_REQUIRED]: the launcher reports the required manual restart instead of
     * claiming that the machine is ready, and it never restarts Windows itself.
     */
    override suspend fun prepareWsl() {
        val action = operation.withLock {
            if (mutableState.value.setup.status == SetupStatus.PREPARING) return
            val decided = WslSetupPlanner.decide(mutableState.value.preflight.wsl)
            if (!decided.executable) {
                mutableState.value = mutableState.value.copy(setup = WslSetupPlanner.refusalState(decided))
                return
            }
            mutableState.value = mutableState.value.copy(setup = WslSetupPlanner.preparingState(decided))
            decided
        }

        val result = wslPreparer.prepare(action)
        val finished = WslSetupPlanner.stateFor(result)
        val machineChanged = result.outcome == WslPreparationOutcome.COMPONENTS_CHANGED_RESTART_REQUIRED ||
            result.outcome == WslPreparationOutcome.COMPLETED
        if (!machineChanged) {
            operation.withLock { mutableState.value = mutableState.value.copy(setup = finished) }
            return
        }

        operation.withLock {
            mutableState.value = mutableState.value.copy(setup = WslSetupPlanner.rechecking(finished))
        }
        refreshEnvironment()
        operation.withLock { mutableState.value = mutableState.value.copy(setup = finished) }
    }

    /**
     * Runs the single Docker action the last diagnostics allow, then repeats the diagnostics.
     *
     * The action is decided here, never on the screen: the UI only reports that the user pressed the
     * button and, for an installation, that the Docker agreement was accepted with that same click.
     * The shared [operation] mutex keeps this exclusive with WSL preparation, refresh, start and stop.
     */
    override suspend fun prepareDocker(acceptDockerLicense: Boolean) {
        if (mutableState.value.preflight.platform == HostPlatform.LINUX) {
            prepareLinuxPackage(SetupTarget.DOCKER, true)
            return
        }
        if (mutableState.value.preflight.platform == HostPlatform.MACOS) {
            prepareMacDocker()
            return
        }
        val action = operation.withLock {
            if (mutableState.value.setup.status == SetupStatus.PREPARING) return
            val decided = DockerSetupPlanner.decide(
                wsl = mutableState.value.preflight.wsl,
                docker = mutableState.value.preflight.docker,
            )
            if (!decided.executable) {
                mutableState.value = mutableState.value.copy(setup = DockerSetupPlanner.refusalState(decided))
                return
            }
            if (decided.requiresLicenseAcceptance && !acceptDockerLicense) {
                mutableState.value = mutableState.value.copy(
                    setup = DockerSetupPlanner.stateFor(
                        DockerPreparationResult(DockerPreparationOutcome.LICENSE_NOT_ACCEPTED),
                    ),
                )
                return
            }
            mutableState.value = mutableState.value.copy(setup = DockerSetupPlanner.preparingState(decided))
            decided
        }

        val diagnostics = mutableState.value.preflight.docker ?: DockerDiagnostics()
        val context = DockerPreparationContext(
            diagnostics = diagnostics,
            engineState = { withContext(Dispatchers.IO) { dockerProbe.probeEngine().state } },
            refreshDiagnostics = { withContext(Dispatchers.IO) { dockerProbe.probe() } },
            locateDesktop = { withContext(Dispatchers.IO) { dockerProbe.locateDesktopExecutable() } },
            locateCli = { withContext(Dispatchers.IO) { dockerProbe.locateCliExecutable() } },
            prepareUnattendedStart = {
                withContext(Dispatchers.IO) { dockerUserSettings.prepareUnattendedStart() }
            },
            onStage = { step -> publishSetup(DockerSetupPlanner.stageState(step)) },
            onDownloadProgress = { downloaded, total ->
                publishSetup(DockerSetupPlanner.downloadState(downloaded, total))
            },
        )

        val result = dockerPreparer.prepare(action, acceptDockerLicense, context)
        val finished = DockerSetupPlanner.stateFor(result)
        val machineChanged = result.outcome == DockerPreparationOutcome.INSTALLED_AND_READY ||
            result.outcome == DockerPreparationOutcome.STARTED_AND_READY ||
            result.outcome == DockerPreparationOutcome.ALREADY_READY
        if (!machineChanged) {
            operation.withLock { mutableState.value = mutableState.value.copy(setup = finished) }
            return
        }

        operation.withLock { mutableState.value = mutableState.value.copy(setup = DockerSetupPlanner.rechecking()) }
        refreshEnvironment()
        operation.withLock { mutableState.value = mutableState.value.copy(setup = finished) }
    }

    private suspend fun prepareMacChrome(acceptGoogleTerms: Boolean) {
        operation.withLock {
            if (mutableState.value.setup.inProgress) return
            if (mutableState.value.preflight.chrome?.installation == null ||
                mutableState.value.preflight.chrome?.installation == ChromeInstallationState.CHECK_FAILED) {
                mutableState.value = mutableState.value.copy(setup = SetupState(SetupStatus.FAILED,
                    message = "Не удалось определить, установлен ли Chrome. Повторите проверку", target = SetupTarget.CHROME))
                return
            }
            mutableState.value = mutableState.value.copy(setup = ChromeSetupPlanner.stageState(SetupStep.DOWNLOADING_CHROME))
        }
        val result = runCatching {
            macChromePreparer.prepare(acceptGoogleTerms, ChromePreparationContext(
                locateChrome = { chromeProbe.locateExecutable() },
                onStage = { step -> publishSetup(ChromeSetupPlanner.stageState(step)) },
                onDownloadProgress = { downloaded, total -> publishSetup(ChromeSetupPlanner.downloadState(downloaded, total)) },
            ))
        }.getOrElse { failure ->
            MacChromePreparer.Result(false, "Подготовка Chrome прервана: ${failure.message ?: "ошибка ввода-вывода"}")
        }
        if (result.success) {
            publishSetup(ChromeSetupPlanner.rechecking())
            refreshEnvironment()
        }
        operation.withLock {
            mutableState.value = mutableState.value.copy(setup = SetupState(
                if (result.success) SetupStatus.SUCCEEDED else SetupStatus.FAILED,
                message = result.message, target = SetupTarget.CHROME,
            ))
        }
    }

    private suspend fun prepareMacDocker() {
        operation.withLock {
            if (mutableState.value.setup.inProgress) return
            mutableState.value = mutableState.value.copy(
                setup = SetupState(SetupStatus.PREPARING, SetupStep.DOWNLOADING_DOCKER,
                    "Подготавливаем Docker Desktop для Mac", SetupTarget.DOCKER),
            )
        }
        val result = runCatching {
            macDockerPreparer.prepare(
                onStage = { step -> publishSetup(SetupState(SetupStatus.PREPARING, step,
                    macDockerStepMessage(step), SetupTarget.DOCKER)) },
                onDownloadProgress = { downloaded, total -> publishSetup(SetupState(
                    SetupStatus.PREPARING, SetupStep.DOWNLOADING_DOCKER, "Скачиваем Docker Desktop",
                    SetupTarget.DOCKER, downloaded, total,
                )) },
            )
        }.getOrElse { failure ->
            MacDockerPreparer.Result(false, "Подготовка Docker прервана: ${failure.message ?: "ошибка ввода-вывода"}")
        }
        if (result.success) refreshEnvironment()
        operation.withLock {
            mutableState.value = mutableState.value.copy(setup = SetupState(
                if (result.success) SetupStatus.SUCCEEDED else SetupStatus.FAILED,
                message = result.message,
                target = SetupTarget.DOCKER,
            ))
        }
    }

    private fun macDockerStepMessage(step: SetupStep): String = when (step) {
        SetupStep.DOWNLOADING_DOCKER -> "Скачиваем Docker Desktop"
        SetupStep.VERIFYING_DOCKER_INSTALLER -> "Проверяем образ и подпись Docker Desktop"
        SetupStep.INSTALLING_DOCKER -> "Устанавливаем Docker Desktop; macOS может запросить пароль"
        SetupStep.STARTING_DOCKER -> "Открываем Docker Desktop"
        else -> "Подготавливаем Docker Desktop"
    }

    /**
     * Installs the system Google Chrome the Browser Agent needs, then repeats the diagnostics.
     *
     * Chrome is the third preparation target, after WSL 2 and Docker Desktop, and it is completely
     * independent of them: an acceptance given for the Docker agreement never reaches this method. The
     * shared [operation] mutex keeps it exclusive with every other launcher operation, so a second
     * installation cannot start while this one runs, and the refresh at the end turns the Chrome row
     * green without restarting the launcher.
     */
    override suspend fun prepareChrome(acceptGoogleTerms: Boolean) {
        if (mutableState.value.preflight.platform == HostPlatform.MACOS) {
            prepareMacChrome(acceptGoogleTerms)
            return
        }
        if (mutableState.value.preflight.platform == HostPlatform.LINUX) {
            prepareLinuxPackage(SetupTarget.CHROME, acceptGoogleTerms)
            return
        }
        val action = operation.withLock {
            if (mutableState.value.setup.status == SetupStatus.PREPARING) return
            val decided = ChromeSetupPlanner.decide(mutableState.value.preflight.chrome)
            if (!decided.executable) {
                mutableState.value = mutableState.value.copy(setup = ChromeSetupPlanner.refusalState(decided))
                return
            }
            if (decided.requiresTermsAcceptance && !acceptGoogleTerms) {
                mutableState.value = mutableState.value.copy(
                    setup = ChromeSetupPlanner.stateFor(
                        ChromePreparationResult(ChromePreparationOutcome.TERMS_NOT_ACCEPTED),
                    ),
                )
                return
            }
            mutableState.value = mutableState.value.copy(setup = ChromeSetupPlanner.preparingState(decided))
            decided
        }

        val context = ChromePreparationContext(
            // The download, the signature check and msiexec all run off the UI dispatcher.
            locateChrome = { withContext(Dispatchers.IO) { chromeProbe.locateExecutable() } },
            onStage = { step -> publishSetup(ChromeSetupPlanner.stageState(step)) },
            onDownloadProgress = { downloaded, total ->
                publishSetup(ChromeSetupPlanner.downloadState(downloaded, total))
            },
        )

        val result = chromePreparer.prepare(action, acceptGoogleTerms, context)
        val finished = ChromeSetupPlanner.stateFor(result)
        val machineChanged = result.outcome == ChromePreparationOutcome.INSTALLED_AND_READY ||
            result.outcome == ChromePreparationOutcome.INSTALLED_RESTART_RECOMMENDED ||
            result.outcome == ChromePreparationOutcome.ALREADY_INSTALLED
        if (!machineChanged) {
            operation.withLock { mutableState.value = mutableState.value.copy(setup = finished) }
            return
        }

        operation.withLock { mutableState.value = mutableState.value.copy(setup = ChromeSetupPlanner.rechecking()) }
        refreshEnvironment()
        operation.withLock { mutableState.value = mutableState.value.copy(setup = finished) }
    }

    override suspend fun prepareNode() {
        val action = operation.withLock {
            if (mutableState.value.setup.status == SetupStatus.PREPARING) return
            val decided = NodeSetupPlanner.decide(mutableState.value.preflight.node)
            if (decided != NodeSetupAction.DOWNLOAD_PORTABLE) {
                if (decided == NodeSetupAction.ALREADY_IN_PROGRESS) {
                    mutableState.value = mutableState.value.copy(setup = NodeSetupPlanner.refusalState())
                }
                return
            }
            mutableState.value = mutableState.value.copy(setup = NodeSetupPlanner.preparingState())
            decided
        }

        val context = NodePreparationContext(
                nodeReady = { withContext(Dispatchers.IO) { nodeRuntimeLocator.probe(workspace).diagnostics.ready } },
                onStage = { publishSetup(NodeSetupPlanner.stageState(it)) },
                onDownloadProgress = { downloaded, total -> publishSetup(NodeSetupPlanner.downloadState(downloaded, total)) },
        )
        val result = if (mutableState.value.preflight.platform in setOf(HostPlatform.LINUX, HostPlatform.MACOS)) {
            unixNodePreparer.prepare(action, context)
        } else {
            nodePreparer.prepare(action, context)
        }
        val finished = NodeSetupPlanner.stateFor(result)
        if (result.outcome != NodePreparationOutcome.INSTALLED_AND_READY &&
            result.outcome != NodePreparationOutcome.ALREADY_READY
        ) {
            operation.withLock { mutableState.value = mutableState.value.copy(setup = finished) }
            return
        }
        operation.withLock { mutableState.value = mutableState.value.copy(setup = NodeSetupPlanner.rechecking()) }
        refreshEnvironment()
        operation.withLock { mutableState.value = mutableState.value.copy(setup = finished) }
    }

    private suspend fun prepareLinuxPackage(target: SetupTarget, acceptedTerms: Boolean) {
        operation.withLock {
            if (mutableState.value.setup.inProgress) return
            val required = when (target) {
                SetupTarget.DOCKER -> listOf("docker-cli", "docker-compose")
                SetupTarget.CHROME -> listOf("chrome")
                else -> return
            }
            if (mutableState.value.preflight.checks.none { it.id in required && !it.passed }) return
            if (target == SetupTarget.CHROME && !acceptedTerms) {
                mutableState.value = mutableState.value.copy(setup = SetupState(
                    SetupStatus.FAILED, message = "Подтвердите условия Google Chrome кнопкой установки", target = target,
                ))
                return
            }
            mutableState.value = mutableState.value.copy(setup = SetupState(
                SetupStatus.PREPARING, message = "Подготавливаем пакеты Debian 13", target = target,
            ))
        }
        val result = runCatching { when (target) {
            SetupTarget.DOCKER -> linuxPackagePreparer.prepareDocker { step ->
                publishSetup(SetupState(SetupStatus.PREPARING, step, "Устанавливаем Docker из репозитория Debian", target))
            }
            SetupTarget.CHROME -> linuxPackagePreparer.prepareChrome(
                onStage = { step -> publishSetup(SetupState(SetupStatus.PREPARING, step, "Устанавливаем Google Chrome", target)) },
                onDownloadProgress = { downloaded, total ->
                    publishSetup(SetupState(SetupStatus.PREPARING, SetupStep.DOWNLOADING_CHROME,
                        "Скачиваем Google Chrome", target, downloaded, total))
                },
            )
            else -> return
        } }.getOrElse { failure ->
            LinuxPackagePreparer.Result(false, "Подготовка прервана: ${failure.message ?: "ошибка ввода-вывода"}")
        }
        val finished = SetupState(
            status = when {
                !result.success -> SetupStatus.FAILED
                result.restartSession -> SetupStatus.RESTART_REQUIRED
                else -> SetupStatus.SUCCEEDED
            },
            message = result.message,
            target = target,
        )
        if (result.success) refreshEnvironment()
        operation.withLock { mutableState.value = mutableState.value.copy(setup = finished) }
    }

    /**
     * Publishes a stage update while an operation holds no lock.
     *
     * Progress arrives from inside the running preparation, so it updates the flow directly instead of
     * re-entering [operation]; only one preparation can run at a time, so there is nothing to race with.
     */
    private fun publishSetup(setup: SetupState) {
        mutableState.value = mutableState.value.copy(setup = setup)
    }

    override suspend fun refreshCodexAuth() {
        var loginInProgress = false
        val url = operation.withLock {
            val running = mutableState.value.components.any {
                it.id == ComponentId.GENERATION_AGENT && it.status == ComponentStatus.RUNNING
            }
            if (!running) {
                mutableState.value = mutableState.value.copy(
                    codexAuth = CodexAuthState(
                        CodexAuthStatus.UNAVAILABLE,
                        message = "Сначала запустите Generation Agent",
                    ),
                    codexRuntime = null,
                )
                null
            } else {
                loginInProgress = mutableState.value.codexAuth.status == CodexAuthStatus.AUTHORIZING
                if (!loginInProgress) {
                    mutableState.value = mutableState.value.copy(
                        codexAuth = mutableState.value.codexAuth.copy(status = CodexAuthStatus.CHECKING),
                    )
                }
                endpoints?.generationUrl
            }
        } ?: return
        if (loginInProgress) {
            // A poll that never reached the Generation Agent is not an answer about the login, so the
            // visible state is kept and the loop keeps polling until enough silence has accumulated.
            val poll = generationAuthClient.loginState(url)
            operation.withLock {
                val decision = CodexLoginTolerance.next(
                    current = mutableState.value.codexAuth,
                    poll = poll,
                    consecutiveFailures = codexLoginFailures,
                )
                codexLoginFailures = decision.consecutiveFailures
                mutableState.value = mutableState.value.copy(codexAuth = decision.state)
            }
            if (mutableState.value.codexAuth.status == CodexAuthStatus.SIGNED_IN) {
                val snapshot = generationAuthClient.status(url)
                operation.withLock { mutableState.value = mutableState.value.copy(codexAuth = snapshot) }
            }
            return
        }

        val result = generationAuthClient.refreshStatus(url)
        operation.withLock {
            codexLoginFailures = 0
            mutableState.value = mutableState.value.copy(codexAuth = result)
        }
    }

    override suspend fun startCodexLogin() {
        val url = operation.withLock {
            codexLoginFailures = 0
            endpoints?.generationUrl
        } ?: return
        val result = generationAuthClient.startLogin(url)
        operation.withLock { mutableState.value = mutableState.value.copy(codexAuth = result) }
    }

    override suspend fun cancelCodexLogin() {
        val url = operation.withLock {
            codexLoginFailures = 0
            endpoints?.generationUrl
        } ?: return
        val result = generationAuthClient.cancelLogin(url)
        operation.withLock { mutableState.value = mutableState.value.copy(codexAuth = result) }
    }

    override suspend fun logoutCodex() {
        val url = operation.withLock {
            codexLoginFailures = 0
            mutableState.value = mutableState.value.copy(
                codexAuth = mutableState.value.codexAuth.copy(
                    status = CodexAuthStatus.CHECKING,
                    message = "Выходим из Codex…",
                ),
            )
            endpoints?.generationUrl
        } ?: return
        val result = generationAuthClient.logout(url)
        operation.withLock { mutableState.value = mutableState.value.copy(codexAuth = result) }
    }

    override suspend fun startAll() = operation.withLock {
        if (mutableState.value.preflight.status != PreflightStatus.READY) return@withLock
        if (mutableState.value.status == LauncherStatus.RUNNING) return@withLock
        val currentWorkspace = workspace ?: run {
            mutableState.value = mutableState.value.copy(status = LauncherStatus.FAILED, error = "Рабочая папка не найдена")
            return@withLock
        }
        val currentEndpoints = endpoints ?: run {
            mutableState.value = mutableState.value.copy(status = LauncherStatus.FAILED, error = "Адреса сервисов не определены")
            return@withLock
        }
        val currentConfiguration = configuration ?: run {
            mutableState.value = mutableState.value.copy(status = LauncherStatus.FAILED, error = "Постоянные настройки не созданы")
            return@withLock
        }

        runtimeRevision++
        mutableState.value = mutableState.value.copy(status = LauncherStatus.STARTING, error = null)
        val backendRunning = componentRunning(ComponentId.BACKEND)
        val frontendRunning = componentRunning(ComponentId.FRONTEND)
        if (!backendRunning || !frontendRunning) {
            if (!backendRunning) updateComponent(ComponentId.BACKEND, ComponentStatus.STARTING)
            if (!frontendRunning) updateComponent(ComponentId.FRONTEND, ComponentStatus.STARTING)

            val result = infraEnvironment.start(currentWorkspace, currentConfiguration)
            updateComponent(
                ComponentId.BACKEND,
                if (result.backendReady) ComponentStatus.RUNNING else ComponentStatus.FAILED,
                result.error,
            )
            updateComponent(
                ComponentId.FRONTEND,
                if (result.frontendReady) ComponentStatus.RUNNING else ComponentStatus.FAILED,
                result.error,
            )
            mutableState.value = if (result.successful) {
                mutableState.value.copy(error = null)
            } else {
                mutableState.value.copy(status = LauncherStatus.FAILED, error = result.error)
            }
            if (!result.successful) return@withLock
        }

        if (!componentRunning(ComponentId.GENERATION_AGENT)) {
            updateComponent(ComponentId.GENERATION_AGENT, ComponentStatus.STARTING)
            val generationError = generationEnvironment.start(currentWorkspace, currentConfiguration)
            if (generationError == null) {
                updateComponent(ComponentId.GENERATION_AGENT, ComponentStatus.RUNNING)
                mutableState.value = mutableState.value.copy(
                    codexAuth = generationAuthClient.status(currentEndpoints.generationUrl),
                    codexRuntime = generationAuthClient.runtimeInfo(currentEndpoints.generationUrl),
                )
            } else {
                updateComponent(ComponentId.GENERATION_AGENT, ComponentStatus.FAILED, generationError)
                mutableState.value = mutableState.value.copy(status = LauncherStatus.FAILED, error = generationError)
                return@withLock
            }
        }

        if (!componentRunning(ComponentId.BROWSER_AGENT)) {
            updateComponent(ComponentId.BROWSER_AGENT, ComponentStatus.STARTING)
            val browserError = browserAgent.start(currentWorkspace, currentEndpoints)
            if (browserError == null) {
                updateComponent(ComponentId.BROWSER_AGENT, ComponentStatus.RUNNING)
            } else {
                updateComponent(ComponentId.BROWSER_AGENT, ComponentStatus.FAILED, browserError)
                mutableState.value = mutableState.value.copy(status = LauncherStatus.FAILED, error = browserError)
                return@withLock
            }
        }
        mutableState.value = mutableState.value.copy(status = LauncherStatus.RUNNING, error = null)
    }

    /**
     * Stops everything the launcher owns, component by component.
     *
     * The Browser Agent is stopped first and unconditionally. It is a local child process of this
     * launcher and its lifetime has nothing to do with the workspace: bailing out early when the
     * workspace or the durable configuration could not be resolved used to leave a browser automation
     * process running with nobody in control of it. A missing workspace can only prevent `compose down`,
     * and that is reported as the failure of those components alone.
     */
    override suspend fun stopAll() = operation.withLock {
        // A launcher that believes everything is stopped still has to be able to release a Browser Agent
        // it actually owns — for example one adopted from a previous run.
        if (mutableState.value.status == LauncherStatus.STOPPED &&
            mutableState.value.components.all { it.status == ComponentStatus.STOPPED } &&
            !browserAgent.isRunning()
        ) return@withLock

        runtimeRevision++
        mutableState.value = mutableState.value.copy(status = LauncherStatus.STOPPING, error = null)
        updateComponent(ComponentId.BROWSER_AGENT, ComponentStatus.STOPPING)
        updateComponent(ComponentId.GENERATION_AGENT, ComponentStatus.STOPPING)
        updateComponent(ComponentId.FRONTEND, ComponentStatus.STOPPING)
        updateComponent(ComponentId.BACKEND, ComponentStatus.STOPPING)

        // Independent attempts: one failure must not prevent the others from being tried.
        val browserError = runCatching { browserAgent.stop() }
            .getOrElse { failure -> failure.message ?: "Не удалось завершить Browser Agent" }

        // Compose needs both the workspace and the durable configuration; without them only these two
        // components fail, and they fail with a reason instead of silently staying as they were.
        val composeTarget = workspace?.let { located -> configuration?.let { current -> located to current } }
        val generationError = if (composeTarget == null) {
            COMPOSE_TARGET_UNKNOWN
        } else {
            runCatching { generationEnvironment.stop(composeTarget.first, composeTarget.second) }
                .getOrElse { failure -> failure.message ?: "Не удалось остановить Generation Agent" }
        }
        val infraError = if (composeTarget == null) {
            COMPOSE_TARGET_UNKNOWN
        } else {
            runCatching { infraEnvironment.stop(composeTarget.first, composeTarget.second) }
                .getOrElse { failure -> failure.message ?: "Не удалось остановить инфраструктуру" }
        }

        val stopErrors = listOfNotNull(browserError, generationError, infraError).distinct()
        val error = stopErrors.takeIf { it.isNotEmpty() }?.joinToString("; ")
        updateComponent(
            ComponentId.BROWSER_AGENT,
            if (browserError == null) ComponentStatus.STOPPED else ComponentStatus.FAILED,
            browserError,
        )
        updateComponent(
            ComponentId.GENERATION_AGENT,
            if (generationError == null) ComponentStatus.STOPPED else ComponentStatus.FAILED,
            generationError,
        )
        updateComponent(
            ComponentId.FRONTEND,
            if (infraError == null) ComponentStatus.STOPPED else ComponentStatus.FAILED,
            infraError,
        )
        updateComponent(
            ComponentId.BACKEND,
            if (infraError == null) ComponentStatus.STOPPED else ComponentStatus.FAILED,
            infraError,
        )
        mutableState.value = if (error == null) {
            mutableState.value.copy(
                status = LauncherStatus.STOPPED,
                codexAuth = CodexAuthState(
                    CodexAuthStatus.UNAVAILABLE,
                    message = "Сначала запустите Generation Agent",
                ),
                codexRuntime = null,
            )
        } else {
            mutableState.value.copy(
                status = LauncherStatus.FAILED,
                error = error,
                codexRuntime = if (generationError == null) null else mutableState.value.codexRuntime,
            )
        }
    }

    private fun updateComponent(id: ComponentId, status: ComponentStatus, details: String? = null) {
        mutableState.value = mutableState.value.copy(
            components = mutableState.value.components.map { component ->
                if (component.id == id) component.copy(status = status, details = details) else component
            },
        )
    }

    private fun componentRunning(id: ComponentId): Boolean = mutableState.value.components.any {
        it.id == id && it.status == ComponentStatus.RUNNING
    }

    private companion object {
        const val COMPOSE_TARGET_UNKNOWN =
            "Рабочая папка или постоянные настройки недоступны: Docker Compose остановить не удалось"
    }
}

/**
 * Projects one observation onto the component list.
 *
 * The Browser Agent is a local child process this launcher owns, so its answer is always definite and
 * entirely independent of Docker. A Docker component follows [dockerServices] only while that snapshot is
 * [DockerServiceSnapshot.Observed]; an [DockerServiceSnapshot.Unknown] one leaves the component, and its
 * details, exactly as they were — a query that failed is not a report that the container stopped.
 */
internal fun reconcileRuntimeComponents(
    current: List<LauncherComponentState>,
    endpoints: ServiceEndpoints,
    dockerServices: DockerServiceSnapshot,
    browserAgentRunning: Boolean,
): List<LauncherComponentState> = current.map { component ->
    val dockerService = when (component.id) {
        ComponentId.BACKEND -> "backend"
        ComponentId.FRONTEND -> "frontend"
        ComponentId.GENERATION_AGENT -> "generation-agent"
        ComponentId.BROWSER_AGENT -> null
    }
    val observed = dockerService?.let(dockerServices::isRunning)
    component.copy(
        location = when (component.id) {
            ComponentId.BACKEND -> endpoints.backendUrl
            ComponentId.FRONTEND -> endpoints.frontendUrl
            ComponentId.GENERATION_AGENT -> endpoints.generationUrl
            ComponentId.BROWSER_AGENT -> component.location
        },
        status = when {
            dockerService == null -> if (browserAgentRunning) ComponentStatus.RUNNING else ComponentStatus.STOPPED
            observed == null -> component.status
            observed -> ComponentStatus.RUNNING
            else -> ComponentStatus.STOPPED
        },
        details = if (dockerService != null && observed == null) component.details else null,
    )
}

internal fun reconciledLauncherStatus(components: List<LauncherComponentState>): LauncherStatus =
    if (components.all { it.status == ComponentStatus.RUNNING }) LauncherStatus.RUNNING else LauncherStatus.STOPPED

/**
 * Whether an observation that was started earlier may still be published.
 *
 * The Docker query runs off the lock, so a start, a stop, a port reassignment or a machine preparation
 * can begin while it is in flight. Such a snapshot describes the world the user has just left: publishing
 * it would repaint the screen with the state from before the button was pressed. Codex authentication and
 * every other presentation change deliberately do not invalidate it — only [PreflightLauncherController]
 * operations that really move the runtime bump the revision.
 */
internal fun runtimeSnapshotIsCurrent(
    revisionAtInspection: Long,
    revisionNow: Long,
    configurationAtInspection: LauncherRuntimeConfiguration?,
    configurationNow: LauncherRuntimeConfiguration?,
    latest: LauncherState,
): Boolean =
    revisionAtInspection == revisionNow &&
        configurationAtInspection === configurationNow &&
        !latest.operationInProgress &&
        !latest.setup.inProgress

/**
 * Whether the machine may be re-diagnosed on this tick.
 *
 * Wider than [canRefreshRuntime] on purpose: a launcher that is reporting a failed start is precisely
 * when the user needs to be told that Docker Desktop is no longer running, and a diagnosis rewrites only
 * the environment rows, never the components or the error that is being displayed.
 */
internal fun canRefreshDiagnostics(state: LauncherState): Boolean =
    !state.operationInProgress &&
        !state.setup.inProgress &&
        state.preflight.status != PreflightStatus.NOT_CHECKED &&
        state.preflight.status != PreflightStatus.CHECKING

internal fun shouldRefreshMacDockerAfterInstall(state: LauncherState): Boolean {
    val docker = state.preflight.docker ?: return false
    return state.preflight.platform == HostPlatform.MACOS &&
        docker.desktop == DockerDesktopState.INSTALLED &&
        (docker.cli != DockerCliState.AVAILABLE || docker.compose != DockerComposeState.AVAILABLE)
}

/** Codex authentication is independent and must never suspend container observation. */
internal fun canRefreshRuntime(state: LauncherState): Boolean =
    canRefreshDiagnostics(state) && state.status != LauncherStatus.FAILED

/**
 * Folds a fresh engine verdict into the container observation.
 *
 * A Docker service cannot publish a host port without the daemon that publishes it, so an engine that
 * answered "unreachable" has answered for every container at once. That is an observation and not a
 * guess, which is why it is allowed to replace an undetermined container query: keeping green "Работает"
 * badges while the engine is provably gone is the same lie as greying them out on a failed query, only in
 * the other direction.
 *
 * Anything less definite leaves the observation alone. An engine that is still coming up says nothing
 * about containers that are about to be restarted, and an engine that was not probed says nothing at all.
 */
internal fun foldEngineVerdict(
    snapshot: DockerServiceSnapshot,
    engine: DockerEngineProbe?,
): DockerServiceSnapshot = if (engine?.state == DockerEngineState.UNAVAILABLE) {
    DockerServiceSnapshot.Observed(emptySet())
} else {
    snapshot
}

/**
 * Drops a preparation result that the machine has since contradicted.
 *
 * [SetupStatus.SUCCEEDED] is a statement about the moment the button was pressed. Once Docker is no
 * longer ready it is simply wrong, and leaving it on screen paints a green "Docker Desktop запущен и
 * работает на WSL 2" header over a red Docker Engine row. A failed result is kept: it explains an attempt
 * that really happened and is the only place its reason is shown.
 */
internal fun setupAfterDiagnosis(setup: SetupState, docker: DockerDiagnostics?): SetupState =
    if (setup.status == SetupStatus.SUCCEEDED && docker?.ready != true) SetupState() else setup

/**
 * Whether this tick has to ask the Docker engine as well as the containers.
 *
 * The container query cannot explain itself: "the user stopped a container" and "Docker Desktop was
 * closed" produce the same silence. The engine is therefore asked whenever the picture does not add up —
 * the observation came back undetermined, the engine is not currently known to be ready, or the launcher
 * is displaying a failure it has not accounted for. It keeps being asked until the picture is consistent
 * again, so recovery is automatic instead of waiting for someone to press the re-check button.
 *
 * A null [snapshot] means no container observation was made on this tick and carries no evidence either
 * way; on the healthy path this predicate is false and the tick costs nothing beyond the container query.
 */
internal fun shouldProbeDockerEngine(
    snapshot: DockerServiceSnapshot?,
    docker: DockerDiagnostics?,
    launcherFailed: Boolean,
): Boolean = docker == null || !docker.ready || launcherFailed || snapshot?.trustworthy == false
