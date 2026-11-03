package org.hhclicker.launcher.runtime

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.hhclicker.launcher.setup.JsonValue
import org.hhclicker.launcher.setup.MiniJson

/** Applies the user-level Docker settings needed for an unattended start. */
class DockerUserSettings(
    private val environment: (String) -> String? = System::getenv,
) {
    /**
     * Marks the optional welcome survey as displayed and disables opening the Dashboard on startup.
     * Unknown fields are retained. A malformed or unexpectedly large existing file is left untouched
     * rather than being replaced.
     */
    fun prepareUnattendedStart(): Boolean {
        val settings = settingsPath() ?: return false
        return runCatching {
            Files.createDirectories(settings.parent)
            val document = if (Files.exists(settings)) {
                if (!Files.isRegularFile(settings) || Files.size(settings) > MAX_SETTINGS_BYTES) return false
                MiniJson.parseOrNull(Files.readString(settings)) as? JsonValue.JsonObject ?: return false
            } else {
                JsonValue.JsonObject(emptyMap())
            }

            val onboardingHidden = MiniJson.booleanField(document, DISPLAYED_ONBOARDING) == true
            val dashboardHidden = MiniJson.booleanField(document, OPEN_UI_ON_STARTUP_DISABLED) == true
            if (onboardingHidden && dashboardHidden) return true

            val updatedEntries = LinkedHashMap(document.entries)
            updatedEntries[DISPLAYED_ONBOARDING] = JsonValue.JsonBoolean(true)
            updatedEntries[OPEN_UI_ON_STARTUP_DISABLED] = JsonValue.JsonBoolean(true)
            val temporary = settings.resolveSibling("${settings.fileName}.hh-clicker.tmp")
            Files.writeString(temporary, MiniJson.stringify(JsonValue.JsonObject(updatedEntries)))
            try {
                Files.move(
                    temporary,
                    settings,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, settings, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.getOrDefault(false)
    }

    private fun settingsPath(): Path? {
        val appData = environment("APPDATA")?.takeIf(String::isNotBlank) ?: return null
        return runCatching { Path.of(appData, "Docker", SETTINGS_STORE_FILE) }.getOrNull()
    }

    private companion object {
        const val SETTINGS_STORE_FILE = "settings-store.json"
        const val DISPLAYED_ONBOARDING = "DisplayedOnboarding"
        const val OPEN_UI_ON_STARTUP_DISABLED = "OpenUIOnStartupDisabled"
        const val MAX_SETTINGS_BYTES = 1_048_576L
    }
}
