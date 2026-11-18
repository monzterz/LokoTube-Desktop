/**
 * LOKOTUBE (C) 2026 — regression: renderer wajib men-decode video di KEDUA container.
 * xzCTxi3IFLM 720p = AVC/fMP4 (itag 298, mime video/mp4); dQw4w9WgXcQ 360p = VP9/WebM.
 * Dulu renderer hardcode format=webm -> MP4 gagal avformat_open_input -2, lalu OOM karena
 * JavaCV emulasi seek (maximumSize ~2GB) bikin demuxer mov seek di pipe non-seekable.
 */
package com.lokotube.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VideoContainerDecodeTest {
    private fun decode(videoId: String, height: Int?, label: String) {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val player = VideoPlayer(scope)
            try {
                println("=== play $label ($videoId, height=$height) ===")
                player.play(videoId, height)

                var frames = 0
                var firstFrameAt = -1L
                val deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline) {
                    val f = player.frame.value
                    val err = player.state.value.error
                    if (err != null) {
                        println("ERROR STATE: $err")
                        break
                    }
                    if (f != null && frames == 0) {
                        firstFrameAt = player.position.value
                        println("FIRST FRAME ${f.width}x${f.height} at pos=$firstFrameAt ms")
                    }
                    if (f != null) frames++
                    if (frames >= 12) break
                    delay(200)
                }
                val st = player.state.value
                println("frames=$frames error=${st.error} durationMs=${st.durationMs} height=${st.height}")
                check(frames > 0) { "tidak ada frame yang di-decode untuk $label" }
                check(st.error == null) { "state error untuk $label: ${st.error}" }
            } finally {
                player.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun decodeMp4Video() = decode("xzCTxi3IFLM", 720, "MP4/AVC 720p")

    @Test
    fun decodeWebmVideo() = decode("dQw4w9WgXcQ", 360, "WebM/VP9 360p")
}
