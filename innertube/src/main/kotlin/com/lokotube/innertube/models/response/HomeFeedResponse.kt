package com.lokotube.innertube.models.response

import com.lokotube.innertube.models.Runs
import com.lokotube.innertube.models.Thumbnails
import com.lokotube.innertube.models.VideoItem
import com.lokotube.innertube.models.VideoRenderer
import com.lokotube.innertube.utils.decodeHtmlEntities
import kotlinx.serialization.Serializable

/**
 * Standard YouTube home browse response (WEB client, `FEwhat_to_watch`).
 * The home grid nests video/shorts items under `richItemRenderer` (old) or `lockupViewModel` (new).
 */
@Serializable
data class HomeFeedResponse(
    val contents: Contents?,
) {
    @Serializable
    data class Contents(
        val twoColumnBrowseResultsRenderer: TwoColumnBrowseResultsRenderer?,
    )

    @Serializable
    data class TwoColumnBrowseResultsRenderer(
        val tabs: List<Tab>?,
    )

    @Serializable
    data class Tab(
        val tabRenderer: TabRenderer?,
    )

    @Serializable
    data class TabRenderer(
        val content: Content?,
    )

    @Serializable
    data class Content(
        val richGridRenderer: RichGridRenderer?,
    )

    @Serializable
    data class RichGridRenderer(
        val contents: List<Item>?,
    )

    @Serializable
    data class Item(
        val richItemRenderer: RichItemRenderer? = null,
        val richSectionRenderer: RichSectionRenderer? = null,
    )

    @Serializable
    data class RichItemRenderer(
        val content: ItemContent?,
    )

    @Serializable
    data class ItemContent(
        val videoRenderer: VideoRenderer? = null,
        val reelItemRenderer: ReelItemRenderer? = null,
        val lockupViewModel: LockupViewModel? = null,
    )

    @Serializable
    data class RichSectionRenderer(
        val content: SectionContent?,
    )

    @Serializable
    data class SectionContent(
        val richShelfRenderer: RichShelfRenderer?,
    )

    @Serializable
    data class RichShelfRenderer(
        val content: ShelfContent? = null,
        val contents: List<Item>? = null,
    )

    @Serializable
    data class ShelfContent(
        val reelShelfRenderer: ReelShelfRenderer?,
    )

    @Serializable
    data class ReelShelfRenderer(
        val items: List<ReelItem>?,
    )

    @Serializable
    data class ReelItem(
        val reelItemRenderer: ReelItemRenderer?,
    )
}

/** A Shorts item in the home feed. */
@Serializable
data class ReelItemRenderer(
    val videoId: String,
    val headline: Runs?,
    val thumbnail: Thumbnails?,
    val navigationEndpoint: com.lokotube.innertube.models.NavigationEndpoint?,
) {
    val title: String? get() = headline?.runs?.firstOrNull()?.text?.decodeHtmlEntities()
    val thumbnailUrl: String? get() = thumbnail?.thumbnails?.lastOrNull()?.url
}

/**
 * The newer "lockup view model" renderer YouTube migrated to on web. Minimal: enough to read the
 * video id, title and thumbnail.
 */
@Serializable
data class LockupViewModel(
    val contentId: String?,
    val metadata: Metadata?,
) {
    @Serializable
    data class Metadata(
        val lockupMetadataViewModel: LockupMetadataViewModel?,
    )

    @Serializable
    data class LockupMetadataViewModel(
        val title: Title?,
        val image: Image?,
    )

    @Serializable
    data class Title(
        val content: String?,
    )

    @Serializable
    data class Image(
        val decorations: List<Decoration>?,
    )

    @Serializable
    data class Decoration(
        val lockedThumbnailViewModel: LockedThumbnailViewModel?,
    )

    @Serializable
    data class LockedThumbnailViewModel(
        val image: ThumbnailImage?,
    )

    @Serializable
    data class ThumbnailImage(
        val sources: List<Source>?,
    )

    @Serializable
    data class Source(
        val url: String?,
    )
}

fun ReelItemRenderer.toVideoItem() =
    VideoItem(
        id = videoId,
        title = title.orEmpty(),
        thumbnail = thumbnailUrl,
        channel = null,
        channelId = null,
        duration = null,
        viewCountText = null,
        publishedText = null,
    )

fun LockupViewModel.toVideoItem() =
    VideoItem(
        id = contentId ?: "",
        title = metadata?.lockupMetadataViewModel?.title?.content.orEmpty().decodeHtmlEntities(),
        thumbnail =
            metadata
                ?.lockupMetadataViewModel
                ?.image
                ?.decorations
                ?.firstOrNull()
                ?.lockedThumbnailViewModel
                ?.image
                ?.sources
                ?.lastOrNull()
                ?.url,
        channel = null,
        channelId = null,
        duration = null,
        viewCountText = null,
        publishedText = null,
    )

/** Flattens every video in the grid, including those nested in shelves (e.g. the Live destination). */
fun HomeFeedResponse.RichGridRenderer.allVideos(): List<VideoItem> {
    val result = mutableListOf<VideoItem>()
    contents.orEmpty().forEach { item ->
        item.richItemRenderer?.content?.let { c ->
            c.videoRenderer?.let { result += it.toVideoItem() }
            c.lockupViewModel?.let { result += it.toVideoItem() }
        }
        item.richSectionRenderer?.content?.richShelfRenderer?.contents.orEmpty().forEach { shelfItem ->
            shelfItem.richItemRenderer?.content?.let { c ->
                c.videoRenderer?.let { result += it.toVideoItem() }
                c.lockupViewModel?.let { result += it.toVideoItem() }
            }
        }
    }
    return result.filter { it.id.isNotBlank() }.distinctBy { it.id }
}
