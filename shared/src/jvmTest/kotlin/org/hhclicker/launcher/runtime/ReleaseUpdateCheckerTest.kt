package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import org.hhclicker.launcher.model.UpdateUrgency

class ReleaseUpdateCheckerTest {
    @Test
    fun compares_numeric_versions_including_nightly_builds() {
        val checker = ReleaseUpdateChecker({ """{"tag_name":"v2.1.0"}""" }, { """{"minimumSupportedVersion":"2.0.0"}""" })
        assertEquals(UpdateUrgency.NORMAL, checker.check("2.0.1 · nightly").urgency)
        assertEquals(UpdateUrgency.NONE, checker.check("2.1.0").urgency)
        assertEquals(UpdateUrgency.NONE, checker.check("2.10.0").urgency)
        assertEquals("2.1.0", checker.check("2.0.1").latestVersion)
    }

    @Test
    fun marks_versions_below_the_published_support_floor_as_critical() {
        val checker = ReleaseUpdateChecker({ """{"tag_name":"v2.2.0"}""" }, { """{"minimumSupportedVersion":"2.1.0"}""" })
        assertEquals(UpdateUrgency.CRITICAL, checker.check("2.0.0").urgency)
        assertEquals(UpdateUrgency.NORMAL, checker.check("2.1.0").urgency)
    }

    @Test
    fun invalid_or_unavailable_response_does_not_claim_an_update() {
        assertEquals(UpdateUrgency.NONE, ReleaseUpdateChecker({ "not json" }).check("2.0.0").urgency)
        assertEquals(UpdateUrgency.NONE, ReleaseUpdateChecker({ """{"tag_name":"v2.1.0-rc1"}""" }).check("2.0.0").urgency)
        assertEquals(UpdateUrgency.NONE, ReleaseUpdateChecker({ throw IllegalStateException("offline") }).check("2.0.0").urgency)
        assertEquals(UpdateUrgency.NONE, ReleaseUpdateChecker({ error("must not fetch") }).check("dev").urgency)
        assertEquals(UpdateUrgency.NORMAL, ReleaseUpdateChecker(
            { """{"tag_name":"v2.1.0"}""" }, { "bad policy" },
        ).check("2.0.0").urgency)
    }
}
