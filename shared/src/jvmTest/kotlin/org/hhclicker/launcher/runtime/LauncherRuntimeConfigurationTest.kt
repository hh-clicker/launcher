package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.hhclicker.launcher.preflight.BundleLayout
import org.hhclicker.launcher.model.ComponentId

class LauncherRuntimeConfigurationTest {
    @Test
    fun shared_analysis_limit_takes_precedence_over_the_legacy_limit_when_importing() {
        val local = createTempDirectory("hh-local")
        val workspace = portableWorkspace("2.0.0", "legacy-password")
        Files.writeString(workspace.resolve("generation-agent/.env"),
            "GENERATION_MAX_CONCURRENT_ANALYSIS=3\nGENERATION_MAX_CONCURRENT_RELEVANCE=6\n")
        val configuration = LauncherRuntimeConfigurationStore(rootOverride = local).loadOrCreate(workspace)
        assertEquals("3", environment(configuration.generationEnvironmentFile)["GENERATION_MAX_CONCURRENT_ANALYSIS"])
    }

    @Test
    fun generation_resource_settings_are_imported_and_existing_values_survive_reload() {
        val local = createTempDirectory("hh-local")
        val workspace = portableWorkspace("2.0.0", "legacy-password")
        Files.writeString(workspace.resolve("generation-agent/.env"),
            "GENERATION_MEMORY_PERCENT=40\nGENERATION_JOB_MEMORY_MIB=256\nGENERATION_MAX_CONCURRENT_RELEVANCE=6\n")
        val store = LauncherRuntimeConfigurationStore(rootOverride = local)
        val configuration = store.loadOrCreate(workspace)
        assertEquals("40", environment(configuration.generationEnvironmentFile)["GENERATION_MEMORY_PERCENT"])
        assertEquals("256", environment(configuration.generationEnvironmentFile)["GENERATION_JOB_MEMORY_MIB"])
        assertEquals("6", environment(configuration.generationEnvironmentFile)["GENERATION_MAX_CONCURRENT_ANALYSIS"])
        Files.writeString(configuration.generationEnvironmentFile,
            Files.readString(configuration.generationEnvironmentFile).replace("GENERATION_MEMORY_PERCENT=40", "GENERATION_MEMORY_PERCENT=30"))
        store.loadOrCreate(workspace)
        assertEquals("30", environment(configuration.generationEnvironmentFile)["GENERATION_MEMORY_PERCENT"])
        assertEquals("6", environment(configuration.generationEnvironmentFile)["GENERATION_MAX_CONCURRENT_ANALYSIS"])
    }

    @Test
    fun portable_versions_share_identity_password_and_production_projects() {
        val local = createTempDirectory("hh-local")
        val firstWorkspace = portableWorkspace("0.1.0", "legacy-password")
        val secondWorkspace = portableWorkspace("0.2.0", "different-bundle-password")
        val expectedId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val store = LauncherRuntimeConfigurationStore(rootOverride = local, uuid = { expectedId })

        val first = store.loadOrCreate(firstWorkspace)
        val second = store.loadOrCreate(secondWorkspace)

        assertEquals(expectedId.toString(), first.instanceId)
        assertEquals(first.instanceId, second.instanceId)
        assertEquals(first.directory, second.directory)
        assertEquals("hh-clicker", first.infraProjectName)
        assertEquals("hh-clicker-generation", first.generationProjectName)
        assertEquals("legacy-password", environment(first.infraEnvironmentFile)["POSTGRES_PASSWORD"])
        assertEquals("legacy-password", environment(second.infraEnvironmentFile)["POSTGRES_PASSWORD"])
        assertEquals("0.2.0", environment(second.infraEnvironmentFile)["HH_CLICKER_VERSION"])
    }

    @Test
    fun development_is_isolated_from_the_portable_installation() {
        val local = createTempDirectory("hh-local")
        val portable = portableWorkspace("0.1.0", "portable-password")
        val checkout = workspace("checkout", "development-password")
        val ids = ArrayDeque(
            listOf(
                UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
            ),
        )
        val store = LauncherRuntimeConfigurationStore(rootOverride = local, uuid = { ids.removeFirst() })

        val production = store.loadOrCreate(portable)
        val development = store.loadOrCreate(checkout)

        assertNotEquals(production.instanceId, development.instanceId)
        assertNotEquals(production.directory, development.directory)
        assertEquals("hh-clicker-dev", development.infraProjectName)
        assertEquals("hh-clicker-generation-dev", development.generationProjectName)
        assertEquals("portable-password", environment(production.infraEnvironmentFile)["POSTGRES_PASSWORD"])
        assertEquals("development-password", environment(development.infraEnvironmentFile)["POSTGRES_PASSWORD"])
    }

    @Test
    fun portable_upgrade_replaces_only_previous_generation_defaults() {
        val local = createTempDirectory("hh-local")
        val store = LauncherRuntimeConfigurationStore(rootOverride = local)
        val first = store.loadOrCreate(portableWorkspace("1.7.0", "keep-password"))
        val generationFile = first.generationEnvironmentFile
        Files.writeString(
            generationFile,
            Files.readString(generationFile)
                .replace("CODEX_CLI_VERSION=0.163.0", "CODEX_CLI_VERSION=0.153.0")
                .replace("CODEX_MODEL=gpt-6-luna", "CODEX_MODEL=gpt-5.6-luna"),
        )

        val upgraded = store.loadOrCreate(portableWorkspace("1.7.1", "different-password"))
        val values = environment(upgraded.generationEnvironmentFile)
        assertEquals("0.163.0", values["CODEX_CLI_VERSION"])
        assertEquals("gpt-6-luna", values["CODEX_MODEL"])
        assertEquals("low", values["CODEX_REASONING_EFFORT"])
        assertEquals("1.7.1", values["HH_CLICKER_VERSION"])
        assertEquals("keep-password", environment(upgraded.infraEnvironmentFile)["POSTGRES_PASSWORD"])
    }

    @Test
    fun portable_upgrade_preserves_custom_generation_settings() {
        val local = createTempDirectory("hh-local")
        val store = LauncherRuntimeConfigurationStore(rootOverride = local)
        val first = store.loadOrCreate(portableWorkspace("1.7.0", "keep-password"))
        val generationFile = first.generationEnvironmentFile
        Files.writeString(
            generationFile,
            Files.readString(generationFile)
                .replace("CODEX_CLI_VERSION=0.163.0", "CODEX_CLI_VERSION=0.160.0")
                .replace("CODEX_MODEL=gpt-6-luna", "CODEX_MODEL=gpt-6.1-sol"),
        )

        store.loadOrCreate(portableWorkspace("1.7.1", "different-password"))
        val values = environment(generationFile)
        assertEquals("0.160.0", values["CODEX_CLI_VERSION"])
        assertEquals("gpt-6.1-sol", values["CODEX_MODEL"])
    }

    @Test
    fun placeholder_password_is_replaced_with_a_private_random_value() {
        val local = createTempDirectory("hh-local")
        val configuration = LauncherRuntimeConfigurationStore(rootOverride = local)
            .loadOrCreate(portableWorkspace("0.1.0", "managed-by-launcher"))

        val password = environment(configuration.infraEnvironmentFile).getValue("POSTGRES_PASSWORD")
        assertTrue(password.matches(Regex("[0-9a-f]{48}")))
        assertEquals("production", environment(configuration.infraEnvironmentFile)["HH_CLICKER_PROFILE"])
        assertEquals("http://localhost:8080", configuration.endpoints.backendUrl)
    }

    @Test
    fun a_new_portable_profile_prefers_port_80_and_picks_stable_fallbacks_when_ports_are_busy() {
        val local = createTempDirectory("hh-local")
        val workspace = portableWorkspace("0.1.0", "managed-by-launcher")
        Files.writeString(
            workspace.resolve("infra/.env"),
            "POSTGRES_PASSWORD=managed-by-launcher\nBACKEND_PORT=managed-by-launcher\nFRONTEND_PORT=managed-by-launcher\n",
        )
        Files.writeString(workspace.resolve("generation-agent/.env"), "GENERATION_PORT=managed-by-launcher\n")
        val free = setOf(18080, 3000, 17375)

        val configuration = LauncherRuntimeConfigurationStore(
            rootOverride = local,
            portAvailable = free::contains,
        ).loadOrCreate(workspace)

        val infra = environment(configuration.infraEnvironmentFile)
        assertEquals("18080", infra["BACKEND_PORT"])
        assertEquals("3000", infra["FRONTEND_PORT"])
        assertEquals("17375", infra["GENERATION_PORT"])
        assertEquals("17375", environment(configuration.generationEnvironmentFile)["GENERATION_PORT"])
        assertEquals("http://localhost:3000", configuration.endpoints.frontendUrl)
    }

    @Test
    fun port_80_is_rendered_as_the_clean_localhost_address() {
        val local = createTempDirectory("hh-local")
        val workspace = portableWorkspace("0.1.0", "managed-by-launcher")
        Files.writeString(
            workspace.resolve("infra/.env"),
            "POSTGRES_PASSWORD=managed-by-launcher\nBACKEND_PORT=8080\nFRONTEND_PORT=80\n",
        )

        val configuration = LauncherRuntimeConfigurationStore(rootOverride = local)
            .loadOrCreate(workspace)

        assertEquals("http://localhost", configuration.endpoints.frontendUrl)
    }

    @Test
    fun only_conflicting_ports_are_reassigned_and_credentials_are_preserved() {
        val local = createTempDirectory("hh-local")
        val workspace = portableWorkspace("0.1.0", "keep-this-password")
        val store = LauncherRuntimeConfigurationStore(
            rootOverride = local,
            portAvailable = setOf(3000)::contains,
        )
        val original = store.loadOrCreate(workspace)

        val updated = store.reassignPorts(original, setOf(ComponentId.FRONTEND))
        val infra = environment(updated.infraEnvironmentFile)

        assertEquals("8080", infra["BACKEND_PORT"])
        assertEquals("3000", infra["FRONTEND_PORT"])
        assertEquals("keep-this-password", infra["POSTGRES_PASSWORD"])
        assertEquals("17374", environment(updated.generationEnvironmentFile)["GENERATION_PORT"])
        assertEquals("17374", infra["GENERATION_PORT"])
        assertEquals("http://localhost:3000", updated.endpoints.frontendUrl)
    }

    @Test
    fun compose_commands_always_use_the_explicit_profile_and_env_file() {
        val env = createTempDirectory("hh-env").resolve("infra.env")
        val docker = """C:\Docker\resources\bin\docker.exe"""

        val up = DockerComposeCommands.up(docker, "hh-clicker-dev", env)
        val down = DockerComposeCommands.down(docker, "hh-clicker-dev", env)

        assertEquals(listOf(docker, "compose", "--project-name", "hh-clicker-dev", "--env-file"), up.take(5))
        assertEquals(env.toAbsolutePath().toString(), up[5])
        assertTrue(up.takeLast(3).containsAll(listOf("up", "-d", "--build")))
        assertEquals("down", down.last())
        assertEquals("hh-clicker-dev", down[3])
    }

    private fun portableWorkspace(version: String, password: String): Path =
        workspace("portable-$version", password).also { workspace ->
            Files.writeString(workspace.resolve(BundleLayout.MARKER_FILE_NAME), "version=$version")
        }

    private fun workspace(name: String, password: String): Path {
        val workspace = createTempDirectory(name)
        Files.createDirectories(workspace.resolve("infra"))
        Files.createDirectories(workspace.resolve("generation-agent"))
        Files.writeString(
            workspace.resolve("infra/.env"),
            "POSTGRES_PASSWORD=$password\nBACKEND_PORT=8080\nFRONTEND_PORT=80\n",
        )
        Files.writeString(workspace.resolve("generation-agent/.env"), "GENERATION_PORT=17374\n")
        return workspace
    }

    private fun environment(file: Path): Map<String, String> = Files.readAllLines(file).associate { line ->
        line.substringBefore('=') to line.substringAfter('=')
    }
}
