package com.readerapp.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** JSON shapes of `/favorites` (server/readerserver/api/favorites.py). Times are ISO-8601 instants. */

/** One favorite; the same shape is sent by `PUT /favorites` and returned by both routes (`updated_at` is server-only). */
@Serializable
data class FavoriteDto(
    val key: String,
    @SerialName("work_id") val workId: String? = null,
    @SerialName("novel_url") val novelUrl: String? = null,
    val title: String,
    val author: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val domain: String? = null,
    /** False = un-starred (a tombstone). */
    val favorite: Boolean = true,
    @SerialName("changed_at") val changedAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class FavoritesResponse(
    val favorites: List<FavoriteDto> = emptyList(),
    /** Pass back as `since` on the next pull. */
    @SerialName("server_time") val serverTime: String,
)
