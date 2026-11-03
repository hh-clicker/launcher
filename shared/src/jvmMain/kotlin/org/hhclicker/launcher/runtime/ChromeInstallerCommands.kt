package org.hhclicker.launcher.runtime

import java.nio.file.Path

/**
 * The closed set of Google Chrome operations the launcher may perform, and the only download URL it
 * will ever use.
 *
 * Every command and script fragment is a compile time constant. The only runtime values are two
 * launcher-owned absolute paths — msiexec.exe under the Windows directory and the cached MSI — and
 * both reach the static elevation wrapper through child-process-only environment variables. Neither
 * path is interpolated into PowerShell source or written to the technical report.
 */
object ChromeInstallerCommands {

    /**
     * Official Google Chrome for Business / Enterprise standalone MSI for Windows x64, stable channel.
     * HTTPS only, Google-hosted, no mirror, no package manager. Redirects to another Google download
     * host are followed by the downloader within a bounded hop count and stay on HTTPS.
     */
    const val INSTALLER_URL = "https://dl.google.com/dl/chrome/install/googlechromestandaloneenterprise64.msi"

    const val INSTALLER_FILE_NAME = "GoogleChromeStandaloneEnterprise64.msi"

    /** Directory name of the launcher-owned cache under LOCALAPPDATA. */
    const val CACHE_DIRECTORY = "HH Clicker/data/launcher-cache/chrome"

    /** The published MSI is well under 300 MB; anything beyond this ceiling is not it. */
    const val MAX_INSTALLER_BYTES = 400L * 1024 * 1024

    /** msiexec is bounded like every other launcher operation and can never wait forever. */
    const val INSTALL_TIMEOUT_MS = 900_000L

    // --- documented Windows Installer exit codes the launcher distinguishes -----------------------

    const val EXIT_SUCCESS = 0

    /** ERROR_SUCCESS_REBOOT_REQUIRED: the product is installed; Windows wants a restart eventually. */
    const val EXIT_SUCCESS_REBOOT_REQUIRED = 3010

    /** ERROR_INSTALL_USEREXIT: the user cancelled, including dismissing the UAC prompt. */
    const val EXIT_USER_CANCELLED = 1602

    /** ERROR_INSTALL_ALREADY_RUNNING: another Windows Installer transaction holds the mutex. */
    const val EXIT_ANOTHER_INSTALLATION_RUNNING = 1618

    /** Child-process-only variables: their values never become part of PowerShell source or logs. */
    const val MSIEXEC_VARIABLE = "HHCLICKER_CHROME_MSIEXEC"
    const val INSTALLER_VARIABLE = "HHCLICKER_CHROME_INSTALLER"

    const val ELEVATION_DECLINED_MARKER = "HHCLICKER_CHROME_ELEVATION_DECLINED"
    const val ELEVATION_FAILED_MARKER = "HHCLICKER_CHROME_ELEVATION_FAILED"
    const val EXIT_CODE_MARKER = "HHCLICKER_CHROME_EXIT="

    /**
     * Static elevation wrapper. The Enterprise MSI is a per-machine package, so a quiet direct
     * msiexec call from an ordinary launcher process can end with 1603 without ever showing UAC.
     * Start-Process with RunAs deliberately asks Windows for elevation and still keeps the MSI UI
     * quiet. The two paths arrive through child-process environment variables; neither is interpolated
     * into executable script text.
     */
    val ELEVATION_SCRIPT: String = buildString {
        append("${'$'}ErrorActionPreference = 'Stop'; ")
        append("${'$'}native = 0; ")
        append("${'$'}msiexec = ${'$'}env:$MSIEXEC_VARIABLE; ")
        append("${'$'}installer = ${'$'}env:$INSTALLER_VARIABLE; ")
        append("if (-not ${'$'}msiexec -or -not ${'$'}installer) { Write-Output '$ELEVATION_FAILED_MARKER'; exit 0 }; ")
        // Start-Process joins ArgumentList items into one command line. Windows paths cannot contain a
        // double quote, so surrounding the MSI path here preserves spaces without accepting code.
        append("${'$'}quotedInstaller = [char]34 + ${'$'}installer + [char]34; ")
        append("${'$'}arguments = @('/i', ${'$'}quotedInstaller, '/qn', '/norestart'); ")
        append("try { ${'$'}process = Start-Process -FilePath ${'$'}msiexec -ArgumentList ${'$'}arguments -Verb RunAs -Wait -PassThru } ")
        append("catch { ")
        append("${'$'}failure = ${'$'}_.Exception; ")
        append("if (${'$'}failure -is [System.ComponentModel.Win32Exception]) { ${'$'}native = ${'$'}failure.NativeErrorCode } ")
        append("elseif (${'$'}failure.InnerException -is [System.ComponentModel.Win32Exception]) { ${'$'}native = ${'$'}failure.InnerException.NativeErrorCode }; ")
        append("if (${'$'}native -eq 1223) { Write-Output '$ELEVATION_DECLINED_MARKER' } ")
        append("else { Write-Output '$ELEVATION_FAILED_MARKER' }; exit 0 }; ")
        append("Write-Output ('$EXIT_CODE_MARKER' + ${'$'}process.ExitCode); exit 0")
    }

    /**
     * Resolves msiexec.exe from the Windows directory instead of relying on the inherited PATH, so the
     * executable cannot be shadowed by something earlier on it.
     */
    fun msiexecPath(environment: (String) -> String? = System::getenv): Path? {
        val windowsDirectory = environment("SYSTEMROOT")?.takeIf(String::isNotBlank)
            ?: environment("WINDIR")?.takeIf(String::isNotBlank)
        return windowsDirectory?.let { runCatching { Path.of(it, "System32", "msiexec.exe") }.getOrNull() }
    }

    /** Fixed outer command; paths are supplied only through [elevatedEnvironment]. */
    fun elevatedArguments(): List<String> = listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        ELEVATION_SCRIPT,
    )

    fun elevatedEnvironment(msiexec: Path, installer: Path): Map<String, String> = mapOf(
        MSIEXEC_VARIABLE to msiexec.toAbsolutePath().toString(),
        INSTALLER_VARIABLE to installer.toAbsolutePath().toString(),
    )

    /** Exit code printed by the elevated child, or null when the wrapper did not produce one. */
    fun reportedExitCode(output: String): Int? {
        val markerIndex = output.indexOf(EXIT_CODE_MARKER)
        if (markerIndex < 0) return null
        return output.substring(markerIndex + EXIT_CODE_MARKER.length)
            .takeWhile { it == '-' || it.isDigit() }
            .toIntOrNull()
    }
}
