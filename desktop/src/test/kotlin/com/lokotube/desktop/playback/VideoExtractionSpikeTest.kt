/**
 * LOKOTUBE (C) 2026 — SPIKE (throwaway): verifikasi lapisan data video + pemilihan kualitas.
 * Bukan bagian rilis; dipakai buat buktiin InnerTubeX kasih videoUrl + ladder + maxVideoHeight.
 */
package com.lokotube.desktop.playback

import kotlinx.coroutines.runBlocking
import org.junit.Test

class VideoExtractionSpikeTest {
    @Test
    fun resolveVideoStream() {
        runBlocking {
            // "dQw4w9WgXcQ" = video YouTube publik populer dengan banyak resolusi (sampai 1080p/4K).
            val result = runCatching { StreamResolver.resolveVideo("dQw4w9WgXcQ") }

            result.onSuccess { s ->
                println("=== VIDEO RESOLVED (default, kualitas tertinggi) ===")
                println("videoUrl null?       : ${s.videoUrl == null}")
                println("videoUrl (prefix)    : ${s.videoUrl?.take(140)}")
                println("resolution           : ${s.videoWidth}x${s.videoHeight}")
                println("availableHeights     : ${s.availableHeights}")
                println("selectedHeight       : ${s.selectedHeight}")
                println("videoMimeType        : ${s.videoMimeType}")
                println("videoCodecs          : ${s.videoCodecs}")
                println("videoItag / bitrate  : ${s.videoItag} / ${s.videoBitrate}")
                println("videoContentLength   : ${s.videoContentLengthBytes}")
                println("durationMs           : ${s.durationMs}")
                println("usesSabr             : ${s.usesSabr}")
                println("videoBootstrap url   : ${s.videoBootstrap?.serverAbrStreamingUrl?.take(120)}")
                println("audioBootstrap null? : ${s.audioBootstrap == null}")
                println("audioBootstrap itag  : ${s.audioBootstrap?.audioFormat?.itag}")
                println("client / mime        : ${s.client} / ${s.mimeType}")
                println("audioUrl (prefix)    : ${s.audioUrl.take(140)}")
                println("headers keys         : ${s.headers.keys}")
                // Video bisa datang via URL progresif ATAU SABR bootstrap — salah satu harus ada.
                check(s.videoUrl != null || s.videoBootstrap != null) { "tidak ada videoUrl ATAU videoBootstrap" }
                check(s.availableHeights.isNotEmpty()) { "availableHeights kosong -> tidak ada ladder resolusi" }
            }
            result.onFailure { e ->
                println("=== VIDEO RESOLVE FAILED ===")
                e.printStackTrace()
                throw e
            }
        }
    }

    @Test
    fun resolveVideoCappedAt1080() {
        runBlocking {
            val result = runCatching { StreamResolver.resolveVideo("dQw4w9WgXcQ", maxHeight = 1080) }

            result.onSuccess { s ->
                println("=== VIDEO RESOLVED (maxHeight=1080) ===")
                println("selectedHeight   : ${s.selectedHeight}")
                println("videoHeight      : ${s.videoHeight}")
                println("availableHeights : ${s.availableHeights}")
                println("videoItag        : ${s.videoItag}")
                println("videoCodecs      : ${s.videoCodecs}")
                val h = s.selectedHeight ?: s.videoHeight
                check(h != null && h <= 1080) { "maxHeight=1080 tapi yang dipilih $h" }
            }
            result.onFailure { e ->
                println("=== VIDEO 1080 RESOLVE FAILED ===")
                e.printStackTrace()
                throw e
            }
        }
    }
}
