package org.hhclicker.launcher.model

data class DesktopIntegrationState(
    val installed: Boolean,
    val busy: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
)
