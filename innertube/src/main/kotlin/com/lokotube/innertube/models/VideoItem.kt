package com.lokotube.innertube.models

import com.lokotube.innertube.utils.decodeHtmlEntities
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
            title = title?.runs?.firstOrNull()?.text.orEmpty().decodeHtmlEntities(),
            thumbnail = thumbnail?.thumbnails?.lastOrNull()?.url,
            channel = ownerText?.runs?.firstOrNull()?.text?.decodeHtmlEntities(),
            channelId = ownerText?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint?.browseId,
            duration = lengthText?.runs?.firstOrNull()?.text?.toDurationSeconds(),
            viewCountText = viewCountText?.runs?.firstOrNull()?.text,
            publishedText = publishedTimeText?.runs?.firstOrNull()?.text,
        )
}

/** "Up next" renderer from the WEB `next` response's secondary results. */
@Serializable
data class WatchLockupViewModel(
    val contentId: String? = null,
    val contentImage: ContentImage? = null,
    val metadata: Metadata? = null,
) {
    @Serializable
    data class ContentImage(
        val thumbnailViewModel: ThumbnailViewModel? = null,
    ) {
        @Serializable
        data class ThumbnailViewModel(
            val image: Image? = null,
            val overlays: List<Overlay>? = null,
        ) {
            @Serializable
            data class Image(
                val sources: List<Source>? = null,
            ) {
                @Serializable
                data class Source(val url: String? = null)
            }
        }

        @Serializable
        data class Overlay(
            val thumbnailBottomOverlayViewModel: ThumbnailBottomOverlayViewModel? = null,
        ) {
            @Serializable
            data class ThumbnailBottomOverlayViewModel(
                val badges: List<Badge>? = null,
            ) {
                @Serializable
                data class Badge(
                    val thumbnailBadgeViewModel: ThumbnailBadgeViewModel? = null,
                ) {
                    @Serializable
                    data class ThumbnailBadgeViewModel(
                        val text: String? = null,
                    )
                }
            }
        }
    }

    @Serializable
    data class Metadata(
        val lockupMetadataViewModel: LockupMetadataViewModel? = null,
    ) {
        @Serializable
        data class LockupMetadataViewModel(
            val title: Title? = null,
            val metadata: MetadataContent? = null,
        ) {
            @Serializable
            data class Title(val content: String? = null)

            @Serializable
            data class MetadataContent(val contentMetadataViewModel: ContentMetadataViewModel? = null) {
                @Serializable
                data class ContentMetadataViewModel(val metadataRows: List<MetadataRow>? = null) {
                    @Serializable
                    data class MetadataRow(val metadataParts: List<MetadataPart>? = null) {
                        @Serializable
                        data class MetadataPart(val text: Text? = null) {
                            @Serializable
                            data class Text(val content: String? = null)
                        }
                    }
                }
            }
        }
    }

    fun toVideoItem(): VideoItem {
        val rows = metadata?.lockupMetadataViewModel?.metadata?.contentMetadataViewModel?.metadataRows.orEmpty()
        val channel = rows.getOrNull(0)?.metadataParts?.firstOrNull()?.text?.content?.decodeHtmlEntities()
        val parts = rows.getOrNull(1)?.metadataParts.orEmpty()
        val duration = contentImage?.thumbnailViewModel?.overlays?.firstOrNull()?.thumbnailBottomOverlayViewModel?.badges?.firstOrNull()?.thumbnailBadgeViewModel?.text?.toDurationSeconds()
        return VideoItem(
            id = contentId ?: "",
            title = metadata?.lockupMetadataViewModel?.title?.content.orEmpty().decodeHtmlEntities(),
            thumbnail = contentImage?.thumbnailViewModel?.image?.sources?.lastOrNull()?.url,
            channel = channel,
            channelId = null,
            duration = duration,
            viewCountText = parts.getOrNull(0)?.text?.content,
            publishedText = parts.getOrNull(1)?.text?.content,
        )
    }
}

/** "3:33" or "1:02:03" -> seconds, or null when it isn't a duration. */
private fun String.toDurationSeconds(): Int? {
    val parts = trim().split(":")
    if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
    return parts.fold(0) { acc, part -> acc * 60 + part.toInt() }
}
