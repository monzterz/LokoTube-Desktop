/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.playback

import com.metrolist.innertubex.sabr.SabrVideoStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import timber.log.Timber
import java.awt.image.BufferedImage
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Decodes a single video track to frames. Isolated behind an interface so the FFmpeg/JavaCV backend
 * can be swapped for libmpv (hardware decode) without touching the player or the UI.
 */
interface VideoRenderer {
    val width: Int
    val height: Int
    val durationMs: Long

    /** Blocks; the next decoded frame (timestamp in ms), or null at end-of-stream. */
    fun nextFrame(): Frame?

    /** Jump to a position. Streaming sources are non-seekable; the player re-loads for a real seek. */
    fun seek(ms: Long)

    fun close()

    data class Frame(val image: BufferedImage, val timestampMs: Long)
}

/**
 * Streams a [SabrVideoStream] through a pipe into FFmpeg and hands decoded frames out one at a time.
 * The pipe blocks on read (true streaming, no full download). The container is chosen from
 * [mimeType] — YouTube serves VP9 as WebM but AVC/AV1 as fragmented MP4, so hardcoding one demuxer
 * breaks the other. `live=1` is Matroska-only: it tells the demuxer the input has no trailing
 * seekhead, keeping `start()` to ~7s instead of reading to EOF (~18s). MP4 puts its `moov` in the
 * init chunk up front, so it starts fast without `live`.
 */
class JavaCvVideoRenderer(
    stream: SabrVideoStream,
    scope: CoroutineScope,
    resolvedDurationMs: Long,
    mimeType: String? = null,
) : VideoRenderer {
    private val pipeIn: PipedInputStream
    private val pipeOut = PipedOutputStream()
    private val producer: Job
    private val converter = Java2DFrameConverter()
    private val grabber: FFmpegFrameGrabber

    /** Set by the producer if `chunks()` dies; surfaced so `start()` failures are debuggable. */
    @Volatile private var producerError: Throwable? = null

    private val isMp4 = mimeType?.contains("mp4", ignoreCase = true) == true

    override var width = 0
        private set
    override var height = 0
        private set

    /** From the resolver (`SabrBootstrap.durationMs`); a live pipe can't report its own length. */
    override val durationMs = resolvedDurationMs

    init {
        pipeIn = PipedInputStream(pipeOut, PIPE_BUFFER_BYTES)
        producer =
            scope.launch(Dispatchers.IO) {
                try {
                    stream.chunks().collect { chunk -> pipeOut.write(chunk.data) }
                } catch (error: Throwable) {
                    producerError = error
                    Timber.w(error, "SABR video producer failed")
                } finally {
                    runCatching { pipeOut.close() }
                }
            }
        grabber =
            try {
                // maximumSize=0 disables JavaCV's seek emulation (a giant in-memory mark/reset
                // buffer). A live SABR pipe is non-seekable; reporting it as seekable makes the mov
                // demuxer seek -> buffer the whole stream -> OOM. Non-seekable avio reads fMP4
                // sequentially, which is exactly what we want.
                FFmpegFrameGrabber(pipeIn, 0).also {
                    it.format = if (isMp4) "mp4" else "webm"
                    it.setOption("analyzeduration", "10000")
                    it.setOption("probesize", "500000")
                    if (!isMp4) it.setOption("live", "1")
                    it.start()
                }
            } catch (error: Exception) {
                val cause = producerError
                producer.cancel()
                runCatching { pipeIn.close() }
                if (cause != null) {
                    throw IllegalStateException("SABR video stream failed: ${cause.message}", cause)
                }
                throw error
            }
        width = grabber.imageWidth
        height = grabber.imageHeight
    }

    override fun nextFrame(): VideoRenderer.Frame? {
        val frame = grabber.grab() ?: return null
        val image = converter.convert(frame) ?: return null
        return VideoRenderer.Frame(image, grabber.timestamp / US_PER_MS)
    }

    override fun seek(ms: Long) {
        // A pipe is not seekable; the player re-loads the stream for a real seek.
    }

    override fun close() {
        runCatching { producer.cancel() }
        runCatching { grabber.close() }
        runCatching { pipeIn.close() }
    }

    private companion object {
        const val PIPE_BUFFER_BYTES = 1 shl 20
        const val US_PER_MS = 1000L
    }
}

/**
 * Decodes a live HLS manifest (`.m3u8`) straight from its URL. FFmpeg's HLS demuxer handles the
 * playlist and segment downloads itself, so unlike the SABR path there is no producer pipe to fill.
 * Live streams have no seek and no known end, so duration is 0 and [seek] is a no-op.
 */
class HlsVideoRenderer(
    private val url: String,
    headers: Map<String, String> = emptyMap(),
) : VideoRenderer {
    private val converter = Java2DFrameConverter()
    private var grabber: FFmpegFrameGrabber
    private val headerOption: String? =
        if (headers.isNotEmpty()) headers.entries.joinToString("\r\n") { (k, v) -> "$k: $v" } else null

    override var width = 0
        private set
    override var height = 0
        private set

    override val durationMs = 0L

    init {
        grabber = open()
        width = grabber.imageWidth
        height = grabber.imageHeight
    }

    private fun open(): FFmpegFrameGrabber =
        FFmpegFrameGrabber(url).also {
            it.setOption("probesize", "1000000")
            it.setOption("analyzeduration", "1000000")
            it.setOption("reconnect", "1")
            it.setOption("reconnect_streamed", "1")
            headerOption?.let { h -> it.setOption("headers", h) }
            it.start()
        }

    override fun nextFrame(): VideoRenderer.Frame? {
        while (true) {
            val frame = runCatching { grabber.grab() }.getOrNull()
            if (frame != null) {
                val image = converter.convert(frame) ?: continue
                return VideoRenderer.Frame(image, grabber.timestamp / US_PER_MS)
            }
            // Live edge reached (the playlist has no more segments yet): wait a beat, then re-open
            // the manifest so FFmpeg sees the newly published segments. For a live stream this is
            // the normal loop — never treat it as end-of-stream.
            Thread.sleep(LIVE_EDGE_WAIT_MS)
            runCatching { grabber.close() }
            val reopened = runCatching { open() }.getOrNull() ?: return null
            grabber = reopened
        }
    }

    override fun seek(ms: Long) {
        // A live HLS playlist can't be seeked arbitrarily.
    }

    override fun close() {
        runCatching { grabber.close() }
    }

    private companion object {
        const val US_PER_MS = 1000L
        const val LIVE_EDGE_WAIT_MS = 500L
    }
}
