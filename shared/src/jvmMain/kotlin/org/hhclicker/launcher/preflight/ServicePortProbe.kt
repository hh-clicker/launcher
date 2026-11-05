package org.hhclicker.launcher.preflight

import org.hhclicker.launcher.runtime.LauncherRuntimeConfiguration
import org.hhclicker.launcher.runtime.LocalPortAvailability
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.model.DockerServiceSnapshot
import org.hhclicker.launcher.model.PortConflict
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.runtime.HostPlatformDetector

/**
 * One runtime observation: which host ports are occupied by something foreign, and which of this
 * instance's Compose services are running.
 *
 * [services] is deliberately a [DockerServiceSnapshot] and not a set: an unanswered Docker query used to
 * be reported as "no service is running", which is the same value a genuinely stopped stack produces.
 */
data class ServicePortInspection(
    val conflicts: List<PortConflict>,
    val services: DockerServiceSnapshot,
) {
    /** True only when [services] may be acted upon; kept for readability at the call sites. */
    val dockerQuerySuccessful: Boolean
        get() = services.trustworthy

    companion object {
        fun unknown(diagnostics: String) = ServicePortInspection(
            conflicts = emptyList(),
            services = DockerServiceSnapshot.Unknown(diagnostics),
        )
    }
}

/** Distinguishes free host ports and ports already published by this exact launcher instance. */
class ServicePortProbe(
    private val commandProbe: CommandProbe = CommandProbe(),
    private val portAvailable: (Int) -> Boolean = LocalPortAvailability::isAvailable,
    /** Shared with every executing Docker command so observation and execution can never disagree. */
    private val dockerExecutable: () -> String = DockerCliResolver()::executable,
    private val portOwner: (Int) -> LocalPortOwner? = if (HostPlatformDetector.detect() == HostPlatform.WINDOWS) {
        WindowsPortOwnerProbe()::owner
    } else {
        { null }
    },
) {
    fun conflicts(configuration: LauncherRuntimeConfiguration): List<PortConflict> = inspect(configuration).conflicts

    /**
     * Answers three questions per configured service, in order of decreasing certainty.
     *
     * The label filtered `docker ps` is the primary source and, when it answers, it is complete. When it
     * does not answer, a service can still reach a definite answer on its own: a free host port proves
     * nothing is serving it, and an occupied one is resolved through Docker's own published-port filter.
     *
     * A free port on its own is not enough, though. "The user stopped the container" and "Docker Desktop
     * was closed" both free every port and fail every query, and reporting the second as a stopped stack
     * is what let the launcher offer a start that could not possibly work. So a port is allowed to mean
     * "stopped" only while Docker demonstrably answers something; without that, and for any service that
     * reaches no definite answer at all, the whole snapshot is [DockerServiceSnapshot.Unknown].
     */
    fun inspect(configuration: LauncherRuntimeConfiguration): ServicePortInspection {
        val docker = resolveDockerExecutable()
        val endpoints = configuration.endpoints
        val secrets = listOf(configuration.instanceId)
        val conflicts = mutableListOf<PortConflict>()
        val diagnostics = mutableListOf<String>()

        val owned = ownedRunningServices(docker, configuration.instanceId, secrets)
        owned.diagnostics?.let(diagnostics::add)
        val runningServices = owned.services.toMutableSet()
        var undetermined = false
        // Evidence that Docker itself is reachable right now. Without it a free port says nothing about
        // the container that used to publish it.
        var dockerAnswered = owned.successful

        val candidates = listOf(
            Service(ComponentId.BACKEND, "Backend", "backend", endpoints.backendPort),
            Service(ComponentId.FRONTEND, "Frontend", "frontend", endpoints.frontendPort),
            Service(ComponentId.GENERATION_AGENT, "Generation Agent", "generation-agent", endpoints.generationPort),
        )
        for (candidate in candidates) {
            val (component, label, service, port) = candidate
            if (service in runningServices) continue
            // A free host port is a fact about this machine, not a Docker answer: nothing is serving it.
            if (portAvailable(port)) continue

            val published = publishedContainer(docker, port, secrets)
            if (!published.successful) {
                // The port is taken by something this launcher cannot identify. Claiming a conflict would
                // accuse an innocent process, and claiming ownership would adopt a foreign container.
                published.diagnostics?.let(diagnostics::add)
                undetermined = true
                continue
            }
            dockerAnswered = true
            val container = published.owner
            // Defensive second source of ownership. A transiently incomplete broad `docker ps`
            // must not turn Docker Desktop's port proxy into a foreign Windows process.
            if (container?.instanceId == configuration.instanceId) {
                runningServices += service
                continue
            }
            val owner = portOwner(port)
            conflicts += PortConflict(
                component = component,
                label = label,
                port = port,
                processId = owner?.processId,
                processName = owner?.processName,
                dockerContainerName = container?.name,
                hhClickerProfile = container?.takeIf { it.instanceId != null }?.profile,
            )
        }

        // The broad query answers for every service at once; without it each service must have answered
        // for itself, and Docker must have answered at least once so that a free port carries meaning.
        // Confirmed conflicts survive either way, because each one is a completed query.
        val trustworthy = owned.successful || (dockerAnswered && !undetermined)
        return ServicePortInspection(
            conflicts = conflicts,
            services = if (trustworthy) {
                DockerServiceSnapshot.Observed(runningServices)
            } else {
                DockerServiceSnapshot.Unknown(
                    diagnostics.firstOrNull() ?: "Docker не ответил на запрос состояния контейнеров",
                )
            },
        )
    }

    /** [DockerCliResolver] owns the caching, so the registry is not re-read on every tick. */
    private fun resolveDockerExecutable(): String =
        runCatching(dockerExecutable).getOrDefault(DockerCliResolver.PATH_FALLBACK)

    /** Reads only stable Compose service labels; the human-readable `.Ports` column is never parsed. */
    private fun ownedRunningServices(
        docker: String,
        instanceId: String,
        secrets: List<String>,
    ): OwnedServicesSnapshot {
        val result = execute(DockerCliQuery.runningServices(docker, instanceId))
        if (!result.successful) {
            return OwnedServicesSnapshot(
                successful = false,
                services = emptySet(),
                diagnostics = CommandDiagnostics.describe("docker ps", result, secrets),
            )
        }
        return OwnedServicesSnapshot(
            successful = true,
            services = result.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet(),
            diagnostics = null,
        )
    }

    private fun publishedContainer(docker: String, port: Int, secrets: List<String>): PublishedContainerSnapshot {
        val result = execute(DockerCliQuery.publishedContainer(docker, port))
        if (!result.successful) {
            return PublishedContainerSnapshot(
                successful = false,
                owner = null,
                diagnostics = CommandDiagnostics.describe("docker ps --filter publish=$port", result, secrets),
            )
        }
        val fields = result.output.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.split(DockerCliQuery.FIELD_SEPARATOR)
            ?: return PublishedContainerSnapshot(successful = true, owner = null, diagnostics = null)
        val name = fields.getOrNull(0)?.trim()?.takeIf(String::isNotEmpty)
            ?: return PublishedContainerSnapshot(successful = true, owner = null, diagnostics = null)
        val instanceId = fields.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
        val profile = fields.getOrNull(2)?.trim()?.takeIf(String::isNotEmpty)
        return PublishedContainerSnapshot(
            successful = true,
            owner = DockerPortOwner(name, instanceId, profile),
            diagnostics = null,
        )
    }

    /** A probe that throws is a failed query, never an empty one. */
    private fun execute(arguments: List<String>): CommandProbeResult =
        runCatching { commandProbe.execute(arguments) }
            .getOrElse { CommandProbeResult(started = false, exitCode = null, output = it.message.orEmpty()) }

    private data class Service(val component: ComponentId, val label: String, val service: String, val port: Int)
    private data class OwnedServicesSnapshot(
        val successful: Boolean,
        val services: Set<String>,
        val diagnostics: String?,
    )

    private data class PublishedContainerSnapshot(
        val successful: Boolean,
        val owner: DockerPortOwner?,
        val diagnostics: String?,
    )

    private data class DockerPortOwner(val name: String, val instanceId: String?, val profile: String?)
}
