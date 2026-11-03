package org.hhclicker.launcher.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import org.hhclicker.launcher.model.BundleKind

internal data class ComposeBuildDecision(val build: Boolean, val payloadId: String?)

/** Rebuild an installed payload once, but never trust a marker after its Docker images are removed. */
internal class ComposeBuildPolicy(
    private val commandRunner: CommandRunner,
    private val dockerExecutable: () -> String,
) {
    suspend fun decide(
        workspace: Path,
        configuration: LauncherRuntimeConfiguration,
        projectName: String,
        services: List<String>,
        buildInputs: Map<String, String> = emptyMap(),
    ): ComposeBuildDecision {
        // A checkout can change without a new release manifest, so keep rebuilding it on start.
        if (configuration.bundleKind != BundleKind.PORTABLE) return ComposeBuildDecision(true, null)

        val payloadId = payloadId(workspace, buildInputs) ?: return ComposeBuildDecision(true, null)
        val recordedId = runCatching { Files.readString(marker(configuration, projectName)).trim() }.getOrNull()
        if (recordedId != payloadId) return ComposeBuildDecision(true, payloadId)

        val images = services.map { "$projectName-$it" }
        val inspected = runCatching {
            commandRunner.execute(
                arguments = listOf(dockerExecutable(), "image", "inspect", "--format", "{{.Id}}") + images,
                workingDirectory = workspace,
                timeoutMs = IMAGE_INSPECT_TIMEOUT_MS,
            )
        }.getOrNull()
        return ComposeBuildDecision(inspected?.successful != true, payloadId)
    }

    fun rememberSuccessfulBuild(
        configuration: LauncherRuntimeConfiguration,
        projectName: String,
        decision: ComposeBuildDecision,
    ) {
        if (!decision.build || decision.payloadId == null) return
        // Losing this optimization must not turn a successfully started service into a failure.
        runCatching { Files.writeString(marker(configuration, projectName), decision.payloadId) }
    }

    private fun payloadId(workspace: Path, buildInputs: Map<String, String>): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(workspace.resolve("release-manifest.json")))
        buildInputs.toSortedMap().forEach { (key, value) ->
            digest.update(0.toByte())
            digest.update(key.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(value.toByteArray(Charsets.UTF_8))
        }
        Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }.getOrNull()

    private fun marker(configuration: LauncherRuntimeConfiguration, projectName: String): Path {
        require(projectName.matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "Некорректное имя Compose project" }
        return configuration.directory.resolve("$projectName.built-payload")
    }

    private companion object {
        const val IMAGE_INSPECT_TIMEOUT_MS = 20_000L
    }
}
