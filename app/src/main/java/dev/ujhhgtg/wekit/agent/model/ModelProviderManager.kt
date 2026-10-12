package dev.ujhhgtg.wekit.agent.model

import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds and caches [LlmClient] adapters per model provider, and resolves a stored [ModelEntity]
 * into a ready-to-send [LlmRequest] shell (model id + reasoning gear + custom-JSON override).
 *
 * One shared [HttpClient] (Ktor CIO) is reused across all adapters, mirroring [dev.ujhhgtg.wekit.utils.EdgeTtsClient]'s
 * singleton pattern. Request/socket/connect timeouts come from the user setting (see
 * [WeAgentSettings.llmRequestTimeoutSecSnapshot]) and default to the long read timeout that
 * accommodates slow streaming completions; editing the setting rebuilds the client, so the new
 * values apply from the next request on without restarting WeChat.
 */
object ModelProviderManager {

    /**
     * The most recently built shared client; [sharedRequestTimeoutSec] and [sharedConnectTimeoutSec]
     * record the timeout pair it was built with. Read through [httpClient], which swaps in a fresh
     * instance as soon as the settings snapshot no longer matches.
     */
    @Volatile
    private var sharedHttpClient: HttpClient? = null

    @Volatile
    private var sharedRequestTimeoutSec = WeAgentSettings.DEFAULT_LLM_REQUEST_TIMEOUT_SEC

    @Volatile
    private var sharedConnectTimeoutSec = WeAgentSettings.DEFAULT_LLM_CONNECT_TIMEOUT_SEC

    /**
     * The shared Ktor CIO client, rebuilt when the configured timeouts change. The superseded
     * instance is deliberately left open instead of being closed: a background turn may still be
     * streaming on it, and closing the engine would abort that stream mid-response. Nothing is
     * dispatched to it afterwards, so it only holds idle keep-alive sockets, and one client is
     * abandoned per timeout value actually used by a request — at most a handful per WeChat run.
     */
    private val httpClient: HttpClient
        get() = synchronized(this) {
            val requestTimeoutSec = WeAgentSettings.llmRequestTimeoutSecSnapshot
            val connectTimeoutSec = WeAgentSettings.llmConnectTimeoutSecSnapshot
            val cached = sharedHttpClient
            if (cached != null &&
                requestTimeoutSec == sharedRequestTimeoutSec &&
                connectTimeoutSec == sharedConnectTimeoutSec
            ) {
                cached
            } else {
                buildHttpClient(requestTimeoutSec, connectTimeoutSec).also {
                    sharedHttpClient = it
                    sharedRequestTimeoutSec = requestTimeoutSec
                    sharedConnectTimeoutSec = connectTimeoutSec
                }
            }
        }

    private fun buildHttpClient(requestTimeoutSec: Int, connectTimeoutSec: Int): HttpClient = HttpClient(CIO) {
        install(UserAgent) {
            agent = "WeAgent/${BuildConfig.VERSION_NAME}"
        }
        install(HttpTimeout) {
            // Socket idle timeout tracks the request timeout: the original hard-coded pair was
            // 600_000 ms for both, and a stream that stalls for the whole request budget is dead.
            requestTimeoutMillis = requestTimeoutSec * 1000L
            socketTimeoutMillis = requestTimeoutSec * 1000L
            connectTimeoutMillis = connectTimeoutSec * 1000L
        }
    }

    // providerId -> adapter, rebuilt when the provider's config or the configured timeouts change.
    private val clientCache = HashMap<String, CachedClient>()

    private data class CachedClient(
        val configHash: Int,
        val requestTimeoutSec: Int,
        val connectTimeoutSec: Int,
        val client: LlmClient,
    )

    /**
     * Returns an [LlmClient] for [provider], building one if it is absent, if the provider's config
     * (type/baseUrl/apiKey) changed, or if the configured timeouts changed since it was cached
     * (an adapter holds the [HttpClient] it was built with, so a new timeout needs a new adapter).
     * [provider] must carry a usable apiKey (use [WeAgentRepository.getModelProvider]); keys are
     * stored as-is, so nothing needs decrypting.
     */
    @Synchronized
    fun clientFor(provider: ModelProviderEntity): LlmClient {
        val requestTimeoutSec = WeAgentSettings.llmRequestTimeoutSecSnapshot
        val connectTimeoutSec = WeAgentSettings.llmConnectTimeoutSecSnapshot
        val hash = provider.type.hashCode() * 31 + provider.baseUrl.hashCode() * 31 + provider.apiKey.hashCode()
        clientCache[provider.id]?.let {
            if (it.configHash == hash &&
                it.requestTimeoutSec == requestTimeoutSec &&
                it.connectTimeoutSec == connectTimeoutSec
            ) return it.client
        }
        val client = build(provider)
        clientCache[provider.id] = CachedClient(hash, requestTimeoutSec, connectTimeoutSec, client)
        return client
    }

    private fun build(provider: ModelProviderEntity): LlmClient = when (provider.type) {
        ModelProviderType.OPENAI_CHAT_COMPLETION ->
            OpenAiChatCompletionsClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.OPENAI_RESPONSES ->
            OpenAiResponsesClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.ANTHROPIC_MESSAGES ->
            AnthropicMessagesClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.GEMINI_GENERATE_CONTENT ->
            GeminiGenerateContentClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.GEMINI_INTERACTIONS ->
            GeminiInteractionsClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)
    }

    /**
     * Resolves a [ModelEntity] + a fully-composed message/tool set into an [LlmRequest]. The stored
     * [ModelEntity.reasoningEffort] is passed through literally; the sentinel "off"/"none" and null
     * both mean "omit the field", so callers store a real gear string or null.
     */
    fun buildRequest(
        model: ModelEntity,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        sessionId: String,
        stream: Boolean = true,
    ): LlmRequest {
        val effort = model.reasoningEffort?.takeIf { it.isNotBlank() && it != "off" }
        val override = model.customJsonOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        return LlmRequest(
            modelIdRemote = model.modelIdRemote,
            messages = messages,
            sessionId = sessionId,
            tools = tools,
            reasoningEffort = effort,
            customJsonOverride = override,
            maxTokens = model.maxTokens,
            stream = stream,
        )
    }

    /** Drops a cached adapter (call after a provider is edited/deleted). */
    @Synchronized
    fun invalidate(providerId: String) {
        clientCache.remove(providerId)
    }

    /**
     * Fetches the provider's available model ids. Three list-endpoint shapes are supported:
     *  - **OpenAI** (`OPENAI_CHAT_COMPLETION` / `OPENAI_RESPONSES`):
     *    `GET {baseUrl}/models` → `{ "data": [ { "id": "…" }, … ] }`. Auth: `Authorization: Bearer`.
     *  - **Gemini** (`GEMINI_GENERATE_CONTENT` / `GEMINI_INTERACTIONS`): `GET {baseUrl}/models` →
     *    `{ "models": [ { "name": "models/gemini-…", … }, … ] }`. Auth: `x-goog-api-key`.
     *    We strip the `"models/"` prefix so the stored id matches what goes in the URL / request.
     *  - **Anthropic** (`ANTHROPIC_MESSAGES`): no public list endpoint; returns a failure.
     *
     * [provider] must carry a decrypted API key.
     */
    suspend fun listRemoteModels(provider: ModelProviderEntity): Result<List<String>> {
        if (provider.type == ModelProviderType.ANTHROPIC_MESSAGES) {
            return Result.failure(LlmException("Anthropic 不支持自动获取模型列表，请手动添加。"))
        }
        val isGemini = provider.type == ModelProviderType.GEMINI_GENERATE_CONTENT
                || provider.type == ModelProviderType.GEMINI_INTERACTIONS
        val resolvedBase = provider.baseUrl.trimEnd('/')
        val endpoint = "$resolvedBase/models"
        return runCatching {
            val resp = httpClient.get(endpoint) {
                if (provider.apiKey.isNotBlank()) {
                    if (isGemini) {
                        header(GeminiCommon.API_KEY_HEADER, provider.apiKey)
                    } else {
                        header(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                    }
                }
            }
            if (!resp.status.isSuccess()) {
                throw LlmException("HTTP ${resp.status.value}: ${resp.bodyAsText().take(300)}")
            }
            val root = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            if (isGemini) {
                // Gemini: { "models": [ { "name": "models/gemini-…" }, … ] }
                root["models"]?.jsonArray
                    ?.mapNotNull {
                        it.jsonObject["name"]?.jsonPrimitive?.content
                            ?.removePrefix("models/")
                            ?.takeIf(String::isNotBlank)
                    }
                    ?.distinct()
                    ?.sorted()
                    ?: emptyList()
            } else {
                // OpenAI: { "data": [ { "id": "…" }, … ] }
                root["data"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) }
                    ?.distinct()
                    ?.sorted()
                    ?: emptyList()
            }
        }
    }
}
