package org.hhclicker.launcher.runtime

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.DockerInstallScope
import org.hhclicker.launcher.preflight.DockerProbe

/**
 * The installer contract is asserted literally: these flags are what makes the installation per user,
 * WSL 2 only and Linux containers only.
 */
class DockerDesktopCommandsTest {

    private val installer = Path.of("C:\\cache\\Docker Desktop Installer.exe")

    @Test
    fun the_download_url_is_the_official_https_x64_installer() {
        assertEquals(
            "https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe",
            DockerDesktopCommands.INSTALLER_URL,
        )
        assertTrue(DockerDesktopCommands.INSTALLER_URL.startsWith("https://"))
    }

    @Test
    fun an_accepted_installation_uses_per_user_wsl2_and_linux_containers() {
        val arguments = DockerDesktopCommands.installArguments(installer, licenseAccepted = true)

        assertEquals(installer.toAbsolutePath().toString(), arguments.first())
        assertEquals("install", arguments[1])
        assertTrue(arguments.contains("--quiet"), arguments.toString())
        assertTrue(arguments.contains("--user"), arguments.toString())
        assertTrue(arguments.contains("--backend=wsl-2"), arguments.toString())
        assertTrue(arguments.contains("--no-windows-containers"), arguments.toString())
        assertTrue(arguments.contains("--accept-license"), arguments.toString())
    }

    @Test
    fun the_licence_flag_appears_only_after_an_explicit_acceptance() {
        val arguments = DockerDesktopCommands.installArguments(installer, licenseAccepted = false)
        assertFalse(arguments.contains("--accept-license"), arguments.toString())
        // Everything else about the installation is unchanged.
        assertTrue(arguments.contains("--user"))
        assertTrue(arguments.contains("--backend=wsl-2"))
        assertTrue(arguments.contains("--no-windows-containers"))
    }

    @Test
    fun an_all_users_update_preserves_its_installation_scope() {
        val arguments = DockerDesktopCommands.installArguments(
            installer,
            licenseAccepted = true,
            scope = DockerInstallScope.ALL_USERS,
            freshInstall = false,
        )

        assertFalse(arguments.contains("--user"), arguments.toString())
        assertTrue(arguments.contains("--quiet"), arguments.toString())
        assertFalse(arguments.contains("--backend=wsl-2"), arguments.toString())
        assertFalse(arguments.contains("--no-windows-containers"), arguments.toString())
        assertTrue(arguments.contains("--accept-license"), arguments.toString())
    }

    @Test
    fun unsupported_backends_and_service_modes_are_never_requested() {
        val forbidden = listOf(
            "hyper-v",
            "hyperv",
            "windows",
            "vmm",
            "--always-run-service",
            "--allowed-org",
            "--admin-settings",
        )
        listOf(true, false).forEach { accepted ->
            val rendered = DockerDesktopCommands.installArguments(installer, accepted)
                .drop(1)
                .joinToString(" ")
                .lowercase()
            forbidden.forEach { flag ->
                if (flag == "windows") {
                    // Only the negative form may mention Windows containers.
                    assertFalse(rendered.contains("--windows-containers"), rendered)
                } else {
                    assertFalse(rendered.contains(flag), "$flag present in: $rendered")
                }
            }
        }
    }

    @Test
    fun the_start_paths_are_fixed() {
        // There is deliberately no PATH-based variant: a Docker Desktop installed during this launcher
        // run is not on the inherited PATH, so only the absolute CLI may be started.
        val cli = Path.of("C:\\Docker\\resources\\bin\\docker.exe")
        assertEquals(
            listOf(cli.toAbsolutePath().toString(), "desktop", "start", "--detach"),
            DockerDesktopCommands.desktopCliStartArguments(cli),
        )
        val executable = Path.of("C:\\Users\\test\\AppData\\Local\\Programs\\Docker\\Docker\\Docker Desktop.exe")
        assertEquals(
            listOf(executable.toAbsolutePath().toString()),
            DockerDesktopCommands.desktopExecutableArguments(executable),
        )
    }

    @Test
    fun diagnostics_can_use_the_absolute_cli_path_after_a_fresh_installation() {
        val cli = "C:\\Users\\test\\AppData\\Local\\Programs\\DockerDesktop\\resources\\bin\\docker.exe"

        assertEquals(listOf(cli, "--version"), DockerProbe.cliVersionCommand(cli))
        assertEquals(listOf(cli, "compose", "version"), DockerProbe.composeVersionCommand(cli))
        assertEquals(cli, DockerProbe.infoCommand(cli).first())
    }

    @Test
    fun every_operation_is_bounded_in_time_and_size() {
        assertTrue(DockerDesktopCommands.INSTALL_TIMEOUT_MS > 0)
        assertTrue(DockerDesktopCommands.DESKTOP_CLI_START_TIMEOUT_MS > 0)
        assertTrue(DockerDesktopCommands.ENGINE_READY_TIMEOUT_MS > 0)
        assertTrue(DockerDesktopCommands.ENGINE_POLL_INTERVAL_MS > 0)
        assertTrue(DockerDesktopCommands.MAX_INSTALLER_BYTES in 1..(4L * 1024 * 1024 * 1024))
    }
}
