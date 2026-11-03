package org.hhclicker.launcher.runtime

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hhclicker.launcher.preflight.BundleLayout
import org.hhclicker.launcher.preflight.NodeRuntimeProbe
import org.hhclicker.launcher.model.BundleKind
import org.hhclicker.launcher.model.NodeRuntimeDiagnostics
import org.hhclicker.launcher.model.NodeRuntimeSource
import org.hhclicker.launcher.model.NodeRuntimeState

class BrowserAgentProcessArgumentsTest {
    @Test
    fun agent_uses_the_absolute_launcher_managed_node() {
        val workspace = createTempDirectory("hh-agent-portable")
        Files.writeString(workspace.resolve(BundleLayout.MARKER_FILE_NAME), "portable")
        val node = workspace.resolve("managed-node/node.exe")
        val main = workspace.resolve("browser-agent/dist/main.js")
        Files.createDirectories(node.parent)
        Files.createDirectories(main.parent)
        Files.write(node, byteArrayOf(1))
        Files.writeString(main, "")

        assertEquals(
            listOf(node.toAbsolutePath().toString(), main.toString()),
            BrowserAgentProcess(nodeRuntimeProbe = { ready(NodeRuntimeSource.LOCAL_PORTABLE, node) }).launchArguments(workspace),
        )
    }

    @Test
    fun agent_refuses_to_start_without_a_resolved_node_runtime() {
        val workspace = createTempDirectory("hh-agent-damaged")
        Files.writeString(workspace.resolve(BundleLayout.MARKER_FILE_NAME), "portable")
        val main = workspace.resolve("browser-agent/dist/main.js")
        Files.createDirectories(main.parent)
        Files.writeString(main, "")

        assertNull(BrowserAgentProcess(nodeRuntimeProbe = { missing() }).launchArguments(workspace))
    }

    @Test
    fun developer_checkout_retains_the_system_node_fallback() {
        val workspace = createTempDirectory("hh-agent-development")
        val main = workspace.resolve("browser-agent/dist/main.js")
        Files.createDirectories(main.parent)
        Files.writeString(main, "")

        assertEquals(
            listOf("node", main.toString()),
            BrowserAgentProcess(nodeRuntimeProbe = { ready(NodeRuntimeSource.SYSTEM_PATH, null) }).launchArguments(workspace),
        )
    }

    private fun ready(source: NodeRuntimeSource, executable: java.nio.file.Path?) = NodeRuntimeProbe(
        NodeRuntimeDiagnostics(BundleKind.PORTABLE, source, NodeRuntimeState.COMPATIBLE, "24.20.0"),
        executable,
    )

    private fun missing() = NodeRuntimeProbe(
        NodeRuntimeDiagnostics(BundleKind.PORTABLE, NodeRuntimeSource.NONE, NodeRuntimeState.MISSING),
        null,
    )
}
