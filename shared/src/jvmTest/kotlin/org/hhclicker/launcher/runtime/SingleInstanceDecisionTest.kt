package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A failed bind used to mean exactly one thing, and the second process exited without a word. Anything
 * else holding the port produced the same silence, which from the outside is indistinguishable from the
 * launcher not starting at all.
 */
class SingleInstanceDecisionTest {
    private val port = SingleInstanceGuard.DEFAULT_PORT

    @Test
    fun only_a_correct_handshake_counts_as_a_second_hh_clicker() {
        assertEquals(
            SingleInstanceOutcome.ActivatedRunningLauncher,
            SingleInstanceDecision.afterBindConflict(port, SingleInstanceHandshake.LAUNCHER_ANSWERED),
        )
    }

    @Test
    fun every_other_owner_of_the_port_is_reported_to_the_user() {
        listOf(
            SingleInstanceHandshake.CONNECTION_REFUSED,
            SingleInstanceHandshake.NO_ANSWER,
            SingleInstanceHandshake.WRONG_PROTOCOL,
        ).forEach { handshake ->
            val outcome = SingleInstanceDecision.afterBindConflict(port, handshake)

            val held = assertIs<SingleInstanceOutcome.PortHeldByOtherApplication>(outcome, "$handshake")
            assertEquals(port, held.port)
            assertEquals(handshake, held.handshake)
        }
    }

    @Test
    fun a_silent_exit_is_allowed_only_for_a_confirmed_second_instance() {
        assertNull(SingleInstanceDecision.message(SingleInstanceOutcome.ActivatedRunningLauncher))

        val messages = listOf(
            SingleInstanceDecision.afterBindConflict(port, SingleInstanceHandshake.WRONG_PROTOCOL),
            SingleInstanceOutcome.StartupFailed(port, "Access denied"),
        ).map(SingleInstanceDecision::message)

        messages.forEach { message ->
            assertNotNull(message)
            assertTrue(message.contains(port.toString()), message)
            assertTrue(message.contains("HH Clicker"), message)
        }
    }

    @Test
    fun the_message_never_leaks_the_technical_detail_it_was_given() {
        val message = assertNotNull(
            SingleInstanceDecision.message(
                SingleInstanceOutcome.StartupFailed(port, "java.net.SocketException: Permission denied"),
            ),
        )

        assertFalse(message.contains("SocketException"), message)
        assertFalse(message.contains("java.net"), message)
    }
}
