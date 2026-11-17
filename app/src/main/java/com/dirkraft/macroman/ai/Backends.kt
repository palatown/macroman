package com.dirkraft.macroman.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Base64

/**
 * Where the AI calls go. Anthropic is the one that has actually been used and tested. The rest share
 * one OpenAI-compatible client and are written against those providers' docs, but haven't been verified.
 */
enum class Provider(
    val id: String,
    val label: String,
    /** Blank for Anthropic (the SDK knows its address) and for the ones the user has to fill in. */
    val defaultBaseUrl: String,
    val keysUrl: String?,
    val keyOptional: Boolean = false,
    val tested: Boolean = false,
) {
    ANTHROPIC("anthropic", "Anthropic", "", "https://console.anthropic.com/settings/keys", tested = true),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/settings/keys"),
    LOCAL("local", "Ollama / LM Studio", "", null, keyOptional = true),
    CUSTOM("custom", "Other OpenAI-compatible", "", null, keyOptional = true);

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id } ?: ANTHROPIC
    }
}

enum class Effort { LOW, HIGH }

/** One structured call: a system prompt, the user's text and maybe a photo, and the JSON schema the answer must fit. */
class LlmRequest(
    val system: String,
    val text: String,
    val jpeg: ByteArray?,
    val schema: Map<String, Any>,
    val effort: Effort,
    /** Small rewrite jobs go to the cheaper "quick" model. */
    val quick: Boolean = false,
)

/** What the settings say to talk to; a snapshot, so a backend is rebuilt only when this changes. */
data class LlmConfig(
    val provider: Provider,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val quickModel: String,
)

interface LlmBackend {
    /** Checks key and model; returns a short label for the model, or throws with a message fit to show. */
    suspend fun check(): String
    suspend fun models(): List<String>
    /** Returns the answer as JSON text matching [LlmRequest.schema]. */
    suspend fun complete(req: LlmRequest): String
}

class AnthropicBackend(private val config: LlmConfig) : LlmBackend {
    private val client: AnthropicClient by lazy {
        if (config.apiKey.isBlank()) throw IllegalStateException("Add your Anthropic API key in Settings first.")
        AnthropicOkHttpClient.builder().apiKey(config.apiKey).build()
    }

    /** A Models API lookup, which costs no tokens. */
    override suspend fun check(): String = call { client.models().retrieve(config.model).displayName() }

    override suspend fun models(): List<String> = call { client.models().list().data().map { it.id() } }

    override suspend fun complete(req: LlmRequest): String = withContext(Dispatchers.IO) {
        val model = if (req.quick) config.quickModel.ifBlank { config.model } else config.model
        val schema = JsonOutputFormat.Schema.builder().apply {
            req.schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
        }.build()
        val blocks = buildList {
            if (req.jpeg != null) {
                add(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder().source(
                            Base64ImageSource.builder()
                                .data(Base64.getEncoder().encodeToString(req.jpeg))
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                .build()
                        ).build()
                    )
                )
            }
            add(ContentBlockParam.ofText(TextBlockParam.builder().text(req.text).build()))
        }
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(req.system)
            .outputConfig(
                OutputConfig.builder()
                    .effort(if (req.effort == Effort.HIGH) OutputConfig.Effort.HIGH else OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build()
            )
            .addUserMessageOfBlockParams(blocks)
            .apply {
                // Server-side fallback: a safety-classifier refusal is retried on a suitable model instead of failing.
                // Haiku, the default quick model, has no fallback, so only main calls ask for it.
                if (!req.quick) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()
        val message = call { client.messages().create(params) }
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            throw IllegalStateException("Claude declined that request. Try rewording it.")
        }
        message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    }

    private suspend fun <T> call(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: UnauthorizedException) {
            throw IllegalStateException("Key not recognised")
        } catch (e: PermissionDeniedException) {
            throw IllegalStateException("Key doesn't have access")
        } catch (e: NotFoundException) {
            throw IllegalStateException("Model \"${config.model}\" wasn't found")
        } catch (e: AnthropicServiceException) {
            throw IllegalStateException("Anthropic returned an error (${e.statusCode()})")
        } catch (e: AnthropicIoException) {
            throw IllegalStateException("Couldn't reach Anthropic. Check your connection.")
        }
    }
}

/**
 * Any server speaking the OpenAI Chat Completions format: OpenAI, Gemini's compatibility endpoint, OpenRouter,
 * Ollama, LM Studio, a LiteLLM proxy and so on. Unverified apart from what's noted in the README.
 *
 * Providers differ in which optional request fields they accept, so a rejected request steps down a ladder,
 * from strict JSON schema plus reasoning effort to plain "answer in JSON" with the schema in the prompt, and
 * the step that worked is remembered for this endpoint and model.
 */
class OpenAiCompatBackend(private val config: LlmConfig) : LlmBackend {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = config.baseUrl.trim().trimEnd('/')

    private enum class Mode { SCHEMA_AND_EFFORT, SCHEMA, JSON_OBJECT, PROMPT_ONLY }

    override suspend fun check(): String {
        val ids = models()
        val model = config.model.ifBlank { throw IllegalStateException("Connected. Now pick a model.") }
        if (ids.isNotEmpty() && model !in ids) throw IllegalStateException("Connected, but \"$model\" isn't in this provider's model list")
        return model
    }

    override suspend fun models(): List<String> {
        val body = request("GET", "/models", null)
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.sorted()
    }

    override suspend fun complete(req: LlmRequest): String {
        val model = (if (req.quick) config.quickModel.ifBlank { config.model } else config.model)
            .ifBlank { throw IllegalStateException("Pick a model in Settings first.") }
        val key = "$base|$model"
        var mode = modes[key] ?: Mode.SCHEMA_AND_EFFORT
        while (true) {
            try {
                val answer = chat(model, req, mode)
                modes[key] = mode
                return answer
            } catch (e: HttpError) {
                // Only a "bad request" suggests an unsupported field; anything else is a real failure.
                val next = Mode.entries.getOrNull(mode.ordinal + 1)
                if (e.code != 400 && e.code != 422 || next == null) throw IllegalStateException(e.message)
                mode = next
            }
        }
    }

    private suspend fun chat(model: String, req: LlmRequest, mode: Mode): String {
        val schemaJson = toJson(req.schema)
        val system = if (mode >= Mode.JSON_OBJECT) {
            "${req.system}\n\nAnswer with only a JSON object matching this JSON schema, no other text:\n$schemaJson"
        } else req.system
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject {
                    put("role", "user")
                    if (req.jpeg == null) put("content", req.text) else put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(req.jpeg))
                            })
                        })
                        add(buildJsonObject { put("type", "text"); put("text", req.text) })
                    })
                })
            })
            when (mode) {
                Mode.SCHEMA_AND_EFFORT, Mode.SCHEMA -> put("response_format", buildJsonObject {
                    put("type", "json_schema")
                    put("json_schema", buildJsonObject {
                        put("name", "result")
                        put("strict", true)
                        put("schema", schemaJson)
                    })
                })
                Mode.JSON_OBJECT -> put("response_format", buildJsonObject { put("type", "json_object") })
                Mode.PROMPT_ONLY -> {}
            }
            if (mode == Mode.SCHEMA_AND_EFFORT) put("reasoning_effort", if (req.effort == Effort.HIGH) "high" else "low")
        }
        val response = json.parseToJsonElement(request("POST", "/chat/completions", body.toString())).jsonObject
        val choice = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("The provider sent back no answer.")
        if (choice["finish_reason"]?.jsonPrimitive?.content == "content_filter") {
            throw IllegalStateException("The provider declined that request. Try rewording it.")
        }
        val content = choice["message"]?.jsonObject?.get("content")
        val text = when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            else -> ""
        }
        return extractJson(text)
    }

    private class HttpError(val code: Int, message: String) : Exception(message)

    private suspend fun request(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        if (base.isEmpty()) throw IllegalStateException("Enter the provider's base URL in Settings first.")
        val url = try { URL(base + path) } catch (e: Exception) { throw IllegalStateException("That base URL isn't valid.") }
        if (url.protocol == "http" && !isLocal(url.host)) {
            throw IllegalStateException("Use https for remote servers; plain http is only allowed on your own network.")
        }
        if (config.apiKey.isBlank() && !config.provider.keyOptional) {
            throw IllegalStateException("Add your ${config.provider.label} API key in Settings first.")
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            // Local models on a home computer can take minutes on a photo.
            readTimeout = 300_000
            if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) throw HttpError(code, describe(code, text))
            text
        } catch (e: IOException) {
            throw IllegalStateException("Couldn't reach ${url.host}. Check the base URL and your connection.")
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int, body: String): String {
        val detail = runCatching {
            val err = json.parseToJsonElement(body).jsonObject["error"]
            (err as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: (err as? JsonPrimitive)?.content
        }.getOrNull()?.take(160)
        val what = when (code) {
            401 -> "Key not recognised"
            403 -> "Key doesn't have access"
            404 -> "Not found; check the base URL and model"
            429 -> "Rate limited or out of credit"
            else -> "The provider returned an error ($code)"
        }
        return if (detail.isNullOrBlank()) what else "$what: $detail"
    }

    companion object {
        /** Which request shape worked, per endpoint and model, for the life of the app. */
        private val modes = mutableMapOf<String, Mode>()

        /** Plain http is allowed only to this phone or the home network (including Tailscale's range). */
        fun isLocal(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local")) return true
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
            val b = addr.address
            return b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
        }

        /** Models in prompt-only mode may wrap the JSON in a code fence or a sentence. */
        fun extractJson(text: String): String {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start >= 0 && end > start) text.substring(start, end + 1) else text
        }

        private fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
            is List<*> -> JsonArray(v.map(::toJson))
            else -> JsonPrimitive(v.toString())
        }
    }
}
