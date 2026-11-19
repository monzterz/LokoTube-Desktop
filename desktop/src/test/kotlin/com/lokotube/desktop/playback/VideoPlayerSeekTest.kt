package com.lokotube.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Regression: seek() must re-load the stream from the target position (SABR initialPlayerTimeMs). */
class VideoPlayerSeekTest {
    @Test
    fun seekRepositionsPlayback() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val player = VideoPlayer(scope)
            try {
                player.play("dQw4w9WgXcQ", height = 360)
                var waited = 0
                while (player.frame.value == null && waited < 60_000) {
                    delay(150)
                    waited += 150
                }
                println("first frame at pos=${player.position.value} dur=${player.state.value.durationMs}")

                player.seek(60_000)

                var sawNearTarget = false
                val deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline) {
                    val err = player.state.value.error
                    if (err != null) {
                        println("ERROR STATE: $err")
                        break
                    }
                    val pos = player.position.value
                    if (player.frame.value != null && pos >= 55_000 && pos <= 65_000) {
                        sawNearTarget = true
                        break
                    }
                    delay(150)
                }
                println("after seek: pos=${player.position.value} dur=${player.state.value.durationMs} near60s=$sawNearTarget")
                check(sawNearTarget) { "seek tidak melompat ke ~60s (pos=${player.position.value})" }
            } finally {
                player.stop()
                scope.cancel()
            }
        }
    }
}