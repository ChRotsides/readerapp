package com.readerapp.scrape.heal

import com.readerapp.ai.ClaudeModels
import com.readerapp.ai.ModelPricing
import com.readerapp.data.db.AdapterKind
import com.readerapp.scrape.adapter.AdapterTag
import com.readerapp.scrape.adapter.JsAdapterRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

class MiscHealTest {
    // ---- Jev mapping
    @Test fun jevRequestShape() {
        val body = Json.parseToJsonElement(JevApi.requestBody("state text")).jsonObject
        assertEquals("jev-latest", body["model"]!!.jsonPrimitive.content)
        assertEquals("state text", body["state"]!!.jsonPrimitive.content)
        val q = body["questions"]!!.jsonObject[JevApi.QUESTION_ID]!!.jsonObject
        assertEquals("choice", q["type"]!!.jsonPrimitive.content)
        assertEquals(Verdict.entries.map { it.name }.toSet(), q["criteria"]!!.jsonObject.keys)
    }

    @Test fun jevUrlJoin() {
        assertEquals("https://api.typesafe.ai/v1/systemone", JevApi.url(null))
        assertEquals("https://api.typesafe.ai/v1/systemone", JevApi.url(" "))
        assertEquals("https://x.test/v1/systemone", JevApi.url("https://x.test/"))
        assertEquals("https://x.test/v1/systemone", JevApi.url("https://x.test/v1/systemone"))
    }

    @Test fun jevResponsePicksMostProbableOption() {
        val r = JevApi.parseResponse(
            """{"model":"jev-latest","answers":{"verdict":{"type":"choice","choice":"TRUNCATED","confidence":0.7,
              "probabilities":{"VALID_CHAPTER":0.1,"TRUNCATED":0.82,"BOT_CHECK":0.08}}},"usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        assertEquals(Verdict.TRUNCATED, r.verdict)
        assertEquals(0.82, r.probability, 1e-9)
        assertEquals("jev", r.source)
        try { JevApi.parseResponse("""{"answers":{}}"""); fail() } catch (_: Exception) {}
    }

    // ---- JS output validation (sandbox result is untrusted)
    private fun parse(json: String) = JsAdapterRunner.parseResult(Json.parseToJsonElement(json))

    @Test fun jsResultSchema() {
        val ok = parse("""{"title":"T","paragraphs":["a","b"],"nextUrl":"https://x.test/2","prevUrl":null}""").getOrThrow()
        assertEquals(listOf("a", "b"), ok.paragraphs)
        assertEquals("https://x.test/2", ok.nextUrl)
        assertTrue(parse("""[1,2]""").isFailure)
        assertTrue(parse("""{"title":"T","paragraphs":"nope"}""").isFailure)
        assertTrue(parse("""{"title":"T","paragraphs":[1]}""").isFailure)
        assertTrue(parse("""{"paragraphs":[],"extra":1}""").isFailure)
        assertTrue(parse("""{"paragraphs":["a"],"nextUrl":"javascript:alert(1)"}""").isFailure)
        assertTrue(parse("""{"paragraphs":["a"],"title":5}""").isFailure)
        assertNull(parse("""{"paragraphs":["a"]}""").getOrThrow().title)
    }

    @Test fun jsRunnerRejectsNonJsonAndEmptyOutput() {
        val sandbox = object : com.readerapp.scrape.adapter.JsSandbox {
            var out = "garbage"
            override suspend fun run(code: String, html: String, pageUrl: String) = out
        }
        val runner = JsAdapterRunner(sandbox)
        assertTrue(runner.validate("garbage", "t", "https://site.example/ch") is com.readerapp.scrape.ExtractionResult.Failure)
        assertTrue(runner.validate("""{"paragraphs":[]}""", "t", "https://site.example/ch") is com.readerapp.scrape.ExtractionResult.Failure)
        assertTrue(runner.validate("""{"title":"x","paragraphs":["real text"]}""", "t", "https://site.example/ch") is com.readerapp.scrape.ExtractionResult.Success)
    }

    // ---- writer parsing
    private val writer = AdapterWriter { error("not called") }

    @Test fun writerParsesJsonAdapter() {
        val spec = """{\"chapter\":{\"content\":\"#c\",\"title\":null,\"paragraphs\":\"p\",\"remove\":[],\"next\":null,\"prev\":null},\"cleanup\":{\"dropLines\":[]}}"""
        val c = writer.parse("""{"kind":"json","spec_json":"$spec","js_code":"","notes":"x"}""")
        assertEquals(AdapterKind.JSON, c.kind)
        assertTrue(c.code.contains("\"#c\""))
    }

    @Test fun writerRejectsBadSelectorsAndMalformedReplies() {
        val bad = """{\"chapter\":{\"content\":\"div[\"}}"""
        for (reply in listOf(
            """{"kind":"json","spec_json":"$bad","js_code":"","notes":""}""",
            """{"kind":"js","spec_json":"","js_code":"","notes":""}""",
            """{"kind":"xml","spec_json":"","js_code":"","notes":""}""",
            "plain text",
        )) {
            try { writer.parse(reply); fail("accepted: $reply") } catch (_: AdapterParseException) {}
        }
    }

    // ---- misc
    @Test fun adapterTagRoundTrip() {
        assertEquals("site.example" to 3, AdapterTag.parse(AdapterTag.of("site.example", 3)))
        assertNull(AdapterTag.parse("generic"))
    }

    @Test fun pricingAndMonthStart() {
        // 1M in + 1M out
        assertEquals(600_000L, ModelPricing.costMicros(ClaudeModels.HAIKU, 1_000_000, 1_000_000))
        assertEquals(12_000_000L, ModelPricing.costMicros(ClaudeModels.SONNET, 1_000_000, 1_000_000))
        assertEquals(0.6, ModelPricing.microsToUsd(600_000), 1e-9)
        val zone = ZoneId.of("UTC")
        val mid = java.time.ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val start = java.time.ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(start, ModelPricing.monthStartMillis(mid, zone))
        assertNotNull(ClaudeModels.SONNET)
    }
}
