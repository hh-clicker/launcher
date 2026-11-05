package org.hhclicker.launcher.preflight

import java.nio.file.Path
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.runtime.HostPlatformDetector

/**
 * The single place that decides which `docker.exe` every launcher command runs.
 *
 * A `docker` resolved through PATH is not good enough for two independent reasons. It may belong to
 * something that is not Docker Desktop, and — the reason this class exists — a Docker Desktop the
 * launcher has just installed itself is **not** on the PATH this process inherited: Windows updates the
 * environment for processes started afterwards. Diagnostics already resolved the absolute
 * `resources\bin\docker.exe` for that reason, while `compose up`, `compose down`, `image load` and
 * `image inspect` still ran a bare `docker`, so the very first start after an automatic installation
 * could only fail until the launcher was restarted.
 *
 * Caching is deliberately one-directional: a successful resolution is remembered so the registry is not
 * re-read on every four-second monitoring tick, and a failure is never remembered, so an installation
 * performed inside this same process is picked up by the next call.
 */
class DockerCliResolver(
    private val locate: () -> Path? = {
        when (HostPlatformDetector.detect()) {
            HostPlatform.WINDOWS -> DockerInstallationLocator().locate()?.cliExecutable
            HostPlatform.MACOS -> MacDockerCliLocator().locate()
            else -> null
        }
    },
) {
    @Volatile
    private var cached: String? = null

    /**
     * Absolute path of Docker Desktop's own CLI, or [PATH_FALLBACK] while no installation can be found.
     *
     * The fallback is deliberately the last resort and never cached: on a machine where Docker Desktop is
     * genuinely absent the command has to fail with Docker's own message rather than with a made-up one.
     */
    fun executable(): String {
        cached?.let { return it }
        val resolved = runCatching(locate).getOrNull()?.toAbsolutePath()?.toString()
        if (resolved != null) cached = resolved
        return resolved ?: PATH_FALLBACK
    }

    /** True once an absolute Docker Desktop CLI has been found; used only for diagnostics and tests. */
    val resolvedAbsolutePath: String?
        get() = cached

    companion object {
        const val PATH_FALLBACK = "docker"
    }
}
