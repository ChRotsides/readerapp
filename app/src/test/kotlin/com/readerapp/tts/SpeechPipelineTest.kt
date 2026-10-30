package com.readerapp.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

private class FakeEngine(private val gate: Semaphore? = null) : TtsEngine {
    val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val calls = AtomicInteger()

    override suspend fun synthesize(text: String, speaker: Int, speed: Float): PcmAudio {
        gate?.acquire()
        spoken += text
        calls.incrementAndGet()
        return PcmAudio(FloatArray(2_400), 24_000)
    }

    override fun release() = Unit
}

private class FakeSource(val chapters: Map<Long, List<List<String>>>, val order: List<Long>, val unavailable: Set<Long> = emptySet()) : SpeechSource {
    override suspend fun load(chapterId: Long): ChapterLoad {
        if (chapterId in unavailable) return ChapterLoad.Unavailable("nope $chapterId")
        val paragraphs = chapters.getValue(chapterId)
        val sentences = paragraphs.flatMapIndexed { p, ss -> ss.mapIndexed { s, t -> SpokenSentence(SpeechRef(chapterId, p, s), t) } }
        return ChapterLoad.Ready(SpeechChapter(chapterId, "Chapter $chapterId", "Novel", "ORIGINAL", sentences))
    }

    override suspend fun nextChapterId(chapterId: Long): Long? = order.getOrNull(order.indexOf(chapterId) + 1)
}

private class RecordingSink : PlaylistSink {
    val items: MutableList<ReadyItem> = Collections.synchronizedList(mutableListOf())
    val clears = AtomicInteger()

    override suspend fun append(item: ReadyItem) { items += item }

    override suspend fun clear() { items.clear(); clears.incrementAndGet() }
}

class SpeechPipelineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var cache: AudioCache

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        cache = AudioCache(tmp.newFolder(), 1L shl 30)
    }

    @After fun tearDown() = scope.cancel()

    private val book = mapOf(
        1L to listOf(listOf("a1", "a2"), listOf("b1")),
        2L to listOf(listOf("c1", "c2")),
        3L to listOf(listOf("d1")),
    )

    private fun refs(sink: RecordingSink) = sink.items.map { Triple(it.ref.chapterId, it.ref.paragraph, it.ref.sentence) }

    /** Starts [p] and returns the first event it emits (subscribing before the start so none is missed). */
    private fun firstEvent(p: SpeechPipeline, from: SpeechRef): PipelineEvent = runBlocking {
        val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { p.events.first() } }
        p.start(from, 1, 1f)
        event.await()
    }

    private fun until(timeoutMs: Long = 5_000, cond: () -> Boolean) = runBlocking {
        withTimeout(timeoutMs) { while (!cond()) delay(5) }
    }

    @Test
    fun generatesInOrderAndRollsIntoNextChapter() {
        val engine = FakeEngine()
        val sink = RecordingSink()
        val p = SpeechPipeline(scope, FakeSource(book, listOf(1, 2, 3)), { engine }, cache, sink, lookahead = 100)
        p.start(SpeechRef(1, 0, 0), voice = 1, speed = 1f)
        until { p.done.value }
        assertEquals(
            listOf(Triple(1L, 0, 0), Triple(1L, 0, 1), Triple(1L, 1, 0), Triple(2L, 0, 0), Triple(2L, 0, 1), Triple(3L, 0, 0)),
            refs(sink),
        )
        assertEquals(listOf("a1", "a2", "b1", "c1", "c2", "d1"), engine.spoken)
        assertEquals(listOf(false, false, true, false, true, true), sink.items.map { it.lastInChapter })
        assertEquals(6, p.generated.value)
        assertEquals("Chapter 2", sink.items[3].chapterTitle)
        assertTrue(sink.items.all { it.durationMs == 100L })
    }

    @Test
    fun startsMidChapterAndEmitsFinishedAtEndOfBook() {
        val sink = RecordingSink()
        val p = SpeechPipeline(scope, FakeSource(book, listOf(1, 2)), { FakeEngine() }, cache, sink, lookahead = 100)
        val event = firstEvent(p, SpeechRef(1, 1, 0))
        assertEquals(PipelineEvent.Finished(null), event)
        assertEquals(listOf(Triple(1L, 1, 0), Triple(2L, 0, 0), Triple(2L, 0, 1)), refs(sink))
    }

    @Test
    fun staysAtMostLookaheadAheadOfThePlayhead() {
        val engine = FakeEngine()
        val sink = RecordingSink()
        val many = mapOf(1L to listOf((1..10).map { "s$it" }))
        val p = SpeechPipeline(scope, FakeSource(many, listOf(1)), { engine }, cache, sink, lookahead = 3)
        p.start(SpeechRef(1, 0, 0), 1, 1f)
        until { p.generated.value == 3 }
        runBlocking { delay(150) }
        assertEquals(3, engine.calls.get()) // throttled: nothing beyond playhead(0) + 3
        assertFalse(p.done.value)

        p.playhead(2) // two sentences consumed -> two more may be generated
        until { p.generated.value == 5 }
        runBlocking { delay(150) }
        assertEquals(5, engine.calls.get())

        p.playhead(10)
        until { p.done.value }
        assertEquals(10, sink.items.size)
    }

    @Test
    fun reportsStarvedWhenPlaybackCatchesUpWithGeneration() {
        val gate = Semaphore(1) // one sentence at a time
        val engine = FakeEngine(gate)
        val sink = RecordingSink()
        val p = SpeechPipeline(scope, FakeSource(mapOf(1L to listOf(listOf("x", "y"))), listOf(1)), { engine }, cache, sink, lookahead = 10)
        p.start(SpeechRef(1, 0, 0), 1, 1f)
        until { p.generated.value == 1 }
        assertFalse(p.isStarved(0)) // item 0 is available
        assertTrue(p.isStarved(1)) // playhead moved past everything generated and "y" is still being made
        gate.release()
        until { p.done.value }
        assertFalse(p.isStarved(2)) // finished: not starved, just the end
    }

    @Test
    fun cachedSentencesSkipTheEngine() {
        val first = FakeEngine()
        val s1 = RecordingSink()
        val p1 = SpeechPipeline(scope, FakeSource(book, listOf(1)), { first }, cache, s1, lookahead = 100)
        p1.start(SpeechRef(1, 0, 0), 1, 1f)
        until { p1.done.value }
        assertEquals(3, first.calls.get())

        val second = FakeEngine()
        val s2 = RecordingSink()
        val p2 = SpeechPipeline(scope, FakeSource(book, listOf(1)), { second }, cache, s2, lookahead = 100)
        p2.start(SpeechRef(1, 0, 0), 1, 1f)
        until { p2.done.value }
        assertEquals(0, second.calls.get())
        assertEquals(3, s2.items.size)

        // A different voice or speed is a different cache entry.
        val third = FakeEngine()
        val p3 = SpeechPipeline(scope, FakeSource(book, listOf(1)), { third }, cache, RecordingSink(), lookahead = 100)
        p3.start(SpeechRef(1, 0, 0), 2, 1.25f)
        until { p3.done.value }
        assertEquals(3, third.calls.get())
    }

    @Test
    fun restartClearsTheSinkAndRestartsFromTheNewPosition() {
        val sink = RecordingSink()
        val p = SpeechPipeline(scope, FakeSource(book, listOf(1, 2, 3)), { FakeEngine() }, cache, sink, lookahead = 100)
        p.start(SpeechRef(1, 0, 0), 1, 1f)
        until { p.done.value }
        p.start(SpeechRef(2, 0, 1), 1, 1f)
        until { p.done.value && p.generated.value == 2 && sink.items.size == 2 }
        assertEquals(listOf(Triple(2L, 0, 1), Triple(3L, 0, 0)), refs(sink))
        assertEquals(2, sink.clears.get())
    }

    @Test
    fun unavailableNextChapterEndsGenerationWithReason() {
        val sink = RecordingSink()
        val p = SpeechPipeline(scope, FakeSource(book, listOf(1, 2, 3), unavailable = setOf(2)), { FakeEngine() }, cache, sink, lookahead = 100)
        val event = firstEvent(p, SpeechRef(1, 0, 0))
        assertEquals(PipelineEvent.Finished("nope 2"), event)
        assertEquals(3, sink.items.size)
        assertTrue(p.done.value)
    }

    @Test
    fun unavailableFirstChapterIsAStartFailure() {
        val p = SpeechPipeline(scope, FakeSource(book, listOf(1), unavailable = setOf(1)), { FakeEngine() }, cache, RecordingSink())
        val event = firstEvent(p, SpeechRef(1, 0, 0))
        assertEquals(PipelineEvent.StartFailed("nope 1"), event)
    }
}
