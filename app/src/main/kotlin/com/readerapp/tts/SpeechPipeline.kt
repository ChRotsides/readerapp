package com.readerapp.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Position of a sentence: chapter, paragraph index in the displayed variant, sentence index in the paragraph. */
data class SpeechRef(val chapterId: Long, val paragraph: Int, val sentence: Int)

data class SpokenSentence(val ref: SpeechRef, val text: String)

/** A chapter's speakable sentences, in order, and the text variant they came from. */
data class SpeechChapter(
    val chapterId: Long,
    val chapterTitle: String,
    val novelTitle: String,
    /** Name of the TextVariant that was resolved (part of the audio cache key). */
    val variant: String,
    val sentences: List<SpokenSentence>,
)

sealed interface ChapterLoad {
    data class Ready(val chapter: SpeechChapter) : ChapterLoad

    /** The chapter can't be read now (not English, locked, fetch failed...). */
    data class Unavailable(val reason: String) : ChapterLoad
}

interface SpeechSource {
    suspend fun load(chapterId: Long): ChapterLoad

    /** The chapter after [chapterId], or null at the end of the book. */
    suspend fun nextChapterId(chapterId: Long): Long?
}

/** A generated sentence ready to play. */
data class ReadyItem(
    val ref: SpeechRef,
    val file: File,
    val durationMs: Long,
    val text: String,
    val chapterTitle: String,
    val novelTitle: String,
    val variant: String,
    /** Last sentence of its chapter (used by the "end of chapter" sleep timer and chapter progress). */
    val lastInChapter: Boolean,
)

/** The playlist the audio is appended to (the Media3 player in the service). */
interface PlaylistSink {
    suspend fun append(item: ReadyItem)

    suspend fun clear()
}

/**
 * Bounded mirror of the player's playlist: items already played are dropped from the head once more than
 * [keepBehind] of them sit behind the playhead. [trimmed] counts the dropped ones, so `trimmed + index` is the
 * playhead's absolute position in the generation order (what [SpeechPipeline.playhead] expects).
 */
class PlaylistWindow(private val keepBehind: Int) {
    val items = ArrayList<ReadyItem>()
    var trimmed = 0
        private set

    fun append(item: ReadyItem) { items += item }

    fun clear() { items.clear(); trimmed = 0 }

    fun absolute(index: Int): Int = trimmed + index

    /** How many head items may be dropped now that item [current] is playing. */
    fun excess(current: Int): Int = (current - keepBehind).coerceAtLeast(0)

    /** Drops the first [n] items and returns them. */
    fun drop(n: Int): List<ReadyItem> {
        val k = n.coerceIn(0, items.size)
        val gone = items.subList(0, k).toList()
        items.subList(0, k).clear()
        trimmed += k
        return gone
    }

    /** True when something before the window's first item was dropped (going back needs regeneration). */
    val hasTrimmedHead: Boolean get() = trimmed > 0
}

sealed interface PipelineEvent {
    /** Nothing more will be generated: end of the book, or the next chapter is unavailable ([reason] != null). */
    data class Finished(val reason: String?) : PipelineEvent

    /** The first chapter could not be loaded at all. */
    data class StartFailed(val reason: String) : PipelineEvent
}

/**
 * Generates speech ahead of the playhead. Sentences are synthesized in order (through the cache), appended to the
 * [sink], and generation pauses once [lookahead] sentences are queued beyond the playhead. When a chapter's
 * sentences run out it continues into the next chapter.
 *
 * Android-free: the engine, cache and text source are injected.
 */
class SpeechPipeline(
    private val scope: CoroutineScope,
    private val source: SpeechSource,
    private val engine: suspend () -> TtsEngine,
    private val cache: AudioCache,
    private val sink: PlaylistSink,
    private val lookahead: Int = TtsConfig.LOOKAHEAD_SENTENCES,
    private val evictEvery: Int = 10,
) {
    private var job: Job? = null
    private val playIndex = MutableStateFlow(0)
    private val _generated = MutableStateFlow(0)
    private val _done = MutableStateFlow(true)
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 8)
    private val recent = ArrayDeque<String>()

    /** Sentences appended to the sink since the last [start]. */
    val generated: StateFlow<Int> = _generated.asStateFlow()

    /** True when generation has finished (or never started): no more items will be appended. */
    val done: StateFlow<Boolean> = _done.asStateFlow()
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    /**
     * True when playback has consumed everything generated so far but more is still coming: the player should
     * wait (buffer) instead of ending.
     */
    fun isStarved(playheadIndex: Int): Boolean = playheadIndex >= _generated.value && !_done.value

    /** Tells the pipeline which playlist item is playing so it can keep [lookahead] sentences ahead. */
    fun playhead(index: Int) {
        playIndex.value = index
    }

    /** (Re)starts generation at [from] (sentence-level) using [voice] and [speed]. Clears the sink first. */
    fun start(from: SpeechRef, voice: Int, speed: Float) {
        job?.cancel()
        val previous = job
        _done.value = false
        job = scope.launch {
            previous?.join()
            sink.clear()
            playIndex.value = 0
            _generated.value = 0
            _done.value = false
            recent.clear()
            try {
                run(from, voice, speed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _done.value = true
                _events.emit(PipelineEvent.Finished(e.message ?: "Speech generation failed"))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _done.value = true
    }

    private suspend fun run(from: SpeechRef, voice: Int, speed: Float) {
        var chapterId = from.chapterId
        var first = true
        while (true) {
            val chapter = when (val load = source.load(chapterId)) {
                is ChapterLoad.Ready -> load.chapter
                is ChapterLoad.Unavailable -> {
                    _done.value = true
                    _events.emit(if (first) PipelineEvent.StartFailed(load.reason) else PipelineEvent.Finished(load.reason))
                    return
                }
            }
            val sentences = if (first) {
                chapter.sentences.filter { (it.ref.paragraph to it.ref.sentence) >= (from.paragraph to from.sentence) }
            } else chapter.sentences
            first = false
            for ((i, s) in sentences.withIndex()) {
                // Throttle: stay at most `lookahead` sentences ahead of what is playing.
                playIndex.first { _generated.value - it < lookahead }
                val key = AudioKey(chapter.chapterId, chapter.variant, voice, speed, s.ref.paragraph, s.ref.sentence, AudioKey.hash(s.text))
                val file = cache.get(key) ?: withContext(Dispatchers.IO) {
                    val audio = engine().synthesize(s.text, voice, speed)
                    cache.put(key) { WavWriter.write(it, audio.samples, audio.sampleRate) }
                }
                val duration = withContext(Dispatchers.IO) { WavWriter.durationMs(file) }
                recent.addLast(file.name)
                while (recent.size > RECENT_PROTECTED) recent.removeFirst()
                sink.append(ReadyItem(s.ref, file, duration, s.text, chapter.chapterTitle, chapter.novelTitle, chapter.variant, i == sentences.lastIndex))
                _generated.value += 1
                if (_generated.value % evictEvery == 0) {
                    val protectedNames = recent.toSet()
                    withContext(Dispatchers.IO) { cache.evict(protectedNames) }
                }
            }
            chapterId = source.nextChapterId(chapter.chapterId) ?: run {
                _done.value = true
                _events.emit(PipelineEvent.Finished(null))
                return
            }
        }
    }

    private companion object {
        const val RECENT_PROTECTED = 64
    }
}

private operator fun Pair<Int, Int>.compareTo(other: Pair<Int, Int>): Int =
    if (first != other.first) first.compareTo(other.first) else second.compareTo(other.second)
