package org.hhclicker.launcher.runtime

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hhclicker.launcher.model.CodexAuthState
import org.hhclicker.launcher.model.CodexAuthStatus
import org.hhclicker.launcher.model.CodexRuntimeInfo
import org.hhclicker.launcher.setup.CodexLoginPoll
import org.hhclicker.launcher.setup.JsonValue
import org.hhclicker.launcher.setup.MiniJson

/** Loopback-only client for the Generation Agent authentication bridge. */
class GenerationAuthClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build(),
) {
    suspend fun status(baseUrl: String): CodexAuthState = request(baseUrl, "/auth/status")
        ?.let(::parseAccountStatus)
        ?: unavailable()

    suspend fun runtimeInfo(baseUrl: String): CodexRuntimeInfo? =
        request(baseUrl, "/health", timeout = Duration.ofSeconds(3))?.let(::parseRuntimeInfo)

    suspend fun refreshStatus(baseUrl: String): CodexAuthState = request(baseUrl, "/auth/status/refresh", post = true,
        timeout = Duration.ofSeconds(45))
        ?.let(::parseAccountStatus) ?: failed("Не удалось проверить авторизацию Codex")

    suspend fun startLogin(baseUrl: String): CodexAuthState = request(baseUrl, "/auth/login/start", post = true)
        ?.let(::parseLoginState)
        ?: failed("Generation Agent не ответил на запрос авторизации")

    /**
     * Polls a device-code login in progress.
     *
     * Returns [CodexLoginPoll] rather than a state, because "the Generation Agent says the login failed"
     * and "this one request did not come back" are different facts and only the first one may end an
     * authorization the user is still completing in their browser.
     */
    suspend fun loginState(baseUrl: String): CodexLoginPoll {
        val body = request(baseUrl, "/auth/login")
            ?: return CodexLoginPoll.Unavailable("Generation Agent не ответил на проверку авторизации")
        val parsed = parseLoginState(body)
            ?: return CodexLoginPoll.Unavailable("Generation Agent вернул неожиданный ответ")
        return CodexLoginPoll.Answered(parsed)
    }

    suspend fun cancelLogin(baseUrl: String): CodexAuthState = request(baseUrl, "/auth/login/cancel", post = true)
        ?.let(::parseLoginState)
        ?: failed("Не удалось отменить авторизацию")

    suspend fun logout(baseUrl: String): CodexAuthState = request(baseUrl, "/auth/logout", post = true)
        ?.let(::parseLoginState)
        ?: failed("Не удалось выйти из Codex")

    private suspend fun request(
        baseUrl: String,
        path: String,
        post: Boolean = false,
        timeout: Duration = Duration.ofSeconds(20),
    ): String? = withContext(Dispatchers.IO) {
        try {
            val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                .timeout(timeout)
                .header("Accept", "application/json")
            val request = if (post) builder.POST(HttpRequest.BodyPublishers.noBody()).build() else builder.GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.body().takeIf { response.statusCode() in 200..299 }
        } catch (cancelled: CancellationException) {
            // Cancellation is not a transport failure and must reach the caller unchanged.
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseRuntimeInfo(json: String): CodexRuntimeInfo? {
        val root = MiniJson.parseOrNull(json).objectEntries() ?: return null
        if ((root["ok"] as? JsonValue.JsonBoolean)?.value != true) return null
        fun field(name: String): String? = (root[name] as? JsonValue.JsonString)?.value
            ?.trim()?.takeIf { it.length in 1..100 }
        val model = field("model") ?: return null
        return CodexRuntimeInfo(model, field("reasoningEffort"), field("cliVersion"))
    }

    internal fun parseAccountStatus(json: String): CodexAuthState? {
        val root = MiniJson.parseOrNull(json).objectEntries() ?: return null
        val authenticated = (root["authenticated"] as? JsonValue.JsonBoolean)?.value ?: return null
        val method = (root["method"] as? JsonValue.JsonString)?.value
        val errorCode = ((root["error"] as? JsonValue.JsonObject)?.entries?.get("code") as? JsonValue.JsonString)?.value
        val limits = (root["limits"] as? JsonValue.JsonObject)?.entries
        val checkedAt = (root["checkedAt"] as? JsonValue.JsonString)?.value?.let { value ->
            runCatching { java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")) }.getOrNull()
        }
        val windows = listOf("primary", "secondary").mapNotNull { key ->
            val window = (limits?.get(key) as? JsonValue.JsonObject)?.entries ?: return@mapNotNull null
            val used = (window["usedPercent"] as? JsonValue.JsonNumber)?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val minutes = (window["windowDurationMins"] as? JsonValue.JsonNumber)?.value?.toLongOrNull() ?: return@mapNotNull null
            val reset = (window["resetsAt"] as? JsonValue.JsonNumber)?.value?.toLongOrNull() ?: return@mapNotNull null
            if (!used.isFinite() || used !in 0.0..100.0 || minutes <= 0 || reset < 0) return@mapNotNull null
            val label = when (minutes) { 300L -> "5 часов"; 10080L -> "Неделя"; else -> "$minutes мин." }
            val time = runCatching { java.time.Instant.ofEpochSecond(reset).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")) }.getOrNull() ?: return@mapNotNull null
            Pair("$label: осталось ${kotlin.math.round(100 - used).toInt()}%", "$label: $time")
        }
        return if (authenticated) {
            CodexAuthState(
                status = CodexAuthStatus.SIGNED_IN,
                method = method,
                checkedAtLabel = checkedAt,
                limitsSummary = windows.takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.first },
                limitsResetSummary = windows.takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.second },
                message = when (method) {
                    "chatgpt" -> "Выполнен вход через ChatGPT"
                    "api_key" -> "Выполнен вход с API-ключом"
                    else -> "Codex авторизован"
                },
            )
        } else {
            CodexAuthState(
                if (errorCode == null || errorCode == "AUTH_REQUIRED") CodexAuthStatus.SIGNED_OUT else CodexAuthStatus.FAILED,
                message = if (errorCode == "AUTH_REQUIRED") "Требуется повторный вход в Codex" else if (errorCode != null) "Не удалось проверить авторизацию Codex. Проверьте соединение" else "Требуется вход через ChatGPT",
            )
        }
    }

    internal fun parseLoginState(json: String): CodexAuthState? {
        val root = MiniJson.parseOrNull(json).objectEntries() ?: return null
        val status = (root["status"] as? JsonValue.JsonString)?.value ?: return null
        val message = (root["message"] as? JsonValue.JsonString)?.value
        return when (status) {
            "starting" -> CodexAuthState(CodexAuthStatus.AUTHORIZING, message = "Получаем код авторизации…")
            "pending" -> {
                val url = (root["verificationUrl"] as? JsonValue.JsonString)?.value
                    ?.takeIf(::trustedVerificationUrl)
                val code = (root["userCode"] as? JsonValue.JsonString)?.value
                    ?.takeIf { it.length in 4..32 && it.all { character -> character.isLetterOrDigit() || character == '-' } }
                if (url == null || code == null) failed("Generation Agent вернул некорректный код авторизации")
                else CodexAuthState(CodexAuthStatus.AUTHORIZING, verificationUrl = url, userCode = code)
            }
            "succeeded" -> CodexAuthState(CodexAuthStatus.SIGNED_IN, message = message ?: "Codex подключён")
            "signed_out", "idle" -> CodexAuthState(CodexAuthStatus.SIGNED_OUT, message = "Требуется вход через ChatGPT")
            "failed" -> failed(message ?: "Авторизация Codex не завершена")
            else -> null
        }
    }

    internal fun trustedVerificationUrl(value: String): Boolean = runCatching {
        val uri = URI.create(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("auth.openai.com", ignoreCase = true) &&
            uri.path == "/codex/device" && uri.userInfo == null
    }.getOrDefault(false)

    private fun JsonValue?.objectEntries(): Map<String, JsonValue>? =
        (this as? JsonValue.JsonObject)?.entries

    private fun unavailable() = CodexAuthState(
        CodexAuthStatus.UNAVAILABLE,
        message = "Сначала запустите Generation Agent",
    )

    private fun failed(message: String) = CodexAuthState(CodexAuthStatus.FAILED, message = message)
}
