package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hhclicker.launcher.model.BundleKind
import org.hhclicker.launcher.model.NodeRuntimeSource
import org.hhclicker.launcher.model.NodeRuntimeState

class NodeVersionPolicyTest {

    @Test
    fun the_engine_range_of_the_browser_agent_is_honoured() {
        // "^22.18.0 || >=24.12.0"
        assertTrue(NodeVersionPolicy.isCompatible("22.18.0"))
        assertTrue(NodeVersionPolicy.isCompatible("22.20.5"))
        assertTrue(NodeVersionPolicy.isCompatible("24.12.0"))
        assertTrue(NodeVersionPolicy.isCompatible("24.20.0"))
        assertTrue(NodeVersionPolicy.isCompatible("25.0.0"))

        assertFalse(NodeVersionPolicy.isCompatible("22.17.9"))
        assertFalse(NodeVersionPolicy.isCompatible("24.11.1"))
        // 23.x satisfies neither half of the range.
        assertFalse(NodeVersionPolicy.isCompatible("23.9.0"))
        assertFalse(NodeVersionPolicy.isCompatible("20.19.0"))
        assertFalse(NodeVersionPolicy.isCompatible(null))
        assertFalse(NodeVersionPolicy.isCompatible("not a version"))
    }

    @Test
    fun the_version_printed_by_node_is_parsed_without_its_prefix() {
        assertEquals("24.20.0", NodeVersionPolicy.parseVersion("v24.20.0"))
        assertEquals("24.20.0", NodeVersionPolicy.parseVersion("  v24.20.0\n"))
        assertEquals("22.18.1", NodeVersionPolicy.parseVersion("22.18.1"))
        assertNull(NodeVersionPolicy.parseVersion(""))
        assertNull(NodeVersionPolicy.parseVersion("v"))
        assertNull(NodeVersionPolicy.parseVersion("node: command not found"))
    }
}

class NodeRuntimePolicyTest {

    @Test
    fun a_compatible_system_runtime_wins_over_fallbacks_in_both_shapes() {
        listOf(BundleKind.PORTABLE, BundleKind.DEVELOPER).forEach { bundle ->
            val diagnostics = NodeRuntimePolicy.evaluate(
                bundle = bundle,
                pathVersion = "22.20.0",
                localPortablePresent = true,
                localPortableVersion = "24.20.0",
            )
            assertEquals(NodeRuntimeSource.SYSTEM_PATH, diagnostics.source, bundle.toString())
            assertEquals(NodeRuntimeState.COMPATIBLE, diagnostics.state, bundle.toString())
            assertEquals("22.20.0", diagnostics.version)
            assertTrue(diagnostics.ready)
        }
    }

    @Test
    fun the_launcher_managed_runtime_is_announced_as_local() {
        val diagnostics = NodeRuntimePolicy.evaluate(
            bundle = BundleKind.PORTABLE,
            pathVersion = null,
            localPortablePresent = true,
            localPortableVersion = "24.20.0",
        )
        assertEquals("Локальный Node.js 24.20.0, управляется лаунчером", NodeRuntimePolicy.message(diagnostics))
    }

    @Test
    fun a_portable_bundle_can_use_a_compatible_system_runtime() {
        val diagnostics = NodeRuntimePolicy.evaluate(
            bundle = BundleKind.PORTABLE,
            pathVersion = "24.20.0",
        )
        assertEquals(NodeRuntimeState.COMPATIBLE, diagnostics.state)
        assertEquals(NodeRuntimeSource.SYSTEM_PATH, diagnostics.source)
        assertTrue(diagnostics.ready)
    }

    @Test
    fun a_compatible_system_runtime_wins_over_an_old_managed_runtime() {
        val diagnostics = NodeRuntimePolicy.evaluate(
            bundle = BundleKind.PORTABLE,
            pathVersion = "24.20.0",
            localPortablePresent = true,
            localPortableVersion = "20.11.0",
        )
        assertEquals(NodeRuntimeState.COMPATIBLE, diagnostics.state)
        assertEquals(NodeRuntimeSource.SYSTEM_PATH, diagnostics.source)
    }

    @Test
    fun a_developer_checkout_keeps_the_path_fallback() {
        val diagnostics = NodeRuntimePolicy.evaluate(
            bundle = BundleKind.DEVELOPER,
            pathVersion = "24.20.0",
        )
        assertEquals(NodeRuntimeSource.SYSTEM_PATH, diagnostics.source)
        assertEquals(NodeRuntimeState.COMPATIBLE, diagnostics.state)
        assertTrue(diagnostics.ready)
    }

    @Test
    fun a_developer_checkout_reports_an_old_or_absent_path_node() {
        val old = NodeRuntimePolicy.evaluate(BundleKind.DEVELOPER, "20.11.0")
        assertEquals(NodeRuntimeState.INCOMPATIBLE, old.state)
        assertFalse(old.ready)

        val absent = NodeRuntimePolicy.evaluate(BundleKind.DEVELOPER, null)
        assertEquals(NodeRuntimeState.MISSING, absent.state)
        assertEquals(NodeRuntimeSource.NONE, absent.source)
        assertFalse(absent.ready)
    }

    @Test
    fun no_message_ever_carries_a_path() {
        listOf(
            NodeRuntimePolicy.evaluate(BundleKind.PORTABLE, null, true, "24.20.0"),
            NodeRuntimePolicy.evaluate(BundleKind.PORTABLE, null),
            NodeRuntimePolicy.evaluate(BundleKind.DEVELOPER, "24.20.0"),
            NodeRuntimePolicy.evaluate(BundleKind.DEVELOPER, "20.11.0"),
            NodeRuntimePolicy.evaluate(BundleKind.DEVELOPER, null),
        ).forEach { diagnostics ->
            val message = NodeRuntimePolicy.message(diagnostics)
            assertFalse(message.contains("\\"), message)
            assertFalse(message.contains("node.exe"), message)
        }
    }
}
