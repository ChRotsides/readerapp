package com.readerapp.scrape.heal

import com.readerapp.data.settings.SecureSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Request/response mapping for TypeSafe AI's Jev "System One" endpoint, kept in one place.
 * Source: https://docs.typesafe.ai/api  (POST {base}/v1/systemone, `Authorization: Bearer <key>`,
 * body {model, state, questions}, a `choice` question answers with per-option `probabilities`).
 */
object JevApi {
    const val DEFAULT_BASE_URL = "https://api.typesafe.ai"
    const val DEFAULT_MODEL = "jev-latest"
    const val PATH = "/v1/systemone"
    const val QUESTION_ID = "verdict"

    private val CRITERIA = linkedMapOf(
        Verdict.VALID_CHAPTER to "The extracted text is the complete, correct body of a novel chapter (story prose), with no missing start or end and no site junk mixed in.",
        Verdict.TRUNCATED to "The extracted text is the beginning of a chapter that is cut off or only partially extracted, or is much shorter than the page's chapter.",
        Verdict.NAVIGATION_OR_JUNK to "The extracted text is mostly menus, links, ads, comments, footers, chapter lists or other site furniture rather than story prose.",
        Verdict.WRONG_CHAPTER to "The extracted text is story prose but from a different chapter or page than the one the URL/page title says.",
        Verdict.LOGIN_WALL to "The page asks the reader to log in or register before the chapter can be read.",
        Verdict.LOCKED_CHAPTER to "The chapter is locked behind payment, a subscription, VIP or coins; only a teaser or an unlock prompt is visible.",
        Verdict.BOT_CHECK to "The page is a bot or human-verification challenge (captcha, 'checking your browser', Cloudflare).",
        Verdict.ERROR_PAGE to "The page is an error page (404, 403, 5xx, maintenance, 'chapter not found').",
    )

    fun url(baseUrl: String?): String {
        val base = (baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE_URL).trimEnd('/')
        return if (base.endsWith(PATH)) base else base + PATH
    }

    fun requestBody(state: String, model: String = DEFAULT_MODEL): String = buildJsonObject {
        put("model", model)
        put("state", state)
        put(
            "questions",
            buildJsonObject {
                put(
                    QUESTION_ID,
                    buildJsonObject {
                        put("type", "choice")
                        put(
                            "instructions",
                            "The state describes a web page and the text an extraction program pulled out of it as the " +
                                "chapter of a web novel. Which single description fits best?",
                        )
                        put("criteria", buildJsonObject { CRITERIA.forEach { (k, v) -> put(k.name, v) } })
                    },
                )
            },
        )
    }.toString()

    /** Picks the most probable option from the `probabilities` map of the choice answer. */
    fun parseResponse(body: String): VerdictResult {
        val root = Json.parseToJsonElement(body).jsonObject
        val answer = root["answers"]?.jsonObject?.get(QUESTION_ID)?.jsonObject
            ?: error("response has no answers.$QUESTION_ID")
        val probs: JsonObject = answer["probabilities"]?.jsonObject ?: error("answer has no probabilities")
        val parsed = probs.mapNotNull { (k, v) ->
            val verdict = runCatching { Verdict.valueOf(k) }.getOrNull() ?: return@mapNotNull null
            val p = (v as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            verdict to p
        }
        val best = parsed.maxByOrNull { it.second } ?: error("no known verdict in probabilities")
        return VerdictResult(best.first, best.second.coerceIn(0.0, 1.0), "jev")
    }
}

/** Jev-backed verifier. Unavailable (so Haiku takes over) when no key is stored or the call fails. */
@Singleton
class JevVerifier @Inject constructor(private val settings: SecureSettings) : ExtractionVerifier {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun verify(input: VerificationInput): VerdictResult {
        val secrets = settings.current()
        val key = secrets.jevApiKey?.takeIf { it.isNotBlank() } ?: throw VerifierUnavailableException("Jev API key not set")
        val request = Request.Builder()
            .url(JevApi.url(secrets.jevBaseUrl))
            .header("Authorization", "Bearer $key")
            .post(JevApi.requestBody(VerifierText.render(input)).toRequestBody("application/json".toMediaType()))
            .build()
        try {
            return withTimeout(20_000) {
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        val body = response.body.string()
                        if (!response.isSuccessful) {
                            throw VerifierUnavailableException("Jev returned HTTP ${response.code}")
                        }
                        JevApi.parseResponse(body)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: VerifierUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw VerifierUnavailableException("Jev call failed: ${e.message}", e)
        }
    }
}
