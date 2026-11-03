package org.hhclicker.launcher.setup

import org.hhclicker.launcher.model.BundleKind
import org.hhclicker.launcher.model.NodeRuntimeDiagnostics
import org.hhclicker.launcher.model.NodeRuntimeSource
import org.hhclicker.launcher.model.NodeRuntimeState

/**
 * The Node.js engine contract of the Browser Agent, expressed once.
 *
 * `browser-agent/package.json` declares `"node": "^22.18.0 || >=24.12.0"`. Both halves are checked
 * literally: 23.x satisfies neither range and is rejected, exactly like npm would.
 */
object NodeVersionPolicy {

    /** Browser Agent runtime requirement. */
    const val REQUIREMENT_TEXT = "22.18+ или 24.12+"

    /** Strips the `v` prefix printed by `node --version` and keeps only a plausible version. */
    fun parseVersion(output: String?): String? {
        val text = output?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val candidate = text.removePrefix("v").trim()
        val parts = candidate.split('.')
        if (parts.size < 2) return null
        val numbers = parts.take(3).map { part -> part.takeWhile(Char::isDigit) }
        if (numbers.any(String::isEmpty)) return null
        return numbers.joinToString(".")
    }

    fun isCompatible(version: String?): Boolean {
        val parts = version?.split('.')?.mapNotNull(String::toIntOrNull) ?: return false
        if (parts.size < 2) return false
        val major = parts[0]
        val minor = parts[1]
        return when {
            major == 22 -> minor >= 18
            major == 24 -> minor >= 12
            major > 24 -> true
            else -> false
        }
    }
}

/**
 * Pure interpretation of the system and launcher-managed Node probes.
 */
object NodeRuntimePolicy {
    /**
     * @param pathVersion version printed by `node --version`, or null.
     * @param localPortableVersion version of the isolated runtime downloaded by the launcher.
     */
    fun evaluate(
        bundle: BundleKind,
        pathVersion: String?,
        localPortablePresent: Boolean = false,
        localPortableVersion: String? = null,
    ): NodeRuntimeDiagnostics {
        if (NodeVersionPolicy.isCompatible(pathVersion)) {
            return NodeRuntimeDiagnostics(bundle, NodeRuntimeSource.SYSTEM_PATH, NodeRuntimeState.COMPATIBLE, pathVersion)
        }

        if (localPortablePresent && NodeVersionPolicy.isCompatible(localPortableVersion)) {
            return NodeRuntimeDiagnostics(bundle, NodeRuntimeSource.LOCAL_PORTABLE, NodeRuntimeState.COMPATIBLE, localPortableVersion)
        }

        return when {
            pathVersion != null -> NodeRuntimeDiagnostics(
                bundle = bundle,
                source = NodeRuntimeSource.SYSTEM_PATH,
                state = NodeRuntimeState.INCOMPATIBLE,
                version = pathVersion,
            )

            else -> NodeRuntimeDiagnostics(
                bundle = bundle,
                source = NodeRuntimeSource.NONE,
                state = NodeRuntimeState.MISSING,
            )
        }
    }

    /** Short Russian sentence for the Node.js row. It never contains a path. */
    fun message(diagnostics: NodeRuntimeDiagnostics): String = when (diagnostics.state) {
        NodeRuntimeState.COMPATIBLE -> when (diagnostics.source) {
            NodeRuntimeSource.LOCAL_PORTABLE -> "Локальный Node.js ${diagnostics.version}, управляется лаунчером"
            else -> "Node.js ${diagnostics.version} из PATH"
        }

        NodeRuntimeState.INCOMPATIBLE ->
            "Node.js ${diagnostics.version} слишком старый; лаунчер скачает локальную совместимую версию"

        NodeRuntimeState.MISSING ->
            "Node.js не найден; лаунчер скачает локальную portable-версию"
    }
}
