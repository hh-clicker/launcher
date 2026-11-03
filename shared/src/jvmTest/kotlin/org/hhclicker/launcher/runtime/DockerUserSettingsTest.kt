package org.hhclicker.launcher.runtime

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.hhclicker.launcher.setup.JsonValue
import org.hhclicker.launcher.setup.MiniJson

class DockerUserSettingsTest {
    @Test
    fun creates_the_first_run_setting_when_the_store_is_absent() {
        val appData = createTempDirectory("hh-docker-settings")
        val settings = DockerUserSettings { name -> if (name == "APPDATA") appData.toString() else null }

        assertTrue(settings.prepareUnattendedStart())
        val document = MiniJson.parseOrNull(Files.readString(appData.resolve("Docker/settings-store.json")))
        assertEquals(true, MiniJson.booleanField(document, "DisplayedOnboarding"))
        assertEquals(true, MiniJson.booleanField(document, "OpenUIOnStartupDisabled"))
    }

    @Test
    fun preserves_unknown_settings_while_enabling_the_onboarding_flag() {
        val appData = createTempDirectory("hh-docker-settings")
        val file = appData.resolve("Docker/settings-store.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"SettingsVersion":41,"ThemeSource":"system","DisplayedOnboarding":false}""")
        val settings = DockerUserSettings { name -> if (name == "APPDATA") appData.toString() else null }

        assertTrue(settings.prepareUnattendedStart())
        val document = MiniJson.parseOrNull(Files.readString(file)) as JsonValue.JsonObject
        assertEquals(JsonValue.JsonNumber("41"), document.entries["SettingsVersion"])
        assertEquals(JsonValue.JsonString("system"), document.entries["ThemeSource"])
        assertEquals(true, MiniJson.booleanField(document, "DisplayedOnboarding"))
        assertEquals(true, MiniJson.booleanField(document, "OpenUIOnStartupDisabled"))
    }

    @Test
    fun refuses_to_overwrite_a_malformed_existing_store() {
        val appData = createTempDirectory("hh-docker-settings")
        val file = appData.resolve("Docker/settings-store.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "not-json")
        val settings = DockerUserSettings { name -> if (name == "APPDATA") appData.toString() else null }

        assertFalse(settings.prepareUnattendedStart())
        assertEquals("not-json", Files.readString(file))
    }
}
