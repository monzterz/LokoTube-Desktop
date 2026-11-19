package com.lokotube.desktop.playback

import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ShelfCountSpikeTest {
    @Test
    fun counts() {
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            println("live=${YouTube.liveVideos().getOrNull()?.size}")
            println("trending=${YouTube.trendingVideos().getOrNull()?.size}")
            println("recommended=${YouTube.recommendedVideos().getOrNull()?.size}")
        }
    }
}
