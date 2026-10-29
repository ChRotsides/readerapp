package com.readerapp.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.readerapp.data.settings.SecureSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic Java SDK wrapper (blocking SDK call moved onto Dispatchers.IO). The client is built lazily from
 * the key in [SecureSettings] and rebuilt if the key changes.
 *
 * Rules for the models in use: no prefill, no forced tool_choice (typed JSON goes through structured outputs
 * via `outputConfig.format`), no sampling parameters, thinking left at its default, effort set explicitly.
 * The schema is passed as a manual JSON Schema (not the reflection-based `outputConfig(Class)`) because
 * class-to-schema reflection is heavy and fragile on Android/R8.
 */
@Singleton
class ClaudeClient @Inject constructor(
    private val settings: SecureSettings,
    private val usage: UsageRepository,
) : LlmClient {
    private val lock = Mutex()
    private var cached: Pair<String, AnthropicClient>? = null

    private suspend fun client(): AnthropicClient {
        val key = settings.anthropicApiKey()?.takeIf { it.isNotBlank() } ?: throw LlmException.NotConfigured()
        return lock.withLock {
            cached?.takeIf { it.first == key }?.second ?: AnthropicOkHttpClient.builder()
                .apiKey(key)
                .timeout(Duration.ofMinutes(3))
                .maxRetries(2)
                .build()
                .also { cached = key to it }
        }
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val client = client()
        val params = buildParams(request)
        val message = try {
            withContext(Dispatchers.IO) { client.messages().create(params) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitException) {
            throw LlmException.Retryable("Rate limited by the Anthropic API", e)
        } catch (e: InternalServerException) {
            throw LlmException.Retryable("Anthropic API server error (${e.statusCode()})", e)
        } catch (e: AnthropicIoException) {
            throw LlmException.Retryable("Network error talking to the Anthropic API", e)
        } catch (e: AnthropicServiceException) {
            // 4xx (bad request, auth, permission, not found, ...) will not succeed on retry. 529 overloaded is retryable.
            if (e.statusCode() == 529) throw LlmException.Retryable("Anthropic API overloaded", e)
            throw LlmException.Rejected("Anthropic API error ${e.statusCode()}: ${e.message}", e)
        }

        val input = message.usage().inputTokens()
        val output = message.usage().outputTokens()
        // Tokens were spent whatever the outcome, so record before inspecting the stop reason.
        runCatching { usage.record(request.model, request.purpose, input, output) }

        val stop = message.stopReason().orElse(null)
        when (stop) {
            StopReason.REFUSAL -> throw LlmException.Refused(
                message.stopDetails().map { it.explanation().orElse(null) ?: "no explanation" }.orElse("no details"),
            )
            StopReason.MAX_TOKENS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> throw LlmException.Truncated()
            else -> Unit
        }
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        if (text.isBlank()) throw LlmException.Rejected("The model returned no text")
        return LlmResponse(text, input, output)
    }

    private fun buildParams(request: LlmRequest): MessageCreateParams {
        val effort = when (request.effort) {
            LlmEffort.LOW -> OutputConfig.Effort.LOW
            LlmEffort.MEDIUM -> OutputConfig.Effort.MEDIUM
            LlmEffort.HIGH -> OutputConfig.Effort.HIGH
        }
        val output = OutputConfig.builder().effort(effort)
        request.jsonSchema?.let { schemaText ->
            val schema = JsonOutputFormat.Schema.builder()
            (Json.parseToJsonElement(schemaText) as JsonObject).forEach { (k, v) ->
                schema.putAdditionalProperty(k, toJsonValue(v))
            }
            output.format(JsonOutputFormat.builder().schema(schema.build()).build())
        }
        return MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(request.maxTokens)
            .system(request.system)
            .addUserMessage(request.user)
            .outputConfig(output.build())
            .build()
    }

    private fun toJsonValue(e: JsonElement): JsonValue = when (e) {
        is JsonNull -> JsonValue.from(null)
        is JsonObject -> JsonValue.from(e.mapValues { toJsonValue(it.value) })
        is JsonArray -> JsonValue.from(e.map { toJsonValue(it) })
        is JsonPrimitive -> when {
            e.isString -> JsonValue.from(e.content)
            e.booleanOrNull != null -> JsonValue.from(e.booleanOrNull)
            e.longOrNull != null -> JsonValue.from(e.longOrNull)
            else -> JsonValue.from(e.content.toDouble())
        }
    }
}
