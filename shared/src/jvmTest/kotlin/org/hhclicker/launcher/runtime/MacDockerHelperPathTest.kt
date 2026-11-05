package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import org.hhclicker.launcher.model.HostPlatform

class MacDockerHelperPathTest {
    @Test
    fun docker_from_a_gui_session_can_find_its_credential_helper() {
        val env = mutableMapOf("PATH" to "/usr/bin:/bin", "LANG" to "C")
        configureMacDockerEnvironment("/Users/test/.docker/bin/docker", env, HostPlatform.MACOS)
        assertEquals("/Applications/Docker.app/Contents/Resources/bin:/usr/bin:/bin", env["PATH"])
        assertEquals("C", env["LANG"])
        assertEquals("0", env["BUILDX_GIT_INFO"])
        configureMacDockerEnvironment("docker", env, HostPlatform.MACOS)
        assertEquals("/Applications/Docker.app/Contents/Resources/bin:/usr/bin:/bin", env["PATH"])
    }

    @Test
    fun other_commands_and_platforms_keep_their_original_path() {
        val env = mutableMapOf("PATH" to "/usr/bin:/bin")
        configureMacDockerEnvironment("/usr/bin/osascript", env, HostPlatform.MACOS)
        assertEquals("/usr/bin:/bin", env["PATH"])
        configureMacDockerEnvironment("docker", env, HostPlatform.LINUX)
        assertEquals("/usr/bin:/bin", env["PATH"])
        assertEquals(null, env["BUILDX_GIT_INFO"])
    }
}
