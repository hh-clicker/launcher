package org.hhclicker.launcher.runtime

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.hhclicker.launcher.model.ReleaseUpdateState
import org.hhclicker.launcher.model.UpdateUrgency
import org.hhclicker.launcher.setup.JsonValue
import org.hhclicker.launcher.setup.MiniJson

/** Checks the latest published stable release; network failures never block launcher startup. */
class ReleaseUpdateChecker(
    private val fetchLatest: () -> String? = { fetch(LATEST_API_URL) },
    private val fetchPolicy: () -> String? = { fetch(POLICY_URL) },
) {
    fun check(displayVersion: String): ReleaseUpdateState {
        val current = parseVersion(displayVersion.substringBefore(' ')) ?: return ReleaseUpdateState()
        val document = runCatching { fetchLatest()?.let(MiniJson::parseOrNull) }.getOrNull()
        val root = (document as? JsonValue.JsonObject)?.entries ?: return ReleaseUpdateState()
        val tag = (root["tag_name"] as? JsonValue.JsonString)?.value ?: return ReleaseUpdateState()
        val latest = parseVersion(tag.removePrefix("v")) ?: return ReleaseUpdateState()
        if (compareVersions(latest, current) <= 0) return ReleaseUpdateState()
        val policy = runCatching { fetchPolicy()?.let(MiniJson::parseOrNull) }.getOrNull()
        val requiredVersion = ((policy as? JsonValue.JsonObject)?.entries?.get("minimumSupportedVersion")
            as? JsonValue.JsonString)?.value?.let(::parseVersion)
        val urgency = if (requiredVersion != null && compareVersions(current, requiredVersion) < 0 &&
            compareVersions(requiredVersion, latest) <= 0) UpdateUrgency.CRITICAL else UpdateUrgency.NORMAL
        return ReleaseUpdateState(tag.removePrefix("v"), urgency)
    }

    private fun parseVersion(value: String): List<Int>? {
        if (!value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) return null
        return value.split('.').map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersions(left: List<Int>, right: List<Int>): Int =
        left.zip(right).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.compareTo(b) } ?: 0

    companion object {
        private const val LATEST_API_URL = "https://api.github.com/repos/hh-clicker/.github/releases/latest"
        private const val POLICY_URL = "https://raw.githubusercontent.com/hh-clicker/.github/master/update-policy.json"

        private fun fetch(url: String): String? {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "HH-Clicker-Launcher")
                .GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return response.body().takeIf { response.statusCode() == 200 }
        }
    }
}
