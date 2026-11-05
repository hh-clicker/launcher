package org.hhclicker.launcher.preflight

import java.nio.file.Files
import java.nio.file.Path

/** Finds Docker Desktop's CLI even when it was installed after the launcher started. */
class MacDockerCliLocator(
    private val userHome: () -> String? = { System.getProperty("user.home") },
    private val executable: (Path) -> Boolean = Files::isExecutable,
) {
    fun locate(): Path? = listOfNotNull(
        userHome()?.takeIf(String::isNotBlank)?.let { Path.of(it, ".docker", "bin", "docker") },
        Path.of("/usr/local/bin/docker"),
        Path.of("/Applications/Docker.app/Contents/Resources/bin/docker"),
    ).firstOrNull(executable)
}
