package org.hhclicker.launcher.preflight

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class MacDockerCliLocatorTest {
    @Test
    fun finds_per_user_cli_without_relying_on_inherited_path() {
        val expected = Path.of("/Users/tester/.docker/bin/docker")
        val locator = MacDockerCliLocator(userHome = { "/Users/tester" }, executable = { it == expected })

        assertEquals(expected, locator.locate())
    }
}
