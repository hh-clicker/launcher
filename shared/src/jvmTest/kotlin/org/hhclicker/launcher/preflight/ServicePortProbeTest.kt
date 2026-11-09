package org.hhclicker.launcher.preflight

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import org.hhclicker.launcher.model.BundleKind
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.model.DockerServiceSnapshot
import org.hhclicker.launcher.model.PortConflict
import org.hhclicker.launcher.runtime.LauncherRuntimeConfiguration

class ServicePortProbeTest {
    private class FakeCommandProbe(private val result: CommandProbeResult) : CommandProbe() {
        override fun execute(arguments: List<String>): CommandProbeResult = result
    }

    /** Answers the label filtered query with a fixed service list and every other query with nothing. */
    private class OwnedServicesProbe(private val services: List<String>) : CommandProbe() {
        val commands = mutableListOf<List<String>>()

        override fun execute(arguments: List<String>): CommandProbeResult {
            commands += arguments
            val ownership = arguments.any { it.startsWith("label=com.hh-clicker.instance-id=") }
            return CommandProbeResult(true, 0, if (ownership) services.joinToString("\n") else "")
        }
    }

    private class RoutingCommandProbe : CommandProbe() {
        override fun execute(arguments: List<String>): CommandProbeResult {
            val checkingCurrentInstance = arguments.any { it.startsWith("label=com.hh-clicker.instance-id=") }
            return if (checkingCurrentInstance) {
                CommandProbeResult(true, 0, "")
            } else {
                CommandProbeResult(true, 0, "other-frontend|bbbbbbbb-cccc-4ddd-8eee-ffffffffffff|development")
            }
        }
    }

    private class PublishedByCurrentInstanceProbe : CommandProbe() {
        override fun execute(arguments: List<String>): CommandProbeResult {
            val checkingCurrentInstance = arguments.any { it.startsWith("label=com.hh-clicker.instance-id=") }
            return if (checkingCurrentInstance) {
                // Simulates a failed broad Docker snapshot followed by a successful per-port query.
                CommandProbeResult(started = true, exitCode = null, output = "", timedOut = true)
            } else {
                CommandProbeResult(
                    true,
                    0,
                    "hh-clicker-frontend-1|aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee|production",
                )
            }
        }
    }

    // 1. A successful Docker snapshot.
    @Test
    fun a_successful_docker_snapshot_reports_every_owned_service_as_running() {
        val probe = OwnedServicesProbe(listOf("postgres", "backend", "frontend", "generation-agent"))
        val inspection = ServicePortProbe(
            probe,
            portAvailable = { false },
            dockerExecutable = { "docker" },
        ).inspect(configuration())

        assertTrue(inspection.conflicts.isEmpty())
        assertEquals(
            DockerServiceSnapshot.Observed(setOf("postgres", "backend", "frontend", "generation-agent")),
            inspection.services,
        )
        assertEquals(true, inspection.services.isRunning("frontend"))
        // One label filtered query answers for the whole instance; no per-port query is needed.
        assertEquals(1, probe.commands.size)
    }

    // 2. The Frontend container was stopped by hand.
    @Test
    fun a_container_stopped_by_hand_becomes_a_stopped_service_not_an_unknown_one() {
        val inspection = ServicePortProbe(
            OwnedServicesProbe(listOf("postgres", "backend", "generation-agent")),
            // Stopping the container releases its published host port.
            portAvailable = { port -> port == 80 },
            dockerExecutable = { "docker" },
        ).inspect(configuration())

        assertEquals(
            DockerServiceSnapshot.Observed(setOf("postgres", "backend", "generation-agent")),
            inspection.services,
        )
        assertEquals(false, inspection.services.isRunning("frontend"))
        assertTrue(inspection.conflicts.isEmpty())
    }

    // 3. The same container is started again.
    @Test
    fun a_container_started_again_is_observed_as_running_again() {
        val inspection = ServicePortProbe(
            OwnedServicesProbe(listOf("postgres", "backend", "frontend", "generation-agent")),
            portAvailable = { false },
            dockerExecutable = { "docker" },
        ).inspect(configuration())

        assertEquals(true, inspection.services.isRunning("frontend"))
        assertTrue(inspection.dockerQuerySuccessful)
    }

    // 4. Docker cannot be queried at all.
    @Test
    fun an_unreachable_docker_query_is_unknown_and_never_an_empty_running_set() {
        val probe = ServicePortProbe(
            FakeCommandProbe(CommandProbeResult(started = true, exitCode = 1, output = "docker: cannot connect")),
            portAvailable = { false },
            dockerExecutable = { "docker" },
            portOwner = { LocalPortOwner(8052, "com.docker.backend.exe") },
        )

        val inspection = probe.inspect(configuration())

        // Nothing may be concluded, so no port is accused and no service is declared stopped.
        assertTrue(inspection.conflicts.isEmpty())
        val unknown = assertIs<DockerServiceSnapshot.Unknown>(inspection.services)
        assertNull(inspection.services.isRunning("frontend"))
        assertEquals(false, inspection.dockerQuerySuccessful)
        // The reason survives instead of being swallowed.
        assertTrue(unknown.diagnostics.contains("docker"), unknown.diagnostics)
        assertTrue(unknown.diagnostics.contains("cannot connect"), unknown.diagnostics)
    }

    @Test
    fun a_timed_out_docker_query_is_unknown() {
        val inspection = ServicePortProbe(
            FakeCommandProbe(CommandProbeResult(started = true, exitCode = null, output = "", timedOut = true)),
            portAvailable = { false },
            dockerExecutable = { "docker" },
            portOwner = { LocalPortOwner(8052, "com.docker.backend.exe") },
        ).inspect(configuration())

        assertIs<DockerServiceSnapshot.Unknown>(inspection.services)
        assertTrue(inspection.conflicts.isEmpty())
    }

    @Test
    fun an_occupied_port_not_owned_by_the_instance_is_reported() {
        val docker = CommandProbeResult(true, 0, "")
        val probe = ServicePortProbe(
            FakeCommandProbe(docker),
            portAvailable = { port -> port != 80 },
            dockerExecutable = { "docker" },
            portOwner = { LocalPortOwner(4242, "other.exe") },
        )

        assertEquals(
            listOf(PortConflict(ComponentId.FRONTEND, "Frontend", 80, 4242, "other.exe")),
            probe.conflicts(configuration()),
        )
    }

    // 8. A container belonging to another instance stays a conflict and is never adopted.
    @Test
    fun another_hh_clicker_profile_is_identified_as_a_container_owner() {
        val inspection = ServicePortProbe(
            RoutingCommandProbe(),
            portAvailable = { port -> port != 80 },
            dockerExecutable = { "docker" },
            portOwner = { null },
        ).inspect(configuration())

        assertEquals(
            listOf(
                PortConflict(
                    component = ComponentId.FRONTEND,
                    label = "Frontend",
                    port = 80,
                    dockerContainerName = "other-frontend",
                    hhClickerProfile = "development",
                ),
            ),
            inspection.conflicts,
        )
        // The foreign container must never enter this instance's runtime picture.
        assertEquals(false, inspection.services.isRunning("frontend"))
        assertEquals(DockerServiceSnapshot.Observed(emptySet()), inspection.services)
    }

    @Test
    fun published_port_fallback_recovers_a_service_when_broad_docker_query_fails() {
        val probe = ServicePortProbe(
            PublishedByCurrentInstanceProbe(),
            portAvailable = { port -> port != 80 },
            dockerExecutable = { "docker" },
            portOwner = { LocalPortOwner(8052, "com.docker.backend.exe") },
        )

        val inspection = probe.inspect(configuration())

        assertTrue(inspection.conflicts.isEmpty())
        assertEquals(DockerServiceSnapshot.Observed(setOf("frontend"), setOf("postgres")), inspection.services)
        assertEquals(null, inspection.services.isRunning("postgres"))
        assertTrue(inspection.dockerQuerySuccessful)
    }

    /**
     * Closing Docker Desktop stops the containers and frees their ports, which looks exactly like a stack
     * the user stopped on purpose. Without an answer from Docker itself the two cannot be told apart, so
     * the snapshot must stay undetermined instead of quietly reporting a stopped stack and letting the
     * screen offer a start that cannot work.
     */
    @Test
    fun docker_desktop_closed_leaves_free_ports_undetermined() {
        val inspection = ServicePortProbe(
            FakeCommandProbe(
                CommandProbeResult(
                    started = true,
                    exitCode = 1,
                    output = "error during connect: open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file",
                ),
            ),
            // Every published port was released together with the engine.
            portAvailable = { true },
            dockerExecutable = { "docker" },
        ).inspect(configuration())

        assertIs<DockerServiceSnapshot.Unknown>(inspection.services)
        assertNull(inspection.services.isRunning("frontend"))
        assertTrue(inspection.conflicts.isEmpty())
    }

    @Test
    fun a_free_port_means_stopped_once_docker_has_answered_at_least_once() {
        val probe = object : CommandProbe() {
            override fun execute(arguments: List<String>): CommandProbeResult = when {
                // The broad query is momentarily unavailable...
                arguments.any { it.startsWith("label=com.hh-clicker.instance-id=") } ->
                    CommandProbeResult(started = true, exitCode = null, output = "", timedOut = true)
                // ...but Docker itself is demonstrably alive and owns the one occupied port.
                else -> CommandProbeResult(
                    true,
                    0,
                    "hh-clicker-backend-1|aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee|production",
                )
            }
        }

        val inspection = ServicePortProbe(
            probe,
            portAvailable = { port -> port != 8080 },
            dockerExecutable = { "docker" },
        ).inspect(configuration())

        assertEquals(DockerServiceSnapshot.Observed(setOf("backend"), setOf("postgres")), inspection.services)
        assertEquals(null, inspection.services.isRunning("postgres"))
        assertEquals(false, inspection.services.isRunning("frontend"))
    }

    @Test
    fun one_undetermined_port_makes_the_whole_snapshot_unknown() {
        val probe = object : CommandProbe() {
            override fun execute(arguments: List<String>): CommandProbeResult = when {
                // The broad query fails, so every service has to answer for itself...
                arguments.any { it.startsWith("label=com.hh-clicker.instance-id=") } ->
                    CommandProbeResult(started = true, exitCode = 1, output = "broad query failed")
                // ...and the port that is actually occupied cannot answer either.
                else -> CommandProbeResult(started = false, exitCode = null, output = "")
            }
        }

        val inspection = ServicePortProbe(
            probe,
            portAvailable = { port -> port != 80 },
            dockerExecutable = { "docker" },
            portOwner = { LocalPortOwner(4242, "other.exe") },
        ).inspect(configuration())

        assertIs<DockerServiceSnapshot.Unknown>(inspection.services)
        // An unidentified owner is never turned into an accusation against a foreign process.
        assertTrue(inspection.conflicts.isEmpty())
    }

    private fun configuration() = LauncherRuntimeConfiguration(
        bundleKind = BundleKind.PORTABLE,
        instanceId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        version = "0.1.0",
        directory = Path.of("config"),
        infraEnvironmentFile = Path.of("config/infra.env"),
        generationEnvironmentFile = Path.of("config/generation.env"),
        infraProjectName = "hh-clicker",
        generationProjectName = "hh-clicker-generation",
        endpoints = ServiceEndpoints(
            backendPort = 8080,
            frontendPort = 80,
            generationPort = 17374,
            backendUrl = "http://localhost:8080",
            frontendUrl = "http://localhost",
            generationUrl = "http://localhost:17374",
        ),
    )
}
