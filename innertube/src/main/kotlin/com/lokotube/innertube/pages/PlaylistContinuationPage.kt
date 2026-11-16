package com.lokotube.innertube.pages

import com.lokotube.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
