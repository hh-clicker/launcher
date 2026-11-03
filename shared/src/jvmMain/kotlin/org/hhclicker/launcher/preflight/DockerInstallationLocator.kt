package org.hhclicker.launcher.preflight

import java.nio.file.Files
import java.nio.file.Path
import org.hhclicker.launcher.model.DockerInstallScope

/**
 * Where Docker Desktop was found, together with the version recorded by its uninstall entry.
 *
 * [desktopExecutable] is what the launcher starts when the `docker desktop` CLI is not available;
 * [cliExecutable] is used only for diagnostics, because the CLI on PATH may belong to something else.
 */
data class DockerInstallation(
    val desktopExecutable: Path,
    val version: String?,
    val scope: DockerInstallScope,
    val cliExecutable: Path? = null,
)

/**
 * Finds an existing Docker Desktop installation without trusting PATH.
 *
 * A freshly installed Docker Desktop does not appear in the PATH this process inherited, and a stray
 * `docker.exe` on PATH does not prove Docker Desktop exists. The locator therefore looks at the
 * uninstall registry, App Paths and the documented per-user and all-users install locations, in that
 * order, and only reports a location whose executable is really on disk.
 *
 * Every command is a fixed, read-only `reg.exe query`. Nothing is written and no value is logged.
 */
class DockerInstallationLocator(
    private val commandProbe: CommandProbe = CommandProbe(),
    private val environment: (String) -> String? = System::getenv,
) {

    fun locate(): DockerInstallation? {
        val registryHits = registrySources().mapNotNull { source -> readRegistry(source) }

        val fromRegistry = registryHits.firstNotNullOfOrNull { hit ->
            hit.executable()?.let { executable ->
                DockerInstallation(
                    desktopExecutable = executable,
                    version = hit.version ?: fileVersionNeighbour(executable),
                    scope = hit.scope,
                    cliExecutable = cliNextTo(executable),
                )
            }
        }
        if (fromRegistry != null) return fromRegistry

        // Registry entries can be missing on a repaired installation; the documented paths still hold.
        return knownLocations().firstOrNull { Files.isRegularFile(it.first) }?.let { (executable, scope) ->
            DockerInstallation(
                desktopExecutable = executable,
                version = registryHits.firstNotNullOfOrNull { it.version },
                scope = scope,
                cliExecutable = cliNextTo(executable),
            )
        }
    }

    /**
     * Docker Desktop keeps `docker.exe` in `resources\bin` next to the application. Finding it there is
     * what distinguishes a real Docker Desktop CLI from an unrelated `docker.exe` on PATH.
     */
    private fun cliNextTo(desktopExecutable: Path): Path? =
        desktopExecutable.parent?.resolve("resources/bin/docker.exe")?.takeIf(Files::isRegularFile)

    private data class RegistryHit(
        val installLocation: String?,
        val displayIcon: String?,
        val version: String?,
        val scope: DockerInstallScope,
    ) {
        fun executable(): Path? {
            installLocation?.let { location ->
                val candidate = runCatching { Path.of(location.trim('"'), DESKTOP_EXECUTABLE_NAME) }.getOrNull()
                if (candidate != null && Files.isRegularFile(candidate)) return candidate
            }
            displayIcon?.let { icon ->
                val path = icon.substringBefore(',').trim().trim('"')
                val candidate = runCatching { Path.of(path) }.getOrNull()
                if (candidate != null && Files.isRegularFile(candidate)) return candidate
            }
            return null
        }
    }

    private data class RegistrySource(val root: String, val key: String, val scope: DockerInstallScope)

    private fun registrySources(): List<RegistrySource> = listOf(
        RegistrySource("HKCU", PER_USER_UNINSTALL_KEY, DockerInstallScope.PER_USER),
        RegistrySource("HKLM", ALL_USERS_UNINSTALL_KEY, DockerInstallScope.ALL_USERS),
        RegistrySource("HKCU", APP_PATHS_KEY, DockerInstallScope.PER_USER),
        RegistrySource("HKLM", APP_PATHS_KEY, DockerInstallScope.ALL_USERS),
    )

    private fun readRegistry(source: RegistrySource): RegistryHit? {
        // Querying the key without /v lists every value of that key in one call and avoids the
        // locale dependent behaviour of a wildcard value name.
        val result = commandProbe.execute(listOf("reg.exe", "query", "${source.root}\\${source.key}"))
        if (!result.successful) return null
        val values = parseRegistryValues(result.output)
        if (values.isEmpty()) return null
        return RegistryHit(
            installLocation = values["InstallLocation"],
            displayIcon = values["DisplayIcon"] ?: values[""],
            version = values["DisplayVersion"],
            scope = source.scope,
        )
    }

    /**
     * `reg.exe query` prints `    Name    TYPE    Value`, and the default value is printed as
     * `(Default)` or `<NO NAME>` depending on the Windows locale, so it is stored under an empty key.
     */
    private fun parseRegistryValues(output: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        output.lineSequence().forEach { rawLine ->
            val match = REGISTRY_VALUE_PATTERN.find(rawLine.trim()) ?: return@forEach
            val name = match.groupValues[1].trim()
            val value = match.groupValues[3].trim()
            if (value.isEmpty()) return@forEach
            val key = if (name.startsWith("(") || name.startsWith("<")) "" else name
            values.putIfAbsent(key, value)
        }
        return values
    }

    /** Falls back to the version file Docker Desktop keeps next to the executable. */
    private fun fileVersionNeighbour(executable: Path): String? {
        val parent = executable.parent ?: return null
        val versionFile = parent.resolve("version-bin/version.txt")
        return runCatching {
            if (Files.isRegularFile(versionFile)) Files.readString(versionFile).trim().takeIf(String::isNotEmpty) else null
        }.getOrNull()
    }

    private fun knownLocations(): List<Pair<Path, DockerInstallScope>> = buildList {
        environment("LOCALAPPDATA")?.let { localAppData ->
            // Current documented location used by `install --user`.
            add(Path.of(localAppData, "Programs/DockerDesktop", DESKTOP_EXECUTABLE_NAME) to DockerInstallScope.PER_USER)
            // Retain the older layout as a compatibility fallback.
            add(Path.of(localAppData, "Programs/Docker/Docker", DESKTOP_EXECUTABLE_NAME) to DockerInstallScope.PER_USER)
        }
        environment("PROGRAMFILES")?.let { programFiles ->
            add(Path.of(programFiles, "Docker/Docker", DESKTOP_EXECUTABLE_NAME) to DockerInstallScope.ALL_USERS)
        }
    }

    private companion object {
        const val DESKTOP_EXECUTABLE_NAME = "Docker Desktop.exe"
        const val PER_USER_UNINSTALL_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Docker Desktop"
        const val ALL_USERS_UNINSTALL_KEY = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Docker Desktop"
        const val APP_PATHS_KEY = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\Docker Desktop.exe"

        val REGISTRY_VALUE_PATTERN =
            Regex("""^(\S.*?)\s{2,}(REG_[A-Z_]+)\s{2,}(.*)$""")
    }
}
