package org.hhclicker.launcher.setup

/**
 * Minimal comparable semantic version.
 *
 * Docker prints versions as `27.3.1`, `4.34.2`, `v2.29.7` and sometimes with a build suffix such as
 * `4.34.2 (167172)`. Only the leading numeric components are compared; anything after them is ignored,
 * because a build number never changes compatibility for the launcher.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

        /**
         * Extracts the first `major[.minor[.patch]]` group from arbitrary text.
         *
         * Returns null when no numeric version is present, so an unreadable version is never silently
         * treated as `0.0.0`.
         */
        fun parseOrNull(text: String?): SemanticVersion? {
            if (text.isNullOrBlank()) return null
            val match = pattern.find(text.trim().removePrefix("v").removePrefix("V")) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            return SemanticVersion(
                major = major,
                minor = match.groupValues[2].toIntOrNull() ?: 0,
                patch = match.groupValues[3].toIntOrNull() ?: 0,
            )
        }
    }
}
