package org.hhclicker.launcher.preflight

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hhclicker.launcher.model.HostPlatform
import org.hhclicker.launcher.runtime.NodePortableRuntime

class NodeRuntimeLocatorTest {
    @Test
    fun linux_portable_node_is_found_under_xdg_data() {
        val xdg = createTempDirectory("hh-node-linux")
        val node = xdg.resolve("hh-clicker/data/runtime/node-${NodePortableRuntime.VERSION}/node")
        Files.createDirectories(node.parent)
        Files.writeString(node, "node")

        assertEquals(
            node,
            NodeRuntimeLocator.localPortableExecutable(
                environment = { if (it == "XDG_DATA_HOME") xdg.toString() else null },
                platform = HostPlatform.LINUX,
            ),
        )
    }
}
