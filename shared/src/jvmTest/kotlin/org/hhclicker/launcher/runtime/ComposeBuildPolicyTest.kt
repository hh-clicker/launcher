package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.hhclicker.launcher.model.BundleKind
import org.hhclicker.launcher.preflight.ServiceEndpoints

class ComposeBuildPolicyTest {
    @Test
    fun installed_payload_is_built_once_and_reused_only_while_images_exist() = runBlocking {
        val workspace = createTempDirectory("compose-payload")
        Files.writeString(workspace.resolve("release-manifest.json"), "release-1")
        val configuration = configuration(workspace, BundleKind.PORTABLE)
        val runner = FakeRunner()
        val policy = ComposeBuildPolicy(runner) { "docker.exe" }
        val services = listOf("backend", "frontend")

        val first = policy.decide(workspace, configuration, "hh-clicker", services)
        assertTrue(first.build)
        assertTrue(runner.calls.isEmpty())

        policy.rememberSuccessfulBuild(configuration, "hh-clicker", first)
        val unchanged = policy.decide(workspace, configuration, "hh-clicker", services)
        assertFalse(unchanged.build)
        assertEquals(listOf("hh-clicker-backend", "hh-clicker-frontend"), runner.calls.last().takeLast(2))

        runner.imageInspectSuccessful = false
        assertTrue(policy.decide(workspace, configuration, "hh-clicker", services).build)
    }

    @Test
    fun a_new_payload_rebuilds_each_compose_project_independently() = runBlocking {
        val workspace = createTempDirectory("compose-upgrade")
        val manifest = workspace.resolve("release-manifest.json")
        Files.writeString(manifest, "nightly-1")
        val configuration = configuration(workspace, BundleKind.PORTABLE)
        val policy = ComposeBuildPolicy(FakeRunner()) { "docker.exe" }

        val infra = policy.decide(workspace, configuration, "hh-clicker", listOf("backend", "frontend"))
        policy.rememberSuccessfulBuild(configuration, "hh-clicker", infra)
        assertTrue(policy.decide(workspace, configuration, "hh-clicker-generation", listOf("generation-agent")).build)

        Files.writeString(manifest, "nightly-2")
        assertTrue(policy.decide(workspace, configuration, "hh-clicker", listOf("backend", "frontend")).build)
    }

    @Test
    fun changed_build_argument_requires_a_new_generation_image() = runBlocking {
        val workspace = createTempDirectory("compose-build-arg")
        Files.writeString(workspace.resolve("release-manifest.json"), "release-1")
        val configuration = configuration(workspace, BundleKind.PORTABLE)
        val policy = ComposeBuildPolicy(FakeRunner()) { "docker.exe" }
        val project = "hh-clicker-generation"
        val services = listOf("generation-agent")

        val first = policy.decide(workspace, configuration, project, services, mapOf("CODEX_CLI_VERSION" to "0.163.0"))
        policy.rememberSuccessfulBuild(configuration, project, first)

        assertFalse(policy.decide(workspace, configuration, project, services, mapOf("CODEX_CLI_VERSION" to "0.163.0")).build)
        assertTrue(policy.decide(workspace, configuration, project, services, mapOf("CODEX_CLI_VERSION" to "0.164.0")).build)
    }

    @Test
    fun developer_checkout_always_rebuilds() = runBlocking {
        val workspace = createTempDirectory("compose-checkout")
        val runner = FakeRunner()
        val policy = ComposeBuildPolicy(runner) { "docker.exe" }

        val decision = policy.decide(workspace, configuration(workspace, BundleKind.DEVELOPER), "hh-clicker-dev", listOf("backend"))

        assertTrue(decision.build)
        assertEquals(null, decision.payloadId)
        assertTrue(runner.calls.isEmpty())
    }

    private fun configuration(workspace: Path, kind: BundleKind): LauncherRuntimeConfiguration {
        val directory = Files.createDirectories(workspace.resolve("data"))
        return LauncherRuntimeConfiguration(
            bundleKind = kind,
            instanceId = "test-instance",
            version = "1.9.0",
            directory = directory,
            infraEnvironmentFile = directory.resolve("infra.env"),
            generationEnvironmentFile = directory.resolve("generation.env"),
            infraProjectName = "hh-clicker",
            generationProjectName = "hh-clicker-generation",
            endpoints = ServiceEndpoints(8080, 80, 17374, "http://localhost:8080", "http://localhost", "http://localhost:17374"),
        )
    }

    private class FakeRunner : CommandRunner() {
        val calls = mutableListOf<List<String>>()
        var imageInspectSuccessful = true

        override suspend fun execute(
            arguments: List<String>,
            workingDirectory: Path,
            timeoutMs: Long,
            environment: Map<String, String>,
        ): CommandResult {
            calls += arguments
            return CommandResult(if (imageInspectSuccessful) 0 else 1, "")
        }
    }
}
