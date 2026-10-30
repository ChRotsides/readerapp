package com.readerapp.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.readerapp.data.settings.SecureSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
        } catch (e: AnthropicServiceException) {
            // 4xx (bad request, auth, permission, not found, ...) will not succeed on retry. 529 overloaded is retryable.
            throw translate(e)
        } catch (e: AnthropicIoException) {
            throw translate(e)
        }

        val u = message.usage()
        val input = effectiveInputTokens(u.inputTokens(), u.cacheCreationInputTokens().orElse(0), u.cacheReadInputTokens().orElse(0))
        val output = u.outputTokens()
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

    /**
     * Streams the reply text. Usage is recorded when the stream ends (also when it fails after starting); a stop
     * reason of refusal / max_tokens is thrown after the text that was already produced has been emitted.
     */
    override fun stream(request: LlmRequest): Flow<String> = flow {
        val client = client()
        val params = buildParams(request)
        var input = 0L
        var output = 0L
        var stop: StopReason? = null
        var stopExplanation: String? = null
        try {
            val sr = try {
                withContext(Dispatchers.IO) { client.messages().createStreaming(params) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw translate(e)
            }
            sr.use { response ->
                val events = response.stream().iterator()
                while (withContext(Dispatchers.IO) { events.hasNext() }) {
                    val event = withContext(Dispatchers.IO) { events.next() }
                    event.messageStart().ifPresent { start ->
                        val u = start.message().usage()
                        input = effectiveInputTokens(u.inputTokens(), u.cacheCreationInputTokens().orElse(0), u.cacheReadInputTokens().orElse(0))
                    }
                    event.messageDelta().ifPresent { d ->
                        output = d.usage().outputTokens()
                        d.delta().stopReason().ifPresent { stop = it }
                        d.delta().stopDetails().ifPresent { stopExplanation = it.explanation().orElse(null) }
                    }
                    val text = event.contentBlockDelta().flatMap { it.delta().text() }.map { it.text() }.orElse(null)
                    if (!text.isNullOrEmpty()) emit(text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw translate(e)
        } finally {
            if (input > 0 || output > 0) {
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { usage.record(request.model, request.purpose, input, output) } }
            }
        }
        when (stop) {
            StopReason.REFUSAL -> throw LlmException.Refused(stopExplanation ?: "no details")
            StopReason.MAX_TOKENS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> throw LlmException.Truncated()
            else -> Unit
        }
    }.flowOn(Dispatchers.Default)

    private fun translate(e: Exception): Exception = when (e) {
        is RateLimitException -> LlmException.Retryable("Rate limited by the Anthropic API", e)
        is InternalServerException -> LlmException.Retryable("Anthropic API server error (${e.statusCode()})", e)
        is AnthropicIoException -> LlmException.Retryable("Network error talking to the Anthropic API", e)
        is AnthropicServiceException ->
            if (e.statusCode() == 529) LlmException.Retryable("Anthropic API overloaded", e)
            else LlmException.Rejected("Anthropic API error ${e.statusCode()}: ${e.message}", e)
        else -> e
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
        val builder = MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(request.maxTokens)
            .outputConfig(output.build())
        if (request.cacheSystem) {
            builder.systemOfTextBlockParams(listOf(textBlock(request.system, cache = true)))
        } else {
            builder.system(request.system)
        }
        // Conversation: [context (cached) + first question], assistant, question, ..., newest question.
        val questions = request.history.map { it.first } + request.user
        questions.forEachIndexed { i, q ->
            if (i == 0 && request.context != null) {
                builder.addUserMessageOfContentBlockParams(
                    listOf(
                        ContentBlockParam.ofText(textBlock(request.context, cache = true)),
                        ContentBlockParam.ofText(textBlock(q, cache = false)),
                    ),
                )
            } else {
                builder.addUserMessage(q)
            }
            request.history.getOrNull(i)?.let { builder.addAssistantMessage(it.second) }
        }
        if (request.serverFallback) {
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private fun textBlock(text: String, cache: Boolean): TextBlockParam {
        val b = TextBlockParam.builder().text(text)
        if (cache) b.cacheControl(CacheControlEphemeral.builder().build())
        return b.build()
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

    companion object {
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Cache writes cost 1.25x and cache reads 0.1x the input price; fold them into one input-token figure. */
        fun effectiveInputTokens(input: Long, cacheWrite: Long, cacheRead: Long): Long =
            input + Math.round(cacheWrite * 1.25 + cacheRead * 0.1)
    }
}
