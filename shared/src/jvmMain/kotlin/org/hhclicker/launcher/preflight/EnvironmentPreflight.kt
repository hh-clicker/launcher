package org.hhclicker.launcher.preflight

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hhclicker.launcher.model.CheckSeverity
import org.hhclicker.launcher.model.ChromeDiagnostics
import org.hhclicker.launcher.model.ChromeInstallationState
import org.hhclicker.launcher.model.ContainerMode
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.model.DockerCliState
import org.hhclicker.launcher.model.DockerComposeState
import org.hhclicker.launcher.model.DockerDesktopState
import org.hhclicker.launcher.model.DockerDiagnostics
import org.hhclicker.launcher.model.DockerEngineState
import org.hhclicker.launcher.model.DockerServiceSnapshot
import org.hhclicker.launcher.model.EnvironmentCheck
import org.hhclicker.launcher.model.NodeRuntimeDiagnostics
import org.hhclicker.launcher.model.PreflightState
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.model.PortConflict
import org.hhclicker.launcher.model.VirtualizationState
import org.hhclicker.launcher.model.WslDefaultVersionState
import org.hhclicker.launcher.model.WslDiagnostics
import org.hhclicker.launcher.model.WslInstallationState
import org.hhclicker.launcher.setup.DockerEngineProbe
import org.hhclicker.launcher.setup.DockerVersionPolicy
import org.hhclicker.launcher.setup.NodeRuntimePolicy
import org.hhclicker.launcher.setup.WslDiagnosticsParser
import org.hhclicker.launcher.runtime.LauncherRuntimeConfiguration
import org.hhclicker.launcher.runtime.LauncherRuntimeConfigurationStore

data class PreflightResult(
    val workspace: Path?,
    val endpoints: ServiceEndpoints?,
    val configuration: LauncherRuntimeConfiguration?,
    /**
     * Explicitly [DockerServiceSnapshot.Unknown] when Docker could not be asked.
     *
     * A full re-check is not a licence to invent facts: an unanswered query must leave the components as
     * they were last observed, so it can never be reported here as an empty set of running services.
     */
    val dockerServices: DockerServiceSnapshot,
    val state: PreflightState,
)

class EnvironmentPreflight(
    private val workspaceLocator: WorkspaceLocator = WorkspaceLocator(),
    private val commandProbe: CommandProbe = CommandProbe(),
    private val wslProbe: WslProbe = WslProbe(commandProbe),
    private val dockerProbe: DockerProbe = DockerProbe(commandProbe),
    private val chromeProbe: ChromeProbe = ChromeProbe(),
    private val nodeRuntimeLocator: NodeRuntimeLocator = NodeRuntimeLocator(),
    private val configurationStore: LauncherRuntimeConfigurationStore = LauncherRuntimeConfigurationStore(),
    private val servicePortProbe: ServicePortProbe = ServicePortProbe(commandProbe),
) {
    /** Applies a lightweight runtime port/container snapshot without repeating machine diagnostics. */
    fun withServiceInspection(
        state: PreflightState,
        configuration: LauncherRuntimeConfiguration,
        inspection: ServicePortInspection,
    ): PreflightState {
        val portCheck = servicePortsCheck(configuration, inspection)
        val checks = state.checks.map { check -> if (check.id == portCheck.id) portCheck else check }
        val blocked = checks.any { !it.passed && it.severity == CheckSeverity.REQUIRED }
        return state.copy(
            status = if (blocked) PreflightStatus.BLOCKED else PreflightStatus.READY,
            checks = checks,
            portConflicts = inspection.conflicts,
        )
    }

    /**
     * Applies a fresh engine-only probe to a preflight state that is already filled in.
     *
     * The background monitor has to be able to say "Docker Desktop is no longer there" on its own. Until
     * it could, closing Docker Desktop left every machine row green: the screen kept offering a start that
     * could not possibly work, and the failure only surfaced as a Compose error after the user pressed it.
     *
     * Only what the probe actually established is replaced. Installation, CLI, Compose and backend are
     * facts about the machine that an engine probe never observed, so they keep what the last full check
     * found, and a state without the engine row is returned untouched rather than recomputed from nothing.
     */
    fun withEngineState(state: PreflightState, engine: DockerEngineProbe): PreflightState {
        if (state.checks.none { it.id == DOCKER_ENGINE_CHECK_ID }) return state
        val docker = (state.docker ?: DockerDiagnostics()).copy(
            engine = engine.state,
            engineVersion = engine.serverVersion,
            containerMode = engine.containerMode,
            // Whether Docker Desktop is alive decides which single action the setup screen may offer, so
            // it has to come from this probe. Left at its old value it kept offering a wait for an engine
            // whose application had been closed.
            desktopProcess = engine.desktopProcess,
        )
        val engineCheck = dockerEngineCheck(docker)
        val checks = state.checks.map { check -> if (check.id == engineCheck.id) engineCheck else check }
        val blocked = checks.any { !it.passed && it.severity == CheckSeverity.REQUIRED }
        return state.copy(
            status = if (blocked) PreflightStatus.BLOCKED else PreflightStatus.READY,
            checks = checks,
            docker = docker,
        )
    }

    suspend fun check(): PreflightResult = withContext(Dispatchers.IO) {
        val workspace = workspaceLocator.locate()
        val wsl = wslProbe.probe()
        val docker = dockerProbe.probe()
        // Bundle kind still comes from the located workspace, while Node itself comes from PATH or the
        // isolated launcher-managed runtime under LOCALAPPDATA.
        val node = nodeRuntimeLocator.probe(workspace).diagnostics
        val chrome = chromeProbe.probe()
        val configurationResult = workspace?.let { runCatching { configurationStore.loadOrCreate(it) } }
        val configuration = configurationResult?.getOrNull()
        val configurationError = configurationResult?.exceptionOrNull()?.message
        val servicePortInspection = configuration?.let { current ->
            runCatching { servicePortProbe.inspect(current) }.getOrElse { failure ->
                ServicePortInspection.unknown(
                    CommandDiagnostics.describe("Проверка портов", failure, listOf(current.instanceId)),
                )
            }
        }
        val checks = buildList {
            add(virtualizationCheck(wsl))
            add(wslCheck(wsl))

            add(dockerDesktopCheck(docker))
            add(dockerCliCheck(docker))
            add(dockerComposeCheck(docker))
            add(dockerEngineCheck(docker))

            add(
                EnvironmentCheck(
                    id = "workspace",
                    label = "Рабочая папка",
                    passed = workspace != null,
                    message = workspace?.toString()
                        ?: "Распакуйте комплект целиком: рядом нужны infra, generation-agent и browser-agent",
                ),
            )
            add(nodeCheck(node))
            add(chromeCheck(chrome))

            if (workspace != null) {
                add(
                    configurationCheck(
                        id = "infra-config",
                        label = "Настройки инфраструктуры",
                        path = configuration?.infraEnvironmentFile,
                        error = configurationError,
                    ),
                )
                add(
                    configurationCheck(
                        id = "generation-config",
                        label = "Настройки Generation Agent",
                        path = configuration?.generationEnvironmentFile,
                        error = configurationError,
                    ),
                )
                if (configuration != null && servicePortInspection != null) {
                    add(servicePortsCheck(configuration, servicePortInspection))
                }
                add(browserAgentCheck(workspace.resolve("browser-agent")))
            }
        }

        val blocked = checks.any { !it.passed && it.severity == CheckSeverity.REQUIRED }
        PreflightResult(
            workspace = workspace,
            endpoints = configuration?.endpoints,
            configuration = configuration,
            dockerServices = servicePortInspection?.services
                ?: DockerServiceSnapshot.Unknown("Постоянные настройки HH Clicker недоступны"),
            state = PreflightState(
                status = if (blocked) PreflightStatus.BLOCKED else PreflightStatus.READY,
                checks = checks,
                wsl = wsl,
                docker = docker,
                node = node,
                chrome = chrome,
                portConflicts = servicePortInspection?.conflicts.orEmpty(),
            ),
        )
    }

    /**
     * Hardware virtualization is a manual blocker: the launcher only reports it. An undetermined result is
     * a warning instead of a hard block, otherwise a machine the probe cannot read would be a dead end.
     */
    private fun virtualizationCheck(wsl: WslDiagnostics): EnvironmentCheck = EnvironmentCheck(
        id = "virtualization",
        label = "Аппаратная виртуализация",
        passed = wsl.virtualization == VirtualizationState.ENABLED,
        message = when (wsl.virtualization) {
            VirtualizationState.ENABLED -> "Включена в BIOS/UEFI, процессор поддерживает SLAT"
            VirtualizationState.DISABLED_IN_FIRMWARE -> "Включите Intel VT-x или AMD-V (SVM) в BIOS/UEFI и перезагрузите компьютер"
            VirtualizationState.UNKNOWN -> "Состояние определить не удалось; убедитесь, что виртуализация включена в BIOS/UEFI"
        },
        severity = if (wsl.virtualization == VirtualizationState.UNKNOWN) CheckSeverity.OPTIONAL else CheckSeverity.REQUIRED,
    )

    /**
     * One row for the whole WSL prerequisite. The default version is folded into the message so that the
     * approved check list keeps its shape, while the independent states stay available in [WslDiagnostics].
     */
    private fun wslCheck(wsl: WslDiagnostics): EnvironmentCheck = EnvironmentCheck(
        id = "wsl",
        label = "WSL 2",
        passed = wsl.ready,
        message = when (wsl.installation) {
            WslInstallationState.NOT_INSTALLED ->
                "WSL 2 не установлен; лаунчер может установить его без дистрибутива Linux"

            WslInstallationState.OUTDATED ->
                "Установлен WSL ${wsl.installedVersion ?: "неизвестной версии"}; нужна ${WslDiagnosticsParser.minimumVersionText} или новее"

            WslInstallationState.CHECK_FAILED ->
                "Не удалось определить состояние WSL: команда wsl --version не вернула версию"

            WslInstallationState.COMPATIBLE -> when (wsl.defaultVersion) {
                WslDefaultVersionState.VERSION_2 -> "WSL ${wsl.installedVersion}, версия по умолчанию — WSL 2"
                WslDefaultVersionState.VERSION_1 -> "WSL ${wsl.installedVersion}; версия по умолчанию — WSL 1, нужен WSL 2"
                WslDefaultVersionState.UNKNOWN -> "WSL ${wsl.installedVersion}; требуется явно выбрать WSL 2 версией по умолчанию"
            }
        },
    )

    /**
     * The Docker Desktop application itself, independent of whatever `docker.exe` is on PATH.
     *
     * A supported but old version is a warning inside the message, never a separate blocker: the
     * launcher's real contract is behavioural, and a Linux engine on WSL 2 with Compose v2 is what
     * actually matters.
     */
    private fun dockerDesktopCheck(docker: DockerDiagnostics): EnvironmentCheck = EnvironmentCheck(
        id = "docker-desktop",
        label = "Docker Desktop",
        passed = docker.desktop == DockerDesktopState.INSTALLED,
        message = when (docker.desktop) {
            DockerDesktopState.NOT_INSTALLED ->
                "Не установлен; лаунчер может установить его для текущего пользователя с backend WSL 2"

            DockerDesktopState.OUTDATED ->
                "Версия ${docker.desktopVersion ?: "ниже поддерживаемой"}; нужна ${DockerVersionPolicy.minimumDesktop} или новее"

            DockerDesktopState.CHECK_FAILED ->
                "Не удалось определить, установлен ли Docker Desktop"

            DockerDesktopState.INSTALLED ->
                if (docker.updateRecommended) {
                    "Версия ${docker.desktopVersion ?: "неизвестна"}; работает, но обновление рекомендуется"
                } else {
                    "Версия ${docker.desktopVersion ?: "определена частично"}"
                }
        },
    )

    private fun dockerCliCheck(docker: DockerDiagnostics): EnvironmentCheck = EnvironmentCheck(
        id = "docker-cli",
        label = "Docker CLI",
        passed = docker.cli == DockerCliState.AVAILABLE,
        message = when (docker.cli) {
            DockerCliState.AVAILABLE -> "Docker ${docker.cliVersion}"
            DockerCliState.MISSING -> "Команда docker недоступна; установите Docker Desktop"
            DockerCliState.FAILED -> "Команда docker --version завершилась ошибкой"
        },
    )

    private fun dockerComposeCheck(docker: DockerDiagnostics): EnvironmentCheck = EnvironmentCheck(
        id = "docker-compose",
        label = "Docker Compose",
        passed = docker.compose == DockerComposeState.AVAILABLE,
        message = when (docker.compose) {
            DockerComposeState.AVAILABLE -> "Compose ${docker.composeVersion}"
            DockerComposeState.MISSING -> "Нужен Compose v2: команда docker compose недоступна"
            DockerComposeState.FAILED -> "Команда docker compose version завершилась ошибкой"
        },
    )

    /**
     * One row for engine reachability, container platform and backend.
     *
     * `OSType=linux` alone is not proof of WSL 2, because Linux containers also run on Hyper-V and on
     * Docker VMM, so an unconfirmed backend keeps this row red.
     */
    private fun dockerEngineCheck(docker: DockerDiagnostics): EnvironmentCheck = EnvironmentCheck(
        id = DOCKER_ENGINE_CHECK_ID,
        label = "Docker Engine",
        passed = docker.ready,
        message = when {
            docker.engine == DockerEngineState.STARTING -> "Docker Desktop запускается; дождитесь готовности Engine"
            docker.engine == DockerEngineState.UNAVAILABLE -> "Engine недоступен; запустите Docker Desktop"
            docker.engine == DockerEngineState.FAILED -> "Не удалось выполнить docker info"
            docker.containerMode == ContainerMode.WINDOWS -> "Включён режим Windows containers; нужен Linux"
            docker.containerMode == ContainerMode.UNKNOWN -> "docker info не сообщил платформу контейнеров"
            docker.backend == DockerBackend.NOT_WSL_2 -> "Engine работает не на WSL 2; включите Use WSL 2 based engine"
            docker.backend == DockerBackend.UNKNOWN -> "Backend WSL 2 не подтверждён в настройках Docker"
            docker.compose != DockerComposeState.AVAILABLE -> "Engine работает, но Compose v2 недоступен"
            else -> "Engine ${docker.engineVersion ?: "работает"}: Linux containers на WSL 2"
        },
    )

    /**
     * The Node.js row.
     *
     * A compatible system runtime wins. Otherwise the row offers the launcher-managed portable
     * download; it is isolated under LOCALAPPDATA and never changes the machine's PATH.
     */
    private fun nodeCheck(node: NodeRuntimeDiagnostics): EnvironmentCheck = EnvironmentCheck(
        id = "node",
        label = "Node.js",
        passed = node.ready,
        message = NodeRuntimePolicy.message(node),
    )

    /**
     * The Google Chrome row.
     *
     * The message never carries the discovered path: the screen shows short sentences, and the launcher
     * keeps file locations to itself. An unreadable check is not reported as "absent", so it can never
     * turn into an offer to install over an existing Chrome.
     */
    private fun chromeCheck(chrome: ChromeDiagnostics): EnvironmentCheck = EnvironmentCheck(
        id = "chrome",
        label = "Google Chrome",
        passed = chrome.ready,
        message = when (chrome.installation) {
            ChromeInstallationState.INSTALLED -> "Google Chrome установлен"
            ChromeInstallationState.NOT_INSTALLED ->
                "Не найден; лаунчер может скачать и установить официальную версию"

            ChromeInstallationState.CHECK_FAILED ->
                "Не удалось проверить, установлен ли Google Chrome"
        },
    )

    private fun browserAgentCheck(path: Path): EnvironmentCheck {
        val compiled = Files.isRegularFile(path.resolve("dist/main.js"))
        val development = Files.isRegularFile(path.resolve("src/main.ts")) && Files.isDirectory(path.resolve("node_modules/tsx"))
        return EnvironmentCheck(
            id = "browser-agent",
            label = "Browser Agent",
            passed = compiled || development,
            message = when {
                compiled -> "Найдена собранная версия dist/main.js"
                development -> "Найдена версия для разработки через tsx"
                else -> "В browser-agent выполните npm install и npm run build"
            },
        )
    }

    private fun configurationCheck(id: String, label: String, path: Path?, error: String?): EnvironmentCheck {
        val exists = path != null && Files.isRegularFile(path)
        return EnvironmentCheck(
            id = id,
            label = label,
            passed = exists,
            message = if (exists) path.parent.toString() else error ?: "Не удалось создать постоянные настройки HH Clicker",
        )
    }

    /**
     * The local ports row.
     *
     * A conflict is a completed query and therefore a proven fact: it keeps blocking the start even while
     * the rest of the Docker picture is undetermined. An undetermined picture on its own never blocks —
     * it only says so, with the reason, and the components keep their last observed state.
     */
    private fun servicePortsCheck(
        configuration: LauncherRuntimeConfiguration,
        inspection: ServicePortInspection,
    ): EnvironmentCheck {
        val conflicts = inspection.conflicts
        val unknown = inspection.services as? DockerServiceSnapshot.Unknown
        return EnvironmentCheck(
            id = "service-ports",
            label = "Локальные порты",
            passed = conflicts.isEmpty() && unknown == null,
            severity = if (conflicts.isNotEmpty() || unknown == null) CheckSeverity.REQUIRED else CheckSeverity.OPTIONAL,
            message = when {
                conflicts.isNotEmpty() -> "Заняты другими приложениями: " + conflicts.joinToString(", ") { conflict ->
                    val owner = conflict.shortOwnerDescription()
                    "${conflict.label} — ${conflict.port}" + (owner?.let { " ($it)" } ?: "")
                }

                unknown != null ->
                    "Временно не удалось проверить владельцев портов (${unknown.diagnostics}); " +
                        "состояние компонентов сохранено с прошлой успешной проверки"

                else ->
                    "Frontend ${configuration.endpoints.frontendPort}, Backend ${configuration.endpoints.backendPort}, " +
                        "Generation Agent ${configuration.endpoints.generationPort}"
            },
        )
    }

    private companion object {
        /** The engine row is the only one a cheap engine-only probe is allowed to rewrite. */
        const val DOCKER_ENGINE_CHECK_ID = "docker-server"
    }

    private fun PortConflict.shortOwnerDescription(): String? = when {
        hhClickerProfile != null -> "HH Clicker $hhClickerProfile"
        dockerContainerName != null -> "Docker: $dockerContainerName"
        else -> listOfNotNull(processName, processId?.let { "PID $it" })
            .joinToString(", ")
            .takeIf(String::isNotEmpty)
    }
}
