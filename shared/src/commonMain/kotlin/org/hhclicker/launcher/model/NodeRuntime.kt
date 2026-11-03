package org.hhclicker.launcher.model

/**
 * Which of the two shapes the launcher is running in.
 *
 * The distinction controls persistent configuration and Compose project identity. It is decided from
 * the files around the workspace, never from the current working directory alone.
 */
enum class BundleKind {
    /** Installed or otherwise packaged distribution. */
    PORTABLE,

    /** Repository checkout used for development. */
    DEVELOPER,
}

/** Where the Node runtime the launcher will use comes from. */
enum class NodeRuntimeSource {
    /** Launcher-managed portable node.exe under the current user's LOCALAPPDATA. */
    LOCAL_PORTABLE,

    /** A compatible `node` resolved through the inherited PATH. */
    SYSTEM_PATH,

    /** No usable runtime was found. */
    NONE,
}

/** Result of the Node runtime check. */
enum class NodeRuntimeState {
    /** A runtime was found and its version satisfies the Browser Agent engine range. */
    COMPATIBLE,

    /** A runtime was found but its version is too old. */
    INCOMPATIBLE,

    /** No runtime at all; the launcher may download its private portable copy. */
    MISSING,
}

/**
 * Independent facts behind the Node.js row.
 *
 * [version] is the plain `24.20.0` form without the leading `v`, or null when nothing answered.
 */
data class NodeRuntimeDiagnostics(
    val bundle: BundleKind,
    val source: NodeRuntimeSource,
    val state: NodeRuntimeState,
    val version: String? = null,
) {
    val ready: Boolean
        get() = state == NodeRuntimeState.COMPATIBLE
}
