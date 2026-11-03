package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthenticodeParserTest {

    private fun output(status: String?, subject: String?): CommandOutcome {
        val text = buildString {
            if (status != null) appendLine("${AuthenticodeParser.STATUS_MARKER}$status")
            if (subject != null) appendLine("${AuthenticodeParser.SUBJECT_MARKER}$subject")
        }
        return CommandOutcome(started = true, exitCode = 0, output = text)
    }

    private val dockerSubject =
        """CN="Docker Inc", O="Docker Inc", L="Palo Alto", S="California", C="US""""

    private val googleSubject =
        """CN="Google LLC", O="Google LLC", L="Mountain View", S="California", C="US""""

    @Test
    fun a_valid_docker_signature_is_trusted() {
        val result = AuthenticodeParser.parse(output("Valid", dockerSubject), ExpectedPublisher.DOCKER)
        assertEquals(SignatureVerdict.VALID, result.verdict)
        assertTrue(result.trusted)
    }

    @Test
    fun a_valid_google_signature_is_trusted() {
        val result = AuthenticodeParser.parse(output("Valid", googleSubject), ExpectedPublisher.GOOGLE)
        assertEquals(SignatureVerdict.VALID, result.verdict)
        assertTrue(result.trusted)
    }

    /** Generalising the check must not let one publisher's installer pass as another's. */
    @Test
    fun a_publisher_is_never_accepted_for_the_other_installer() {
        assertEquals(
            SignatureVerdict.WRONG_PUBLISHER,
            AuthenticodeParser.parse(output("Valid", googleSubject), ExpectedPublisher.DOCKER).verdict,
        )
        assertEquals(
            SignatureVerdict.WRONG_PUBLISHER,
            AuthenticodeParser.parse(output("Valid", dockerSubject), ExpectedPublisher.GOOGLE).verdict,
        )
    }

    @Test
    fun an_unquoted_subject_is_also_accepted() {
        val result = AuthenticodeParser.parse(
            output("Valid", "CN=Docker Inc, O=Docker Inc, C=US"),
            ExpectedPublisher.DOCKER,
        )
        assertEquals(SignatureVerdict.VALID, result.verdict)
    }

    @Test
    fun an_untrusted_or_tampered_file_is_rejected() {
        listOf("NotSigned", "HashMismatch", "UnknownError", "NotTrusted").forEach { status ->
            val result = AuthenticodeParser.parse(output(status, dockerSubject), ExpectedPublisher.DOCKER)
            assertEquals(SignatureVerdict.INVALID_STATUS, result.verdict, "status $status")
            assertFalse(result.trusted, "status $status")
        }
        listOf("NotSigned", "HashMismatch").forEach { status ->
            val result = AuthenticodeParser.parse(output(status, googleSubject), ExpectedPublisher.GOOGLE)
            assertEquals(SignatureVerdict.INVALID_STATUS, result.verdict, "status $status")
        }
    }

    @Test
    fun a_valid_signature_from_another_publisher_is_rejected() {
        val result = AuthenticodeParser.parse(
            output("Valid", """CN="Definitely Not Docker", O="Evil Corp", C="US""""),
            ExpectedPublisher.DOCKER,
        )
        assertEquals(SignatureVerdict.WRONG_PUBLISHER, result.verdict)
        assertFalse(result.trusted)
    }

    @Test
    fun a_lookalike_organisation_is_rejected() {
        assertEquals(
            SignatureVerdict.WRONG_PUBLISHER,
            AuthenticodeParser.parse(
                output("Valid", """CN="Docker", O="Dockerish Ltd", C="US""""),
                ExpectedPublisher.DOCKER,
            ).verdict,
        )
        assertEquals(
            SignatureVerdict.WRONG_PUBLISHER,
            AuthenticodeParser.parse(
                output("Valid", """CN="Google", O="Googles LLC", C="US""""),
                ExpectedPublisher.GOOGLE,
            ).verdict,
        )
    }

    /** The check is whole-value equality: neither a prefix nor a substring may pass. */
    @Test
    fun a_publisher_name_with_a_trusted_prefix_is_still_rejected() {
        assertEquals(
            SignatureVerdict.WRONG_PUBLISHER,
            AuthenticodeParser.parse(output("Valid", "O=Docker Inc Malware Ltd, C=US"), ExpectedPublisher.DOCKER).verdict,
        )
        assertEquals(
            SignatureVerdict.WRONG_PUBLISHER,
            AuthenticodeParser.parse(output("Valid", "O=Google LLC Fake, C=US"), ExpectedPublisher.GOOGLE).verdict,
        )
        assertEquals(
            SignatureVerdict.WRONG_PUBLISHER,
            AuthenticodeParser.parse(output("Valid", "O=Not Google LLC, C=US"), ExpectedPublisher.GOOGLE).verdict,
        )
    }

    @Test
    fun a_missing_subject_is_never_trusted() {
        assertEquals(
            SignatureVerdict.WRONG_PUBLISHER,
            AuthenticodeParser.parse(output("Valid", null), ExpectedPublisher.DOCKER).verdict,
        )
        assertFalse(AuthenticodeParser.matchesPublisher(null, ExpectedPublisher.DOCKER))
        assertFalse(AuthenticodeParser.matchesPublisher("", ExpectedPublisher.GOOGLE))
        assertFalse(AuthenticodeParser.matchesPublisher("no distinguished name here", ExpectedPublisher.GOOGLE))
    }

    @Test
    fun an_unreadable_check_is_not_a_verdict() {
        assertEquals(
            SignatureVerdict.UNREADABLE,
            AuthenticodeParser.parse(output(null, dockerSubject), ExpectedPublisher.DOCKER).verdict,
        )
        assertEquals(
            SignatureVerdict.UNREADABLE,
            AuthenticodeParser.parse(CommandOutcome.failedToStart(), ExpectedPublisher.DOCKER).verdict,
        )
        assertEquals(
            SignatureVerdict.UNREADABLE,
            AuthenticodeParser.parse(
                CommandOutcome(started = true, exitCode = 2, output = ""),
                ExpectedPublisher.GOOGLE,
            ).verdict,
        )
        assertEquals(
            SignatureVerdict.UNREADABLE,
            AuthenticodeParser.parse(
                CommandOutcome(started = true, exitCode = null, output = "", timedOut = true),
                ExpectedPublisher.GOOGLE,
            ).verdict,
        )
    }

    @Test
    fun commas_inside_quoted_components_do_not_split_the_subject() {
        assertTrue(
            AuthenticodeParser.matchesPublisher(
                """CN="Docker, Inc.", O="Docker Inc", L="Palo Alto"""",
                ExpectedPublisher.DOCKER,
            ),
        )
        assertTrue(
            AuthenticodeParser.matchesPublisher(
                """CN="Google, LLC", O="Google LLC", L="Mountain View"""",
                ExpectedPublisher.GOOGLE,
            ),
        )
    }
}
