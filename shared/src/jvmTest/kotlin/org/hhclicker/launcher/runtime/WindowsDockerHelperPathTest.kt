package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import org.hhclicker.launcher.model.HostPlatform

class WindowsDockerHelperPathTest {
    private val directory = "C:\\Program Files\\Docker\\Docker\\resources\\bin"
    private val docker = "$directory\\docker.exe"

    @Test
    fun freshly_installed_docker_can_find_its_sibling_helper_without_restarting_launcher() {
        val env = mutableMapOf("Path" to "C:\\Windows\\System32", "OTHER" to "unchanged")
        configureWindowsDockerEnvironment(docker, env, HostPlatform.WINDOWS)
        assertEquals(mapOf("Path" to "$directory;C:\\Windows\\System32", "OTHER" to "unchanged"), env)
    }

    @Test
    fun existing_docker_directory_is_moved_first_without_duplicates() {
        val env = mutableMapOf("PATH" to "C:\\Windows;\"c:/program files/docker/docker/resources/bin/\"")
        repeat(2) { configureWindowsDockerEnvironment(docker, env, HostPlatform.WINDOWS) }
        assertEquals("$directory;C:\\Windows", env["PATH"])
    }

    @Test
    fun per_user_installation_and_missing_path_are_supported() {
        val env = mutableMapOf<String, String>()
        configureWindowsDockerEnvironment("C:/Users/test/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe", env, HostPlatform.WINDOWS)
        assertEquals("C:/Users/test/AppData/Local/Programs/DockerDesktop/resources/bin", env["PATH"])
    }

    @Test
    fun unrelated_commands_relative_paths_and_other_platforms_are_unchanged() {
        for ((command, platform) in listOf(
            docker to HostPlatform.LINUX,
            docker to HostPlatform.MACOS,
            "C:\\Tools\\node.exe" to HostPlatform.WINDOWS,
            "docker.exe" to HostPlatform.WINDOWS,
            "relative/docker.exe" to HostPlatform.WINDOWS,
        )) {
            val env = mutableMapOf("PATH" to "original")
            configureWindowsDockerEnvironment(command, env, platform)
            assertEquals(mapOf("PATH" to "original"), env)
        }
    }
}
