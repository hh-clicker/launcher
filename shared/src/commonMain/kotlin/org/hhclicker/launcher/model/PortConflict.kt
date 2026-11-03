package org.hhclicker.launcher.model

/** A configured host port occupied by something outside the current launcher instance. */
data class PortConflict(
    val component: ComponentId,
    val label: String,
    val port: Int,
    val processId: Long? = null,
    val processName: String? = null,
    val dockerContainerName: String? = null,
    val hhClickerProfile: String? = null,
)
