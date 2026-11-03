package org.hhclicker.launcher.preflight

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hhclicker.launcher.model.BundleKind
import org.hhclicker.launcher.model.NodeRuntimeSource
import org.hhclicker.launcher.model.NodeRuntimeState

class BundleAndNodeRuntimeTest {
    private class FakeCommandProbe(
        private val answers: (List<String>) -> CommandProbeResult,
    ) : CommandProbe() {
        val invocations = ArrayList<List<String>>()

        override fun execute(arguments: List<String>): CommandProbeResult {
            invocations += arguments
            return answers(arguments)
        }
    }

    private fun successful(version: String) = CommandProbeResult(true, 0, "v$version")

    @Test
    fun marker_identifies_a_portable_bundle_without_using_the_working_directory() {
        val workspace = createTempDirectory("hh-portable")
        Files.writeString(workspace.resolve(BundleLayout.MARKER_FILE_NAME), "version=0.1.0")

        assertEquals(BundleKind.PORTABLE, BundleLayout.kind(workspace))
    }

    @Test
    fun nightly_is_visible_without_changing_the_numeric_runtime_version() {
        val workspace = createTempDirectory("hh-nightly")
        Files.writeString(workspace.resolve(BundleLayout.MARKER_FILE_NAME), "version=1.3.1\nchannel=nightly\n")
        assertEquals("1.3.1", BundleLayout.version(workspace))
        assertEquals("1.3.1 · nightly", BundleLayout.displayVersion(workspace))
        assertEquals(BundleKind.PORTABLE, BundleLayout.kind(workspace))
        Files.writeString(workspace.resolve(BundleLayout.MARKER_FILE_NAME), "version=1.3.1\n")
        assertEquals("1.3.1", BundleLayout.displayVersion(workspace))
        assertNull(BundleLayout.displayVersion(null))
    }

    @Test
    fun an_ordinary_checkout_is_developer_mode() {
        val workspace = createTempDirectory("hh-checkout")
        Files.createDirectories(workspace.resolve("browser-agent/src"))

        assertEquals(BundleKind.DEVELOPER, BundleLayout.kind(workspace))
    }

    @Test
    fun portable_bundle_uses_a_compatible_path_node() {
        val workspace = createTempDirectory("hh-portable-without-node")
        Files.writeString(workspace.resolve(BundleLayout.MARKER_FILE_NAME), "portable")
        val probe = FakeCommandProbe { successful("24.20.0") }

        val result = NodeRuntimeLocator(probe, environment = { null }).probe(workspace)

        assertEquals(NodeRuntimeState.COMPATIBLE, result.diagnostics.state)
        assertEquals(NodeRuntimeSource.SYSTEM_PATH, result.diagnostics.source)
        assertNull(result.executable)
        assertEquals(listOf(listOf("node", "--version")), probe.invocations)
    }

    @Test
    fun developer_checkout_uses_path_node() {
        val workspace = createTempDirectory("hh-developer-node")
        val probe = FakeCommandProbe { arguments ->
            assertEquals(listOf("node", "--version"), arguments)
            successful("24.20.0")
        }

        val result = NodeRuntimeLocator(probe, environment = { null }).probe(workspace)

        assertEquals(NodeRuntimeState.COMPATIBLE, result.diagnostics.state)
        assertEquals(NodeRuntimeSource.SYSTEM_PATH, result.diagnostics.source)
        assertNull(result.executable)
        assertEquals(1, probe.invocations.size)
    }
}
