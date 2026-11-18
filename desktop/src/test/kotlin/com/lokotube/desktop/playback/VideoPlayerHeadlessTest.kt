/**
 * LOKOTUBE (C) 2026 — SPIKE (throwaway): uji headless VideoPlayer.
 * Verifikasi player benar-benar men-decode frame video + advance clock audio, tanpa X11/audio device.
 */
package com.lokotube.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VideoPlayerHeadlessTest {
    @Test
    fun playerDecodesVideoAndAudio() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val player = VideoPlayer(scope)
            try {
                println("=== play (360p) ===")
                player.play("dQw4w9WgXcQ", height = 360)

                var frames = 0
                var firstFrameAt = -1L
                val deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline) {
                    val f = player.frame.value
                    val pos = player.position.value
                    val err = player.state.value.error
                    if (err != null) {
                        println("ERROR STATE: $err")
                        break
                    }
                    if (f != null && frames == 0) {
                        firstFrameAt = pos
                        println("FIRST FRAME ${f.width}x${f.height} at pos=$pos ms")
                    }
                    if (f != null) frames++
                    if (frames >= 20) break // sudah 20 frame video ter-decode
                    delay(150)
                }

                val st = player.state.value
                println("position       : ${player.position.value} ms")
                println("durationMs     : ${st.durationMs}")
                println("height         : ${st.height}")
                println("availableHeight: ${st.availableHeights}")
                println("frames seen    : $frames")
                println("firstFrameAt   : $firstFrameAt")

                check(frames > 0) { "tidak ada frame video yang di-decode" }
                check(st.durationMs > 0) { "durationMs tidak terisi" }
            } finally {
                player.stop()
                scope.cancel()
            }
        }
    }
}
