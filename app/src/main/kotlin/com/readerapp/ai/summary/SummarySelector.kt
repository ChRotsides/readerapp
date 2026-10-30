package com.readerapp.ai.summary

import com.readerapp.data.db.ArcSummary
import com.readerapp.data.db.IndexedSummary

/** The summaries that go into a Q&A prompt: compressed older arcs plus recent per-chapter summaries. */
data class SummarySelection(val arcs: List<ArcSummary>, val chapters: List<IndexedSummary>)

object ArcMath {
    const val ARC_SIZE = 50

    fun arcIndex(chapterIndex: Int): Int = Math.floorDiv(chapterIndex, ARC_SIZE)
    fun start(arcIndex: Int): Int = arcIndex * ARC_SIZE
    fun end(arcIndex: Int): Int = arcIndex * ARC_SIZE + ARC_SIZE - 1
}

object SummarySelector {
    const val RECENT_CHAPTERS = 50

    /**
     * Spoiler-safe selection for a reader at chapter index [currentIndex]: only chapters with index below it are ever
     * used. The most recent [RECENT_CHAPTERS] summarised chapters are included individually; older ones are covered
     * by their arc summary when one exists (arcs that overlap the recent window are not used, their chapters are
     * included individually instead) and individually otherwise.
     */
    fun select(arcs: List<ArcSummary>, summaries: List<IndexedSummary>, currentIndex: Int): SummarySelection {
        val prior = summaries.filter { it.chapterIndex < currentIndex }.sortedBy { it.chapterIndex }
        val recent = prior.takeLast(RECENT_CHAPTERS)
        val cutoff = recent.firstOrNull()?.chapterIndex ?: currentIndex
        val usableArcs = arcs.filter { it.endIndex < cutoff }.sortedBy { it.startIndex }
        val older = prior.dropLast(recent.size).filter { s -> usableArcs.none { s.chapterIndex in it.startIndex..it.endIndex } }
        return SummarySelection(usableArcs, older + recent)
    }
}
