package org.hhclicker.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockerVersionPolicyTest {

    @Test
    fun wsl2_is_only_inferred_from_versions_where_it_is_the_fixed_windows_default() {
        assertFalse(DockerVersionPolicy.wslIsDefault(null))
        assertFalse(DockerVersionPolicy.wslIsDefault("4.5.0"))
        assertTrue(DockerVersionPolicy.wslIsDefault("4.5.1"))
        assertTrue(DockerVersionPolicy.wslIsDefault("4.89.0"))
    }

    @Test
    fun semantic_versions_parse_from_real_docker_output() {
        assertEquals(SemanticVersion(27, 3, 1), SemanticVersion.parseOrNull("27.3.1"))
        assertEquals(SemanticVersion(2, 29, 7), SemanticVersion.parseOrNull("v2.29.7"))
        assertEquals(SemanticVersion(4, 34, 2), SemanticVersion.parseOrNull("4.34.2 (167172)"))
        assertEquals(SemanticVersion(4, 0, 0), SemanticVersion.parseOrNull("4"))
        assertEquals(SemanticVersion(4, 37, 0), SemanticVersion.parseOrNull("4.37"))
    }

    @Test
    fun an_unreadable_version_is_null_and_never_zero() {
        assertNull(SemanticVersion.parseOrNull(null))
        assertNull(SemanticVersion.parseOrNull(""))
        assertNull(SemanticVersion.parseOrNull("unknown"))
    }

    @Test
    fun semantic_versions_compare_by_component() {
        assertTrue(SemanticVersion(4, 37, 0) > SemanticVersion(4, 9, 9))
        assertTrue(SemanticVersion(4, 0, 0) > SemanticVersion(3, 99, 99))
        assertTrue(SemanticVersion(2, 0, 0) <= SemanticVersion(2, 0, 0))
    }

    @Test
    fun a_version_below_the_documented_minimum_is_offered_an_update() {
        assertEquals(VersionCompatibility.BELOW_MINIMUM, DockerVersionPolicy.classifyDesktop("3.6.0"))
        assertTrue(DockerVersionPolicy.desktopUpdateRequired("3.6.0"))
    }

    @Test
    fun an_old_but_supported_version_is_a_recommendation_not_a_blocker() {
        assertEquals(VersionCompatibility.UPDATE_RECOMMENDED, DockerVersionPolicy.classifyDesktop("4.20.0"))
        assertFalse(DockerVersionPolicy.desktopUpdateRequired("4.20.0"))
        assertTrue(DockerVersionPolicy.desktopUpdateRecommended("4.20.0"))
    }

    @Test
    fun a_current_version_is_compatible() {
        assertEquals(VersionCompatibility.COMPATIBLE, DockerVersionPolicy.classifyDesktop("4.45.0"))
        assertFalse(DockerVersionPolicy.desktopUpdateRequired("4.45.0"))
        assertFalse(DockerVersionPolicy.desktopUpdateRecommended("4.45.0"))
    }

    @Test
    fun an_unknown_version_is_never_invented_into_a_verdict() {
        assertEquals(VersionCompatibility.UNKNOWN, DockerVersionPolicy.classifyDesktop(null))
        assertFalse(DockerVersionPolicy.desktopUpdateRequired(null))
        assertFalse(DockerVersionPolicy.desktopUpdateRecommended(null))
    }

    @Test
    fun compose_v2_is_the_hard_contract() {
        assertEquals(VersionCompatibility.BELOW_MINIMUM, DockerVersionPolicy.classifyCompose("1.29.2"))
        assertEquals(VersionCompatibility.COMPATIBLE, DockerVersionPolicy.classifyCompose("2.0.0"))
        assertEquals(VersionCompatibility.COMPATIBLE, DockerVersionPolicy.classifyCompose("2.29.7"))
        assertEquals(VersionCompatibility.UNKNOWN, DockerVersionPolicy.classifyCompose(null))
    }

    @Test
    fun the_desktop_cli_start_path_is_only_assumed_when_the_version_proves_it() {
        assertTrue(DockerVersionPolicy.supportsDesktopCli("4.37.0"))
        assertTrue(DockerVersionPolicy.supportsDesktopCli("4.45.1"))
        assertFalse(DockerVersionPolicy.supportsDesktopCli("4.30.0"))
        assertFalse(DockerVersionPolicy.supportsDesktopCli(null))
    }
}
