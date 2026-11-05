package org.hhclicker.launcher.preflight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsPortOwnerProbeTest {
    private class FakeCommandProbe(private val output: String) : CommandProbe() {
        override fun execute(arguments: List<String>): CommandProbeResult = CommandProbeResult(true, 0, output)
    }

    @Test
    fun listener_pid_and_executable_name_are_reported() {
        val netstat = """
            TCP    127.0.0.1:8080       0.0.0.0:0       LISTENING       4242
            TCP    127.0.0.1:8081       127.0.0.1:50000 ESTABLISHED     9000
        """.trimIndent()
        val probe = WindowsPortOwnerProbe(
            commandProbe = FakeCommandProbe(netstat),
            environment = { "C:\\Windows" },
            processCommand = { "C:\\Tools\\other.exe" },
        )

        assertEquals(LocalPortOwner(4242, "other.exe"), probe.owner(8080))
        assertEquals(false, probe.isAvailable(8080))
        assertEquals(true, probe.isAvailable(8081))
    }

    @Test
    fun a_recently_closed_socket_does_not_count_as_a_listener() {
        val probe = WindowsPortOwnerProbe(
            commandProbe = FakeCommandProbe("TCP    127.0.0.1:17374    0.0.0.0:0    TIME_WAIT    0"),
            environment = { "C:\\Windows" },
        )

        assertNull(probe.owner(17374))
        assertEquals(true, probe.isAvailable(17374))
    }
}

