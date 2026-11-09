package org.hhclicker.launcher.runtime

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.hhclicker.launcher.preflight.ServiceEndpoints
import org.hhclicker.launcher.preflight.BundleLayout
import org.hhclicker.launcher.preflight.NodeRuntimeLocator
import org.hhclicker.launcher.preflight.NodeRuntimeProbe
import org.hhclicker.launcher.model.NodeRuntimeSource

/** Open so the launcher lifecycle can be tested without starting a real Node process. */
open class BrowserAgentProcess(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    private val nodeRuntimeProbe: (Path) -> NodeRuntimeProbe = { NodeRuntimeLocator().probe(it) },
    /** Survives the launcher process, so a run that was killed can still be identified afterwards. */
    private val handleStore: BrowserAgentHandleStore = BrowserAgentHandleStore(),
    private val processFacts: (Long) -> RunningProcessFacts? = ::runningProcessFacts,
    private val terminate: (Long) -> Boolean = ::terminateProcess,
    private val registerShutdownHook: (Runnable) -> Unit = { hook ->
        runCatching { Runtime.getRuntime().addShutdownHook(Thread(hook, "hh-clicker-browser-agent-shutdown")) }
    },
) {
    @Volatile
    private var ownedProcess: Process? = null
    private val output = StringBuilder()
    private val shutdownHookRegistered = AtomicBoolean(false)

    open fun isRunning(): Boolean = ownedProcess?.isAlive == true

    /**
     * Deals with a Browser Agent left behind by a launcher run that ended without stopping it.
     *
     * The agent drives a real browser, so an abandoned one keeps acting on the user's machine with
     * nobody in control of it. It cannot be adopted back: the launcher talks to it through the standard
     * input of the process it started, and those streams die with the process that owned them. What is
     * possible — and what happens here — is proving that the recorded process really is the previous
     * agent and only then ending it.
     *
     * Proof is required, never assumed. The record is written by this launcher into its own directory,
     * and the process must still match it by id, by creation instant and by executable path. Anything
     * missing or different means the process is left untouched and only the stale record is dropped.
     *
     * TODO: a reaped agent may leave the browser it launched behind, and a stopped agent cannot be
     * resumed. Both need the agent itself to expose a control endpoint (a local socket carrying its
     * agent id) so a new launcher can ask it to shut down or take it over instead of terminating it.
     */
    open fun reclaimPreviousInstance(): BrowserAgentReclaim {
        if (ownedProcess?.isAlive == true) return BrowserAgentReclaim.NOTHING_TO_DO
        val record = handleStore.read()
        val facts = record?.let { runCatching { processFacts(it.processId) }.getOrNull() }
        return when (val verdict = BrowserAgentOwnershipCheck.verdict(record, facts)) {
            BrowserAgentOwnership.NothingRecorded -> BrowserAgentReclaim.NOTHING_TO_DO

            BrowserAgentOwnership.ProcessGone -> {
                handleStore.clear()
                BrowserAgentReclaim.STALE_RECORD_REMOVED
            }

            is BrowserAgentOwnership.NotOurs -> {
                // The process keeps running. Only our own claim on it is withdrawn.
                handleStore.clear()
                BrowserAgentReclaim.LEFT_ALONE
            }

            is BrowserAgentOwnership.Owned -> {
                val ended = runCatching { terminate(verdict.record.processId) }.getOrDefault(false)
                if (ended) handleStore.clear()
                if (ended) BrowserAgentReclaim.TERMINATED else BrowserAgentReclaim.TERMINATION_FAILED
            }
        }
    }

    open suspend fun start(workspace: Path, endpoints: ServiceEndpoints): String? {
        if (ownedProcess?.isAlive == true) return null

        val directory = workspace.resolve("browser-agent")
        val arguments = launchArguments(workspace)
            ?: return "Browser Agent не собран и tsx недоступен"
        val agentId = "launcher-${UUID.randomUUID()}"
        val child = try {
            ProcessBuilder(arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["HH_CLICKER_LAUNCHER_MANAGED"] = "true"
                    environment()["HH_CLICKER_BACKEND_URL"] = endpoints.backendUrl
                    environment()["HH_CLICKER_GENERATION_URL"] = endpoints.generationUrl
                    environment()["HH_CLICKER_AGENT_ID"] = agentId
                    environment()["HH_CLICKER_AGENT_VERSION"] = BundleLayout.version(workspace) ?: "dev"
                    WindowsJavaDllIsolation.cleanChildEnvironment(environment())
                }
                .start()
        } catch (exception: Exception) {
            return exception.message ?: "Не удалось запустить Browser Agent"
        }

        synchronized(output) { output.clear() }
        ownedProcess = child
        rememberOwnership(child, agentId)
        installShutdownHook()
        captureOutput(child)

        val ready = awaitHeartbeat(endpoints.backendUrl, agentId, child)
        if (ready) return null

        val reason = if (!child.isAlive) {
            lastOutputLine() ?: "Browser Agent завершился сразу после запуска"
        } else {
            "Backend не получил heartbeat от Browser Agent"
        }
        stop()
        return reason
    }

    open suspend fun stop(): String? = withContext(Dispatchers.IO) {
        val child = ownedProcess ?: run {
            // Nothing of ours is running, so no claim may remain on disk either.
            handleStore.clear()
            return@withContext null
        }
        if (!child.isAlive) {
            ownedProcess = null
            handleStore.clear()
            return@withContext null
        }

        runCatching {
            child.outputStream.bufferedWriter().use { writer ->
                writer.appendLine("shutdown")
                writer.flush()
            }
        }
        if (!child.waitFor(GRACEFUL_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            child.destroy()
            if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly()
        }
        val stopped = !child.isAlive
        if (stopped) {
            ownedProcess = null
            handleStore.clear()
        }
        if (stopped) null else "Не удалось завершить Browser Agent"
    }

    /**
     * Records what was started, so a launcher that never gets to run any shutdown code still leaves a
     * verifiable claim behind. Failing to write it is not a reason to refuse the start: the agent works,
     * only the automatic cleanup of an abandoned one is lost.
     */
    private fun rememberOwnership(child: Process, agentId: String) {
        val info = runCatching { child.info() }.getOrNull()
        val command = info?.command()?.orElse(null)
        val startedAt = info?.startInstant()?.orElse(null)?.toEpochMilli()
        if (command == null || startedAt == null) {
            handleStore.clear()
            return
        }
        handleStore.write(
            BrowserAgentRecord(
                processId = child.pid(),
                startedAtEpochMilli = startedAt,
                command = command,
                agentId = agentId,
            ),
        )
    }

    /**
     * Best-effort cleanup for the exits that still run JVM code — the window closing without a stop, an
     * unhandled failure, Ctrl+C. It deliberately does not replace [reclaimPreviousInstance]: a process
     * killed from Task Manager runs no hook at all, and that case is handled on the next start instead.
     */
    private fun installShutdownHook() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) return
        registerShutdownHook(Runnable { releaseOnShutdown() })
    }

    /**
     * The body of the shutdown hook, separated so both of its outcomes can be tested without a real exit.
     *
     * The ownership record is dropped only after the process has been *observed* to be gone.
     * [Process.destroyForcibly] is asynchronous — it asks the operating system to end the process and
     * returns immediately — so treating the call itself as proof would delete the claim while the agent
     * is still alive, and the next launcher would then have nothing left to identify it by. When the exit
     * cannot be confirmed, or the check itself fails, the record deliberately survives: a stale record
     * costs one harmless verification on the next start, a missing one costs an unattended browser.
     */
    internal fun releaseOnShutdown(child: Process? = ownedProcess) {
        val owned = child ?: return
        val ended = runCatching {
            if (!owned.isAlive) {
                true
            } else {
                owned.destroy()
                if (owned.waitFor(SHUTDOWN_DESTROY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    true
                } else {
                    owned.destroyForcibly()
                    owned.waitFor(SHUTDOWN_FORCED_TIMEOUT_SECONDS, TimeUnit.SECONDS) || !owned.isAlive
                }
            }
        }.getOrDefault(false)
        if (ended) handleStore.clear()
    }

    /**
     * The exact argument list the agent is started with.
     *
     * A compatible system Node.js is used through PATH. Otherwise the launcher-managed portable
     * runtime is passed by absolute path. A missing or incompatible runtime returns null and is handled
     * by the setup screen before services can start.
     *
     * Internal so that the resolution is unit tested without starting a process. It is always a list:
     * nothing here is ever handed to a shell as a command string.
     */
    internal fun launchArguments(workspace: Path): List<String>? {
        val directory = workspace.resolve("browser-agent")
        val runtime = nodeRuntimeProbe(workspace)
        if (!runtime.diagnostics.ready) return null
        val node = when (runtime.diagnostics.source) {
            NodeRuntimeSource.SYSTEM_PATH -> "node"
            NodeRuntimeSource.LOCAL_PORTABLE -> runtime.executable?.toAbsolutePath()?.toString() ?: return null
            NodeRuntimeSource.NONE -> return null
        }

        val compiled = directory.resolve("dist/main.js")
        if (Files.isRegularFile(compiled)) return listOf(node, compiled.toString())

        val source = directory.resolve("src/main.ts")
        val tsx = directory.resolve("node_modules/tsx/dist/cli.mjs")
        return if (Files.isRegularFile(source) && Files.isRegularFile(tsx)) {
            listOf(node, tsx.toString(), source.toString())
        } else {
            null
        }
    }

    private suspend fun awaitHeartbeat(backendUrl: String, agentId: String, child: Process): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (child.isAlive && System.currentTimeMillis() < deadline) {
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    val request = HttpRequest.newBuilder(URI.create("$backendUrl/api/v1/browser-agent/status"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() in 200..299) response.body() else ""
                }.getOrDefault("")
            }
            if (body.contains("\"agentId\":\"$agentId\"") && !body.contains("\"status\":\"OFFLINE\"")) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun captureOutput(child: Process) {
        thread(name = "browser-agent-output", isDaemon = true) {
            runCatching {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            output.appendLine(line)
                            if (output.length > MAX_OUTPUT_CHARACTERS) {
                                output.delete(0, output.length - MAX_OUTPUT_CHARACTERS)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun lastOutputLine(): String? = synchronized(output) {
        output.lineSequence().lastOrNull { it.isNotBlank() }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 500L
        const val GRACEFUL_STOP_TIMEOUT_SECONDS = 20L

        /** A shutdown hook holds up the whole exit, so both waits are short and bounded. */
        const val SHUTDOWN_DESTROY_TIMEOUT_SECONDS = 2L
        const val SHUTDOWN_FORCED_TIMEOUT_SECONDS = 3L
        const val MAX_OUTPUT_CHARACTERS = 8_000
    }
}

/** Facts the operating system reports about a process id, or null when nothing holds it. */
internal fun runningProcessFacts(processId: Long): RunningProcessFacts? {
    val handle = ProcessHandle.of(processId).orElse(null) ?: return null
    if (!handle.isAlive) return null
    val info = handle.info()
    return RunningProcessFacts(
        processId = handle.pid(),
        startedAtEpochMilli = info.startInstant().orElse(null)?.toEpochMilli(),
        command = info.command().orElse(null),
    )
}

/**
 * Ends a process that has already been proven to belong to this application.
 *
 * It is never called with an unverified id: [BrowserAgentOwnershipCheck] decides, this only executes.
 */
internal fun terminateProcess(processId: Long): Boolean {
    val handle = ProcessHandle.of(processId).orElse(null) ?: return true
    if (!handle.isAlive) return true
    handle.destroy()
    val exited = runCatching {
        handle.onExit().orTimeout(RECLAIM_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).join()
        true
    }.getOrDefault(false)
    if (exited || !handle.isAlive) return true
    handle.destroyForcibly()
    return !handle.isAlive
}

private const val RECLAIM_TIMEOUT_SECONDS = 5L
