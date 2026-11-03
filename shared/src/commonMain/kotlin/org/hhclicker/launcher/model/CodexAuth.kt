package org.hhclicker.launcher.model

enum class CodexAuthStatus {
    UNAVAILABLE,
    CHECKING,
    SIGNED_OUT,
    AUTHORIZING,
    SIGNED_IN,
    FAILED,
}

/** Public authentication presentation only; credentials always stay inside the Docker volume. */
data class CodexAuthState(
    val status: CodexAuthStatus = CodexAuthStatus.UNAVAILABLE,
    val method: String? = null,
    val verificationUrl: String? = null,
    val userCode: String? = null,
    val message: String? = null,
    val limitsSummary: String? = null,
    val limitsResetSummary: String? = null,
    val checkedAtLabel: String? = null,
) {
    val busy: Boolean
        get() = status == CodexAuthStatus.CHECKING || status == CodexAuthStatus.AUTHORIZING
}

/** Values reported by the running Generation Agent, not by the saved Compose configuration. */
data class CodexRuntimeInfo(
    val model: String,
    val reasoningEffort: String?,
    val cliVersion: String?,
)
