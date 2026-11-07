package com.readerapp.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server-side crawl jobs (`/crawl-jobs`) and the library-chapter upload (`/library/chapters`). */

@Serializable
data class CrawlRequest(
    val url: String,
    /** "novel" (this link's novel) or "site" (everything the link leads to). */
    val scope: String = SCOPE_NOVEL,
    /** null = all chapters. Sent as an explicit JSON null. */
    @SerialName("chapters_per_novel") val chaptersPerNovel: Int? = null,
) {
    companion object {
        const val SCOPE_NOVEL = "novel"
        const val SCOPE_SITE = "site"
    }
}

@Serializable
data class CrawlJob(
    val id: Long,
    val url: String = "",
    val scope: String = CrawlRequest.SCOPE_NOVEL,
    @SerialName("chapters_per_novel") val chaptersPerNovel: Int? = null,
    val status: String = "queued",
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("pages_fetched") val pagesFetched: Int = 0,
    @SerialName("novels_found") val novelsFound: Int = 0,
    @SerialName("chapters_embedded") val chaptersEmbedded: Int = 0,
    @SerialName("chapters_skipped") val chaptersSkipped: Int = 0,
    @SerialName("spend_usd") val spendUsd: Double = 0.0,
    val error: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    val canPause: Boolean get() = status == "queued" || status == "running"
    val canResume: Boolean get() = status == "paused" || status == "blocked" || status == "failed" ||
        (status == "done" && stopReason == "legacy_completion")
    val canCancel: Boolean get() = status == "queued" || status == "running" || status == "paused" || status == "blocked"
}

@Serializable
data class LibraryNovelPayload(
    val title: String,
    val author: String? = null,
    val synopsis: String? = null,
    val url: String,
    val domain: String,
)

@Serializable
data class LibraryChapterPayload(
    val index: Int,
    val title: String? = null,
    val url: String? = null,
    val text: String,
)

@Serializable
data class LibraryUploadRequest(val novel: LibraryNovelPayload, val chapters: List<LibraryChapterPayload>) {
    companion object {
        /** The server accepts at most this many chapters per call. */
        const val MAX_CHAPTERS = 50
    }
}

@Serializable
data class LibraryUploadResult(val embedded: Int = 0, val skipped: Int = 0)
