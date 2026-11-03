package org.hhclicker.launcher.model

/**
 * Explicit lifecycle of a machine preparation operation.
 *
 * It is deliberately separate from [LauncherStatus]: preparing Windows components and running the local
 * services are different processes with different terminal states. [RESTART_REQUIRED] is a first class
 * result and must never be reported as [SUCCEEDED].
 */
enum class SetupStatus {
    IDLE,
    PREPARING,
    RESTART_REQUIRED,
    SUCCEEDED,
    FAILED,
}

/** Which prerequisite the current [SetupState] describes. */
enum class SetupTarget {
    WSL,
    DOCKER,
    NODE,
    CHROME,
}

/** Stage shown to the user while [SetupStatus.PREPARING] is active. */
enum class SetupStep {
    NONE,
    INSTALLING_WSL,
    UPDATING_WSL,
    SETTING_DEFAULT_VERSION,
    DOWNLOADING_DOCKER,
    VERIFYING_DOCKER_INSTALLER,
    INSTALLING_DOCKER,
    STARTING_DOCKER,
    WAITING_FOR_DOCKER_ENGINE,
    VERIFYING_DOCKER_BACKEND,
    DOWNLOADING_NODE_CHECKSUMS,
    DOWNLOADING_NODE,
    VERIFYING_NODE_ARCHIVE,
    INSTALLING_NODE_RUNTIME,
    DOWNLOADING_CHROME,
    VERIFYING_CHROME_INSTALLER,
    INSTALLING_CHROME,
    RECHECKING_ENVIRONMENT,
}

/**
 * Preparation state carried by [LauncherState].
 *
 * [message] is a short Russian sentence for the screen. The full technical result stays inside the
 * runtime component that executed the operation. [downloadedBytes] and [totalBytes] are populated only
 * while a download is running and only when the server reported a content length.
 */
data class SetupState(
    val status: SetupStatus = SetupStatus.IDLE,
    val step: SetupStep = SetupStep.NONE,
    val message: String? = null,
    val target: SetupTarget = SetupTarget.WSL,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
) {
    val inProgress: Boolean
        get() = status == SetupStatus.PREPARING

    /** Download progress in percent, or null when the total size is unknown. */
    val progressPercent: Int?
        get() {
            val total = totalBytes ?: return null
            val done = downloadedBytes ?: return null
            if (total <= 0L) return null
            return ((done.coerceAtMost(total) * 100L) / total).toInt()
        }
}
