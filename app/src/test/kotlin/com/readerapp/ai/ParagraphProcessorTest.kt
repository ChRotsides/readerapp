package com.readerapp.ai

import com.readerapp.ai.process.FoundTerm
import com.readerapp.ai.process.ParagraphChunker
import com.readerapp.ai.process.ParagraphProcessor
import com.readerapp.ai.process.ProcessingException
import com.readerapp.ai.process.Script
import com.readerapp.ai.process.SourceLanguage
import com.readerapp.ai.process.TranslationSpec
import com.readerapp.data.db.GlossaryEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParagraphProcessorTest {
    private fun paragraphs(n: Int) = List(n) { "paragraph $it" }

    /** Pulls the indexed paragraphs back out of a request the way the model would see them. */
    private fun inputOf(req: LlmRequest): List<String> {
        val json = req.user.substringAfter("<paragraphs>").substringBefore("</paragraphs>").trim()
        return Json.parseToJsonElement(json).jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
    }

    private fun reply(texts: List<String>, terms: List<Triple<String, String, String>> = emptyList()): String =
        JsonObject(
            mapOf(
                "paragraphs" to JsonArray(texts.mapIndexed { i, t -> JsonObject(mapOf("index" to JsonPrimitive(i), "text" to JsonPrimitive(t))) }),
                "new_terms" to JsonArray(terms.map { (s, t, n) ->
                    JsonObject(mapOf("source" to JsonPrimitive(s), "target" to JsonPrimitive(t), "note" to JsonPrimitive(n)))
                }),
            ),
        ).toString()

    private class ScriptedLlm(private val handler: (Int, LlmRequest) -> String) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return LlmResponse(handler(requests.size, request), 10, 10)
        }
    }

    @Test
    fun `chunker respects paragraph and character budgets and keeps order`() {
        val chunks = ParagraphChunker.chunk(paragraphs(95))
        assertEquals(listOf(40, 40, 15), chunks.map { it.size })
        assertEquals(paragraphs(95), chunks.flatten())

        val big = List(6) { "x".repeat(5_000) }
        val byChars = ParagraphChunker.chunk(big, maxParagraphs = 40, maxChars = 12_000)
        assertEquals(listOf(2, 2, 2), byChars.map { it.size })
        // one oversized paragraph still gets its own chunk
        assertEquals(1, ParagraphChunker.chunk(listOf("y".repeat(50_000))).size)
        assertTrue(ParagraphChunker.chunk(emptyList()).isEmpty())
    }

    @Test
    fun `fix returns one output per input paragraph across chunks`() = runTest {
        val llm = ScriptedLlm { _, req -> reply(inputOf(req).map { it.uppercase() }) }
        val result = ParagraphProcessor(llm).fix(paragraphs(95))
        assertEquals(paragraphs(95).map { it.uppercase() }, result.paragraphs)
        assertEquals(3, llm.requests.size)
        assertEquals(0, result.failedChunks)
        val req = llm.requests.first()
        assertEquals(ClaudeModels.HAIKU, req.model)
        assertTrue("system block must be cached", req.cacheSystem)
        assertNotNull(req.jsonSchema)
        assertTrue(req.system.contains("grammar"))
    }

    @Test
    fun `count mismatch is retried once then succeeds`() = runTest {
        val llm = ScriptedLlm { call, req ->
            val input = inputOf(req)
            if (call == 1) reply(input.dropLast(1)) else reply(input.map { "$it!" })
        }
        val result = ParagraphProcessor(llm).fix(paragraphs(5))
        assertEquals(2, llm.requests.size)
        assertEquals(paragraphs(5).map { "$it!" }, result.paragraphs)
        assertFalse(result.partial)
    }

    @Test
    fun `chunk that never aligns keeps originals while other chunks are processed`() = runTest {
        // chunk 1 (40 paragraphs) always comes back one short; chunk 2 is fine
        val llm = ScriptedLlm { _, req ->
            val input = inputOf(req)
            if (input.first() == "paragraph 0") reply(input.dropLast(1)) else reply(input.map { "fixed $it" })
        }
        val result = ParagraphProcessor(llm).fix(paragraphs(50))
        assertEquals(50, result.paragraphs.size)
        assertEquals(paragraphs(40), result.paragraphs.take(40)) // originals kept
        assertEquals((40 until 50).map { "fixed paragraph $it" }, result.paragraphs.drop(40))
        assertEquals(1, result.failedChunks)
        assertTrue(result.partial)
        assertEquals(2 + 1, llm.requests.size) // failed chunk tried twice, good chunk once
    }

    @Test
    fun `nothing aligned at all is an error not a silent copy`() = runTest {
        val llm = ScriptedLlm { _, _ -> reply(listOf("only one")) }
        try {
            ParagraphProcessor(llm).fix(paragraphs(4))
            fail("expected ProcessingException")
        } catch (e: ProcessingException) {
            assertEquals(2, llm.requests.size)
        }
    }

    @Test
    fun `indices out of order are re-sorted and bad indices count as mismatch`() = runTest {
        val shuffled = JsonObject(
            mapOf("paragraphs" to JsonArray(listOf(1, 0).map { i ->
                JsonObject(mapOf("index" to JsonPrimitive(i), "text" to JsonPrimitive("t$i")))
            })),
        ).toString()
        val ok = ParagraphProcessor(ScriptedLlm { _, _ -> shuffled }).fix(paragraphs(2))
        assertEquals(listOf("t0", "t1"), ok.paragraphs)

        val dup = JsonObject(
            mapOf("paragraphs" to JsonArray(listOf(0, 0).map { i ->
                JsonObject(mapOf("index" to JsonPrimitive(i), "text" to JsonPrimitive("t")))
            })),
        ).toString()
        try {
            ParagraphProcessor(ScriptedLlm { _, _ -> dup }).fix(paragraphs(2))
            fail()
        } catch (_: ProcessingException) {
        }
    }

    @Test
    fun `translation injects the glossary into every request and collects new terms`() = runTest {
        val glossary = listOf(GlossaryEntry(novelId = 1, source = "李云", target = "Li Yun", userEdited = true))
        val llm = ScriptedLlm { call, req ->
            val input = inputOf(req)
            reply(input.map { "EN $it" }, if (call == 1) listOf(Triple("青云宗", "Azure Cloud Sect", "sect")) else emptyList())
        }
        val spec = TranslationSpec(SourceLanguage("Chinese", Script.CHINESE, true), "English", glossary, ClaudeModels.SONNET)
        val result = ParagraphProcessor(llm).translate(paragraphs(45), spec)

        assertEquals(2, llm.requests.size)
        llm.requests.forEach { r ->
            assertTrue(r.user.contains("李云 => Li Yun"))
            assertEquals(ClaudeModels.SONNET, r.model)
            assertTrue(r.system.contains("Chinese") && r.system.contains("English"))
            assertTrue(r.cacheSystem)
        }
        // terms found in chunk 1 are fed to chunk 2 for consistency within the chapter
        assertTrue(llm.requests[1].user.contains("青云宗 => Azure Cloud Sect"))
        assertEquals(listOf(FoundTerm("青云宗", "Azure Cloud Sect", "sect")), result.newTerms)
        assertEquals(45, result.paragraphs.size)
    }

    @Test
    fun `refusal keeps the original text of that chunk`() = runTest {
        val llm = object : LlmClient {
            var calls = 0
            override suspend fun complete(request: LlmRequest): LlmResponse {
                calls++
                if (calls == 1) throw LlmException.Refused("no")
                return LlmResponse(reply(inputOf(request).map { "ok $it" }), 1, 1)
            }
        }
        val result = ParagraphProcessor(llm).fix(paragraphs(45))
        assertEquals(paragraphs(40), result.paragraphs.take(40))
        assertEquals(1, result.failedChunks)
    }
}
