package org.hhclicker.launcher.model

enum class UpdateUrgency { NONE, NORMAL, CRITICAL }

data class ReleaseUpdateState(
    val latestVersion: String? = null,
    val urgency: UpdateUrgency = UpdateUrgency.NONE,
)
