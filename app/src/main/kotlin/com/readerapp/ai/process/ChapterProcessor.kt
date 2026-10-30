package com.readerapp.ai.process

import com.readerapp.ai.ClaudeModels
import com.readerapp.ai.LlmException
import com.readerapp.ai.SpendGuard
import com.readerapp.data.db.ChapterText
import com.readerapp.data.db.TextVariant
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.GlossaryRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.data.settings.AiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ProcessOutcome {
    /** [partial]: some paragraph groups could not be aligned and kept their original text. */
    data class Done(val variant: TextVariant, val partial: Boolean) : ProcessOutcome
    data object AlreadyDone : ProcessOutcome
    data object NoOriginal : ProcessOutcome
    /** Translation requested but the text already looks like the target language. */
    data class NotNeeded(val reason: String) : ProcessOutcome
    data object CapReached : ProcessOutcome
    data object NoApiKey : ProcessOutcome
    /** Temporary problem (rate limit, network): try again later. */
    data class Retry(val reason: String) : ProcessOutcome
    data class Failed(val reason: String) : ProcessOutcome
}

/**
 * Produces the FIXED / TRANSLATED variant of one chapter and stores it. Translation of a novel's chapters is
 * serialized (one at a time per novel) so every chapter sees the glossary the previous ones grew.
 */
@Singleton
class ChapterProcessor @Inject constructor(
    private val chapters: ChapterRepository,
    private val novels: NovelRepository,
    private val glossary: GlossaryRepository,
    private val processor: ParagraphProcessor,
    private val aiSettings: AiSettings,
    private val spend: SpendGuard,
) {
    private val translateLocks = ConcurrentHashMap<Long, Mutex>()
    private val chapterLocks = ConcurrentHashMap<Pair<Long, TextVariant>, Mutex>()

    suspend fun process(chapterId: Long, variant: TextVariant, force: Boolean = false): ProcessOutcome {
        require(variant != TextVariant.ORIGINAL) { "ORIGINAL is not produced by processing" }
        // One job per (chapter, variant): the reader and the background queue may ask for the same chapter at once.
        return chapterLocks.getOrPut(chapterId to variant) { Mutex() }.withLock { processLocked(chapterId, variant, force) }
    }

    private suspend fun processLocked(chapterId: Long, variant: TextVariant, force: Boolean): ProcessOutcome {
        val chapter = chapters.get(chapterId) ?: return ProcessOutcome.Failed("Chapter no longer exists")
        if (!force && chapters.getText(chapterId, variant) != null) return ProcessOutcome.AlreadyDone
        val original = chapters.getText(chapterId, TextVariant.ORIGINAL)?.paragraphs?.takeIf { it.isNotEmpty() }
            ?: return ProcessOutcome.NoOriginal
        if (spend.capReached()) return ProcessOutcome.CapReached

        return try {
            if (variant == TextVariant.FIXED) fix(chapterId, original) else translate(chapter.novelId, chapterId, original, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException.SpendCapReached) {
            ProcessOutcome.CapReached
        } catch (e: LlmException.NotConfigured) {
            ProcessOutcome.NoApiKey
        } catch (e: LlmException.Retryable) {
            ProcessOutcome.Retry(e.message ?: "temporary API error")
        } catch (e: LlmException) {
            ProcessOutcome.Failed(e.message ?: "AI request failed")
        } catch (e: ProcessingException) {
            ProcessOutcome.Failed(e.message ?: "processing failed")
        }
    }

    private val sourceChanged = ProcessOutcome.Retry("the chapter text changed while it was being processed")

    private suspend fun fix(chapterId: Long, original: List<String>): ProcessOutcome {
        val revision = SourceRevision.of(original)
        val result = processor.fix(original, ClaudeModels.HAIKU)
        // Committed only if the ORIGINAL is still the text this result was derived from (checked in the transaction).
        val ok = chapters.commitDerived(chapterId, revision, ChapterText(chapterId, TextVariant.FIXED, result.paragraphs, ClaudeModels.HAIKU))
        return if (ok) ProcessOutcome.Done(TextVariant.FIXED, result.partial) else sourceChanged
    }

    private suspend fun translate(novelId: Long, chapterId: Long, original: List<String>, force: Boolean): ProcessOutcome =
        translateLocks.getOrPut(novelId) { Mutex() }.withLock {
            // Re-check inside the lock: an earlier queued job may just have produced it.
            if (!force && chapters.getText(chapterId, TextVariant.TRANSLATED) != null) return@withLock ProcessOutcome.AlreadyDone
            val novel = novels.get(novelId) ?: return@withLock ProcessOutcome.Failed("Novel no longer exists")
            val target = novel.targetLanguage?.takeIf { it.isNotBlank() } ?: aiSettings.currentTargetLanguage()
            val source = LanguageDetector.detect(novel.language, original)
            if (!LanguageDetector.needsTranslation(source, target)) {
                return@withLock ProcessOutcome.NotNeeded(
                    "This text already looks like $target. If that is wrong, set the novel's source language.",
                )
            }
            val model = if (novel.translateWithSonnet) ClaudeModels.SONNET else ClaudeModels.HAIKU
            val revision = SourceRevision.of(original)
            val existing = glossary.list(novelId)
            val result = processor.translate(original, TranslationSpec(source, target, existing, model))
            // One transaction: source-revision check, the translated variant and the glossary merge against a FRESH
            // read of the glossary (so concurrent manual edits survive and a crash cannot leave terms behind).
            val ok = chapters.commitDerived(
                chapterId, revision, ChapterText(chapterId, TextVariant.TRANSLATED, result.paragraphs, model),
                glossaryNovelId = novelId,
            ) { fresh -> GlossaryMerger.merge(novelId, fresh, result.newTerms) }
            if (ok) ProcessOutcome.Done(TextVariant.TRANSLATED, result.partial) else sourceChanged
        }
}
