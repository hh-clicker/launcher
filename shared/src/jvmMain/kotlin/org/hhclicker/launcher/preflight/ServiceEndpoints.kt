package org.hhclicker.launcher.preflight

import java.nio.file.Files
import java.nio.file.Path

data class ServiceEndpoints(
    val backendPort: Int,
    val frontendPort: Int,
    val generationPort: Int,
    val backendUrl: String,
    val frontendUrl: String,
    val generationUrl: String,
) {
    companion object {
        fun from(workspace: Path): ServiceEndpoints {
            return from(workspace.resolve("infra/.env"), workspace.resolve("generation-agent/.env"))
        }

        fun from(infraEnvironmentFile: Path, generationEnvironmentFile: Path): ServiceEndpoints {
            val backendPort = readPort(infraEnvironmentFile, "BACKEND_PORT", 8080)
            val frontendPort = readPort(infraEnvironmentFile, "FRONTEND_PORT", 80)
            val generationPort = readPort(generationEnvironmentFile, "GENERATION_PORT", 17374)
            return ServiceEndpoints(
                backendPort = backendPort,
                frontendPort = frontendPort,
                generationPort = generationPort,
                backendUrl = "http://localhost:$backendPort",
                frontendUrl = if (frontendPort == 80) "http://localhost" else "http://localhost:$frontendPort",
                generationUrl = "http://localhost:$generationPort",
            )
        }

        private fun readPort(file: Path, name: String, default: Int): Int {
            if (!Files.isRegularFile(file)) return default
            return runCatching {
                Files.readAllLines(file)
                    .asSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .mapNotNull { line ->
                        val separator = line.indexOf('=')
                        if (separator < 1 || line.substring(0, separator).trim() != name) return@mapNotNull null
                        line.substring(separator + 1).trim().trim('"', '\'').toIntOrNull()
                    }
                    .lastOrNull { it in 1..65535 }
                    ?: default
            }.getOrDefault(default)
        }
    }
}
