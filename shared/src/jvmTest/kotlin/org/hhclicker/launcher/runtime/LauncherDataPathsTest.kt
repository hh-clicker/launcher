package org.hhclicker.launcher.runtime

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LauncherDataPathsTest {
    @Test
    fun legacy_mutable_state_moves_under_data_without_touching_the_application_runtime() {
        val localAppData = createTempDirectory("hh-data-layout")
        val root = localAppData.resolve("HH Clicker")
        Files.createDirectories(root.resolve("config/production"))
        Files.writeString(root.resolve("config/production/infra.env"), "POSTGRES_PASSWORD=keep-me")
        Files.createDirectories(root.resolve("browser-agent/profiles/account"))
        Files.writeString(root.resolve("browser-agent/profiles/account/Cookies"), "keep-me")
        Files.createDirectories(root.resolve("runtime/node-${NodePortableRuntime.VERSION}"))
        Files.writeString(root.resolve("runtime/node-${NodePortableRuntime.VERSION}/node.exe"), "node")
        Files.writeString(root.resolve("runtime/${LauncherDataPaths.BROWSER_AGENT_HANDLE_FILE}"), "claim")
        Files.createDirectories(root.resolve("runtime/bin"))
        Files.writeString(root.resolve("runtime/bin/java.exe"), "application-runtime")

        LauncherDataPaths.migrateLegacyLayout { name ->
            if (name == "LOCALAPPDATA") localAppData.toString() else null
        }

        assertTrue(Files.isRegularFile(root.resolve("data/config/production/infra.env")))
        assertTrue(Files.isRegularFile(root.resolve("data/browser-agent/profiles/account/Cookies")))
        assertTrue(Files.isRegularFile(root.resolve("data/runtime/node-${NodePortableRuntime.VERSION}/node.exe")))
        assertTrue(Files.isRegularFile(root.resolve("data/runtime/${LauncherDataPaths.BROWSER_AGENT_HANDLE_FILE}")))
        assertFalse(Files.exists(root.resolve("config")))
        assertTrue(Files.isRegularFile(root.resolve("runtime/bin/java.exe")))
    }

    @Test
    fun an_existing_destination_is_never_replaced_by_legacy_state() {
        val localAppData = createTempDirectory("hh-data-layout-existing")
        val root = localAppData.resolve("HH Clicker")
        Files.createDirectories(root.resolve("config/production"))
        Files.writeString(root.resolve("config/production/infra.env"), "old")
        Files.createDirectories(root.resolve("data/config/production"))
        Files.writeString(root.resolve("data/config/production/infra.env"), "current")

        LauncherDataPaths.migrateLegacyLayout { name ->
            if (name == "LOCALAPPDATA") localAppData.toString() else null
        }

        assertEquals("current", Files.readString(root.resolve("data/config/production/infra.env")))
        assertEquals("old", Files.readString(root.resolve("config/production/infra.env")))
    }
}
