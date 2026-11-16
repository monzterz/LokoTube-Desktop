package com.lokotube.innertube.models

import kotlinx.serialization.Serializable

/** One SponsorBlock segment: a time range the community marked as skippable. */
@Serializable
data class SponsorSegment(
    /** `[start, end]` in seconds. */
    val segment: List<Float> = emptyList(),
    val category: String = "",
    val actionType: String = "",
) {
    val start: Float get() = segment.getOrNull(0) ?: 0f
    val end: Float get() = segment.getOrNull(1) ?: 0f

    /** Only "skip" segments are jumped over; "poi"/"highlight" are bookmarks, not skips. */
    val skippable: Boolean get() = actionType == "skip"

    fun contains(positionSeconds: Float): Boolean =
        skippable && positionSeconds >= start && positionSeconds < end
}

/** Categories the player jumps over. Highlights/points of interest are excluded on purpose. */
val SponsorBlockSkipCategories =
    listOf(
        "sponsor",
        "selfpromo",
        "interaction",
        "intro",
        "outro",
        "preview",
        "music_offtopic",
        "filler",
    )
