package org.hhclicker.launcher.runtime

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.UUID
import org.hhclicker.launcher.model.BundleKind
import org.hhclicker.launcher.model.ComponentId
import org.hhclicker.launcher.preflight.BundleLayout
import org.hhclicker.launcher.preflight.ServiceEndpoints
import org.hhclicker.launcher.setup.JsonValue
import org.hhclicker.launcher.setup.MiniJson

/** Configuration shared by every packaged version of one installation, but isolated from development. */
data class LauncherRuntimeConfiguration(
    val bundleKind: BundleKind,
    val instanceId: String,
    val version: String,
    val directory: Path,
    val infraEnvironmentFile: Path,
    val generationEnvironmentFile: Path,
    val infraProjectName: String,
    val generationProjectName: String,
    val endpoints: ServiceEndpoints,
)

/**
 * Creates the launcher's durable identity and Compose environment outside the replaceable bundle.
 *
 * Packaged applications share the `production` profile across versions. A source checkout uses the
 * independent `development` profile, so builds, containers, volumes, ports and database passwords
 * cannot accidentally collide with a user's installation.
 */
class LauncherRuntimeConfigurationStore(
    private val environment: (String) -> String? = System::getenv,
    private val random: SecureRandom = SecureRandom(),
    private val uuid: () -> UUID = UUID::randomUUID,
    private val rootOverride: Path? = null,
    private val portAvailable: (Int) -> Boolean = LocalPortAvailability::isAvailable,
) {
    fun loadOrCreate(workspace: Path): LauncherRuntimeConfiguration {
        val kind = BundleLayout.kind(workspace)
        val profile = if (kind == BundleKind.PORTABLE) "production" else "development"
        val infraProjectName = if (kind == BundleKind.PORTABLE) "hh-clicker" else "hh-clicker-dev"
        val directory = root().resolve("config").resolve(profile)
        Files.createDirectories(directory)

        val instanceFile = directory.resolve("instance.json")
        val instanceId = readInstanceId(instanceFile) ?: uuid().toString().also { created ->
            writeAtomically(
                instanceFile,
                """
                {
                  "instanceId": "$created",
                  "installedVersion": "${jsonEscape(BundleLayout.version(workspace) ?: "dev")}",
                  "configVersion": 1
                }
                """.trimIndent() + System.lineSeparator(),
            )
        }

        val version = BundleLayout.version(workspace) ?: "dev"
        val claimedPorts = LinkedHashSet<Int>()
        val infraFile = directory.resolve("infra.env")
        if (!Files.isRegularFile(infraFile)) {
            val legacy = readEnvironmentFile(workspace.resolve("infra/.env"))
            val password = legacy["POSTGRES_PASSWORD"]
                ?.takeIf { it.isNotBlank() && it != "change-me" && it != "managed-by-launcher" }
                ?: randomPassword()
            val values = linkedMapOf(
                "HH_CLICKER_INSTANCE_ID" to instanceId,
                "HH_CLICKER_VERSION" to version,
                "HH_CLICKER_PROFILE" to profile,
                "POSTGRES_DB" to legacy["POSTGRES_DB"].orDefault("hh_clicker"),
                "POSTGRES_USER" to legacy["POSTGRES_USER"].orDefault("hh_clicker"),
                "POSTGRES_PASSWORD" to password,
                "DB_SCHEMA" to legacy["DB_SCHEMA"].orDefault("hhclicker"),
                "BACKEND_PORT" to selectPort(legacy["BACKEND_PORT"], BACKEND_PORTS, claimedPorts).toString(),
                "FRONTEND_PORT" to selectPort(legacy["FRONTEND_PORT"], FRONTEND_PORTS, claimedPorts).toString(),
            )
            legacy["HH_CLICKER_IMAGE_TAG"]?.takeIf(String::isNotBlank)?.let { values["HH_CLICKER_IMAGE_TAG"] = it }
            writeEnvironment(infraFile, values)
        }
        refreshManagedMetadata(infraFile, instanceId, version, profile)
        val infraValues = readEnvironmentFile(infraFile)
        require(infraValues["POSTGRES_PASSWORD"].orEmpty().isNotBlank()) {
            "В ${infraFile.fileName} отсутствует пароль PostgreSQL"
        }
        listOf("BACKEND_PORT", "FRONTEND_PORT").mapNotNullTo(claimedPorts) { infraValues[it].validPort() }

        val generationFile = directory.resolve("generation.env")
        if (!Files.isRegularFile(generationFile)) {
            val legacy = readEnvironmentFile(workspace.resolve("generation-agent/.env"))
            val values = linkedMapOf(
                "HH_CLICKER_INSTANCE_ID" to instanceId,
                "HH_CLICKER_VERSION" to version,
                "HH_CLICKER_PROFILE" to profile,
                "HH_CLICKER_INFRA_PROJECT" to infraProjectName,
                "GENERATION_PORT" to selectPort(legacy["GENERATION_PORT"], GENERATION_PORTS, claimedPorts).toString(),
                "CODEX_CLI_VERSION" to legacy["CODEX_CLI_VERSION"].orDefault("0.163.0"),
                "CODEX_MODEL" to legacy["CODEX_MODEL"].orDefault("gpt-6-luna"),
                "CODEX_REASONING_EFFORT" to legacy["CODEX_REASONING_EFFORT"].orDefault("low"),
                "CODEX_TIMEOUT_MS" to legacy["CODEX_TIMEOUT_MS"].orDefault("180000"),
                "GENERATION_MAX_PENDING" to legacy["GENERATION_MAX_PENDING"].orDefault("20"),
                "GENERATION_MAX_CONCURRENT_ANALYSIS" to (legacy["GENERATION_MAX_CONCURRENT_ANALYSIS"]
                    ?: legacy["GENERATION_MAX_CONCURRENT_RELEVANCE"]).orDefault("4"),
                "GENERATION_MEMORY_PERCENT" to legacy["GENERATION_MEMORY_PERCENT"].orDefault("25"),
                "GENERATION_JOB_MEMORY_MIB" to legacy["GENERATION_JOB_MEMORY_MIB"].orDefault("128"),
                "GENERATION_RETENTION_DAYS" to legacy["GENERATION_RETENTION_DAYS"].orDefault("7"),
            )
            legacy["HH_CLICKER_IMAGE_TAG"]?.takeIf(String::isNotBlank)?.let { values["HH_CLICKER_IMAGE_TAG"] = it }
            writeEnvironment(generationFile, values)
        }
        if (kind == BundleKind.PORTABLE) migrateGenerationDefaults(generationFile)
        refreshManagedMetadata(generationFile, instanceId, version, profile,
            mapOf("HH_CLICKER_INFRA_PROJECT" to infraProjectName))
        synchronizeGenerationPort(infraFile, generationFile)

        return LauncherRuntimeConfiguration(
            bundleKind = kind,
            instanceId = instanceId,
            version = version,
            directory = directory,
            infraEnvironmentFile = infraFile,
            generationEnvironmentFile = generationFile,
            infraProjectName = infraProjectName,
            generationProjectName = if (kind == BundleKind.PORTABLE) "hh-clicker-generation" else "hh-clicker-generation-dev",
            endpoints = ServiceEndpoints.from(infraFile, generationFile),
        )
    }

    /** Reassigns only conflicting service ports and preserves identity, credentials and every other value. */
    fun reassignPorts(
        configuration: LauncherRuntimeConfiguration,
        components: Set<ComponentId>,
    ): LauncherRuntimeConfiguration {
        val targets = components intersect PORT_COMPONENTS
        if (targets.isEmpty()) return configuration

        val infra = LinkedHashMap(readEnvironmentFile(configuration.infraEnvironmentFile))
        val generation = LinkedHashMap(readEnvironmentFile(configuration.generationEnvironmentFile))
        require(infra.isNotEmpty() && generation.isNotEmpty()) { "Не удалось прочитать постоянные настройки портов" }

        val claimed = linkedSetOf<Int>()
        if (ComponentId.BACKEND !in targets) infra["BACKEND_PORT"].validPort()?.let(claimed::add)
        if (ComponentId.FRONTEND !in targets) infra["FRONTEND_PORT"].validPort()?.let(claimed::add)
        if (ComponentId.GENERATION_AGENT !in targets) generation["GENERATION_PORT"].validPort()?.let(claimed::add)

        if (ComponentId.BACKEND in targets) {
            infra["BACKEND_PORT"] = selectAvailablePort(BACKEND_PORTS, claimed).toString()
        }
        if (ComponentId.FRONTEND in targets) {
            infra["FRONTEND_PORT"] = selectAvailablePort(FRONTEND_PORTS, claimed).toString()
        }
        if (ComponentId.GENERATION_AGENT in targets) {
            generation["GENERATION_PORT"] = selectAvailablePort(GENERATION_PORTS, claimed).toString()
        }
        infra["GENERATION_PORT"] = generation["GENERATION_PORT"].orDefault("17374")

        writeEnvironment(configuration.infraEnvironmentFile, infra)
        writeEnvironment(configuration.generationEnvironmentFile, generation)
        return configuration.copy(
            endpoints = ServiceEndpoints.from(
                configuration.infraEnvironmentFile,
                configuration.generationEnvironmentFile,
            ),
        )
    }

    private fun synchronizeGenerationPort(infraFile: Path, generationFile: Path) {
        val infra = LinkedHashMap(readEnvironmentFile(infraFile))
        val generationPort = readEnvironmentFile(generationFile)["GENERATION_PORT"].orDefault("17374")
        if (infra["GENERATION_PORT"] == generationPort) return
        infra["GENERATION_PORT"] = generationPort
        writeEnvironment(infraFile, infra)
    }

    /** Upgrades values supplied by the previous release while retaining explicit custom values. */
    private fun migrateGenerationDefaults(generationFile: Path) {
        val values = LinkedHashMap(readEnvironmentFile(generationFile))
        var changed = false
        if (values["CODEX_CLI_VERSION"] == "0.153.0") {
            values["CODEX_CLI_VERSION"] = "0.163.0"
            changed = true
        }
        if (values["CODEX_MODEL"] == "gpt-5.6-luna") {
            values["CODEX_MODEL"] = "gpt-6-luna"
            changed = true
        }
        if (changed) writeEnvironment(generationFile, values)
    }

    private fun root(): Path {
        rootOverride?.let { return it }
        val dataRoot = LauncherDataPaths.dataRoot(environment)
            ?: throw IllegalStateException("Не удалось определить пользовательский каталог данных")
        val legacyRoot = LauncherDataPaths.applicationRoot(environment)
            ?.takeIf { HostPlatformDetector.detect() == org.hhclicker.launcher.model.HostPlatform.WINDOWS }
        return if (!Files.exists(dataRoot.resolve("config")) && legacyRoot != null && Files.exists(legacyRoot.resolve("config"))) {
            legacyRoot
        } else {
            dataRoot
        }
    }

    private fun readInstanceId(file: Path): String? {
        if (!Files.isRegularFile(file)) return null
        val document = MiniJson.parseOrNull(runCatching { Files.readString(file) }.getOrNull())
            ?: throw IllegalStateException("Повреждён постоянный файл ${file.fileName}")
        val value = ((document as? JsonValue.JsonObject)?.entries?.get("instanceId") as? JsonValue.JsonString)?.value
            ?: throw IllegalStateException("В ${file.fileName} отсутствует instanceId")
        return runCatching { UUID.fromString(value).toString() }
            .getOrElse { throw IllegalStateException("В ${file.fileName} записан неверный instanceId") }
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun readEnvironmentFile(file: Path): Map<String, String> {
        if (!Files.isRegularFile(file)) return emptyMap()
        return runCatching {
            Files.readAllLines(file).mapNotNull { line ->
                val trimmed = line.trim()
                val separator = trimmed.indexOf('=')
                if (trimmed.isEmpty() || trimmed.startsWith('#') || separator < 1) return@mapNotNull null
                trimmed.substring(0, separator).trim() to trimmed.substring(separator + 1).trim().trim('"', '\'')
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeEnvironment(file: Path, values: Map<String, String>) {
        values.forEach { (key, value) ->
            require(key.matches(ENVIRONMENT_NAME)) { "Некорректное имя настройки: $key" }
            require(!value.contains('\n') && !value.contains('\r')) { "Некорректное значение настройки: $key" }
        }
        writeAtomically(file, values.entries.joinToString(System.lineSeparator(), postfix = System.lineSeparator()) { (key, value) ->
            "$key=$value"
        })
    }

    private fun refreshManagedMetadata(
        file: Path,
        instanceId: String,
        version: String,
        profile: String,
        additional: Map<String, String> = emptyMap(),
    ) {
        val values = LinkedHashMap(readEnvironmentFile(file))
        if (values.isEmpty()) throw IllegalStateException("Повреждён постоянный файл ${file.fileName}")
        val expected = mapOf(
            "HH_CLICKER_INSTANCE_ID" to instanceId,
            "HH_CLICKER_VERSION" to version,
            "HH_CLICKER_PROFILE" to profile,
        ) + additional
        if (expected.all { (key, value) -> values[key] == value }) return
        expected.forEach { (key, value) -> values[key] = value }
        writeEnvironment(file, values)
    }

    private fun writeAtomically(target: Path, content: String) {
        val temporary = target.resolveSibling("${target.fileName}.hh-clicker.tmp")
        Files.writeString(temporary, content)
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun String?.orDefault(default: String): String = this?.takeIf(String::isNotBlank) ?: default

    private fun String?.validPort(): Int? = this?.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun selectPort(raw: String?, candidates: Sequence<Int>, claimed: MutableSet<Int>): Int {
        raw.validPort()?.let { configured ->
            claimed += configured
            return configured
        }
        return candidates.firstOrNull { candidate -> candidate !in claimed && portAvailable(candidate) }
            ?.also { claimed += it }
            ?: throw IllegalStateException("Не удалось найти свободный локальный порт для HH Clicker")
    }

    private fun selectAvailablePort(candidates: Sequence<Int>, claimed: MutableSet<Int>): Int =
        candidates.firstOrNull { candidate -> candidate !in claimed && portAvailable(candidate) }
            ?.also { claimed += it }
            ?: throw IllegalStateException("Не удалось найти свободный локальный порт для HH Clicker")

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
    }

    private companion object {
        val ENVIRONMENT_NAME = Regex("[A-Z][A-Z0-9_]*")
        val BACKEND_PORTS = sequenceOf(8080) + (18080..18179).asSequence()
        val FRONTEND_PORTS = sequenceOf(80) + (3000..3099).asSequence()
        val GENERATION_PORTS = (17374..17473).asSequence()
        val PORT_COMPONENTS = setOf(ComponentId.BACKEND, ComponentId.FRONTEND, ComponentId.GENERATION_AGENT)

    }
}
