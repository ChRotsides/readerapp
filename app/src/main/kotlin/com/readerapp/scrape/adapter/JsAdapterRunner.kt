package com.readerapp.scrape.adapter

import com.readerapp.scrape.ExtractedChapter
import com.readerapp.scrape.ExtractionResult
import com.readerapp.scrape.FetchedPage
import com.readerapp.scrape.TextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Runs adapter JS in complete isolation and returns the raw JSON text the function produced.
 * Implementations must give the script a DOM of [html] and nothing else: no network, no bridges, no cookies.
 */
interface JsSandbox {
    /** [code] defines `function extract(document)`. Throws on timeout or script error. */
    suspend fun run(code: String, html: String, pageUrl: String): String
}

/** JS adapter: `extract(document)` returns `{title, paragraphs, nextUrl, prevUrl}`; anything else is rejected. */
class JsAdapterRunner(private val sandbox: JsSandbox, private val cleaner: TextCleaner = TextCleaner()) {

    suspend fun run(code: String, page: FetchedPage, extractorName: String): ExtractionResult {
        if (code.length > MAX_CODE_CHARS) return ExtractionResult.Failure("JS adapter too large")
        val raw = try {
            sandbox.run(code, page.html, page.url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExtractionResult.Failure("JS adapter failed: ${e.message}")
        }
        // Parsing and validating untrusted output never happens on the caller's (possibly main) thread.
        return withContext(Dispatchers.Default) { validate(raw, extractorName, page.url) }
    }

    internal fun validate(raw: String, extractorName: String, pageUrl: String): ExtractionResult {
        if (raw.length > MAX_RESULT_CHARS) return ExtractionResult.Failure("JS adapter result too large")
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrElse {
            return ExtractionResult.Failure("JS adapter output is not JSON")
        }
        val parsed = parseResult(element).getOrElse { return ExtractionResult.Failure(it.message ?: "Invalid JS adapter output") }
        // URLs are resolved against the real page here (the sandbox cannot) and must stay on the page's own site.
        val chapter = parsed.copy(
            nextUrl = AdapterUrls.sameSite(parsed.nextUrl, pageUrl),
            prevUrl = AdapterUrls.sameSite(parsed.prevUrl, pageUrl),
        )
        val paragraphs = cleaner.removeJunk(chapter.paragraphs)
        if (paragraphs.isEmpty()) return ExtractionResult.Failure("JS adapter returned no paragraphs")
        return ExtractionResult.Success(chapter.copy(paragraphs = paragraphs), extractor = extractorName)
    }

    companion object {
        const val MAX_CODE_CHARS = 60_000
        const val MAX_RESULT_CHARS = WebViewJsSandbox.MAX_RESULT_CHARS
        const val MAX_PARAGRAPHS = 20_000
        const val MAX_PARAGRAPH_CHARS = 20_000
        const val MAX_TITLE_CHARS = 500
        const val MAX_URL_CHARS = 2_048

        /** Strict schema check; the only way data produced by adapter JS enters the app. */
        fun parseResult(element: JsonElement): Result<ExtractedChapter> = runCatching {
            require(element is JsonObject) { "JS adapter output must be an object" }
            require(element.keys.all { it in ALLOWED_KEYS }) { "JS adapter output has unexpected keys" }
            val title = optionalString(element["title"], "title", MAX_TITLE_CHARS)
            val paras = element["paragraphs"]
            require(paras is JsonArray) { "paragraphs must be an array of strings" }
            require(paras.size <= MAX_PARAGRAPHS) { "too many paragraphs" }
            val paragraphs = paras.map { p ->
                require(p is JsonPrimitive && p.isString) { "paragraphs must be strings" }
                require(p.content.length <= MAX_PARAGRAPH_CHARS) { "paragraph too long" }
                p.content
            }
            ExtractedChapter(
                title = title,
                paragraphs = paragraphs,
                nextUrl = optionalUrl(element["nextUrl"], "nextUrl"),
                prevUrl = optionalUrl(element["prevUrl"], "prevUrl"),
            )
        }

        private val ALLOWED_KEYS = setOf("title", "paragraphs", "nextUrl", "prevUrl")

        private fun optionalString(e: JsonElement?, name: String, max: Int): String? {
            if (e == null || e is JsonNull) return null
            require(e is JsonPrimitive && e.isString) { "$name must be a string or null" }
            require(e.content.length <= max) { "$name too long" }
            return e.content.trim().takeIf { it.isNotEmpty() }
        }

        private fun optionalUrl(e: JsonElement?, name: String): String? {
            val s = optionalString(e, name, MAX_URL_CHARS) ?: return null
            // Absolute or relative (resolved against the page by the caller); scheme restrictions are enforced there.
            require(!s.startsWith("javascript:", ignoreCase = true)) { "$name must be an http(s) URL" }
            return s
        }
    }
}
