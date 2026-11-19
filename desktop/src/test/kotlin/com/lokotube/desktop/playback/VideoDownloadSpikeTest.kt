/**
 * LOKOTUBE (C) 2026 — SPIKE (throwaway): unduh video + audio SABR, mux jadi satu file, lalu buktikan
 * file hasilnya benar-benar punya gambar DAN suara (decode ulang pakai FFmpeg).
 */
package com.lokotube.desktop.playback

import com.lokotube.desktop.data.VideoDownloads
import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.YouTubeLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.Test
import java.io.File
import java.nio.ShortBuffer

class VideoDownloadSpikeTest {
    @Test
    fun downloadsAndMuxesAShort() {
        av_log_set_level(AV_LOG_QUIET)
        runBlocking {
            YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
            val short = YouTube.shortsFeed().getOrThrow().first()
            println("DOWNLOAD short ${short.id} — ${short.title.take(60)}")

            val probe = StreamResolver.resolveVideo(short.id, maxHeight = 720)
            println("ladder     : available=${probe.availableHeights} selected=${probe.selectedHeight} real=${probe.videoWidth}x${probe.videoHeight} bytes=${probe.videoContentLengthBytes} mime=${probe.videoMimeType}")

            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val downloader = VideoDownloader(scope)
            try {
                downloader.start(short.id, short.title.ifBlank { short.id }, height = 720)
                val deadline = System.currentTimeMillis() + 180_000
                var last = ""
                while (System.currentTimeMillis() < deadline) {
                    val progress = downloader.progressOf(short.id)
                    if (progress == null) break
                    val line = "${progress.phase} ${"%.0f".format(progress.fraction * 100)}%"
                    if (line != last) {
                        println("  $line")
                        last = line
                    }
                    if (!progress.running) break
                    delay(400)
                }
                val finished = downloader.progressOf(short.id)
                val saved = VideoDownloads.find(short.id)
                println("phase      : ${finished?.phase} ${finished?.failure.orEmpty()}")
                println("saved file : ${saved?.path}")
                println("bytes      : ${saved?.bytes}")
                check(saved != null) { "file tidak tercatat di VideoDownloads" }
                val file = File(saved.path)
                check(file.exists() && file.length() > 50_000) { "file hasil mux kosong/terlalu kecil: ${file.length()}" }

                FFmpegFrameGrabber(file).use { grabber ->
                    grabber.start()
                    var videoFrames = 0
                    var audioSamples = 0L
                    var firstSize: Pair<Int, Int>? = null
                    val end = System.currentTimeMillis() + 60_000
                    while (System.currentTimeMillis() < end) {
                        val frame = grabber.grab() ?: break
                        frame.image?.let {
                            videoFrames++
                            if (firstSize == null && frame.imageWidth > 1) firstSize = frame.imageWidth to frame.imageHeight
                        }
                        (frame.samples?.getOrNull(0) as? ShortBuffer)?.let { audioSamples += it.remaining() }
                        if (videoFrames > 40 && audioSamples > 48_000) break
                    }
                    println("container  : duration=${grabber.lengthInTime / 1000}ms vfps=${grabber.videoFrameRate} afps=${grabber.audioFrameRate}")
                    println("decoded    : $videoFrames frame video, $audioSamples sample audio, size=$firstSize")
                    check(videoFrames > 0) { "file hasil mux tidak punya gambar" }
                    check(audioSamples > 0) { "file hasil mux tidak punya suara" }
                }
                println("ffprobe:")
                runCatching {
                    val process = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=index,codec_type,codec_name,width,height", "-of", "compact", file.absolutePath).redirectErrorStream(true).start()
                    println(process.inputStream.bufferedReader().readText().trim())
                    process.waitFor()
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
