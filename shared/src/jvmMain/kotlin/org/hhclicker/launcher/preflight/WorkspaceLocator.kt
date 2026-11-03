package org.hhclicker.launcher.preflight

import java.nio.file.Files
import java.nio.file.Path

class WorkspaceLocator(
    private val environment: (String) -> String? = System::getenv,
    private val systemProperty: (String) -> String? = System::getProperty,
    private val workingDirectory: () -> Path = { Path.of(System.getProperty("user.dir")) },
) {
    fun locate(): Path? {
        val configured = environment("HH_CLICKER_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
        val packaged = systemProperty(COMPOSE_RESOURCES_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of)
        val start = workingDirectory()

        return sequence {
            if (configured != null) yield(configured)
            var current: Path? = start
            while (current != null) {
                val candidate = current
                yield(candidate)
                yield(candidate.resolve("services"))
                current = candidate.parent
            }
            // Gradle's development run exposes the same property. Prefer a real checkout found from cwd;
            // an installed shortcut normally has no checkout in its directory ancestry and reaches this fallback.
            if (packaged != null) yield(packaged)
        }.map { it.toAbsolutePath().normalize() }
            .distinct()
            .firstOrNull(::isWorkspace)
    }

    private fun isWorkspace(path: Path): Boolean =
        Files.isRegularFile(path.resolve("infra/compose.yaml")) &&
            Files.isRegularFile(path.resolve("generation-agent/compose.yaml")) &&
            Files.isRegularFile(path.resolve("browser-agent/package.json"))

    companion object {
        /** Set by the Compose Desktop launcher to its packaged `app/resources` directory. */
        const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"
    }
}
