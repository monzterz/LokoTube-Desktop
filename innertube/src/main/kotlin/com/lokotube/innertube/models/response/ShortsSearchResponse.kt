package com.lokotube.innertube.models.response

import com.lokotube.innertube.models.VideoItem
import com.lokotube.innertube.utils.decodeHtmlEntities
import kotlinx.serialization.Serializable

/**
 * A WEB search response filtered to Shorts (`params=EgZzaG9ydHM%3D`). Shorts come back as a
 * `gridShelfViewModel` whose items are `shortsLockupViewModel`s — vertical thumbnails, the title and
 * view count in `overlayMetadata`, and the video id inside the `reelWatchEndpoint` tap command.
 * Works for guests (no login, no PoToken).
 */
@Serializable
data class ShortsSearchResponse(
    val contents: Contents? = null,
) {
    @Serializable
    data class Contents(
        val twoColumnSearchResultsRenderer: TwoColumnSearchResultsRenderer? = null,
    )

    @Serializable
    data class TwoColumnSearchResultsRenderer(
        val primaryContents: PrimaryContents? = null,
    )

    @Serializable
    data class PrimaryContents(
        val sectionListRenderer: SectionListRenderer? = null,
    )

    @Serializable
    data class SectionListRenderer(
        val contents: List<Section>? = null,
    )

    @Serializable
    data class Section(
        val itemSectionRenderer: ItemSectionRenderer? = null,
    )

    @Serializable
    data class ItemSectionRenderer(
        val contents: List<Item>? = null,
    )

    @Serializable
    data class Item(
        val gridShelfViewModel: GridShelfViewModel? = null,
    )

    @Serializable
    data class GridShelfViewModel(
        val contents: List<GridItem>? = null,
    )

    @Serializable
    data class GridItem(
        val shortsLockupViewModel: ShortsLockupViewModel? = null,
    )
}

/** One tile in the search results' Shorts shelf. */
@Serializable
data class ShortsLockupViewModel(
    val entityId: String? = null,
    val accessibilityText: String? = null,
    val onTap: OnTap? = null,
    val overlayMetadata: OverlayMetadata? = null,
    val thumbnailViewModel: ThumbnailViewModel? = null,
) {
    @Serializable
    data class OnTap(
        val innertubeCommand: InnertubeCommand? = null,
    )

    @Serializable
    data class InnertubeCommand(
        val reelWatchEndpoint: ReelWatchEndpoint? = null,
    )

    @Serializable
    data class ReelWatchEndpoint(
        val videoId: String? = null,
        val thumbnail: Thumbnails? = null,
    )

    @Serializable
    data class Thumbnails(
        val thumbnails: List<Thumbnail>? = null,
    )

    @Serializable
    data class Thumbnail(
        val url: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    @Serializable
    data class OverlayMetadata(
        val primaryText: Text? = null,
        val secondaryText: Text? = null,
    )

    @Serializable
    data class Text(
        val content: String? = null,
    )

    @Serializable
    data class ThumbnailViewModel(
        val image: Image? = null,
    )

    @Serializable
    data class Image(
        val sources: List<Source>? = null,
    )

    @Serializable
    data class Source(
        val url: String? = null,
    )
}

fun ShortsLockupViewModel.toVideoItem(): VideoItem =
    VideoItem(
        id = onTap?.innertubeCommand?.reelWatchEndpoint?.videoId
            ?: entityId?.removePrefix("shorts-shelf-item-").orEmpty(),
        title = overlayMetadata?.primaryText?.content.orEmpty().decodeHtmlEntities(),
        thumbnail = thumbnailViewModel?.image?.sources?.lastOrNull()?.url
            ?: onTap?.innertubeCommand?.reelWatchEndpoint?.thumbnail?.thumbnails?.lastOrNull()?.url,
        channel = null,
        channelId = null,
        duration = null,
        viewCountText = overlayMetadata?.secondaryText?.content,
        publishedText = null,
    )
