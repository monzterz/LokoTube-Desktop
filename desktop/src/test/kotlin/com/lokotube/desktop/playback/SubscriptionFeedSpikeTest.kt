/** LOKOTUBE (C) 2026 — SPIKE (throwaway): feed langganan (FEsubscriptions) harus sukses, bukan error. */
package com.lokotube.desktop.playback

import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SubscriptionFeedSpikeTest {
    @Test
    fun feedAnswersWithoutSignIn() {
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            val feed = YouTube.subscriptionFeed()
            val items = feed.getOrNull()
            println("subscriptionFeed -> ${items?.size ?: "FAIL ${feed.exceptionOrNull()?.message}"} items")
            items?.take(6)?.forEach { v -> println("  - ${v.id} | ${v.channel} | ${v.title.take(60)} | ${v.publishedText}") }
            // Signed out this is an empty list, not an exception: the screen shows its sign-in line.
            check(items != null) { "feed langganan melempar error, bukan kosong" }
            check(items.all { it.id.isNotBlank() }) { "ada item tanpa id" }
        }
    }
}
