/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.playback

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.File

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

    /** Jump to a position. The backing source is seekable, so this is a real seek. */
    fun seek(ms: Long)

    fun close()

    data class Frame(val image: BufferedImage, val timestampMs: Long)
}

/**
 * Decodes a fully-buffered SABR video from a temp file. Streaming WebM through a pipe breaks
 * FFmpeg (`avformat_find_stream_info` reads the whole stream because the seekhead sits at the end),
 * so the player downloads the stream to a seekable file first — start() is instant, duration/position
 * are correct, and seek works.
 */
class JavaCvVideoRenderer(private val file: File) : VideoRenderer {
    private val converter = Java2DFrameConverter()
    private val grabber = FFmpegFrameGrabber(file).also { it.start() }

    override val width = grabber.imageWidth
    override val height = grabber.imageHeight
    override val durationMs = grabber.lengthInTime / US_PER_MS

    override fun nextFrame(): VideoRenderer.Frame? {
        val frame = grabber.grab() ?: return null
        val image = converter.convert(frame) ?: return null
        return VideoRenderer.Frame(image, grabber.timestamp / US_PER_MS)
    }

    override fun seek(ms: Long) {
        runCatching { grabber.setTimestamp(ms * US_PER_MS) }
    }

    override fun close() {
        runCatching { grabber.close() }
        runCatching { file.delete() }
    }

    private companion object {
        const val US_PER_MS = 1000L
    }
}
