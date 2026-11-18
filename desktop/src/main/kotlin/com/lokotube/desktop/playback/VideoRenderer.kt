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

    /** Jump to a position. Streaming sources are non-seekable; the player re-loads for a real seek. */
    fun seek(ms: Long)

    fun close()

    data class Frame(val image: BufferedImage, val timestampMs: Long)
}

/**
 * Streams a [SabrVideoStream] through a pipe into FFmpeg and hands decoded frames out one at a time.
 * The pipe blocks on read (true streaming, no full download) and `live=1` tells the Matroska demuxer
 * the input has no trailing seekhead, which keeps `start()` to ~7s instead of reading to EOF (~18s).
 */
class JavaCvVideoRenderer(
    stream: SabrVideoStream,
    scope: CoroutineScope,
    resolvedDurationMs: Long,
) : VideoRenderer {
    private val pipeIn: PipedInputStream
    private val pipeOut = PipedOutputStream()
    private val producer: Job
    private val converter = Java2DFrameConverter()
    private val grabber: FFmpegFrameGrabber

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
                } finally {
                    runCatching { pipeOut.close() }
                }
            }
        grabber =
            FFmpegFrameGrabber(pipeIn).also {
                it.format = "webm"
                it.setOption("analyzeduration", "10000")
                it.setOption("probesize", "500000")
                it.setOption("live", "1")
                it.start()
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
