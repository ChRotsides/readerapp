package com.readerapp.ai.qa

import com.readerapp.ai.LlmClient
import com.readerapp.ai.LlmException
import com.readerapp.ai.process.ChapterTextResolver
import com.readerapp.ai.summary.SummarySelector
import com.readerapp.ai.summary.SummaryService
import com.readerapp.data.db.TextVariant
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.data.repo.SummaryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Highlight Q&A: spoiler-safe context assembly and streaming answers (Sonnet). */
@Singleton
class QaService @Inject constructor(
    private val chapters: ChapterRepository,
    private val novels: NovelRepository,
    private val summaries: SummaryRepository,
    private val summaryService: SummaryService,
    private val resolver: ChapterTextResolver,
    private val llm: LlmClient,
) {
    /**
     * Builds the stable context block for [chapterId]: novel info, summaries of earlier chapters only (missing recent
     * ones are written first, lazily), and the paragraphs of the variant being shown.
     * @throws LlmException when summary generation hits the spend cap / missing key.
     */
    suspend fun buildContext(chapterId: Long, shown: TextVariant, paragraphs: List<String>): String {
        val chapter = chapters.get(chapterId) ?: throw IllegalStateException("Chapter not found")
        val novel = novels.get(chapter.novelId) ?: throw IllegalStateException("Novel not found")
        summaryService.ensureRecent(novel.id, chapter.index)
        val selection = SummarySelector.select(
            summaries.arcs(novel.id), summaries.before(novel.id, chapter.index), chapter.index,
        )
        return QaPromptBuilder.contextBlock(
            QaContext(
                novelTitle = novel.title,
                synopsis = novel.synopsis,
                summaries = selection,
                chapterTitle = chapter.title,
                variantLabel = shown.name.lowercase(),
                paragraphs = paragraphs.ifEmpty { resolver.paragraphs(chapterId, shown) },
            ),
        )
    }

    /** Streams the answer to [userText] (see [QaPromptBuilder.questionText]) given the thread so far. */
    fun ask(context: String, userText: String, history: List<QaTurn>): Flow<String> =
        llm.stream(QaPromptBuilder.request(context, userText, history))
}
