/**
 * LOKOTUBE (C) 2026 — SPIKE (throwaway): feed Shorts untuk guest + playback headless satu Short.
 */
package com.lokotube.desktop.playback

import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.YouTubeLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ShortsFeedSpikeTest {
    @Test
    fun feedIsNotEmptyForGuest() {
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            val started = System.currentTimeMillis()
            val feed = YouTube.shortsFeed()
            val items = feed.getOrElse {
                it.printStackTrace()
                emptyList()
            }
            println("shortsFeed -> ${items.size} items in ${System.currentTimeMillis() - started} ms")
            items.take(8).forEachIndexed { i, v ->
                println("  [$i] id=${v.id} views=${v.viewCountText} title=${v.title.take(60)} thumb=${v.thumbnail?.take(70)}")
            }
            println("unique ids  -> ${items.map { it.id }.distinct().size}")
            check(items.size >= 20) { "feed Shorts kosong/terlalu sedikit: ${items.size}" }
            check(items.all { it.id.isNotBlank() }) { "ada item tanpa id" }
            check(items.all { !it.thumbnail.isNullOrBlank() }) { "ada item tanpa thumbnail" }
            check(items.none { it.title.contains("&#") }) { "judul masih mengandung HTML entity mentah" }
        }
    }

    /** Prefetch: the next Short is resolved in the background, so switching to it skips extraction. */
    @Test
    fun prefetchedShortStartsFaster() {
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            val feed = YouTube.shortsFeed().getOrThrow()
            val (a, b, c) = Triple(feed[0], feed[1], feed[2])
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val player = VideoPlayer(scope)

            suspend fun timeToFirstFrame(videoId: String): Long {
                val start = System.currentTimeMillis()
                player.play(videoId, height = 720)
                while (System.currentTimeMillis() - start < 90_000) {
                    if (player.frame.value != null && player.state.value.videoId == videoId) return System.currentTimeMillis() - start
                    if (player.state.value.error != null) return -1
                    delay(20)
                }
                return -1
            }

            try {
                println("warm-up ${a.id}: ${timeToFirstFrame(a.id)} ms")
                player.prefetch(b.id, height = 720)
                delay(4_000)
                val warm = timeToFirstFrame(b.id)
                val cold = timeToFirstFrame(c.id)
                println("prefetched ${b.id}: $warm ms")
                println("cold      ${c.id}: $cold ms")
                check(warm > 0 && cold > 0) { "frame tidak muncul (warm=$warm cold=$cold)" }
                check(warm <= cold + 500) { "prefetch jauh lebih lambat (warm=$warm cold=$cold)" }
            } finally {
                player.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun firstShortPlaysHeadless() {
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            val first = YouTube.shortsFeed().getOrThrow().first()
            println("PLAY short ${first.id} — ${first.title.take(70)}")

            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val player = VideoPlayer(scope)
            try {
                player.play(first.id, height = 720)
                var frames = 0
                var firstSize: Pair<Int, Int>? = null
                val deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline) {
                    val err = player.state.value.error
                    if (err != null) {
                        println("ERROR STATE: $err")
                        break
                    }
                    val f = player.frame.value
                    if (f != null) {
                        if (frames == 0) {
                            firstSize = f.width to f.height
                            println("FIRST FRAME ${f.width}x${f.height} at pos=${player.position.value}")
                        }
                        frames++
                        if (frames >= 15) break
                    }
                    delay(150)
                }
                val st = player.state.value
                println("position    : ${player.position.value} ms")
                println("durationMs  : ${st.durationMs}")
                println("height      : ${st.height}")
                println("frames      : $frames")
                check(frames > 0) { "Short tidak menghasilkan frame" }
                check((firstSize?.first ?: 0) > 0) { "ukuran frame tidak valid" }
                check(st.durationMs > 0) { "durationMs kosong" }
            } finally {
                player.stop()
                scope.cancel()
            }
        }
    }
}
