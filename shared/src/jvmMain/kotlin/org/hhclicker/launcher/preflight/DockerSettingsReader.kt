package org.hhclicker.launcher.preflight

import java.nio.file.Files
import java.nio.file.Path
import org.hhclicker.launcher.model.DockerBackend
import org.hhclicker.launcher.setup.DockerDiagnosticsParser

/**
 * Read-only reader for the documented Docker Desktop settings files.
 *
 * `docker info` cannot answer which backend is in use: Linux containers also run on Hyper-V and on
 * Docker VMM. The launcher therefore reads the documented `wslEngineEnabled` setting, preferring the
 * current `settings-store.json` over the legacy `settings.json`. Both are parsed as JSON. The reader
 * never writes, never resets and never migrates settings.
 */
class DockerSettingsReader(
    private val environment: (String) -> String? = System::getenv,
) {

    fun backend(defaultWhenAbsent: DockerBackend = DockerBackend.UNKNOWN): DockerBackend = DockerDiagnosticsParser.backend(
        settingsStoreJson = read(SETTINGS_STORE_FILE),
        legacySettingsJson = read(LEGACY_SETTINGS_FILE),
        defaultWhenAbsent = defaultWhenAbsent,
    )

    private fun read(fileName: String): String? {
        val path = settingsPath(fileName) ?: return null
        return runCatching {
            if (!Files.isRegularFile(path)) return@runCatching null
            if (Files.size(path) > MAX_SETTINGS_BYTES) return@runCatching null
            Files.readString(path)
        }.getOrNull()
    }

    private fun settingsPath(fileName: String): Path? {
        val appData = environment("APPDATA")?.takeIf(String::isNotBlank) ?: return null
        return runCatching { Path.of(appData, "Docker", fileName) }.getOrNull()
    }

    private companion object {
        const val SETTINGS_STORE_FILE = "settings-store.json"
        const val LEGACY_SETTINGS_FILE = "settings.json"

        /** Docker settings are a few kilobytes; anything larger is not the file we expect. */
        const val MAX_SETTINGS_BYTES = 1_048_576L
    }
}
