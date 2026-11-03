package org.hhclicker.launcher.preflight

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the rule that broke the very first start on a clean machine: the launcher installed Docker
 * Desktop itself, and then ran `docker` from the PATH it had inherited before that installation existed.
 */
class DockerCliResolverTest {

    @Test
    fun docker_desktops_own_cli_wins_over_the_inherited_path() {
        val cli = Path.of("C:\\Users\\me\\AppData\\Local\\Programs\\DockerDesktop\\resources\\bin\\docker.exe")

        assertEquals(cli.toAbsolutePath().toString(), DockerCliResolver { cli }.executable())
    }

    @Test
    fun a_successful_resolution_is_remembered_so_the_registry_is_not_read_on_every_tick() {
        var lookups = 0
        val resolver = DockerCliResolver {
            lookups++
            Path.of("C:\\Docker\\resources\\bin\\docker.exe")
        }

        repeat(5) { resolver.executable() }

        assertEquals(1, lookups)
    }

    /**
     * The decisive property: an installation performed by this very launcher run must be picked up
     * without a restart, so "not found" is never cached.
     */
    @Test
    fun an_installation_inside_this_process_is_picked_up_by_the_next_call() {
        var installed: Path? = null
        val resolver = DockerCliResolver { installed }

        assertEquals(DockerCliResolver.PATH_FALLBACK, resolver.executable())
        assertNull(resolver.resolvedAbsolutePath)

        installed = Path.of("C:\\Docker\\resources\\bin\\docker.exe")

        assertEquals(installed.toAbsolutePath().toString(), resolver.executable())
        assertEquals(installed.toAbsolutePath().toString(), resolver.resolvedAbsolutePath)
    }

    @Test
    fun a_failing_lookup_falls_back_to_the_path_instead_of_throwing() {
        val resolver = DockerCliResolver { error("реестр недоступен") }

        assertEquals(DockerCliResolver.PATH_FALLBACK, resolver.executable())
    }
}
