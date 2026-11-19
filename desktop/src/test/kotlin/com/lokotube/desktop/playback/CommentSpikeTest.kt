/** LOKOTUBE (C) 2026 — SPIKE (throwaway): parser komentar + status subscribe pada video nyata. */
package com.lokotube.desktop.playback

import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CommentSpikeTest {
    @Test
    fun commentsAndSubscribeState() {
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            val info = YouTube.watchInfo("dQw4w9WgXcQ").getOrThrow()
            println("channel=${info.channelName} id=${info.channelId} subs=${info.subscriberText} subscribed=${info.subscribed}")
            println("token=${info.commentsToken?.take(40)}…")
            println("views=${info.viewCount} date=${info.publishedDate} description=${info.description?.length} chars")
            println("description head: ${info.description?.take(120)?.replace("\n", " / ")}")
            check(!info.description.isNullOrBlank()) { "deskripsi kosong" }
            check(info.description!!.length > 100) { "deskripsi terpotong (${info.description!!.length} chars)" }
            check(!info.viewCount.isNullOrBlank()) { "view count kosong" }
            check(info.channelId != null) { "channelId kosong" }
            check(!info.commentsToken.isNullOrBlank()) { "token komentar kosong" }

            val page1 = YouTube.comments(info.commentsToken!!).getOrThrow()
            println("page1: count=${page1.countText} comments=${page1.comments.size} next=${page1.continuation?.take(20)}…")
            page1.comments.take(5).forEach { c ->
                println("  - ${c.author} (creator=${c.byCreator}) ${c.publishedTime} likes=${c.likes} replies=${c.replies} :: ${c.text.take(70)}")
            }
            check(page1.comments.isNotEmpty()) { "komentar kosong" }
            check(page1.comments.all { it.text.isNotBlank() }) { "ada komentar tanpa teks" }
            check(page1.comments.all { it.author.isNotBlank() }) { "ada komentar tanpa penulis" }

            val page2 = page1.continuation?.let { YouTube.comments(it).getOrNull() }
            println("page2: comments=${page2?.comments?.size} next=${page2?.continuation?.take(20)}…")
            check(page2 != null && page2.comments.isNotEmpty()) { "halaman kedua komentar tidak termuat" }
            check(page2.comments.first().id != page1.comments.first().id) { "halaman kedua mengulang halaman pertama" }
        }
    }
}
