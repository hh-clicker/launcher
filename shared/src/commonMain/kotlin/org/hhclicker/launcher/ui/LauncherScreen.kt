package org.hhclicker.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import launcher.shared.generated.resources.Res
import launcher.shared.generated.resources.icon_browser
import launcher.shared.generated.resources.icon_external_link
import launcher.shared.generated.resources.icon_layout_dashboard
import launcher.shared.generated.resources.icon_player_play
import launcher.shared.generated.resources.icon_player_stop
import launcher.shared.generated.resources.icon_refresh
import launcher.shared.generated.resources.icon_server
import launcher.shared.generated.resources.icon_settings
import launcher.shared.generated.resources.icon_sparkles
import launcher.shared.generated.resources.icon_world
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.hhclicker.launcher.design.LauncherColors
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.model.ComponentStatus
import org.hhclicker.launcher.model.CodexAuthState
import org.hhclicker.launcher.model.CodexAuthStatus
import org.hhclicker.launcher.model.CodexRuntimeInfo
import org.hhclicker.launcher.model.CheckSeverity
import org.hhclicker.launcher.model.EnvironmentCheck
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.model.LauncherComponentState
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.LauncherStatus
import org.hhclicker.launcher.model.PreflightStatus
import org.hhclicker.launcher.model.PortConflict
import org.hhclicker.launcher.model.SetupState
import org.hhclicker.launcher.model.SetupStatus
import org.hhclicker.launcher.model.SetupTarget
import org.hhclicker.launcher.setup.ChromeSetupAction
import org.hhclicker.launcher.setup.ChromeSetupPlanner
import org.hhclicker.launcher.setup.DockerSetupAction
import org.hhclicker.launcher.setup.DockerSetupPlanner
import org.hhclicker.launcher.setup.NodeSetupAction
import org.hhclicker.launcher.setup.NodeSetupPlanner
import org.hhclicker.launcher.setup.WslSetupAction
import org.hhclicker.launcher.setup.WslSetupPlanner

enum class LauncherPage { MAIN, SETTINGS }

@Composable
fun LauncherScreen(
    state: LauncherState,
    page: LauncherPage = LauncherPage.MAIN,
    onNavigate: (LauncherPage) -> Unit = {},
    onPrimaryAction: () -> Unit,
    onStopAction: () -> Unit = {},
    onSelectFreePorts: () -> Unit = {},
    onRefreshEnvironment: () -> Unit = {},
    onPrepareWsl: () -> Unit = {},
    onPrepareDocker: (Boolean) -> Unit = {},
    onPrepareNode: () -> Unit = {},
    onPrepareChrome: (Boolean) -> Unit = {},
    onRefreshCodexAuth: () -> Unit = {},
    onStartCodexLogin: () -> Unit = {},
    onCancelCodexLogin: () -> Unit = {},
    onLogoutCodex: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF1F4F8)),
    ) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(state, page, onNavigate)
            Column(
                modifier = Modifier.fillMaxSize().padding(start = 22.dp, top = 20.dp, end = 22.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (page) {
                    LauncherPage.MAIN -> {
                        Hero(state, onPrimaryAction, onStopAction)
                        ComponentsPanel(state, Modifier.weight(1f))
                    }
                    LauncherPage.SETTINGS -> EnvironmentScreen(
                        state = state,
                        onRefresh = onRefreshEnvironment,
                        onSelectFreePorts = onSelectFreePorts,
                        onPrepareWsl = onPrepareWsl,
                        onPrepareDocker = onPrepareDocker,
                        onPrepareNode = onPrepareNode,
                        onPrepareChrome = onPrepareChrome,
                        onRefreshCodexAuth = onRefreshCodexAuth,
                        onStartCodexLogin = onStartCodexLogin,
                        onCancelCodexLogin = onCancelCodexLogin,
                        onLogoutCodex = onLogoutCodex,
                        onOpenUrl = onOpenUrl,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Sidebar(state: LauncherState, page: LauncherPage, onNavigate: (LauncherPage) -> Unit) {
    val settingsNeedAttention = state.preflight.status == PreflightStatus.BLOCKED ||
        state.status == LauncherStatus.FAILED ||
        state.setup.status == SetupStatus.FAILED ||
        state.setup.status == SetupStatus.RESTART_REQUIRED ||
        state.codexAuth.status == CodexAuthStatus.FAILED
    Column(
        modifier = Modifier.width(205.dp).fillMaxHeight()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        NavigationItem("Главная", Res.drawable.icon_layout_dashboard, selected = page == LauncherPage.MAIN) {
            onNavigate(LauncherPage.MAIN)
        }
        Spacer(Modifier.height(8.dp))
        NavigationItem(
            label = "Настройки",
            icon = Res.drawable.icon_settings,
            selected = page == LauncherPage.SETTINGS,
            attention = settingsNeedAttention,
        ) {
            onNavigate(LauncherPage.SETTINGS)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Версия ${state.version.ifBlank { "—" }}",
            color = LauncherColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NavigationItem(
    label: String,
    icon: DrawableResource,
    selected: Boolean,
    attention: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        selected -> LauncherColors.Primary.copy(alpha = .10f)
        hovered -> Color(0xFFEEF1F5)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(shape)
            .background(background)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TablerIcon(icon, if (selected) LauncherColors.Primary else LauncherColors.Neutral, 21.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (selected) LauncherColors.Text else LauncherColors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (attention) {
            Box(
                Modifier.size(17.dp).background(LauncherColors.Red, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "!",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun EnvironmentScreen(
    state: LauncherState,
    onRefresh: () -> Unit,
    onSelectFreePorts: () -> Unit,
    onPrepareWsl: () -> Unit,
    onPrepareDocker: (Boolean) -> Unit,
    onPrepareNode: () -> Unit,
    onPrepareChrome: (Boolean) -> Unit,
    onRefreshCodexAuth: () -> Unit,
    onStartCodexLogin: () -> Unit,
    onCancelCodexLogin: () -> Unit,
    onLogoutCodex: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val checks = state.preflight.checks
    val passedChecks = checks.count { it.passed }
    val blocked = state.preflight.status == PreflightStatus.BLOCKED
    val wslAction = WslSetupPlanner.decide(state.preflight.wsl)
    val dockerAction = DockerSetupPlanner.decide(state.preflight.wsl, state.preflight.docker)
    val nodeAction = NodeSetupPlanner.decide(state.preflight.node, state.setup.inProgress)
    val chromeAction = ChromeSetupPlanner.decide(state.preflight.chrome, state.setup.inProgress)
    val stage = when (state.preflight.platform) {
        HostPlatform.WINDOWS -> resolveSetupStage(state, wslAction, dockerAction, nodeAction, chromeAction)
        HostPlatform.LINUX -> resolveLinuxSetupStage(state)
        else -> null
    }
    val showPortConflicts = state.preflight.portConflicts.isNotEmpty() &&
        (stage == null || state.setup.status == SetupStatus.SUCCEEDED)
    GlassPanel(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (blocked) "Подготовка компьютера" else "Проверка окружения", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            state.preflight.platform == HostPlatform.LINUX &&
                                state.setup.status == SetupStatus.RESTART_REQUIRED &&
                                checks.any { it.id in setOf("node", "chrome") && !it.passed } ->
                                "Установите остальные зависимости, затем один раз выйдите из сеанса Debian и войдите снова"
                            state.setup.status == SetupStatus.RESTART_REQUIRED ->
                                state.setup.message ?: "Перезагрузите компьютер и запустите лаунчер снова"
                            blocked -> "Выполните обязательные пункты и запустите проверку снова"
                            else -> "Все зависимости для локального запуска доступны"
                        },
                        color = LauncherColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (checks.isNotEmpty()) {
                    Text(
                        "$passedChecks из ${checks.size}",
                        color = LauncherColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                RefreshButton(
                    enabled = state.preflight.status != PreflightStatus.CHECKING && !state.setup.inProgress,
                    checking = state.preflight.status == PreflightStatus.CHECKING,
                    onClick = onRefresh,
                )
            }
            Spacer(Modifier.height(22.dp))
            Divider()
            if (stage != null && !showPortConflicts) {
                SetupSection(
                    presentation = when (stage) {
                        SetupTarget.WSL -> wslPresentation(state, wslAction, onPrepareWsl)
                        SetupTarget.DOCKER -> if (state.preflight.platform == HostPlatform.LINUX)
                            linuxPackagePresentation(state, SetupTarget.DOCKER, onOpenUrl) { onPrepareDocker(false) }
                            else dockerPresentation(state, dockerAction, onPrepareDocker)
                        SetupTarget.NODE -> nodePresentation(state, nodeAction, onPrepareNode)
                        SetupTarget.CHROME -> if (state.preflight.platform == HostPlatform.LINUX)
                            linuxPackagePresentation(state, SetupTarget.CHROME, onOpenUrl) { onPrepareChrome(true) }
                            else chromePresentation(state, chromeAction, onPrepareChrome)
                    },
                    onOpenUrl = onOpenUrl,
                )
                Divider()
            } else if (showPortConflicts) {
                SetupSection(
                    presentation = portConflictPresentation(state.preflight.portConflicts, onSelectFreePorts),
                    onOpenUrl = onOpenUrl,
                )
                Divider()
            }
            if (state.preflight.status == PreflightStatus.READY) {
                CodexAuthSection(
                    auth = state.codexAuth,
                    runtime = state.codexRuntime,
                    onRefresh = onRefreshCodexAuth,
                    onLogin = onStartCodexLogin,
                    onCancel = onCancelCodexLogin,
                    onLogout = onLogoutCodex,
                    onOpenUrl = onOpenUrl,
                )
                Divider()
            }
            if (checks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Проверяем Docker, Node.js, Chrome и файлы конфигурации…", color = LauncherColors.Muted)
                }
            } else {
                val scrollState = rememberScrollState()
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(end = 11.dp).verticalScroll(scrollState),
                    ) {
                        checks.forEachIndexed { index, check ->
                            EnvironmentCheckItem(check, Modifier.height(50.dp))
                            if (index != checks.lastIndex) Divider()
                        }
                    }
                    if (scrollState.maxValue > 0) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scrollState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp),
                            style = ScrollbarStyle(
                                minimalHeight = 32.dp,
                                thickness = 3.dp,
                                shape = RoundedCornerShape(2.dp),
                                hoverDurationMillis = 120,
                                unhoverColor = LauncherColors.Muted.copy(alpha = .32f),
                                hoverColor = LauncherColors.Primary.copy(alpha = .62f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun portConflictPresentation(
    conflicts: List<PortConflict>,
    onSelectFreePorts: () -> Unit,
): SetupPresentation = SetupPresentation(
    accent = LauncherColors.Red,
    title = "Заняты локальные порты",
    description = conflicts.joinToString("; ") { conflict ->
        val owner = when {
            conflict.hhClickerProfile != null ->
                "HH Clicker (${conflict.hhClickerProfile}), контейнер ${conflict.dockerContainerName ?: "без имени"}"
            conflict.dockerContainerName != null -> "Docker-контейнер ${conflict.dockerContainerName}"
            conflict.processName != null && conflict.processId != null ->
                "${conflict.processName}, PID ${conflict.processId}"
            conflict.processName != null -> conflict.processName
            conflict.processId != null -> "PID ${conflict.processId}"
            else -> "владелец не определён"
        }
        "${conflict.label} ${conflict.port} — $owner"
    },
    buttonLabel = "Выбрать свободные порты",
    onClick = onSelectFreePorts,
)

@Composable
private fun CodexAuthSection(
    auth: CodexAuthState,
    runtime: CodexRuntimeInfo?,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val userCode = auth.userCode
    var copied by remember(userCode) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when (auth.status) {
                    CodexAuthStatus.SIGNED_IN -> "Codex подключён"
                    CodexAuthStatus.AUTHORIZING -> "Вход через ChatGPT"
                    CodexAuthStatus.FAILED -> "Не удалось проверить Codex"
                    CodexAuthStatus.CHECKING -> "Проверяем Codex"
                    else -> "Авторизация Codex"
                },
                color = when (auth.status) {
                    CodexAuthStatus.SIGNED_IN -> LauncherColors.Green
                    CodexAuthStatus.FAILED -> LauncherColors.Red
                    CodexAuthStatus.AUTHORIZING -> LauncherColors.Primary
                    else -> LauncherColors.Text
                },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    auth.status == CodexAuthStatus.AUTHORIZING && auth.userCode != null ->
                        "Введите код на открывшейся странице"
                    else -> auth.message ?: "Вход хранится только в локальном Docker volume"
                },
                color = LauncherColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (runtime != null) {
                Text(
                    listOfNotNull(runtime.model, runtime.reasoningEffort, runtime.cliVersion?.let { "CLI $it" })
                        .joinToString(" · "),
                    color = LauncherColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (auth.status == CodexAuthStatus.SIGNED_IN && auth.limitsSummary != null) {
                Text(auth.limitsSummary, color = LauncherColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                auth.checkedAtLabel?.let { Text("Проверено: $it", color = LauncherColors.Muted, style = MaterialTheme.typography.bodySmall) }
                auth.limitsResetSummary?.let { Text("Сброс — $it", color = LauncherColors.Muted, style = MaterialTheme.typography.bodySmall) }
            }
            if (auth.status == CodexAuthStatus.AUTHORIZING && userCode != null) {
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFFF1F3F6), shape = RoundedCornerShape(7.dp)) {
                        SelectionContainer {
                            Text(
                                userCode,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                color = LauncherColors.Text,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextActionButton(if (copied) "Скопировано" else "Копировать") {
                        scope.launch {
                            try {
                                clipboard.setClipEntry(plainTextClipEntry(userCode))
                                copied = true
                            } catch (_: Exception) {
                                copied = false
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        when (auth.status) {
            CodexAuthStatus.SIGNED_IN -> SetupActionButton("Выйти из Codex", true, onLogout)
            CodexAuthStatus.AUTHORIZING -> {
                if (auth.verificationUrl != null) {
                    SetupActionButton("Открыть страницу", true) { onOpenUrl(auth.verificationUrl) }
                    Spacer(Modifier.width(8.dp))
                }
                TextActionButton("Отменить", onCancel)
            }
            CodexAuthStatus.SIGNED_OUT -> SetupActionButton("Войти через ChatGPT", true, onLogin)
            CodexAuthStatus.FAILED -> TextActionButton("Повторить", onRefresh)
            CodexAuthStatus.UNAVAILABLE -> Unit
            CodexAuthStatus.CHECKING -> SetupActionButton("Проверяем…", false, {})
        }
    }
}

@Composable
private fun TextActionButton(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (hovered) LauncherColors.PrimarySoft else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        color = LauncherColors.Primary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun RefreshButton(enabled: Boolean, checking: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        !enabled -> LauncherColors.NeutralSoft
        hovered -> LauncherColors.PrimarySoft
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(background)
            .then(
                if (enabled) {
                    Modifier.hoverable(interactionSource).clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TablerIcon(Res.drawable.icon_refresh, if (enabled) LauncherColors.Primary else LauncherColors.Muted, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (checking) "Проверяем…" else "Проверить снова",
            color = if (enabled) LauncherColors.Primary else LauncherColors.Muted,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
/**
 * Everything the single preparation block renders, already resolved from the diagnostics.
 *
 * The screen never picks a command: it renders one action decided by [WslSetupPlanner] or
 * [DockerSetupPlanner], with an optional licence notice attached to it.
 */
internal data class SetupPresentation(
    val accent: Color,
    val title: String,
    val description: String,
    /** Sentence stating what the button accepts, and the address of those terms. Both or neither. */
    val noticeText: String? = null,
    val noticeUrl: String? = null,
    val buttonLabel: String? = null,
    val buttonEnabled: Boolean = true,
    val onClick: () -> Unit = {},
)

/**
 * Chooses which prerequisite the block talks about.
 *
 * An unread result of a running or finished operation always wins, so a restart request or an error is
 * never replaced by the next suggestion. Otherwise WSL comes first and Docker becomes the next action
 * only once WSL needs nothing.
 */
private fun resolveSetupStage(
    state: LauncherState,
    wslAction: WslSetupAction,
    dockerAction: DockerSetupAction,
    nodeAction: NodeSetupAction,
    chromeAction: ChromeSetupAction,
): SetupTarget? {
    val setup = state.setup
    val unread = setup.status == SetupStatus.PREPARING ||
        setup.status == SetupStatus.RESTART_REQUIRED ||
        setup.status == SetupStatus.FAILED
    return when {
        unread -> setup.target
        // WSL 2 first, then Docker, an isolated Node runtime when PATH has no compatible one, and Chrome.
        wslAction != WslSetupAction.NONE -> SetupTarget.WSL
        dockerAction != DockerSetupAction.NONE -> SetupTarget.DOCKER
        nodeAction != NodeSetupAction.NONE -> SetupTarget.NODE
        chromeAction != ChromeSetupAction.NONE -> SetupTarget.CHROME
        setup.status == SetupStatus.SUCCEEDED -> setup.target
        else -> null
    }
}

internal fun resolveLinuxSetupStage(state: LauncherState): SetupTarget? {
    val setup = state.setup
    if (setup.status == SetupStatus.PREPARING || setup.status == SetupStatus.FAILED) return setup.target
    val failed = state.preflight.checks.filter { !it.passed }.map { it.id }.toSet()
    return when {
        failed.any { it in setOf("docker-cli", "docker-compose") } -> SetupTarget.DOCKER
        "node" in failed -> SetupTarget.NODE
        "chrome" in failed -> SetupTarget.CHROME
        "docker-server" in failed -> SetupTarget.DOCKER
        setup.status == SetupStatus.RESTART_REQUIRED -> setup.target
        setup.status == SetupStatus.SUCCEEDED -> setup.target
        else -> null
    }
}

internal fun linuxPackagePresentation(
    state: LauncherState,
    target: SetupTarget,
    onOpenUrl: (String) -> Unit,
    onPrepare: () -> Unit,
): SetupPresentation {
    val docker = target == SetupTarget.DOCKER
    val setup = state.setup
    val engineOnly = docker && state.preflight.checks.none {
        it.id in setOf("docker-cli", "docker-compose") && !it.passed
    } && state.preflight.checks.any { it.id == "docker-server" && !it.passed }
    if (!state.preflight.linuxPackageAutoInstallSupported) {
        val url = when {
            engineOnly -> "https://docs.docker.com/engine/install/linux-postinstall/"
            docker -> "https://docs.docker.com/engine/install/"
            else -> "https://support.google.com/chrome/answer/95346"
        }
        return SetupPresentation(
            accent = LauncherColors.Text,
            title = when { engineOnly -> "Настроить Docker Engine"; docker -> "Установить Docker"; else -> "Установить Google Chrome" },
            description = when {
                engineOnly -> "Проверьте запуск службы Docker и доступ текущего пользователя к ней без sudo. Затем повторите проверку"
                docker -> "Установите Docker Engine, CLI и Compose plugin по инструкции для своей системы. Запустите службу и настройте доступ без sudo"
                else -> "Установите Google Chrome для своей системы. После установки повторите проверку окружения"
            },
            buttonLabel = "Инструкция",
            buttonEnabled = !state.busy,
            onClick = { onOpenUrl(url) },
        )
    }
    val mine = setup.target == target
    val preparing = mine && setup.inProgress
    val failed = mine && setup.status == SetupStatus.FAILED
    val finished = mine && setup.status == SetupStatus.SUCCEEDED
    val restart = mine && setup.status == SetupStatus.RESTART_REQUIRED
    return SetupPresentation(
        accent = when { failed -> LauncherColors.Red; restart -> LauncherColors.Orange
            finished -> LauncherColors.Green; else -> LauncherColors.Text },
        title = when {
            restart -> "Войдите в Debian снова"
            preparing -> if (docker) "Подготовка Docker" else "Подготовка Google Chrome"
            failed -> if (docker) "Не удалось подготовить Docker" else "Не удалось установить Google Chrome"
            finished -> if (docker) "Docker готов" else "Google Chrome готов"
            engineOnly -> "Docker Engine недоступен"
            else -> if (docker) "Установить Docker" else "Установить Google Chrome"
        },
        description = when {
            preparing -> progressDescription(setup)
            failed || finished || restart -> setup.message.orEmpty()
            engineOnly -> "Если Docker только что установлен, выйдите из сеанса Debian и войдите снова. Если ошибка останется, проверьте службу Docker и доступ пользователя к ней"
            docker -> "Лаунчер установит Docker Engine и Compose из репозитория Debian 13 и добавит текущего пользователя в группу docker. Потребуются права администратора и повторный вход в сеанс"
            else -> "Лаунчер скачает официальный пакет Google Chrome и установит его через apt. Потребуются права администратора"
        },
        noticeText = ChromeSetupPlanner.TERMS_NOTICE.takeIf { !docker && !preparing },
        noticeUrl = ChromeSetupPlanner.TERMS_URL.takeIf { !docker && !preparing },
        buttonLabel = when { preparing -> "Подготовка…"; restart || finished || engineOnly -> null
            failed -> "Повторить"; else -> "Установить" },
        buttonEnabled = !state.busy,
        onClick = onPrepare,
    )
}

private fun nodePresentation(
    state: LauncherState,
    action: NodeSetupAction,
    onPrepareNode: () -> Unit,
): SetupPresentation {
    val setup = state.setup
    val mine = setup.target == SetupTarget.NODE
    val preparing = mine && setup.inProgress
    val failed = mine && setup.status == SetupStatus.FAILED
    val succeeded = mine && setup.status == SetupStatus.SUCCEEDED
    return SetupPresentation(
        accent = when { failed -> LauncherColors.Red; succeeded -> LauncherColors.Green; else -> LauncherColors.Text },
        title = when {
            preparing -> "Подготовка Node.js"
            failed -> "Не удалось подготовить Node.js"
            succeeded -> "Node.js готов"
            else -> "Скачать локальный Node.js"
        },
        description = when {
            preparing -> progressDescription(setup)
            failed || succeeded -> setup.message.orEmpty()
            else -> "Системный Node.js отсутствует или не подходит. Лаунчер скачает portable runtime только для текущего пользователя, не изменяя PATH"
        },
        buttonLabel = when {
            preparing -> "Подготовка…"
            failed -> "Повторить"
            action == NodeSetupAction.DOWNLOAD_PORTABLE -> "Скачать Node.js"
            else -> null
        },
        buttonEnabled = !state.busy,
        onClick = onPrepareNode,
    )
}

/**
 * Presentation of the WSL 2 step.
 *
 * Disabled hardware virtualization replaces the button with an explanation, because only the user can
 * change BIOS/UEFI, and a required restart is shown as the main result instead of a success message.
 */
private fun wslPresentation(
    state: LauncherState,
    action: WslSetupAction,
    onPrepareWsl: () -> Unit,
): SetupPresentation {
    val setup = state.setup
    val mine = setup.target == SetupTarget.WSL
    val preparing = mine && setup.inProgress
    val failed = mine && setup.status == SetupStatus.FAILED
    val succeeded = mine && setup.status == SetupStatus.SUCCEEDED
    val restartRequired = mine && setup.status == SetupStatus.RESTART_REQUIRED

    val accent = when {
        restartRequired -> LauncherColors.Orange
        failed -> LauncherColors.Red
        succeeded -> LauncherColors.Green
        action == WslSetupAction.BLOCKED_BY_FIRMWARE -> LauncherColors.Orange
        else -> LauncherColors.Text
    }
    val title = when {
        restartRequired -> "Перезагрузите компьютер"
        preparing -> "Подготовка WSL 2"
        failed -> "Не удалось подготовить WSL 2"
        action == WslSetupAction.BLOCKED_BY_FIRMWARE -> "Сначала включите виртуализацию"
        action == WslSetupAction.DIAGNOSTICS_FAILED -> "Состояние WSL не определено"
        action == WslSetupAction.INSTALL -> "Установить WSL 2"
        action == WslSetupAction.UPDATE -> "Обновить WSL"
        action == WslSetupAction.SET_DEFAULT_VERSION -> "Включить режим WSL 2"
        succeeded -> "WSL 2 настроен"
        else -> "WSL 2"
    }
    // Title and description follow the same precedence: an unread result first, then the offered action.
    val description = when {
        restartRequired || preparing || failed -> setup.message.orEmpty()
        action == WslSetupAction.BLOCKED_BY_FIRMWARE ->
            "Включите Intel VT-x или AMD-V (SVM) в BIOS/UEFI и запустите проверку снова. Лаунчер не меняет настройки BIOS"
        action == WslSetupAction.DIAGNOSTICS_FAILED ->
            "Команда wsl --version не вернула версию. Проверьте WSL вручную и запустите проверку снова"
        action == WslSetupAction.INSTALL ->
            "Лаунчер включит компоненты Windows и установит WSL 2 без дистрибутива Linux. Нужны права администратора и перезагрузка"
        action == WslSetupAction.UPDATE ->
            "Установленная версия WSL слишком старая. Лаунчер обновит её; нужны права администратора и перезагрузка"
        action == WslSetupAction.SET_DEFAULT_VERSION ->
            "Версия по умолчанию должна быть WSL 2. Лаунчер переключит её; нужны права администратора"
        succeeded -> setup.message.orEmpty()
        else -> "Дополнительных действий не требуется"
    }
    val buttonLabel = when {
        !action.executable || restartRequired -> null
        preparing -> "Подготовка…"
        failed -> "Повторить"
        action == WslSetupAction.INSTALL -> "Установить WSL 2"
        action == WslSetupAction.UPDATE -> "Обновить WSL"
        else -> "Включить WSL 2"
    }

    return SetupPresentation(
        accent = accent,
        title = title,
        description = description,
        buttonLabel = buttonLabel,
        buttonEnabled = !state.busy,
        onClick = onPrepareWsl,
    )
}

/**
 * Presentation of the Docker Desktop step.
 *
 * An installation is the only action that carries the licence notice, and its button is the only place
 * where the Docker Subscription Service Agreement is accepted — by that click and nothing else.
 */
private fun dockerPresentation(
    state: LauncherState,
    action: DockerSetupAction,
    onPrepareDocker: (Boolean) -> Unit,
): SetupPresentation {
    val setup = state.setup
    val mine = setup.target == SetupTarget.DOCKER
    val preparing = mine && setup.inProgress
    val failed = mine && setup.status == SetupStatus.FAILED
    val succeeded = mine && setup.status == SetupStatus.SUCCEEDED

    val blocked = action == DockerSetupAction.BLOCKED_BY_WSL ||
        action == DockerSetupAction.BLOCKED_BY_ARCHITECTURE ||
        action == DockerSetupAction.BLOCKED_BY_CONTAINER_MODE ||
        action == DockerSetupAction.BLOCKED_BY_BACKEND ||
        action == DockerSetupAction.BACKEND_UNCONFIRMED ||
        action == DockerSetupAction.CLI_WITHOUT_DESKTOP

    val accent = when {
        failed -> LauncherColors.Red
        succeeded -> LauncherColors.Green
        blocked -> LauncherColors.Orange
        else -> LauncherColors.Text
    }
    val title = when {
        preparing -> "Подготовка Docker Desktop"
        failed -> "Не удалось подготовить Docker"
        action == DockerSetupAction.BLOCKED_BY_WSL -> "Сначала завершите настройку WSL 2"
        action == DockerSetupAction.BLOCKED_BY_ARCHITECTURE -> "Автоустановка доступна только на x64"
        action == DockerSetupAction.BLOCKED_BY_CONTAINER_MODE -> "Нужен режим Linux containers"
        action == DockerSetupAction.BLOCKED_BY_BACKEND -> "Docker работает не на WSL 2"
        action == DockerSetupAction.BACKEND_UNCONFIRMED -> "Backend WSL 2 не подтверждён"
        action == DockerSetupAction.CLI_WITHOUT_DESKTOP -> "Docker Desktop не найден"
        action == DockerSetupAction.DIAGNOSTICS_FAILED -> "Состояние Docker не определено"
        action == DockerSetupAction.INSTALL -> "Установить Docker Desktop"
        action == DockerSetupAction.UPDATE -> "Обновить Docker Desktop"
        action == DockerSetupAction.START_DESKTOP -> "Запустить Docker Desktop"
        action == DockerSetupAction.WAIT_FOR_ENGINE -> "Docker Desktop запускается"
        succeeded -> "Docker Desktop готов"
        else -> "Docker Desktop"
    }
    val description = when {
        preparing -> progressDescription(setup)
        failed || succeeded -> setup.message.orEmpty()
        action == DockerSetupAction.INSTALL ->
            "Лаунчер скачает официальный установщик, проверит его подпись и установит Docker Desktop для текущего пользователя с backend WSL 2 и Linux containers"
        action == DockerSetupAction.UPDATE ->
            "Установленная версия ниже поддерживаемой. Лаунчер установит актуальную поверх неё, не меняя режим установки и не удаляя данные"
        action == DockerSetupAction.START_DESKTOP ->
            "Docker Desktop установлен, но не запущен. Лаунчер запустит его и дождётся готовности Engine"
        action == DockerSetupAction.WAIT_FOR_ENGINE ->
            "Docker Desktop уже работает, Engine ещё поднимается. Лаунчер дождётся его в пределах таймаута"
        blocked || action == DockerSetupAction.DIAGNOSTICS_FAILED ->
            DockerSetupPlanner.refusalState(action).message.orEmpty()
        else -> "Дополнительных действий не требуется"
    }
    val buttonLabel = when {
        !action.executable -> null
        preparing -> "Подготовка…"
        failed -> "Повторить"
        action == DockerSetupAction.INSTALL || action == DockerSetupAction.UPDATE ->
            DockerSetupPlanner.INSTALL_BUTTON_LABEL
        action == DockerSetupAction.START_DESKTOP -> "Запустить Docker"
        else -> "Дождаться Docker Engine"
    }

    return SetupPresentation(
        accent = accent,
        title = title,
        description = description,
        noticeText = DockerSetupPlanner.LICENSE_NOTICE.takeIf { action.requiresLicenseAcceptance && !preparing },
        noticeUrl = DockerSetupPlanner.LICENSE_URL.takeIf { action.requiresLicenseAcceptance && !preparing },
        buttonLabel = buttonLabel,
        buttonEnabled = !state.busy,
        // The click itself is the explicit acceptance, and only for an action that installs.
        onClick = { onPrepareDocker(action.requiresLicenseAcceptance) },
    )
}

/**
 * Presentation of the Google Chrome step.
 *
 * The install button is the only place where Google terms are accepted — by that click and nothing
 * else. An acceptance given earlier for Docker never reaches it. An installed Chrome produces no
 * action at all: the launcher never offers to update, repair or reinstall a browser that already
 * exists.
 */
private fun chromePresentation(
    state: LauncherState,
    action: ChromeSetupAction,
    onPrepareChrome: (Boolean) -> Unit,
): SetupPresentation {
    val setup = state.setup
    val mine = setup.target == SetupTarget.CHROME
    val preparing = mine && setup.inProgress
    val failed = mine && setup.status == SetupStatus.FAILED
    val succeeded = mine && setup.status == SetupStatus.SUCCEEDED

    val blocked = action == ChromeSetupAction.BLOCKED_BY_ARCHITECTURE ||
        action == ChromeSetupAction.ALREADY_IN_PROGRESS

    val accent = when {
        failed -> LauncherColors.Red
        succeeded -> LauncherColors.Green
        blocked -> LauncherColors.Orange
        else -> LauncherColors.Text
    }
    val title = when {
        preparing -> "Подготовка Google Chrome"
        failed -> "Не удалось установить Google Chrome"
        action == ChromeSetupAction.BLOCKED_BY_ARCHITECTURE -> "Автоустановка доступна только на x64"
        action == ChromeSetupAction.DIAGNOSTICS_FAILED -> "Состояние Chrome не определено"
        action == ChromeSetupAction.ALREADY_IN_PROGRESS -> "Установка уже выполняется"
        action == ChromeSetupAction.INSTALL -> ChromeSetupPlanner.INSTALL_TITLE
        succeeded -> "Google Chrome готов"
        else -> "Google Chrome"
    }
    val description = when {
        preparing -> progressDescription(setup)
        failed || succeeded -> setup.message.orEmpty()
        action == ChromeSetupAction.INSTALL -> ChromeSetupPlanner.INSTALL_DESCRIPTION
        blocked || action == ChromeSetupAction.DIAGNOSTICS_FAILED ->
            ChromeSetupPlanner.refusalState(action).message.orEmpty()
        else -> "Дополнительных действий не требуется"
    }
    val buttonLabel = when {
        // While the operation runs the action is ALREADY_IN_PROGRESS; the disabled button stays visible
        // so the card keeps its shape, exactly as it does for Docker.
        preparing -> "Подготовка…"
        !action.executable -> null
        failed -> "Повторить"
        else -> ChromeSetupPlanner.INSTALL_BUTTON_LABEL
    }
    val showNotice = action.requiresTermsAcceptance && !preparing

    return SetupPresentation(
        accent = accent,
        title = title,
        description = description,
        noticeText = ChromeSetupPlanner.TERMS_NOTICE.takeIf { showNotice },
        noticeUrl = ChromeSetupPlanner.TERMS_URL.takeIf { showNotice },
        buttonLabel = buttonLabel,
        buttonEnabled = !state.busy,
        // The click itself is the explicit acceptance of Google terms, and only for an installation.
        onClick = { onPrepareChrome(action.requiresTermsAcceptance) },
    )
}

/** Adds the download figures to the stage message while a download is running. */
private fun progressDescription(setup: SetupState): String {
    val message = setup.message.orEmpty()
    val downloaded = setup.downloadedBytes ?: return message
    val total = setup.totalBytes
    val percent = setup.progressPercent
    return when {
        total != null && percent != null ->
            "$message · ${megabytes(downloaded)} из ${megabytes(total)} МБ ($percent%)"
        else -> "$message · ${megabytes(downloaded)} МБ"
    }
}

private fun megabytes(bytes: Long): Long = bytes / (1024L * 1024L)

/**
 * The single preparation block of the "Подготовка компьютера" screen.
 *
 * One action at a time, in the approved visual language: no extra frames, no gradients, no modal window.
 */
@Composable
private fun SetupSection(presentation: SetupPresentation, onOpenUrl: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                presentation.title,
                color = presentation.accent,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (presentation.description.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    presentation.description,
                    color = LauncherColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    // A long failure message must not be able to grow the card.
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (presentation.noticeText != null && presentation.noticeUrl != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    presentation.noticeText,
                    color = LauncherColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                TermsLink(presentation.noticeUrl, onOpenUrl)
            }
        }
        if (presentation.buttonLabel != null) {
            Spacer(Modifier.width(16.dp))
            SetupActionButton(
                label = presentation.buttonLabel,
                enabled = presentation.buttonEnabled,
                onClick = presentation.onClick,
            )
        }
    }
}

/** The clickable address of the terms the button accepts. Only launcher constants are ever passed. */
@Composable
private fun TermsLink(url: String, onOpenUrl: (String) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Text(
        url,
        modifier = Modifier
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onOpenUrl(url) },
            ),
        color = if (hovered) Color(0xFFFF6A1A) else LauncherColors.Primary,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SetupActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        !enabled -> Color(0xFFFFA278)
        hovered -> Color(0xFFFF6A1A)
        else -> LauncherColors.Primary
    }
    Row(
        modifier = Modifier.height(42.dp).clip(RoundedCornerShape(10.dp)).background(background)
            .then(
                if (enabled) {
                    Modifier.hoverable(interactionSource).clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            // The button keeps its one-line shape whatever the label is.
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun EnvironmentCheckItem(check: EnvironmentCheck, modifier: Modifier = Modifier) {
    val accent = when {
        check.passed -> LauncherColors.Green
        check.severity == CheckSeverity.OPTIONAL -> LauncherColors.Orange
        else -> LauncherColors.Red
    }
    val soft = when {
        check.passed -> LauncherColors.GreenSoft
        check.severity == CheckSeverity.OPTIONAL -> LauncherColors.OrangeSoft
        else -> LauncherColors.RedSoft
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(soft, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (check.passed) "✓" else "!", color = accent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(check.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                if (check.severity == CheckSeverity.OPTIONAL) {
                    Spacer(Modifier.width(8.dp))
                    Text("НЕОБЯЗАТЕЛЬНО", color = LauncherColors.Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                check.message,
                color = LauncherColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (check.passed) "Готово" else if (check.severity == CheckSeverity.OPTIONAL) "Можно пропустить" else "Требуется",
            color = accent,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Hero(state: LauncherState, onPrimaryAction: () -> Unit, onStopAction: () -> Unit) {
    val authUnconfirmed = state.status == LauncherStatus.RUNNING && state.codexAuth.status != CodexAuthStatus.SIGNED_IN
    GlassPanel(Modifier.fillMaxWidth().height(126.dp)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 32.dp)) {
                Text("ЛОКАЛЬНОЕ ОКРУЖЕНИЕ", color = LauncherColors.Muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(5.dp))
                Text(
                    if (authUnconfirmed) "Сервисы работают" else state.headline,
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (authUnconfirmed) state.codexAuth.message ?: "Авторизация Codex ещё не проверена" else state.supportingText,
                    color = LauncherColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryAction(state, onPrimaryAction)
                if (!state.busy && state.status != LauncherStatus.FAILED &&
                    state.components.any { it.status == ComponentStatus.RUNNING }
                ) {
                    Spacer(Modifier.height(5.dp))
                    SecondaryStopAction(onStopAction)
                }
            }
        }
    }
}

@Composable
private fun SecondaryStopAction(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier.height(25.dp)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (hovered) LauncherColors.Primary else LauncherColors.TextSecondary
        TablerIcon(Res.drawable.icon_player_stop, color, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            "Остановить сервисы",
            color = color,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PrimaryAction(state: LauncherState, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val enabled = !state.busy && state.preflight.status != PreflightStatus.CHECKING
    val buttonColor = when {
        !enabled -> Color(0xFFFFA278)
        hovered -> Color(0xFFFF6A1A)
        else -> LauncherColors.Primary
    }
    Row(
        modifier = Modifier.width(210.dp).height(52.dp).clip(shape)
            .background(buttonColor)
            .then(if (enabled) Modifier.hoverable(interactionSource).clickable(interactionSource = interactionSource, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val actionIcon = when {
            state.preflight.status != PreflightStatus.READY -> Res.drawable.icon_refresh
            state.status == LauncherStatus.RUNNING -> Res.drawable.icon_external_link
            state.status == LauncherStatus.STOPPING -> Res.drawable.icon_player_stop
            else -> Res.drawable.icon_player_play
        }
        TablerIcon(actionIcon, Color.White, 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            state.primaryActionLabel,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ComponentsPanel(state: LauncherState, modifier: Modifier = Modifier) {
    GlassPanel(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Компоненты", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(7.dp).background(state.statusColor, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(state.summaryLabel, color = LauncherColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 10.dp)) {
                Text("КОМПОНЕНТ", modifier = Modifier.weight(1.2f), color = LauncherColors.Muted, style = MaterialTheme.typography.labelMedium)
                Text("АДРЕС / РАСПОЛОЖЕНИЕ", modifier = Modifier.weight(1.15f), color = LauncherColors.Muted, style = MaterialTheme.typography.labelMedium)
                Text("СОСТОЯНИЕ", modifier = Modifier.width(135.dp), color = LauncherColors.Muted, style = MaterialTheme.typography.labelMedium)
            }
            Divider()
            state.components.forEachIndexed { index, component ->
                ComponentItem(component, Modifier.weight(1f), if (component.id == ComponentId.GENERATION_AGENT) state.codexAuth else null)
                if (index != state.components.lastIndex) Divider()
            }
        }
    }
}

/**
 * One component row.
 *
 * A failure reason is collected for every component and used to be dropped on the floor, so a red
 * "Ошибка" badge carried no explanation at all. It is shown here on its own full-width line under the
 * row instead of inside the name column: the reason is a sentence, and squeezing it between the name and
 * the status badge would either truncate it after a few words or push the grid apart.
 */
@Composable
private fun ComponentItem(component: LauncherComponentState, modifier: Modifier = Modifier, auth: CodexAuthState? = null) {
    val authWarning = component.status == ComponentStatus.RUNNING && auth != null && auth.status != CodexAuthStatus.SIGNED_IN
    val failure = component.details?.takeIf { it.isNotBlank() && component.status == ComponentStatus.FAILED }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 5.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(38.dp).background(LauncherColors.Primary.copy(alpha = .09f), RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { TablerIcon(component.id.iconResource, LauncherColors.Primary, 20.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(component.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    Text(component.description, color = LauncherColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(component.location, modifier = Modifier.weight(1.15f), color = LauncherColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.width(135.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (authWarning) LauncherColors.Primary else component.status.color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Surface(color = Color(0xFFF1F3F6).copy(alpha = .86f), shape = RoundedCornerShape(7.dp)) {
                    Text(if (authWarning) { if (auth.status == CodexAuthStatus.SIGNED_OUT) "Авторизация" else "Проверка" } else component.status.label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = LauncherColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (component.status == ComponentStatus.RUNNING && auth != null) {
            val description = if (auth.status == CodexAuthStatus.SIGNED_IN) auth.limitsSummary?.let { summary -> summary + (auth.checkedAtLabel?.let { " · проверено $it" } ?: "") } else auth.message
            description?.let { Text(it, modifier = Modifier.padding(start = 50.dp), color = LauncherColors.Muted, style = MaterialTheme.typography.bodySmall) }
        }
        if (failure != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                failure,
                // Aligned with the component name: the icon is 38.dp wide with a 12.dp gap after it.
                modifier = Modifier.fillMaxWidth().padding(start = 50.dp),
                color = LauncherColors.Red,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = .82f),
        shape = RoundedCornerShape(14.dp),
        content = content,
    )
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFD8DEE8).copy(alpha = .72f)))
}

@Composable
private fun TablerIcon(resource: DrawableResource, tint: Color, size: Dp) {
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = Modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

private val ComponentId.iconResource: DrawableResource
    get() = when (this) {
        ComponentId.BACKEND -> Res.drawable.icon_server
        ComponentId.FRONTEND -> Res.drawable.icon_browser
        ComponentId.GENERATION_AGENT -> Res.drawable.icon_sparkles
        ComponentId.BROWSER_AGENT -> Res.drawable.icon_world
    }

private val ComponentStatus.label: String
    get() = when (this) {
        ComponentStatus.STOPPED -> "Остановлен"
        ComponentStatus.STARTING -> "Запускается"
        ComponentStatus.RUNNING -> "Работает"
        ComponentStatus.STOPPING -> "Остановка"
        ComponentStatus.FAILED -> "Ошибка"
    }

private val ComponentStatus.color: Color
    get() = when (this) {
        ComponentStatus.STOPPED -> Color(0xFF98A2B3)
        ComponentStatus.STARTING -> LauncherColors.Blue
        ComponentStatus.RUNNING -> LauncherColors.Green
        ComponentStatus.STOPPING -> LauncherColors.Orange
        ComponentStatus.FAILED -> LauncherColors.Red
    }

private val LauncherState.statusColor: Color
    get() = when (preflight.status) {
        PreflightStatus.NOT_CHECKED, PreflightStatus.CHECKING -> LauncherColors.Blue
        PreflightStatus.BLOCKED -> LauncherColors.Orange
        PreflightStatus.READY -> when (status) {
            LauncherStatus.STOPPED -> if (components.any { it.status == ComponentStatus.RUNNING }) {
                LauncherColors.Orange
            } else {
                Color(0xFF98A2B3)
            }
            LauncherStatus.STARTING -> LauncherColors.Blue
            LauncherStatus.RUNNING -> LauncherColors.Green
            LauncherStatus.STOPPING -> LauncherColors.Orange
            LauncherStatus.FAILED -> LauncherColors.Red
        }
    }
