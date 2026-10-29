package com.readerapp.scrape

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorChainTest {
    private val page = FetchedPage("https://x.example/1", "<html/>", FetchMode.HTTP)
    private fun ok(name: String) = object : ChapterExtractor {
        override suspend fun extract(page: FetchedPage) =
            ExtractionResult.Success(ExtractedChapter("t", listOf("p"), null, null), extractor = name)
    }
    private val fail = object : ChapterExtractor {
        override suspend fun extract(page: FetchedPage): ExtractionResult = ExtractionResult.Failure("nope")
    }
    private val boom = object : ChapterExtractor {
        override suspend fun extract(page: FetchedPage): ExtractionResult = error("exploded")
    }

    @Test
    fun fallsBackToNextExtractorOnFailureOrException() = runTest {
        val result = ExtractorChain(listOf(fail, boom, ok("generic"))).extract(page)
        assertEquals("generic", (result as ExtractionResult.Success).extractor)
    }

    @Test
    fun firstSuccessWins() = runTest {
        val result = ExtractorChain(listOf(ok("adapter"), ok("generic"))).extract(page)
        assertEquals("adapter", (result as ExtractionResult.Success).extractor)
    }

    @Test
    fun reportsFailureWhenAllFail() = runTest {
        assertTrue(ExtractorChain(listOf(fail)).extract(page) is ExtractionResult.Failure)
    }
}
