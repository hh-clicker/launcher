package org.hhclicker.launcher.preflight

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceLocatorTest {
    @Test
    fun packaged_resources_are_found_without_relying_on_the_process_working_directory() {
        val packaged = workspace("packaged-resources")
        val unrelated = createTempDirectory("unrelated-working-directory")
        val locator = WorkspaceLocator(
            environment = { null },
            systemProperty = { name ->
                if (name == WorkspaceLocator.COMPOSE_RESOURCES_PROPERTY) packaged.toString() else null
            },
            workingDirectory = { unrelated },
        )

        assertEquals(packaged.toAbsolutePath().normalize(), locator.locate())
    }

    @Test
    fun an_explicit_home_still_overrides_packaged_resources() {
        val configured = workspace("configured-home")
        val packaged = workspace("packaged-resources")
        val locator = WorkspaceLocator(
            environment = { name -> if (name == "HH_CLICKER_HOME") configured.toString() else null },
            systemProperty = { name ->
                if (name == WorkspaceLocator.COMPOSE_RESOURCES_PROPERTY) packaged.toString() else null
            },
            workingDirectory = { createTempDirectory("unrelated-working-directory") },
        )

        assertEquals(configured.toAbsolutePath().normalize(), locator.locate())
    }

    @Test
    fun a_development_checkout_wins_over_resources_exposed_by_gradle_run() {
        val root = createTempDirectory("checkout-parent")
        val checkout = workspaceAt(root.resolve("checkout"))
        val packaged = workspace("stale-packaged-resources")
        val locator = WorkspaceLocator(
            environment = { null },
            systemProperty = { name ->
                if (name == WorkspaceLocator.COMPOSE_RESOURCES_PROPERTY) packaged.toString() else null
            },
            workingDirectory = { checkout.resolve("launcher") },
        )

        assertEquals(checkout.toAbsolutePath().normalize(), locator.locate())
    }

    @Test
    fun an_installed_bundle_uses_its_own_services_even_when_started_from_a_checkout() {
        val checkout = workspace("checkout")
        val packaged = workspace("installed-resources")
        Files.writeString(packaged.resolve(BundleLayout.MARKER_FILE_NAME), "version=2.0.1\n")
        val locator = WorkspaceLocator(
            environment = { null },
            systemProperty = { name ->
                if (name == WorkspaceLocator.COMPOSE_RESOURCES_PROPERTY) packaged.toString() else null
            },
            workingDirectory = { checkout.resolve("launcher") },
        )

        assertEquals(packaged.toAbsolutePath().normalize(), locator.locate())
    }

    private fun workspace(prefix: String): Path = workspaceAt(createTempDirectory(prefix))

    private fun workspaceAt(root: Path): Path = root.also {
        Files.createDirectories(root.resolve("infra"))
        Files.writeString(root.resolve("infra/compose.yaml"), "services: {}")
        Files.createDirectories(root.resolve("generation-agent"))
        Files.writeString(root.resolve("generation-agent/compose.yaml"), "services: {}")
        Files.createDirectories(root.resolve("browser-agent"))
        Files.writeString(root.resolve("browser-agent/package.json"), "{}")
    }
}
