package com.readerapp.ai.process

import com.readerapp.data.db.AutoProcessMode
import com.readerapp.data.db.TextVariant
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** The paragraphs the reader is showing for a chapter, plus where they came from. */
data class DisplayedText(val chapterId: Long, val variant: TextVariant, val paragraphs: List<String>, val available: Set<TextVariant>)

/**
 * Single place that answers "which paragraphs does the reader show for this chapter?". The reader uses [observe];
 * the TTS milestone should use [observe] / [displayed] so it reads exactly what is on screen (Original, Fixed or
 * Translated) and sees live changes when a variant finishes processing.
 */
@Singleton
class ChapterTextResolver @Inject constructor(
    private val chapters: ChapterRepository,
    private val novels: NovelRepository,
) {
    /**
     * Live displayed text for [chapterId]. [selected] is the user's explicit variant choice (null = the novel's
     * preferred variant, else original). Falls back to the original paragraphs when a variant does not exist.
     */
    fun observe(chapterId: Long, selected: Flow<TextVariant?>): Flow<DisplayedText> {
        val mode = flow {
            val novelId = chapters.get(chapterId)?.novelId
            emit(novelId?.let { novels.get(it)?.autoProcess } ?: AutoProcessMode.OFF)
        }
        return combine(chapters.observeTexts(chapterId), selected, mode) { texts, sel, m ->
            val byVariant = texts.associateBy { it.variant }
            val variant = VariantChoice.pick(sel, byVariant.keys, m)
            DisplayedText(chapterId, variant, byVariant[variant]?.paragraphs.orEmpty(), byVariant.keys)
        }.distinctUntilChanged()
    }

    /** One-shot version of [observe] with the novel's default preference. */
    suspend fun displayed(chapterId: Long, selected: TextVariant? = null): DisplayedText {
        val texts = chapters.allTexts(chapterId).associateBy { it.variant }
        val mode = chapters.get(chapterId)?.let { novels.get(it.novelId)?.autoProcess } ?: AutoProcessMode.OFF
        val variant = VariantChoice.pick(selected, texts.keys, mode)
        return DisplayedText(chapterId, variant, texts[variant]?.paragraphs.orEmpty(), texts.keys)
    }

    /** Paragraphs of [variant], or the original when that variant does not exist. */
    suspend fun paragraphs(chapterId: Long, variant: TextVariant): List<String> =
        (chapters.getText(chapterId, variant) ?: chapters.getText(chapterId, TextVariant.ORIGINAL))?.paragraphs.orEmpty()

}
