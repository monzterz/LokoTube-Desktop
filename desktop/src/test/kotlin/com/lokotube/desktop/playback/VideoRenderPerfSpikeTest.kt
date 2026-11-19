/**
 * LOKOTUBE (C) 2026 — SPIKE (throwaway): seberapa mampu renderer JavaCV sekarang di 720p vs 1080p?
 * Dipakai utk memutuskan apakah perlu pindah ke mpv (hw-decode) atau cukup renderer yang ada.
 *
 * Yang diukur: realtime factor (posisi video vs jam dinding), frame yang benar-benar tampil per detik,
 * CPU proses, dan puncak heap.
 */
package com.lokotube.desktop.playback

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.junit.Test

class VideoRenderPerfSpikeTest {
    @Test
    fun compare720pAnd1080p() {
        av_log_set_level(AV_LOG_QUIET)
        // "dQw4w9WgXcQ" = 4K remaster: the 1080p rung exists and the video is long enough to sample.
        listOf(1440, 2160).forEach { height -> measure("dQw4w9WgXcQ", height) }
    }

    private fun measure(videoId: String, height: Int) {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val player = VideoPlayer(scope)
            val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            try {
                val startedAt = System.currentTimeMillis()
                player.play(videoId, height)
                // Wait for the first frame (resolver + FFmpeg start), then measure a steady window.
                while (player.frame.value == null && System.currentTimeMillis() - startedAt < 60_000) {
                    if (player.state.value.error != null) break
                    delay(50)
                }
                val firstFrameMs = System.currentTimeMillis() - startedAt
                val windowStart = System.currentTimeMillis()
                val cpuStart = os.processCpuTime
                var positionStart = player.position.value
                var lastPosition = positionStart
                var distinctFrames = 0
                val runtime = Runtime.getRuntime()
                var peakHeapMb = 0L
                while (System.currentTimeMillis() - windowStart < WINDOW_MS) {
                    if (player.state.value.error != null) break
                    val position = player.position.value
                    if (position != lastPosition) {
                        distinctFrames++
                        lastPosition = position
                    }
                    peakHeapMb = maxOf(peakHeapMb, (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576)
                    delay(10)
                }
                val wallMs = System.currentTimeMillis() - windowStart
                val cpuMs = (os.processCpuTime - cpuStart) / 1_000_000
                val advanced = lastPosition - positionStart
                val state = player.state.value
                println(
                    "[${height}p cap] shown=${state.height}px firstFrame@${firstFrameMs}ms " +
                        "advanced=${advanced}ms of ${wallMs}ms wall (realtime=${"%.2f".format(advanced.toDouble() / wallMs)}) " +
                        "frames=${distinctFrames} (~${"%.1f".format(distinctFrames * 1000.0 / wallMs)} fps) " +
                        "cpu=${"%.0f".format(cpuMs * 100.0 / wallMs)}% heapPeak=${peakHeapMb}MB err=${state.error}",
                )
                check(state.error == null) { "renderer error at ${height}p: ${state.error}" }
                check(distinctFrames > 10) { "terlalu sedikit frame tampil di ${height}p" }
            } finally {
                player.stop()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val WINDOW_MS = 12_000L
    }
}
