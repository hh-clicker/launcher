package org.hhclicker.launcher.controller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.model.ComponentStatus
import org.hhclicker.launcher.model.DockerServiceSnapshot
import org.hhclicker.launcher.model.LauncherState
import org.hhclicker.launcher.model.LauncherStatus
import org.hhclicker.launcher.preflight.PostgresHealth
import org.hhclicker.launcher.preflight.ServiceEndpoints

class PostgresReconciliationTest {
    private val endpoints = ServiceEndpoints(8080, 80, 17374,
        "http://localhost:8080", "http://localhost", "http://localhost:17374")
    private val services = setOf("postgres", "backend", "frontend", "generation-agent")
    private val running = LauncherState.stopped().components.map { it.copy(status = ComponentStatus.RUNNING) }

    @Test
    fun stopped_database_explains_backend_failure_and_recovers_after_healthy_restart() {
        val stopped = reconcileRuntimeComponents(running, endpoints,
            DockerServiceSnapshot.Observed(services - "postgres"), true)
        assertEquals(ComponentStatus.STOPPED, stopped.single { it.id == ComponentId.POSTGRES }.status)
        val backend = stopped.single { it.id == ComponentId.BACKEND }
        assertEquals(ComponentStatus.FAILED, backend.status)
        assertTrue(backend.details.orEmpty().contains("PostgreSQL остановлен"))
        assertEquals(LauncherStatus.FAILED, reconciledLauncherStatus(stopped))
        val recovered = reconcileRuntimeComponents(stopped, endpoints,
            DockerServiceSnapshot.Observed(services), true, PostgresHealth.HEALTHY)
        assertTrue(recovered.all { it.status == ComponentStatus.RUNNING && it.details == null })
        assertEquals(LauncherStatus.RUNNING, reconciledLauncherStatus(recovered))
    }

    @Test
    fun running_container_is_not_enough_to_establish_database_readiness() {
        for ((health, expected) in listOf(PostgresHealth.STARTING to ComponentStatus.STARTING,
            PostgresHealth.UNHEALTHY to ComponentStatus.FAILED)) {
            val observed = reconcileRuntimeComponents(running, endpoints,
                DockerServiceSnapshot.Observed(services), true, health)
            assertEquals(expected, observed.single { it.id == ComponentId.POSTGRES }.status)
            assertEquals(ComponentStatus.FAILED, observed.single { it.id == ComponentId.BACKEND }.status)
        }
    }

    @Test
    fun failed_container_observation_preserves_database_and_backend_state() {
        val result = reconcileRuntimeComponents(running, endpoints, DockerServiceSnapshot.Unknown("Unavailable"), true)
        assertEquals(running, result)
    }

    @Test
    fun port_fallback_cannot_claim_that_an_internal_database_stopped() {
        val result = reconcileRuntimeComponents(running, endpoints,
            DockerServiceSnapshot.Observed(services - "postgres", setOf("postgres")), true)
        assertEquals(ComponentStatus.RUNNING, result.single { it.id == ComponentId.POSTGRES }.status)
        assertEquals(ComponentStatus.RUNNING, result.single { it.id == ComponentId.BACKEND }.status)
    }
}
