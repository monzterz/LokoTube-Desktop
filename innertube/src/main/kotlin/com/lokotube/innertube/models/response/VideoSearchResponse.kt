package com.lokotube.innertube.models.response

import com.lokotube.innertube.models.VideoRenderer
import kotlinx.serialization.Serializable

/** Standard YouTube search response (WEB client): `twoColumnSearchResultsRenderer`. */
@Serializable
data class VideoSearchResponse(
    val contents: Contents?,
) {
    @Serializable
    data class Contents(
        val twoColumnSearchResultsRenderer: TwoColumnSearchResultsRenderer?,
    )

    @Serializable
    data class TwoColumnSearchResultsRenderer(
        val primaryContents: PrimaryContents?,
    )

    @Serializable
    data class PrimaryContents(
        val sectionListRenderer: SectionListRenderer?,
    )

    @Serializable
    data class SectionListRenderer(
        val contents: List<Content>?,
    )

    @Serializable
    data class Content(
        val itemSectionRenderer: ItemSectionRenderer?,
    )

    @Serializable
    data class ItemSectionRenderer(
        val contents: List<Item>?,
    )

    @Serializable
    data class Item(
        val videoRenderer: VideoRenderer?,
    )
}
