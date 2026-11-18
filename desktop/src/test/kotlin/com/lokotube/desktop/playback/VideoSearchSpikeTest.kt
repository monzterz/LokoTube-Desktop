/**
 * LOKOTUBE (C) 2026 — SPIKE (throwaway): verifikasi endpoint video Fase 4.
 * searchVideos (WEB) + videoDetail (player WEB). Bukan bagian rilis.
 */
package com.lokotube.desktop.playback

import com.lokotube.innertube.YouTube
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VideoSearchSpikeTest {
    @Test
    fun searchAndDetail() {
        runBlocking {
            println("=== searchVideos(\"lofi girl\") ===")
            YouTube.searchVideos("lofi girl").onSuccess { videos ->
                println("count=${videos.size}")
                videos.take(6).forEach { v ->
                    println("  ${v.id} | ${v.title} | ch=${v.channel} (${v.channelId}) | ${v.duration}s | ${v.viewCountText} | ${v.publishedText}")
                }
                check(videos.isNotEmpty()) { "tidak ada video hasil pencarian" }
            }.onFailure { e ->
                println("SEARCH FAILED: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                throw e
            }

            println("=== videoDetail(\"dQw4w9WgXcQ\") ===")
            YouTube.videoDetail("dQw4w9WgXcQ").onSuccess { d ->
                println("  ${d.id} | ${d.title} | ${d.channel} (${d.channelId}) | ${d.durationSeconds}s | ${d.viewCount} views")
                check(d.title.isNotBlank()) { "title kosong" }
                check(d.channelId.startsWith("UC")) { "channelId bukan UC: ${d.channelId}" }
            }.onFailure { e ->
                println("DETAIL FAILED: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }
}
