package org.hhclicker.launcher.model

enum class ComponentId {
    BACKEND,
    FRONTEND,
    GENERATION_AGENT,
    BROWSER_AGENT,
}

enum class ComponentStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

enum class LauncherStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

enum class PreflightStatus {
    NOT_CHECKED,
    CHECKING,
    READY,
    BLOCKED,
}

enum class CheckSeverity {
    REQUIRED,
    OPTIONAL,
}

data class EnvironmentCheck(
    val id: String,
    val label: String,
    val passed: Boolean,
    val message: String,
    val severity: CheckSeverity = CheckSeverity.REQUIRED,
)

data class PreflightState(
    val status: PreflightStatus = PreflightStatus.NOT_CHECKED,
    val checks: List<EnvironmentCheck> = emptyList(),
    /** Independent WSL facts behind the virtualization and WSL rows; null until the first check ran. */
    val wsl: WslDiagnostics? = null,
    /** Independent Docker facts behind the Docker rows; null until the first check ran. */
    val docker: DockerDiagnostics? = null,
    /** Independent Node runtime facts behind the Node.js row; null until the first check ran. */
    val node: NodeRuntimeDiagnostics? = null,
    /** Independent Chrome facts behind the Google Chrome row; null until the first check ran. */
    val chrome: ChromeDiagnostics? = null,
    /** Host ports occupied outside this exact launcher profile. */
    val portConflicts: List<PortConflict> = emptyList(),
) {
    val blockingCheck: EnvironmentCheck?
        get() = checks.firstOrNull { !it.passed && it.severity == CheckSeverity.REQUIRED }

    companion object {
        val ready = PreflightState(PreflightStatus.READY)
    }
}

data class LauncherComponentState(
    val id: ComponentId,
    val name: String,
    val description: String,
    val location: String,
    val status: ComponentStatus = ComponentStatus.STOPPED,
    val details: String? = null,
)

data class LauncherState(
    val status: LauncherStatus,
    val components: List<LauncherComponentState>,
    val workspacePath: String,
    val version: String = "",
    val preflight: PreflightState = PreflightState(),
    val setup: SetupState = SetupState(),
    val codexAuth: CodexAuthState = CodexAuthState(),
    val codexRuntime: CodexRuntimeInfo? = null,
    val error: String? = null,
) {
    val operationInProgress: Boolean
        get() = status == LauncherStatus.STARTING || status == LauncherStatus.STOPPING

    /** True while any launcher owned operation runs, including machine preparation. */
    val busy: Boolean
        get() = operationInProgress || setup.inProgress || codexAuth.busy

    companion object {
        fun stopped(workspacePath: String = "C:\\Projects\\hh-clicker") = LauncherState(
            status = LauncherStatus.STOPPED,
            workspacePath = workspacePath,
            components = listOf(
                LauncherComponentState(ComponentId.BACKEND, "Backend", "API и бизнес-логика", "http://localhost:8080"),
                LauncherComponentState(ComponentId.FRONTEND, "Frontend", "Веб-интерфейс", "http://localhost"),
                LauncherComponentState(ComponentId.GENERATION_AGENT, "Generation Agent", "Генерация сопроводительных", "http://localhost:17374"),
                LauncherComponentState(ComponentId.BROWSER_AGENT, "Browser Agent", "Автоматизация браузера", "Локальный процесс"),
            ),
        )
    }
}
