package org.hhclicker.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import org.hhclicker.launcher.design.BrandIcon
import org.hhclicker.launcher.design.LauncherTheme
import org.hhclicker.launcher.design.brandIconPainter
import org.hhclicker.launcher.controller.PreflightLauncherController
import org.hhclicker.launcher.runtime.ExternalBrowserOpener
import org.hhclicker.launcher.runtime.HostPlatformDetector
import org.hhclicker.launcher.runtime.LauncherDataPaths
import org.hhclicker.launcher.runtime.LinuxDesktopIntegration
import org.hhclicker.launcher.runtime.ReleaseUpdateChecker
import org.hhclicker.launcher.runtime.SingleInstanceDecision
import org.hhclicker.launcher.runtime.SingleInstanceGuard
import org.hhclicker.launcher.runtime.SingleInstanceOutcome
import org.hhclicker.launcher.runtime.WindowsJavaNetworkCompatibility
import org.hhclicker.launcher.model.HostPlatform

fun main() {
    WindowsJavaNetworkCompatibility.configure()
    // Skiko uses Metal (with its own fallback) on macOS. Forcing OpenGL there aborts window creation.
    if (HostPlatformDetector.detect() != HostPlatform.MACOS && System.getProperty("skiko.renderApi") == null) {
        System.setProperty("skiko.renderApi", "OPENGL")
    }

    // A failed bind is not automatically "HH Clicker is already running". Only a launcher that answers
    // the handshake may make this process exit silently; anything else has to say why nothing opened.
    val singleInstance = when (val outcome = SingleInstanceGuard.acquire()) {
        is SingleInstanceOutcome.Acquired -> outcome.guard
        SingleInstanceOutcome.ActivatedRunningLauncher -> return
        else -> {
            SingleInstanceDecision.message(outcome)?.let(::showStartupFailure)
            return
        }
    }
    try {
        LauncherDataPaths.migrateLegacyLayout()
        application {
            val windowState = rememberWindowState(width = 960.dp, height = 640.dp)
            val windowIcon = brandIconPainter()
            val launcherController = remember { PreflightLauncherController() }
            val desktopIntegration = remember { LinuxDesktopIntegration() }
            val releaseUpdateChecker = remember { ReleaseUpdateChecker() }
            var desktopIntegrationState by remember { mutableStateOf(desktopIntegration.status()) }
            val applicationScope = rememberCoroutineScope()
            var closing by remember { mutableStateOf(false) }
            val closeLauncher: () -> Unit = {
                if (!closing) {
                    closing = true
                    applicationScope.launch {
                        try {
                            launcherController.stopAll()
                        } finally {
                            exitApplication()
                        }
                    }
                }
            }
            Window(
                onCloseRequest = closeLauncher,
                title = "HH Clicker Launcher",
                state = windowState,
                icon = windowIcon,
                resizable = false,
                undecorated = true,
            ) {
                DisposableEffect(window) {
                    // The guard signals from its own listener thread; window changes belong on the EDT.
                    singleInstance.activationHandler = {
                        SwingUtilities.invokeLater {
                            windowState.isMinimized = false
                            window.toFront()
                            window.requestFocus()
                        }
                    }
                    onDispose { singleInstance.activationHandler = null }
                }
                LauncherTheme {
                    Column(Modifier.fillMaxSize().background(Color(0xFFF8FAFC))) {
                        LauncherTitleBar(
                            onMinimize = { windowState.isMinimized = true },
                            onClose = closeLauncher,
                        )
                        Box(Modifier.weight(1f)) {
                            LauncherContent(
                                controller = launcherController,
                                onOpenUrl = ::openInBrowser,
                                desktopIntegration = desktopIntegrationState,
                                onInstallDesktopIntegration = {
                                    val previous = desktopIntegrationState
                                    if (previous != null && !previous.busy) {
                                        desktopIntegrationState = previous.copy(busy = true, message = null, error = false)
                                        applicationScope.launch {
                                            val result = withContext(Dispatchers.IO) { runCatching { desktopIntegration.install() } }
                                            desktopIntegrationState = if (result.isSuccess) {
                                                previous.copy(installed = true, message = if (previous.installed)
                                                    "Ярлык и AppImage обновлены" else "HH Clicker добавлен в меню приложений")
                                            } else {
                                                previous.copy(message = "Не удалось добавить HH Clicker в меню: ${result.exceptionOrNull()?.message ?: "неизвестная ошибка"}", error = true)
                                            }
                                        }
                                    }
                                },
                                onCheckForUpdates = { version ->
                                    withContext(Dispatchers.IO) { releaseUpdateChecker.check(version) }
                                },
                            )
                        }
                    }
                }
            }
        }
    } finally {
        singleInstance.close()
    }
}

/**
 * Reports a start that never produced a window.
 *
 * Compose is not running at this point, so the message goes through Swing. It states what happened and
 * what to do about it, and never carries a stack trace or a socket detail.
 */
private fun showStartupFailure(message: String) {
    runCatching {
        SwingUtilities.invokeAndWait {
            JOptionPane.showMessageDialog(null, message, "HH Clicker", JOptionPane.WARNING_MESSAGE)
        }
    }
}

/**
 * Opens a link in the user's default browser.
 *
 * Setup links are compile-time constants. Runtime links are the local Frontend address from the managed
 * profile and the validated Codex device-login address.
 */
private fun openInBrowser(url: String) {
    ExternalBrowserOpener.open(url) { failedUrl ->
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                null,
                "Не удалось открыть страницу в браузере. Откройте ссылку вручную:\n$failedUrl",
                "HH Clicker",
                JOptionPane.WARNING_MESSAGE,
            )
        }
    }
}

@Composable
private fun WindowScope.LauncherTitleBar(onMinimize: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier.height(38.dp).background(Color(0xFFF8FAFC)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandIcon(Modifier.size(22.dp))
                Text(
                    "HH Clicker Launcher",
                    modifier = Modifier.padding(start = 9.dp),
                    color = Color(0xFF202939),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        WindowControl(WindowControlKind.MINIMIZE, onMinimize)
        WindowControl(WindowControlKind.CLOSE, onClose)
    }
}

private enum class WindowControlKind { MINIMIZE, CLOSE }

@Composable
private fun WindowControl(kind: WindowControlKind, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        !hovered -> Color.Transparent
        kind == WindowControlKind.CLOSE -> Color(0xFFFFE8E8)
        else -> Color(0xFFE9EDF2)
    }
    val lineColor = if (hovered && kind == WindowControlKind.CLOSE) Color(0xFFC62828) else Color(0xFF475467)

    Box(
        modifier = Modifier.size(width = 44.dp, height = 38.dp)
            .background(background)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = 1.25f.dp.toPx()
            if (kind == WindowControlKind.MINIMIZE) {
                drawLine(
                    color = lineColor,
                    start = androidx.compose.ui.geometry.Offset(2.dp.toPx(), 7.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(12.dp.toPx(), 7.dp.toPx()),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            } else {
                val inset = 3.dp.toPx()
                val far = size.width - inset
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Offset(far, far), stroke, StrokeCap.Round)
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(far, inset), androidx.compose.ui.geometry.Offset(inset, far), stroke, StrokeCap.Round)
            }
        }
    }
}
