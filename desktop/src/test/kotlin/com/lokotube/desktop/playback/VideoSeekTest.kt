/**
 * LOKOTUBE (C) 2026 — SPIKE: verify seek works on the file-based renderer.
 */
package com.lokotube.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VideoSeekTest {
    @Test
    fun seekForwardAndBackward() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val player = VideoPlayer(scope)
            try {
                player.play("dQw4w9WgXcQ", height = 360)
                val deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline && player.state.value.loading) delay(100)
                while (System.currentTimeMillis() < deadline && player.position.value < 1000) delay(100)
                val err = player.state.value.error
                if (err != null) println("ERROR: $err")

                val before = player.position.value
                println("before seek      : pos=$before ms")

                player.seek(30_000)
                delay(2000)
                val afterForward = player.position.value
                println("after seek +30s  : pos=$afterForward ms")

                player.seek(5_000)
                delay(2000)
                val afterBack = player.position.value
                println("after seek back  : pos=$afterBack ms")

                check(afterForward >= 25_000) { "forward seek gagal: $afterForward" }
                check(afterBack < 15_000) { "backward seek gagal: $afterBack" }
                println("SEEK OK")
            } finally {
                player.stop()
                scope.cancel()
            }
        }
    }
}
