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

    /** Best-effort jump. FFmpeg over a pipe can't seek; implementations may restart the stream. */
    fun seek(ms: Long)

    fun close()

    data class Frame(val image: BufferedImage, val timestampMs: Long)
}

/**
 * Streams a [SabrVideoStream] through a pipe into FFmpeg and hands decoded frames out one at a time.
 * The SABR byte stream is a self-contained WebM/MP4 container (init segment first), so FFmpeg parses
 * it as it arrives — no full download, no manual container assembly.
 */
class JavaCvVideoRenderer(
    stream: SabrVideoStream,
    scope: CoroutineScope,
) : VideoRenderer {
    private val pipeIn: PipedInputStream
    private val pipeOut = PipedOutputStream()
    private val producer: Job
    private val converter = Java2DFrameConverter()
    private var grabber: FFmpegFrameGrabber? = null

    override var width = 0
        private set
    override var height = 0
        private set
    override var durationMs = 0L
        private set

    init {
        pipeIn = PipedInputStream(pipeOut, PIPE_BUFFER_BYTES)
        producer =
            scope.launch(Dispatchers.IO) {
                try {
                    stream.chunks().collect { chunk -> pipeOut.write(chunk.data) }
                } finally {
                    runCatching { pipeOut.close() }
                }
            }
        val grabber =
            FFmpegFrameGrabber(pipeIn).also {
                it.format = "webm"
                // A pipe can't seek, so cap stream-info analysis: the init segment already carries
                // the resolution, and waiting the default 5s for a near-real-time SABR feed would
                // block the player for seconds before the first frame.
                it.setOption("analyzeduration", "500000")
                it.setOption("probesize", "1000000")
            }
        grabber.start()
        this.grabber = grabber
        width = grabber.imageWidth
        height = grabber.imageHeight
        durationMs = grabber.lengthInTime / US_PER_MS
    }

    override fun nextFrame(): VideoRenderer.Frame? {
        val grabber = grabber ?: return null
        val frame = grabber.grab() ?: return null
        val image = converter.convert(frame) ?: return null
        return VideoRenderer.Frame(image, grabber.timestamp / US_PER_MS)
    }

    override fun seek(ms: Long) {
        // A pipe is not seekable; the player re-opens the renderer for a real seek.
    }

    override fun close() {
        runCatching { producer.cancel() }
        runCatching { grabber?.close() }
        runCatching { pipeIn.close() }
        grabber = null
    }

    private companion object {
        const val PIPE_BUFFER_BYTES = 1 shl 20
        const val US_PER_MS = 1000L
    }
}
