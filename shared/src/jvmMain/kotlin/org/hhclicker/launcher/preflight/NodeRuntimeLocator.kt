package org.hhclicker.launcher.preflight

import java.nio.file.Path
import org.hhclicker.launcher.model.BundleKind
import org.hhclicker.launcher.model.NodeRuntimeDiagnostics
import org.hhclicker.launcher.model.NodeRuntimeSource
import org.hhclicker.launcher.setup.NodeRuntimePolicy
import org.hhclicker.launcher.setup.NodeVersionPolicy
import org.hhclicker.launcher.runtime.NodePortableRuntime
import org.hhclicker.launcher.runtime.LauncherDataPaths

/**
 * The runtime the Browser Agent will be started with, and how it was found.
 *
 * [executable] is an absolute path when the launcher-managed runtime is used and null when the process
 * falls back to a compatible `node` resolved through PATH.
 */
data class NodeRuntimeProbe(
    val diagnostics: NodeRuntimeDiagnostics,
    val executable: Path?,
)

/**
 * Read-only probe of the Node runtime.
 *
 * A compatible system runtime is reused. Otherwise the launcher checks its isolated runtime under
 * LOCALAPPDATA; installation of that runtime is handled separately by [NodePortableRuntime].
 */
class NodeRuntimeLocator(
    private val commandProbe: CommandProbe = CommandProbe(VERSION_PROBE_TIMEOUT_MS),
    private val environment: (String) -> String? = System::getenv,
) {

    fun probe(workspace: Path?): NodeRuntimeProbe {
        val bundle = BundleLayout.kind(workspace)
        val pathVersion = versionOfPathNode()
        val localPortable = localPortableExecutable(environment)
        val localPortableVersion = localPortable?.let(::versionOf)
        val diagnostics = NodeRuntimePolicy.evaluate(
            bundle = bundle,
            pathVersion = pathVersion,
            localPortablePresent = localPortable != null,
            localPortableVersion = localPortableVersion,
        )
        return NodeRuntimeProbe(
            diagnostics = diagnostics,
            executable = when (diagnostics.source) {
                NodeRuntimeSource.LOCAL_PORTABLE -> localPortable
                else -> null
            },
        )
    }

    private fun versionOf(executable: Path): String? {
        val result = commandProbe.execute(listOf(executable.toAbsolutePath().toString(), "--version"))
        if (!result.successful) return null
        return NodeVersionPolicy.parseVersion(result.output)
    }

    private fun versionOfPathNode(): String? {
        val result = commandProbe.execute(listOf("node", "--version"))
        if (!result.successful) return null
        return NodeVersionPolicy.parseVersion(result.output)
    }

    companion object {
        const val VERSION_PROBE_TIMEOUT_MS = 8_000L

        fun localPortableExecutable(environment: (String) -> String? = System::getenv): Path? {
            val applicationRoot = LauncherDataPaths.applicationRoot(environment) ?: return null
            return sequenceOf(
                applicationRoot.resolve("data/runtime/node-${NodePortableRuntime.VERSION}/node.exe"),
                applicationRoot.resolve("runtime/node-${NodePortableRuntime.VERSION}/node.exe"),
            ).firstOrNull { java.nio.file.Files.isRegularFile(it) }
        }
    }
}
