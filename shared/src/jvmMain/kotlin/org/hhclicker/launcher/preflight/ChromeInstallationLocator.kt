package org.hhclicker.launcher.preflight

import java.nio.file.Files
import java.nio.file.Path
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.runtime.HostPlatformDetector

/**
 * The single place that knows where a system Google Chrome may live.
 *
 * Both the preflight row and the installer use it, so "is Chrome installed?" can never be answered one
 * way before an installation and another way after it. The search is a fixed list of documented
 * locations; PATH is not consulted, because a `chrome` on PATH is not proof of a Chrome installation
 * and Playwright looks in these locations too.
 */
class ChromeInstallationLocator(
    private val environment: (String) -> String? = System::getenv,
    private val platform: HostPlatform = HostPlatformDetector.detect(),
) {

    /** Every supported location, whether or not the file is there. */
    fun candidates(): List<Path> = buildList {
        when (platform) {
            HostPlatform.WINDOWS -> {
                directory("PROGRAMFILES")?.let(::add)
                directory("PROGRAMFILES(X86)")?.let(::add)
                directory("LOCALAPPDATA")?.let(::add)
            }
            HostPlatform.LINUX -> {
                add(Path.of("/opt/google/chrome/chrome"))
                add(Path.of("/usr/bin/google-chrome"))
                add(Path.of("/usr/bin/google-chrome-stable"))
            }
            HostPlatform.MACOS -> add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
            HostPlatform.OTHER -> Unit
        }
    }

    /** The first existing chrome.exe, or null. */
    fun locate(): Path? = candidates().firstOrNull { runCatching { Files.isRegularFile(it) }.getOrDefault(false) }

    private fun directory(variable: String): Path? {
        val root = environment(variable)?.takeIf(String::isNotBlank) ?: return null
        return runCatching { Path.of(root, *RELATIVE_SEGMENTS) }.getOrNull()
    }

    private companion object {
        val RELATIVE_SEGMENTS = arrayOf("Google", "Chrome", "Application", "chrome.exe")
    }
}
