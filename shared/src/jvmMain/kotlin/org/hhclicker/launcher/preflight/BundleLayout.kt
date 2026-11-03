package org.hhclicker.launcher.preflight

import java.nio.file.Files
import java.nio.file.Path
import org.hhclicker.launcher.model.BundleKind

/**
 * Tells an installed or packaged application apart from a developer checkout.
 *
 * The current working directory is deliberately not part of the decision: a developer can start the
 * launcher from anywhere, and an installed application can live anywhere. Two file facts around the
 * workspace decide it instead:
 *
 *  1. [MARKER_FILE_NAME], written into the root of an installed distribution. This is the authoritative signal
 *     and can carry its version.
 *  2. A jpackage application image — an `app` directory with the launcher `.cfg` next to a
 *     `runtime\bin\java.exe`. This lets local `createDistributable` builds use production isolation
 *     before an installer writes the marker.
 *
 * A checkout has neither, so the fallback never misfires there: the repository has no `app` directory
 * with a jpackage configuration and no embedded JRE.
 */
object BundleLayout {

    /** Written into the root of an installed or portable distribution. Content is informational. */
    const val MARKER_FILE_NAME = ".hh-clicker-bundle"

    fun kind(workspace: Path?): BundleKind {
        if (workspace == null) return BundleKind.DEVELOPER
        return if (hasMarker(workspace) || hasApplicationImage(workspace)) {
            BundleKind.PORTABLE
        } else {
            BundleKind.DEVELOPER
        }
    }

    /** Version declared by release tooling; a checkout deliberately has no release version. */
    fun version(workspace: Path?): String? {
        if (workspace == null) return null
        val marker = workspace.resolve(MARKER_FILE_NAME)
        return runCatching {
            if (!Files.isRegularFile(marker)) return@runCatching null
            Files.readAllLines(marker).firstNotNullOfOrNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("version=")) trimmed.substringAfter('=').trim().takeIf(String::isNotBlank) else null
            }
        }.getOrNull()
    }

    /** UI label only; update guards and runtime metadata keep using the numeric version. */
    fun displayVersion(workspace: Path?): String? {
        val version = version(workspace) ?: return null
        val nightly = runCatching {
            Files.readAllLines(workspace!!.resolve(MARKER_FILE_NAME)).any { it.trim() == "channel=nightly" }
        }.getOrDefault(false)
        return if (nightly) "$version · nightly" else version
    }

    private fun hasMarker(workspace: Path): Boolean =
        runCatching { Files.isRegularFile(workspace.resolve(MARKER_FILE_NAME)) }.getOrDefault(false)

    /** A jpackage application image produced by Compose Desktop. */
    private fun hasApplicationImage(workspace: Path): Boolean = runCatching {
        val applicationDirectory = workspace.resolve("app")
        if (!Files.isDirectory(applicationDirectory)) return@runCatching false
        if (!Files.isRegularFile(workspace.resolve("runtime/bin/java.exe"))) return@runCatching false
        Files.newDirectoryStream(applicationDirectory, "*.cfg").use { it.iterator().hasNext() }
    }.getOrDefault(false)
}
