/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.playback

import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_guess_format
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_close
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.av_q2d
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import java.io.File

/**
 * Joins a downloaded video stream and its separately downloaded audio stream into one playable file
 * **without re-encoding** — a stream copy, the same thing `ffmpeg -i v -i a -c copy` does. YouTube
 * serves the two apart (SABR/video + a separate audio track), and re-encoding would cost CPU and
 * quality for nothing.
 *
 * The container follows the output file's extension: `.mp4` for AVC/AV1+AAC, `.webm`/`.mkv` for
 * VP9/Opus. Returns false (and leaves no half-written file behind) when anything goes wrong.
 */
object Muxer {
    /** Whether the mux worked, and why not when it didn't. */
    data class Result(val ok: Boolean, val error: String? = null)

    fun remux(video: File, audio: File?, output: File): Result {
        av_log_set_level(AV_LOG_ERROR)
        var inVideo: AVFormatContext? = null
        var inAudio: AVFormatContext? = null
        var out: AVFormatContext? = null
        var videoPacket: AVPacket? = null
        var audioPacket: AVPacket? = null
        var io: AVIOContext? = null
        try {
            inVideo = openInput(video) ?: return fail("Can't read the downloaded video")
            val videoStream = av_find_best_stream(inVideo, AVMEDIA_TYPE_VIDEO, -1, -1, null as AVCodec?, 0)
            if (videoStream < 0) return fail("The downloaded file has no video track")
            if (audio != null) {
                inAudio = openInput(audio)
                // An audio track that can't be read is not fatal: a silent video beats no file.
                if (inAudio == null) return fail("Can't read the downloaded audio")
            }
            val audioStream = inAudio?.let { av_find_best_stream(it, AVMEDIA_TYPE_AUDIO, -1, -1, null as AVCodec?, 0) } ?: -1

            val format = av_guess_format(null, output.name, null) ?: return fail("No container for ${output.extension}")
            val ctx = AVFormatContext(null)
            if (avformat_alloc_output_context2(ctx, format, null as String?, output.absolutePath) < 0) return fail("Can't create the output file")
            out = ctx

            val videoOut = addStream(ctx, inVideo, videoStream)
            val audioOut = if (inAudio != null && audioStream >= 0) addStream(ctx, inAudio, audioStream) else -1
            // The muxer writes a header (moov, codec tables) at the end unless asked for faststart;
            // for a download that is fine and it saves rewriting the file.
            if (ctx.oformat().flags().toInt() and AVFMT_NOFILE == 0) {
                // JavaCPP copies the opened context back into the object we pass (its @ByPtrPtr
                // argument), which is the idiom JavaCV itself uses for avio_open.
                val opened = AVIOContext(null)
                if (avio_open(opened, output.absolutePath, AVIO_FLAG_WRITE) < 0) return fail("Can't open ${output.name} for writing")
                io = opened
                ctx.pb(opened)
            }
            if (avformat_write_header(ctx, null as AVDictionary?) < 0) return fail("The ${output.extension} header was refused")

            videoPacket = av_packet_alloc()
            audioPacket = if (audioOut >= 0) av_packet_alloc() else null
            var haveVideo = av_read_frame(inVideo, videoPacket) >= 0
            var haveAudio = audioPacket != null && av_read_frame(inAudio!!, audioPacket) >= 0
            while (haveVideo || haveAudio) {
                val takeVideo = when {
                    !haveVideo -> false
                    !haveAudio -> true
                    else -> seconds(videoPacket!!, inVideo.streams(videoStream).time_base()) <= seconds(audioPacket!!, inAudio!!.streams(audioStream).time_base())
                }
                if (takeVideo) {
                    // av_interleaved_write_frame takes the packet's reference (it ends up blank), so
                    // the very same packet object is reused for the next read.
                    if (writePacket(ctx, videoPacket!!, videoOut, inVideo.streams(videoStream).time_base()) < 0) return fail("Writing a video packet failed")
                    av_packet_unref(videoPacket)
                    haveVideo = av_read_frame(inVideo, videoPacket) >= 0
                } else {
                    if (writePacket(ctx, audioPacket!!, audioOut, inAudio!!.streams(audioStream).time_base()) < 0) return fail("Writing an audio packet failed")
                    av_packet_unref(audioPacket)
                    haveAudio = av_read_frame(inAudio, audioPacket) >= 0
                }
            }
            if (av_write_trailer(ctx) < 0) return fail("Finishing the file failed")
            if (io != null) {
                avio_close(io)
                io = null
                ctx.pb(null as AVIOContext?)
            }
            return if (output.length() > 0) Result(true) else fail("The file came out empty")
        } catch (error: Throwable) {
            return fail(error.message ?: error::class.simpleName ?: "Unknown error")
        } finally {
            videoPacket?.let { av_packet_free(it) }
            audioPacket?.let { av_packet_free(it) }
            // A failed mux must not leave a file the user will try to play.
            if (out == null || io != null) output.delete()
            if (io != null) runCatching { avio_close(io) }
            out?.let { avformat_free_context(it) }
            inAudio?.let { avformat_free_context(it) }
            inVideo?.let { avformat_free_context(it) }
        }
    }

    private fun fail(reason: String) = Result(false, reason)

    private fun openInput(file: File): AVFormatContext? {
        val ctx = avformat_alloc_context() ?: return null
        if (avformat_open_input(ctx, file.absolutePath, null, null) < 0) {
            avformat_free_context(ctx)
            return null
        }
        if (avformat_find_stream_info(ctx, null as AVDictionary?) < 0) return null
        return ctx
    }

    /** Copies one input stream's codec parameters into a fresh output stream, keeping its time base. */
    private fun addStream(
        out: AVFormatContext,
        input: AVFormatContext,
        index: Int,
    ): Int {
        val source = input.streams(index)
        val stream = avformat_new_stream(out, null) ?: return -1
        if (avcodec_parameters_copy(stream.codecpar(), source.codecpar()) < 0) return -1
        stream.codecpar().codec_tag(0)
        stream.time_base(source.time_base())
        return stream.index()
    }

    /** Timestamps are in each stream's own time base; compare them as seconds. */
    private fun seconds(packet: AVPacket, timeBase: AVRational): Double = packet.dts() * av_q2d(timeBase)

    private fun writePacket(
        out: AVFormatContext,
        packet: AVPacket,
        outIndex: Int,
        inputTimeBase: AVRational,
    ): Int {
        if (outIndex < 0) return -1
        av_packet_rescale_ts(packet, inputTimeBase, out.streams(outIndex).time_base())
        packet.stream_index(outIndex)
        return av_interleaved_write_frame(out, packet)
    }

    /** The file extension for the container that fits [videoMimeType] (`.mp4` unless it is WebM). */
    fun extensionFor(videoMimeType: String?): String = if (videoMimeType?.contains("webm", ignoreCase = true) == true) "webm" else "mp4"
}
