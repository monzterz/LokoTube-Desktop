package com.lokotube.innertube.pages

import com.lokotube.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
