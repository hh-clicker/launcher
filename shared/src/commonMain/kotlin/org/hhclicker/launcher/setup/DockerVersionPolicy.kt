package org.hhclicker.launcher.setup

/** Outcome of comparing an installed version against the launcher's supported range. */
enum class VersionCompatibility {
    /** Meets the contract; nothing to do. */
    COMPATIBLE,

    /** Works, but old. A warning only — never a blocker. */
    UPDATE_RECOMMENDED,

    /** Below the documented minimum contract. An update is offered. */
    BELOW_MINIMUM,

    /** No version could be read. Capabilities decide instead of a guessed number. */
    UNKNOWN,
}

/**
 * Central version policy for the Docker prerequisite.
 *
 * The launcher deliberately does not demand "the newest version". Its real contract is behavioural:
 * it invokes `docker compose up -d` against Compose files that use the top level `name:` key and
 * `depends_on: condition: service_healthy`, so Compose v2 is mandatory. Everything else is measured
 * (engine reachable, Linux containers, WSL 2 backend) rather than inferred from a version number.
 *
 * The two Docker Desktop thresholds below are therefore intentionally conservative and are documented in
 * ARCHITECTURE.md. A version between them is reported as [VersionCompatibility.UPDATE_RECOMMENDED] and
 * never blocks a working installation.
 */
object DockerVersionPolicy {

    /** Docker Desktop 4.5.1 fixed Windows installations to use WSL 2 by default again. */
    val wslDefaultDesktop = SemanticVersion(4, 5, 1)

    /**
     * Minimum supported Docker Desktop.
     *
     * Reason: the 4.x line is where Compose V2 (`docker compose`) ships with Docker Desktop on Windows.
     * Below it the launcher cannot rely on its own `docker compose` contract.
     */
    val minimumDesktop = SemanticVersion(4, 0, 0)

    /**
     * Recommended Docker Desktop.
     *
     * Reason: `docker desktop start` — the preferred, documented way for the launcher to start the
     * application — exists from 4.37. Older versions still work; the launcher falls back to starting
     * `Docker Desktop.exe` directly, so this is a recommendation and not a requirement.
     */
    val recommendedDesktop = SemanticVersion(4, 37, 0)

    /**
     * Minimum supported Compose.
     *
     * Reason: hard contract. The launcher only ever calls `docker compose`, which is Compose v2.
     */
    val minimumCompose = SemanticVersion(2, 0, 0)

    fun classifyDesktop(version: String?): VersionCompatibility {
        val parsed = SemanticVersion.parseOrNull(version) ?: return VersionCompatibility.UNKNOWN
        return when {
            parsed < minimumDesktop -> VersionCompatibility.BELOW_MINIMUM
            parsed < recommendedDesktop -> VersionCompatibility.UPDATE_RECOMMENDED
            else -> VersionCompatibility.COMPATIBLE
        }
    }

    fun classifyCompose(version: String?): VersionCompatibility {
        val parsed = SemanticVersion.parseOrNull(version) ?: return VersionCompatibility.UNKNOWN
        return if (parsed < minimumCompose) VersionCompatibility.BELOW_MINIMUM else VersionCompatibility.COMPATIBLE
    }

    /** True when the Desktop version alone justifies offering an update. */
    fun desktopUpdateRequired(version: String?): Boolean =
        classifyDesktop(version) == VersionCompatibility.BELOW_MINIMUM

    /** True when the Desktop version is supported but worth updating. Shown as a warning. */
    fun desktopUpdateRecommended(version: String?): Boolean =
        classifyDesktop(version) == VersionCompatibility.UPDATE_RECOMMENDED

    /**
     * True when the preferred `docker desktop start` CLI can be expected.
     *
     * An unreadable version answers false, so the launcher falls back to the executable instead of
     * assuming a capability it has not confirmed.
     */
    fun supportsDesktopCli(version: String?): Boolean {
        val parsed = SemanticVersion.parseOrNull(version) ?: return false
        return parsed >= recommendedDesktop
    }

    /** Whether an omitted backend setting has the documented WSL 2 default semantics. */
    fun wslIsDefault(version: String?): Boolean {
        val parsed = SemanticVersion.parseOrNull(version) ?: return false
        return parsed >= wslDefaultDesktop
    }
}
