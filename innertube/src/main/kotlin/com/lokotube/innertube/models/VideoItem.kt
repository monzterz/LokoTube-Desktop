package com.lokotube.innertube.models

import kotlinx.serialization.Serializable

/** A regular YouTube video (not a music item). Standalone — kept out of [YTItem] so the music
 * `when`-branches stay exhaustive. */
data class VideoItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val channel: String?,
    val channelId: String?,
    val duration: Int?,
    val viewCountText: String?,
    val publishedText: String?,
)

/** The metadata for one video, enough to show its page header. */
data class VideoDetail(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val durationSeconds: Long,
    val viewCount: String?,
)

/** Standard YouTube search result renderer (WEB client). */
@Serializable
data class VideoRenderer(
    val videoId: String,
    val title: Runs?,
    val thumbnail: Thumbnails?,
    val ownerText: Runs?,
    val publishedTimeText: Runs?,
    val lengthText: Runs?,
    val viewCountText: Runs?,
    val longBylineText: Runs?,
    val shortBylineText: Runs?,
) {
    fun toVideoItem() =
        VideoItem(
            id = videoId,
            title = title?.runs?.firstOrNull()?.text.orEmpty(),
            thumbnail = thumbnail?.thumbnails?.lastOrNull()?.url,
            channel = ownerText?.runs?.firstOrNull()?.text,
            channelId = ownerText?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint?.browseId,
            duration = lengthText?.runs?.firstOrNull()?.text?.toDurationSeconds(),
            viewCountText = viewCountText?.runs?.firstOrNull()?.text,
            publishedText = publishedTimeText?.runs?.firstOrNull()?.text,
        )
}

/** "3:33" or "1:02:03" -> seconds, or null when it isn't a duration. */
private fun String.toDurationSeconds(): Int? {
    val parts = trim().split(":")
    if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
    return parts.fold(0) { acc, part -> acc * 60 + part.toInt() }
}
