package org.hhclicker.launcher.setup

/**
 * The closed set of publishers the launcher is willing to trust, one per installer it may run.
 *
 * The value is the organisation (`O=`) exactly as it must appear on the code signing certificate,
 * uppercased for comparison. It is a compile time constant: nothing derived from a download, a
 * response header or a file name can ever add a publisher to this list.
 */
enum class ExpectedPublisher(val organisation: String, val displayName: String) {
    /** Docker Desktop installer. */
    DOCKER("DOCKER INC", "Docker Inc"),

    /** Google Chrome Enterprise MSI. */
    GOOGLE("GOOGLE LLC", "Google LLC"),
}

/** Verdict of the Authenticode check performed before an installer is allowed to run. */
enum class SignatureVerdict {
    /** Signature is valid and the certificate belongs to the expected publisher. */
    VALID,

    /** Windows reported a status other than Valid. */
    INVALID_STATUS,

    /** Signature is valid but the publisher is somebody else. */
    WRONG_PUBLISHER,

    /** The check itself could not be performed or produced no answer. */
    UNREADABLE,
}

data class AuthenticodeResult(
    val verdict: SignatureVerdict,
    val status: String? = null,
    val subject: String? = null,
) {
    val trusted: Boolean
        get() = verdict == SignatureVerdict.VALID
}

/**
 * Pure interpretation of the Authenticode markers printed by the verification helper.
 *
 * Keeping this separate from the process call means the whole trust decision — including the rejection
 * paths — is unit tested without running PowerShell or touching a real installer. The expected
 * publisher is always an explicit argument: there is no "any valid signature is fine" path.
 */
object AuthenticodeParser {

    const val STATUS_MARKER = "HHCLICKER_SIG_STATUS="
    const val SUBJECT_MARKER = "HHCLICKER_SIG_SUBJECT="

    /** Windows reports a fully trusted signature with this status name. */
    const val VALID_STATUS = "Valid"

    fun parse(outcome: CommandOutcome, expected: ExpectedPublisher): AuthenticodeResult {
        if (!outcome.started || outcome.timedOut || outcome.exitCode != 0) {
            return AuthenticodeResult(SignatureVerdict.UNREADABLE)
        }
        val status = valueAfter(outcome.output, STATUS_MARKER)
        val subject = valueAfter(outcome.output, SUBJECT_MARKER)
        return when {
            status == null -> AuthenticodeResult(SignatureVerdict.UNREADABLE, status, subject)
            !status.equals(VALID_STATUS, ignoreCase = true) ->
                AuthenticodeResult(SignatureVerdict.INVALID_STATUS, status, subject)
            !matchesPublisher(subject, expected) ->
                AuthenticodeResult(SignatureVerdict.WRONG_PUBLISHER, status, subject)
            else -> AuthenticodeResult(SignatureVerdict.VALID, status, subject)
        }
    }

    /**
     * Checks the certificate subject against one expected publisher.
     *
     * The organisation (`O=`) is authoritative; the common name is accepted as a fallback because some
     * certificate chains only carry it. Values are compared after stripping the quotes PowerShell adds
     * around components that contain commas, and the comparison is a whole-value equality: a name that
     * merely starts with or contains the expected organisation is rejected.
     */
    fun matchesPublisher(subject: String?, expected: ExpectedPublisher): Boolean {
        if (subject.isNullOrBlank()) return false
        val components = splitDistinguishedName(subject)
        val organisation = components["O"] ?: components["CN"] ?: return false
        return organisation.uppercase().trimEnd('.') == expected.organisation
    }

    private fun splitDistinguishedName(subject: String): Map<String, String> {
        val components = LinkedHashMap<String, String>()
        val current = StringBuilder()
        var quoted = false
        val parts = ArrayList<String>()
        subject.forEach { character ->
            when {
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        parts += current.toString()
        parts.forEach { part ->
            val separator = part.indexOf('=')
            if (separator > 0) {
                val key = part.substring(0, separator).trim().uppercase()
                val value = part.substring(separator + 1).trim()
                if (key.isNotEmpty() && !components.containsKey(key)) components[key] = value
            }
        }
        return components
    }

    private fun valueAfter(output: String, marker: String): String? = output.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith(marker) }
        ?.removePrefix(marker)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}
