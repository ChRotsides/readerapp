package com.readerapp.tts

import com.readerapp.ai.process.ChapterTextResolver
import com.readerapp.ai.process.LanguageDetector
import com.readerapp.ai.process.Script
import com.readerapp.data.db.TextVariant
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.scrape.ChapterFetcher
import com.readerapp.scrape.FetchOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the pipeline with exactly the text the reader shows: [ChapterTextResolver.displayed] with the variant the
 * user picked ([selected]; null = the novel's preferred one). Chapters not yet downloaded are fetched first.
 */
@Singleton
class ChapterSpeechSource @Inject constructor(
    private val chapters: ChapterRepository,
    private val novels: NovelRepository,
    private val resolver: ChapterTextResolver,
    private val fetcher: ChapterFetcher,
) : SpeechSource {
    @Volatile var selected: TextVariant? = null

    override suspend fun load(chapterId: Long): ChapterLoad {
        val chapter = chapters.get(chapterId) ?: return ChapterLoad.Unavailable("Chapter not found")
        if (chapters.getText(chapterId) == null) {
            when (val outcome = fetcher.ensureFetched(chapterId)) {
                is FetchOutcome.Success -> Unit
                is FetchOutcome.Locked -> return ChapterLoad.Unavailable("The next chapter is locked: ${outcome.reason}")
                is FetchOutcome.NeedsBrowser -> return ChapterLoad.Unavailable(outcome.reason)
                is FetchOutcome.Failed -> return ChapterLoad.Unavailable("Couldn't load the next chapter: ${outcome.reason}")
            }
        }
        val text = resolver.displayed(chapterId, selected)
        if (text.paragraphs.isEmpty()) return ChapterLoad.Unavailable("This chapter has no text")
        val novel = novels.get(chapter.novelId)
        if (!isEnglish(novel?.language, text.variant, text.paragraphs)) return ChapterLoad.Unavailable(TtsConfig.NOT_ENGLISH_MESSAGE)

        val sentences = buildList {
            text.paragraphs.forEachIndexed { p, para ->
                SentenceSplitter.split(para).forEachIndexed { s, span -> add(SpokenSentence(SpeechRef(chapterId, p, s), span.text)) }
            }
        }
        val title = chapter.title.ifBlank { "Chapter ${chapter.index}" }
        return ChapterLoad.Ready(SpeechChapter(chapterId, title, novel?.title.orEmpty(), text.variant.name, sentences))
    }

    override suspend fun nextChapterId(chapterId: Long): Long? = chapters.get(chapterId)?.let { chapters.next(it)?.id }

    companion object {
        /** Kokoro (English model) only reads Latin-script English; anything else would be read as garbage. */
        fun isEnglish(novelLanguage: String?, variant: TextVariant, paragraphs: List<String>): Boolean {
            val script = LanguageDetector.detectScript(paragraphs.joinToString("\n"))
            if (script != Script.LATIN && script != Script.UNKNOWN) return false
            // Untranslated text of a novel declared as another (Latin-script) language is not English either.
            val declared = novelLanguage?.takeIf { it.isNotBlank() }?.let(LanguageDetector::languageName)
            return !(variant == TextVariant.ORIGINAL && declared != null && !declared.equals("English", ignoreCase = true))
        }
    }
}
