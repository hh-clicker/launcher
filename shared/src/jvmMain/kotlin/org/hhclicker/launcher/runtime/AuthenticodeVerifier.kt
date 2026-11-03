package org.hhclicker.launcher.runtime

import java.nio.file.Path
import org.hhclicker.launcher.setup.AuthenticodeParser
import org.hhclicker.launcher.setup.AuthenticodeResult
import org.hhclicker.launcher.setup.CommandOutcome
import org.hhclicker.launcher.setup.ExpectedPublisher
import org.hhclicker.launcher.setup.SignatureVerdict

/**
 * Checks the Authenticode signature of a downloaded installer.
 *
 * Declared as its own interface so that every test — including the rejection paths — runs without
 * executing PowerShell and without a real installer on disk.
 *
 * The expected publisher is an explicit argument of every call. There is no implementation that
 * accepts "any valid Authenticode signature": each installer names the one organisation it must carry.
 */
fun interface AuthenticodeVerifier {
    suspend fun verify(file: Path, expected: ExpectedPublisher): AuthenticodeResult
}

/**
 * Windows implementation built on `Get-AuthenticodeSignature`.
 *
 * The script text is a compile time constant: the file path is handed to the child process through a
 * dedicated environment variable and is never concatenated into PowerShell code, so no path — however
 * unusual the characters in a Windows profile name — can change what the script does. The variable is
 * set on the child process only and is never written to the technical report.
 */
class WindowsAuthenticodeVerifier(
    private val commandRunner: CommandRunner = CommandRunner(),
    private val workingDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
    private val timeoutMs: Long = VERIFY_TIMEOUT_MS,
) : AuthenticodeVerifier {

    override suspend fun verify(file: Path, expected: ExpectedPublisher): AuthenticodeResult {
        val result = commandRunner.execute(
            arguments = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT),
            workingDirectory = workingDirectory,
            timeoutMs = timeoutMs,
            environment = mapOf(TARGET_VARIABLE to file.toAbsolutePath().toString()),
        )
        if (result.exitCode == null) {
            return AuthenticodeResult(SignatureVerdict.UNREADABLE)
        }
        return AuthenticodeParser.parse(
            CommandOutcome(
                started = true,
                exitCode = result.exitCode,
                output = result.output,
                timedOut = result.timedOut,
            ),
            expected = expected,
        )
    }

    companion object {
        const val VERIFY_TIMEOUT_MS = 120_000L

        /** Name of the variable that carries the path. Its value is never logged. */
        const val TARGET_VARIABLE = "HHCLICKER_SIGNATURE_TARGET"

        /**
         * Static verification script. Nothing is interpolated at runtime: the only variable part is read
         * from the process environment inside PowerShell itself.
         */
        val SCRIPT: String = buildString {
            append("${'$'}ErrorActionPreference = 'Stop'; ")
            append("${'$'}target = ${'$'}env:$TARGET_VARIABLE; ")
            append("if (-not ${'$'}target) { exit 2 }; ")
            append("${'$'}signature = Get-AuthenticodeSignature -LiteralPath ${'$'}target; ")
            append("Write-Output ('${AuthenticodeParser.STATUS_MARKER}' + ${'$'}signature.Status); ")
            append("if (${'$'}signature.SignerCertificate) ")
            append("{ Write-Output ('${AuthenticodeParser.SUBJECT_MARKER}' + ${'$'}signature.SignerCertificate.Subject) }; ")
            append("exit 0")
        }
    }
}
