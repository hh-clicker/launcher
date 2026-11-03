package org.hhclicker.launcher.runtime

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Browser Agent drives a real browser, so the only two acceptable outcomes for a process left behind
 * by a previous launcher run are "proven ours and ended" or "left completely alone".
 */
class BrowserAgentLifecycleTest {

    private val record = BrowserAgentRecord(
        processId = 4242,
        startedAtEpochMilli = 1_725_000_000_000,
        command = "C:\\Users\\me\\AppData\\Local\\HH Clicker\\runtime\\node-24.20.0\\node.exe",
        agentId = "launcher-7f1b0d1e",
    )

    private fun facts(
        processId: Long = record.processId,
        startedAtEpochMilli: Long? = record.startedAtEpochMilli,
        command: String? = record.command,
    ) = RunningProcessFacts(processId, startedAtEpochMilli, command)

    // --- ownership ---------------------------------------------------------------------------------

    @Test
    fun a_process_matching_the_record_by_id_start_instant_and_executable_is_ours() {
        val verdict = BrowserAgentOwnershipCheck.verdict(record, facts())

        assertEquals(BrowserAgentOwnership.Owned(record), verdict)
    }

    @Test
    fun a_reused_process_id_is_never_terminated() {
        val verdict = BrowserAgentOwnershipCheck.verdict(
            record,
            facts(startedAtEpochMilli = record.startedAtEpochMilli + 1),
        )

        val notOurs = assertIs<BrowserAgentOwnership.NotOurs>(verdict)
        assertTrue(notOurs.reason.contains("переиспользован"), notOurs.reason)
    }

    @Test
    fun an_unrelated_node_process_on_the_same_id_is_never_terminated() {
        val verdict = BrowserAgentOwnershipCheck.verdict(
            record,
            facts(command = "C:\\Program Files\\nodejs\\node.exe"),
        )

        assertIs<BrowserAgentOwnership.NotOurs>(verdict)
    }

    @Test
    fun missing_evidence_is_never_treated_as_permission() {
        assertIs<BrowserAgentOwnership.NotOurs>(
            BrowserAgentOwnershipCheck.verdict(record, facts(startedAtEpochMilli = null)),
        )
        assertIs<BrowserAgentOwnership.NotOurs>(
            BrowserAgentOwnershipCheck.verdict(record, facts(command = null)),
        )
        assertIs<BrowserAgentOwnership.NotOurs>(
            BrowserAgentOwnershipCheck.verdict(record, facts(processId = record.processId + 1)),
        )
    }

    @Test
    fun nothing_recorded_and_nothing_running_are_distinguishable() {
        assertEquals(BrowserAgentOwnership.NothingRecorded, BrowserAgentOwnershipCheck.verdict(null, null))
        assertEquals(BrowserAgentOwnership.ProcessGone, BrowserAgentOwnershipCheck.verdict(record, null))
    }

    // --- the record that survives the launcher process ---------------------------------------------

    @Test
    fun the_ownership_record_survives_a_write_and_read_cycle() {
        val file = createTempDirectory("hh-agent-handle").resolve("browser-agent.json")
        val store = BrowserAgentHandleStore(fileOverride = file)

        assertNull(store.read())
        assertTrue(store.write(record))
        assertEquals(record, store.read())

        store.clear()
        assertNull(store.read())
        assertTrue(Files.notExists(file))
    }

    @Test
    fun a_damaged_record_is_ignored_rather_than_half_believed() {
        val file = createTempDirectory("hh-agent-handle-broken").resolve("browser-agent.json")
        Files.writeString(file, """{"processId":4242,"command":"node.exe"}""")

        assertNull(BrowserAgentHandleStore(fileOverride = file).read())
    }

    // --- reclaiming --------------------------------------------------------------------------------

    private fun agent(
        file: java.nio.file.Path,
        facts: (Long) -> RunningProcessFacts?,
        terminated: MutableList<Long> = ArrayList(),
    ) = BrowserAgentProcess(
        handleStore = BrowserAgentHandleStore(fileOverride = file),
        processFacts = facts,
        terminate = { pid -> terminated += pid; true },
        registerShutdownHook = {},
    )

    @Test
    fun an_abandoned_agent_is_terminated_and_its_claim_removed() {
        val file = createTempDirectory("hh-reclaim-own").resolve("browser-agent.json")
        val store = BrowserAgentHandleStore(fileOverride = file)
        store.write(record)
        val terminated = ArrayList<Long>()

        val outcome = agent(file, { facts() }, terminated).reclaimPreviousInstance()

        assertEquals(BrowserAgentReclaim.TERMINATED, outcome)
        assertEquals(listOf(record.processId), terminated)
        assertNull(store.read())
    }

    @Test
    fun a_process_that_cannot_be_proven_ours_keeps_running() {
        val file = createTempDirectory("hh-reclaim-foreign").resolve("browser-agent.json")
        val store = BrowserAgentHandleStore(fileOverride = file)
        store.write(record)
        val terminated = ArrayList<Long>()

        val outcome = agent(file, { facts(command = "C:\\Program Files\\nodejs\\node.exe") }, terminated)
            .reclaimPreviousInstance()

        assertEquals(BrowserAgentReclaim.LEFT_ALONE, outcome)
        assertTrue(terminated.isEmpty(), "чужой процесс не должен завершаться")
        assertNull(store.read())
    }

    @Test
    fun a_stale_record_is_removed_without_touching_anything() {
        val file = createTempDirectory("hh-reclaim-stale").resolve("browser-agent.json")
        BrowserAgentHandleStore(fileOverride = file).write(record)
        val terminated = ArrayList<Long>()

        val outcome = agent(file, { null }, terminated).reclaimPreviousInstance()

        assertEquals(BrowserAgentReclaim.STALE_RECORD_REMOVED, outcome)
        assertTrue(terminated.isEmpty())
    }

    @Test
    fun a_launcher_with_no_previous_run_does_nothing() {
        val file = createTempDirectory("hh-reclaim-none").resolve("browser-agent.json")

        assertEquals(
            BrowserAgentReclaim.NOTHING_TO_DO,
            agent(file, { facts() }).reclaimPreviousInstance(),
        )
    }

    // --- the shutdown hook -------------------------------------------------------------------------

    /**
     * A child process that answers exactly as told, so both outcomes of the hook can be observed without
     * a real exit. [Process.destroyForcibly] only asks the operating system to end the process, which is
     * why "forced" and "gone" are separate flags here.
     */
    private class FakeProcess(
        private var alive: Boolean = true,
        private val diesOnDestroy: Boolean = false,
        private val diesOnForce: Boolean = false,
        private val unreadable: Boolean = false,
    ) : Process() {
        var destroyCalls = 0
        var forcedCalls = 0

        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()
        override fun getInputStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun getErrorStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0

        override fun isAlive(): Boolean {
            if (unreadable) throw IllegalStateException("состояние процесса недоступно")
            return alive
        }

        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean {
            if (unreadable) throw IllegalStateException("состояние процесса недоступно")
            return !alive
        }

        override fun destroy() {
            destroyCalls++
            if (diesOnDestroy) alive = false
        }

        override fun destroyForcibly(): Process {
            forcedCalls++
            if (diesOnForce) alive = false
            return this
        }
    }

    private fun agentWithRecord(): Pair<BrowserAgentProcess, BrowserAgentHandleStore> {
        val file = createTempDirectory("hh-shutdown").resolve("browser-agent.json")
        val store = BrowserAgentHandleStore(fileOverride = file)
        store.write(record)
        val agent = BrowserAgentProcess(
            handleStore = store,
            processFacts = { null },
            terminate = { false },
            registerShutdownHook = {},
        )
        return agent to store
    }

    @Test
    fun the_shutdown_hook_keeps_the_ownership_record_when_the_exit_is_not_confirmed() {
        val (agent, store) = agentWithRecord()
        val child = FakeProcess()

        agent.releaseOnShutdown(child)

        assertEquals(1, child.destroyCalls)
        // destroyForcibly is asynchronous, so calling it is not proof of anything.
        assertEquals(1, child.forcedCalls)
        assertNotNull(store.read(), "запись владения нужна следующему запуску, чтобы найти процесс")
        assertEquals(record, store.read())
    }

    @Test
    fun the_shutdown_hook_drops_the_ownership_record_after_a_confirmed_exit() {
        val (agent, store) = agentWithRecord()

        agent.releaseOnShutdown(FakeProcess(diesOnDestroy = true))

        assertNull(store.read())
    }

    @Test
    fun an_exit_confirmed_only_after_the_forced_kill_still_drops_the_record() {
        val (agent, store) = agentWithRecord()
        val child = FakeProcess(diesOnForce = true)

        agent.releaseOnShutdown(child)

        assertEquals(1, child.forcedCalls)
        assertNull(store.read())
    }

    @Test
    fun a_process_whose_state_cannot_be_read_keeps_its_record() {
        val (agent, store) = agentWithRecord()

        agent.releaseOnShutdown(FakeProcess(unreadable = true))

        assertEquals(record, store.read())
    }

    @Test
    fun a_launcher_that_owns_no_process_leaves_the_record_untouched() {
        val (agent, store) = agentWithRecord()

        agent.releaseOnShutdown(child = null)

        assertEquals(record, store.read())
    }
}
