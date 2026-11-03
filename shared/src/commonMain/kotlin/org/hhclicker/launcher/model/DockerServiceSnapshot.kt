package org.hhclicker.launcher.model

/**
 * What a single Docker observation is allowed to claim about the containers of this launcher instance.
 *
 * "Nothing is running" and "Docker did not answer" are different facts and must never share one
 * representation. An empty [Observed.runningServices] is positive evidence that the owned containers are
 * stopped; [Unknown] is the absence of evidence and obliges every consumer to keep whatever it last
 * observed instead of repainting the screen.
 */
sealed interface DockerServiceSnapshot {

    /**
     * Docker answered. [runningServices] holds the Compose service names owned by this instance that are
     * running right now, and it is complete: a service missing from the set is stopped.
     */
    data class Observed(val runningServices: Set<String>) : DockerServiceSnapshot

    /**
     * Docker could not be asked, or answered something that cannot be trusted.
     *
     * [diagnostics] is one short sentence that is safe to show: it carries the reason the query failed
     * and never a secret, a password or a file system path.
     */
    data class Unknown(val diagnostics: String) : DockerServiceSnapshot

    /** True while the service runs, false while it is stopped, and null while the answer is [Unknown]. */
    fun isRunning(service: String): Boolean? = when (this) {
        is Observed -> service in runningServices
        is Unknown -> null
    }

    /** True only for a trustworthy answer; used where a decision may be taken on the snapshot. */
    val trustworthy: Boolean
        get() = this is Observed
}
