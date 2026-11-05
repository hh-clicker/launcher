package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPortAvailabilityTest {
    @Test
    fun linux_port_check_counts_only_listening_sockets_on_both_ip_families() {
        val tcp = """
            sl local_address rem_address st
             0: 00000000:0050 00000000:0000 06
             1: 00000000:1F90 00000000:0000 0A
        """.trimIndent()
        val tcp6 = """
            sl local_address rem_address st
             0: 00000000000000000000000000000000:0050 00000000000000000000000000000000:0000 0A
        """.trimIndent()

        assertFalse(LocalPortAvailability.linuxListeningPortPresent(80, listOf(tcp, "sl local_address rem_address st")))
        assertTrue(LocalPortAvailability.linuxListeningPortPresent(80, listOf(tcp, tcp6)))
        assertTrue(LocalPortAvailability.linuxListeningPortPresent(8080, listOf(tcp)))
    }
}
