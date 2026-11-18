/**
 * LOKOTUBE (C) 2026 — SPIKE (throwaway): buktiin FFmpeg bisa baca SABR via PIPE (streaming),
 * bukan file utuh. Ini penentu arsitektur player (pipe vs growing-file vs download-then-play).
 */
package com.lokotube.desktop.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

class StreamingPipeSpikeTest {
    @Test
    fun pipeStreamingDecode() {
        runBlocking {
            println("=== open video (480p, biar ringan) ===")
            val streams = StreamResolver.openVideo("dQw4w9WgXcQ", maxHeight = 480)
            val video = checkNotNull(streams.video) { "tidak ada video SABR stream" }
            println("height=${streams.height}, availableHeights=${streams.availableHeights}, durationMs=${streams.durationMs}")

            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut, 1 shl 20) // buffer 1MB

            // Producer: tulis chunk SABR ke pipe (urutan natural = init dulu).
            val producer = launch(Dispatchers.IO) {
                try {
                    video.chunks().collect { chunk ->
                        pipeOut.write(chunk.data)
                        pipeOut.flush()
                    }
                } finally {
                    pipeOut.close()
                }
            }

            println("=== FFmpeg decode dari pipe ===")
            try {
                FFmpegFrameGrabber(pipeIn).use { g ->
                    g.format = "webm"
                    g.start()
                    var n = 0
                    while (g.grab() != null && n < 60) n++
                    println("pipe-decoded frames: $n @ ${g.imageWidth}x${g.imageHeight}, fps=${g.frameRate}")
                    check(n > 0) { "tidak ada frame dari pipe" }
                }
            } finally {
                producer.cancel()
            }
        }
    }
}
