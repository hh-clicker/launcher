package org.hhclicker.launcher.runtime

import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** What the process already holding the single-instance port said when it was asked. */
enum class SingleInstanceHandshake {
    /** It answered with the launcher's own protocol: it really is another HH Clicker. */
    LAUNCHER_ANSWERED,

    /** The port is bound but nothing accepted the connection. */
    CONNECTION_REFUSED,

    /** The connection succeeded and then stayed silent. */
    NO_ANSWER,

    /** Something answered, but not with the launcher's protocol. */
    WRONG_PROTOCOL,
}

/**
 * Result of claiming the single-instance port.
 *
 * A failed bind used to mean one thing — "HH Clicker is already running" — and the second process quit
 * without a word. Any other program holding the port produced the same silent exit, which is
 * indistinguishable from the launcher not starting at all.
 */
sealed interface SingleInstanceOutcome {
    /** This process owns the port and must run the application. */
    data class Acquired(val guard: SingleInstanceGuard) : SingleInstanceOutcome

    /** A running HH Clicker was found and brought to the front; this process must exit quietly. */
    data object ActivatedRunningLauncher : SingleInstanceOutcome

    /** Something that is not HH Clicker holds the port. The user has to be told. */
    data class PortHeldByOtherApplication(
        val port: Int,
        val handshake: SingleInstanceHandshake,
    ) : SingleInstanceOutcome

    /** The port could not be claimed for a reason unrelated to a second instance. */
    data class StartupFailed(val port: Int, val detail: String?) : SingleInstanceOutcome
}

/**
 * Pure interpretation of a bind conflict, separated from the sockets so every branch is unit tested.
 */
object SingleInstanceDecision {

    fun afterBindConflict(port: Int, handshake: SingleInstanceHandshake): SingleInstanceOutcome =
        if (handshake == SingleInstanceHandshake.LAUNCHER_ANSWERED) {
            SingleInstanceOutcome.ActivatedRunningLauncher
        } else {
            SingleInstanceOutcome.PortHeldByOtherApplication(port, handshake)
        }

    /**
     * The sentence to show before the window exists, or null when the outcome needs no message.
     *
     * Nothing technical reaches the user: no stack trace, no socket details — only what happened and
     * what they can do about it.
     */
    fun message(outcome: SingleInstanceOutcome): String? = when (outcome) {
        is SingleInstanceOutcome.Acquired, SingleInstanceOutcome.ActivatedRunningLauncher -> null

        is SingleInstanceOutcome.PortHeldByOtherApplication ->
            "Локальный порт ${outcome.port} занят другой программой.\n\n" +
                "HH Clicker использует его, чтобы не запускать вторую копию, и не может стартовать. " +
                "Закройте программу, которая занимает этот порт, и запустите HH Clicker снова."

        is SingleInstanceOutcome.StartupFailed ->
            "Не удалось занять локальный порт ${outcome.port} для контроля единственной копии HH Clicker.\n\n" +
                "Проверьте настройки сети и антивируса, затем запустите HH Clicker снова."
    }
}

/**
 * A second EXE invocation activates the existing launcher instead of creating competing state.
 *
 * The guard owns a loopback port for the lifetime of the process. It is deliberately not a lock file:
 * a port disappears with the process that held it, including one killed from Task Manager.
 */
class SingleInstanceGuard private constructor(
    private val server: ServerSocket,
) : Closeable {
    @Volatile
    var activationHandler: (() -> Unit)? = null

    private val listener = thread(name = "hh-clicker-single-instance", isDaemon = true) {
        while (!server.isClosed) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = SIGNAL_TIMEOUT_MS
                    val request = socket.getInputStream().bufferedReader().readLine()
                    if (request == ACTIVATE_REQUEST) {
                        socket.getOutputStream().bufferedWriter().use { writer ->
                            writer.write(ACTIVATE_RESPONSE)
                            writer.newLine()
                        }
                        activationHandler?.invoke()
                    }
                }
            } catch (_: Exception) {
                if (!server.isClosed) continue
            }
        }
    }

    override fun close() {
        activationHandler = null
        runCatching { server.close() }
        listener.interrupt()
    }

    companion object {
        const val DEFAULT_PORT = 47_374
        private const val SIGNAL_TIMEOUT_MS = 1_500
        private const val ACTIVATE_REQUEST = "HH_CLICKER_ACTIVATE_V1"
        private const val ACTIVATE_RESPONSE = "HH_CLICKER_OK_V1"

        fun acquire(port: Int = DEFAULT_PORT): SingleInstanceOutcome {
            val address = InetAddress.getLoopbackAddress()
            val server = ServerSocket()
            return try {
                server.bind(InetSocketAddress(address, port), 1)
                SingleInstanceOutcome.Acquired(SingleInstanceGuard(server))
            } catch (_: BindException) {
                runCatching { server.close() }
                SingleInstanceDecision.afterBindConflict(port, greetExistingOwner(address, port))
            } catch (failure: IOException) {
                runCatching { server.close() }
                SingleInstanceOutcome.StartupFailed(port, failure.message)
            }
        }

        /**
         * Asks whoever holds the port to identify itself.
         *
         * Only a complete, correct answer counts as "the other HH Clicker": a stranger that happens to
         * echo something, or accepts the connection and says nothing, is not allowed to make this
         * process disappear silently.
         */
        internal fun greetExistingOwner(address: InetAddress, port: Int): SingleInstanceHandshake {
            val socket = Socket()
            return try {
                socket.connect(InetSocketAddress(address, port), SIGNAL_TIMEOUT_MS)
                socket.soTimeout = SIGNAL_TIMEOUT_MS
                socket.getOutputStream().bufferedWriter().apply {
                    write(ACTIVATE_REQUEST)
                    newLine()
                    flush()
                }
                when (socket.getInputStream().bufferedReader().readLine()) {
                    ACTIVATE_RESPONSE -> SingleInstanceHandshake.LAUNCHER_ANSWERED
                    null -> SingleInstanceHandshake.NO_ANSWER
                    else -> SingleInstanceHandshake.WRONG_PROTOCOL
                }
            } catch (_: Exception) {
                SingleInstanceHandshake.CONNECTION_REFUSED
            } finally {
                runCatching { socket.close() }
            }
        }
    }
}
