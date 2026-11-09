package org.hhclicker.launcher.controller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.hhclicker.launcher.model.CodexAuthState
import org.hhclicker.launcher.model.CodexAuthStatus

class CodexAuthReconciliationTest {
    @Test
    fun restarted_generator_recovers_authentication_from_the_cached_snapshot() {
        val stopped = CodexAuthState(CodexAuthStatus.UNAVAILABLE, message = "Сначала запустите Generation Agent")
        for (status in listOf(CodexAuthStatus.SIGNED_IN, CodexAuthStatus.SIGNED_OUT, CodexAuthStatus.FAILED)) {
            val snapshot = CodexAuthState(status, message = "Observed")
            assertEquals(snapshot, reconcileObservedCodexAuth(stopped, stopped, true, snapshot))
        }
        val waiting = reconcileObservedCodexAuth(stopped, stopped, true, null)
        assertEquals(CodexAuthStatus.FAILED, waiting.status)
        assertFalse(waiting.busy)
        assertFalse(waiting.message!!.contains("Сначала запустите"))
    }

    @Test
    fun runtime_observation_cannot_replace_a_manual_refresh_or_login() {
        val before = CodexAuthState(CodexAuthStatus.UNAVAILABLE)
        val snapshot = CodexAuthState(CodexAuthStatus.SIGNED_OUT)
        for (status in listOf(CodexAuthStatus.CHECKING, CodexAuthStatus.AUTHORIZING, CodexAuthStatus.SIGNED_IN)) {
            val current = CodexAuthState(status)
            assertEquals(current, reconcileObservedCodexAuth(current, before, true, snapshot))
        }
        val checking = CodexAuthState(CodexAuthStatus.CHECKING)
        assertEquals(checking, reconcileObservedCodexAuth(checking, checking, true, snapshot))
    }

    @Test
    fun stopping_the_container_invalidates_the_old_authentication_snapshot() {
        val connected = CodexAuthState(CodexAuthStatus.SIGNED_IN)
        assertEquals(CodexAuthStatus.UNAVAILABLE, reconcileObservedCodexAuth(connected, connected, false, connected).status)
    }
}
