package com.readerapp.ai

import com.readerapp.ai.process.FoundTerm
import com.readerapp.ai.process.GlossaryMerger
import com.readerapp.ai.process.LanguageDetector
import com.readerapp.ai.process.Script
import com.readerapp.ai.process.VariantChoice
import com.readerapp.data.db.AutoProcessMode
import com.readerapp.data.db.GlossaryEntry
import com.readerapp.data.db.TextVariant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GlossaryMergerTest {
    @Test
    fun `new terms are added and user edited entries are never overwritten`() {
        val existing = listOf(
            GlossaryEntry(1, 7, "李云", "Li Yun", "protagonist", userEdited = true),
            GlossaryEntry(2, 7, "青云宗", "Azure Cloud Sect", null, userEdited = false),
        )
        val out = GlossaryMerger.merge(
            7, existing,
            listOf(
                FoundTerm("李云", "Lee Yun", "other note"),       // user-edited: untouched
                FoundTerm("青云宗", "Green Cloud Sect", "sect"),   // auto entry: target kept, missing note filled
                FoundTerm("天城", "Sky City", "city"),            // new
                FoundTerm(" ", "x", null),                        // blank source ignored
            ),
        )
        assertEquals(listOf("青云宗", "天城"), out.map { it.source })
        val sect = out.first { it.source == "青云宗" }
        assertEquals("Azure Cloud Sect", sect.target)
        assertEquals("sect", sect.note)
        assertEquals(2L, sect.id) // updates in place
        assertEquals(0L, out.first { it.source == "天城" }.id)
        assertTrue(out.none { it.source == "李云" })
    }

    @Test
    fun `duplicates within one reply are added once`() {
        val out = GlossaryMerger.merge(1, emptyList(), listOf(FoundTerm("A", "a", null), FoundTerm("a", "b", null)))
        assertEquals(1, out.size)
        assertEquals("a", out[0].target)
    }
}

class LanguageDetectorTest {
    @Test
    fun `script heuristic tells CJK Hangul Kana and Latin apart`() {
        assertEquals(Script.CHINESE, LanguageDetector.detect(null, listOf("他走进了房间，看见桌子上放着一封信。她没有说话。")).script)
        assertEquals(Script.JAPANESE, LanguageDetector.detect(null, listOf("彼は部屋に入って、机の上の手紙を見つけました。")).script)
        assertEquals(Script.KOREAN, LanguageDetector.detect(null, listOf("그는 방에 들어가서 책상 위에 있는 편지를 보았다.")).script)
        assertEquals(Script.LATIN, LanguageDetector.detect(null, listOf("He walked into the room and saw a letter on the desk.")).script)
        assertEquals(Script.UNKNOWN, LanguageDetector.detect(null, listOf("...")).script)
    }

    @Test
    fun `declared language wins over the heuristic`() {
        val d = LanguageDetector.detect("ko", listOf("He walked into the room and saw a letter on the desk."))
        assertEquals("Korean", d.name)
        assertTrue(d.declared)
        assertEquals("Chinese", LanguageDetector.detect("zh-CN", emptyList()).name)
    }

    @Test
    fun `needsTranslation is false for text already in the target`() {
        val en = LanguageDetector.detect(null, listOf("He walked into the room and saw a letter on the desk."))
        assertFalse(LanguageDetector.needsTranslation(en, "English"))
        assertTrue(LanguageDetector.needsTranslation(en, "Spanish"))
        val zh = LanguageDetector.detect(null, listOf("他走进了房间，看见桌子上放着一封信。她没有说话。"))
        assertTrue(LanguageDetector.needsTranslation(zh, "English"))
        assertFalse(LanguageDetector.needsTranslation(zh, "chinese"))
        val declaredEnglish = LanguageDetector.detect("English", emptyList())
        assertFalse(LanguageDetector.needsTranslation(declaredEnglish, "en"))
    }
}

class VariantChoiceTest {
    @Test
    fun `picks selected then preferred then original`() {
        val all = setOf(TextVariant.ORIGINAL, TextVariant.FIXED, TextVariant.TRANSLATED)
        assertEquals(TextVariant.FIXED, VariantChoice.pick(TextVariant.FIXED, all, AutoProcessMode.TRANSLATE))
        assertEquals(TextVariant.TRANSLATED, VariantChoice.pick(null, all, AutoProcessMode.TRANSLATE))
        assertEquals(TextVariant.FIXED, VariantChoice.pick(null, setOf(TextVariant.ORIGINAL, TextVariant.FIXED), AutoProcessMode.FIX))
        assertEquals(TextVariant.ORIGINAL, VariantChoice.pick(null, setOf(TextVariant.ORIGINAL), AutoProcessMode.TRANSLATE))
        // explicit choice that does not exist (yet) shows the original
        assertEquals(TextVariant.ORIGINAL, VariantChoice.pick(TextVariant.TRANSLATED, setOf(TextVariant.ORIGINAL, TextVariant.FIXED), AutoProcessMode.FIX))
        assertEquals(TextVariant.ORIGINAL, VariantChoice.pick(null, all, AutoProcessMode.OFF))
    }
}

class SpendCapTest {
    private class CountingLlm : LlmClient {
        var calls = 0
        override suspend fun complete(request: LlmRequest): LlmResponse { calls++; return LlmResponse("ok", 1, 1) }
    }

    private val request = LlmRequest(ClaudeModels.HAIKU, "s", "u", LlmEffort.LOW, purpose = "t")

    @Test
    fun `guard blocks calls and streams once the cap is reached`() = runTest {
        val inner = CountingLlm()
        val capped = GuardedLlmClient(inner, SpendGuard { true })
        try { capped.complete(request); fail() } catch (_: LlmException.SpendCapReached) {}
        try { capped.stream(request).toList(); fail() } catch (_: LlmException.SpendCapReached) {}
        assertEquals(0, inner.calls)

        val open = GuardedLlmClient(inner, SpendGuard { false })
        assertEquals("ok", open.complete(request).text)
        assertEquals(listOf("ok"), open.stream(request).toList())
        assertEquals(2, inner.calls)
    }
}
