/**
 * LOKOTUBE (C) 2026 — SPIKE: verify home feed endpoints (guest = empty, no crash).
 */
package com.lokotube.desktop.playback

import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Test

class HomeFeedSpikeTest {
    @Test
    fun inspectHomeFeed() {
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            val videos = YouTube.homeVideos()
            println("homeVideos -> ${videos.getOrNull()?.size ?: "FAIL ${videos.exceptionOrNull()?.message}"}")
            val shorts = YouTube.homeShorts()
            println("homeShorts -> ${shorts.getOrNull()?.size ?: "FAIL ${shorts.exceptionOrNull()?.message}"}")
        }
    }
}
