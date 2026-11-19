/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.playback

import com.lokotube.desktop.data.Settings
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrVideoStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.min

data class VideoState(
    val videoId: String? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val error: String? = null,
    /** Current quality tier (video height); null until the stream resolves. */
    val height: Int? = null,
    val availableHeights: List<Int> = emptyList(),
)

/**
 * Plays a YouTube video (audio + video) from its SABR streams. The video is streamed through a pipe
 * into FFmpeg (no full download) and throttled to a wall clock, so it starts as soon as frames are
 * ready — it never freezes waiting for the audio. The audio is buffered to a seekable temp file (it
 * is small and its metadata sits at the end, so it needs the whole file) and seeks to the video's
 * current position once it is ready, keeping the two in sync.
 */
class VideoPlayer(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(VideoState(volume = Settings.volume.value))
    val state: StateFlow<VideoState> = mutableState.asStateFlow()

    private val mutablePosition = MutableStateFlow(0L)

    /** Changes many times a second. */
    val position: StateFlow<Long> = mutablePosition.asStateFlow()

    private val mutableFrame = MutableStateFlow<BufferedImage?>(null)

    /** The latest decoded video frame, ready to draw. */
    val frame: StateFlow<BufferedImage?> = mutableFrame.asStateFlow()

    @Volatile private var session: Session? = null
    private var loadJob: Job? = null

    fun play(videoId: String, height: Int? = null) = load(videoId, height)

    fun toggle() {
        val state = mutableState.value
        if (state.videoId == null) return
        if (session == null && !state.loading) load(state.videoId, state.height) else mutableState.update { it.copy(playing = !it.playing) }
    }

    fun pause() = mutableState.update { it.copy(playing = false) }

    fun resume() = mutableState.update { it.copy(playing = true) }

    fun seek(positionMs: Long) {
        // Streaming (pipe) video is non-seekable, so a seek re-opens the stream from the target
        // position via SABR's initialPlayerTimeMs. Live HLS has no seekable timeline.
        val state = mutableState.value
        val videoId = state.videoId ?: return
        if (state.durationMs <= 0) return
        load(videoId, state.height, fromMs = positionMs.coerceIn(0L, state.durationMs))
    }

    fun seekBy(deltaMs: Long) = seek(mutablePosition.value + deltaMs)

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        Settings.volume.value = clamped
        mutableState.update { it.copy(volume = clamped, muted = false) }
    }

    fun toggleMute() = mutableState.update { it.copy(muted = !it.muted) }

    /** Switches quality by re-resolving with the new height cap. */
    fun setHeight(height: Int) {
        val state = mutableState.value
        if (state.videoId != null) load(state.videoId, height)
    }

    fun stop() {
        session?.cancelled = true
        session = null
        loadJob?.cancel()
        mutableFrame.value = null
        mutablePosition.value = 0
        mutableState.update { it.copy(playing = false, loading = false, videoId = null) }
    }

    private fun load(videoId: String, height: Int?, fromMs: Long = 0L) {
        loadJob?.cancel()
        session?.cancelled = true
        session = null
        val previousDuration = mutableState.value.durationMs
        mutablePosition.value = fromMs
        mutableFrame.value = null
        mutableState.update {
            it.copy(
                videoId = videoId,
                playing = true,
                loading = true,
                durationMs = if (fromMs > 0) previousDuration else 0,
                height = height,
                error = null,
            )
        }
        loadJob =
            scope.launch(Dispatchers.IO) {
                val streams =
                    try {
                        resolveWithRetry(videoId, height, fromMs)
                    } catch (error: Exception) {
                        if (isActive) mutableState.update { it.copy(loading = false, playing = false, error = "This video can't be played right now") }
                        return@launch
                    }
                if (!isActive) return@launch
                session = Session(streams.video, streams.audio, streams, fromMs).also { it.start() }
            }
    }

    /**
     * Resolves a video stream, retrying with exponential backoff when YouTube transiently refuses
     * (rate-limit / network outage). Genuine refusals — age-restricted, not a music video,
     * explicitly unsupported — are not retried and surface immediately.
     */
    private suspend fun resolveWithRetry(videoId: String, height: Int?, fromMs: Long = 0L): VideoStreams {
        var attempt = 0
        while (true) {
            try {
                return StreamResolver.openVideo(videoId, height, fromMs)
            } catch (error: StreamResolveException) {
                if (!error.reason.isTransient() || attempt >= MAX_RESOLVE_ATTEMPTS) throw error
            } catch (error: IOException) {
                if (attempt >= MAX_RESOLVE_ATTEMPTS) throw error
            }
            attempt++
            delay(RESOLVE_RETRY_BASE_MS * (1L shl (attempt - 1)))
        }
    }

    private fun StreamResolveException.Reason.isTransient(): Boolean =
        this == StreamResolveException.Reason.NETWORK ||
            this == StreamResolveException.Reason.NO_PLAYABLE_STREAM ||
            this == StreamResolveException.Reason.UNAVAILABLE ||
            this == StreamResolveException.Reason.UNKNOWN

    private suspend fun download(flow: Flow<ByteArray>, file: File) {
        file.outputStream().use { out -> flow.collect { out.write(it) } }
    }

    private inner class Session(
        private val video: SabrVideoStream?,
        private val audio: SabrAudioStream?,
        private val streams: VideoStreams,
        private val seekTargetMs: Long,
    ) : Thread("lokotube-video") {
        @Volatile var cancelled = false

        /** Wall-clock reference the video throttles to; the audio seeks to catch up to it. */
        @Volatile var clockStartMs = 0L

        /** Raw timestamp of the first decoded frame, the 0-reference for the throttle clock. */
        private var firstFrameTsMs = -1L

        override fun run() {
            val audioThread =
                when {
                    audio != null -> thread(name = "lokotube-video-audio", isDaemon = true) { pumpSabrAudio(audio) }
                    streams.hlsAudioUrl != null -> thread(name = "lokotube-video-audio", isDaemon = true) { pumpHlsAudio(streams.hlsAudioUrl) }
                    else -> null
                }
            try {
                pumpVideo()
            } finally {
                audioThread?.join(1_000)
                if (!cancelled) mutableState.update { it.copy(playing = false) }
            }
        }

        private fun pumpSabrAudio(stream: SabrAudioStream) {
            val file = File.createTempFile("lokotube-a", ".m4a")
            var bytes = ByteArray(BUFFER_BYTES)
            try {
                avutil.av_log_set_level(avutil.AV_LOG_QUIET)
                // Buffer the (small) audio stream fully — its moov sits at the end, so a pipe can't start.
                runBlocking { download(stream.bytes(), file) }
                FFmpegFrameGrabber(file).use { grabber ->
                    grabber.sampleMode = FrameGrabber.SampleMode.SHORT
                    grabber.audioChannels = CHANNELS
                    grabber.sampleRate = SAMPLE_RATE
                    grabber.start()
                    // The audio stream starts at the same segment boundary as the video (both use
                    // initialPlayerTimeMs). Read the container's own start timestamp — absolute
                    // (segment) or relative (0) — and seek to the video's current position from it.
                    val audioStartMs = (grabber.timestamp / US_PER_MS).coerceAtLeast(0L)
                    val elapsed = System.currentTimeMillis() - clockStartMs
                    if (clockStartMs > 0 && elapsed > AUDIO_SEEK_THRESHOLD_MS) {
                        runCatching { grabber.timestamp = (audioStartMs + elapsed) * US_PER_MS }
                    }
                    val format = AudioFormat(SAMPLE_RATE.toFloat(), BITS, CHANNELS, true, false)
                    // No audio device (headless/CI): decode-only, the video still plays on the wall clock.
                    val line =
                        try {
                            AudioSystem.getSourceDataLine(format).also { it.open(format, BUFFER_BYTES); it.start() }
                        } catch (_: Exception) {
                            null
                        }
                    while (!cancelled) {
                        val state = mutableState.value
                        if (!state.playing) {
                            if (line?.isRunning == true) line.stop()
                            sleep(PAUSE_POLL_MS)
                            continue
                        }
                        if (line?.isRunning == false) line.start()
                        val frame = grabber.grabSamples() ?: break
                        val samples = frame.samples?.getOrNull(0) as? ShortBuffer ?: continue
                        val count = samples.remaining()
                        if (line != null) {
                            if (count * 2 > bytes.size) bytes = ByteArray(count * 2)
                            val gain = if (state.muted) 0f else state.volume * state.volume
                            var i = 0
                            while (i + 1 < count) {
                                val left = samples.get().toDouble() * gain
                                val right = samples.get().toDouble() * gain
                                writeSample(bytes, i, left)
                                writeSample(bytes, i + 1, right)
                                i += 2
                            }
                            line.write(bytes, 0, count * 2)
                        }
                    }
                    if (!cancelled) line?.drain()
                    line?.close()
                }
            } catch (error: Exception) {
                if (!cancelled) mutableState.update { it.copy(playing = false, error = "Audio can't be played right now") }
            } finally {
                runCatching { file.delete() }
            }
        }

        private fun pumpHlsAudio(url: String) {
            var bytes = ByteArray(BUFFER_BYTES)
            try {
                avutil.av_log_set_level(avutil.AV_LOG_QUIET)
                FFmpegFrameGrabber(url).use { grabber ->
                    grabber.setOption("reconnect", "1")
                    grabber.setOption("reconnect_streamed", "1")
                    grabber.sampleMode = FrameGrabber.SampleMode.SHORT
                    grabber.audioChannels = CHANNELS
                    grabber.sampleRate = SAMPLE_RATE
                    grabber.start()
                    val format = AudioFormat(SAMPLE_RATE.toFloat(), BITS, CHANNELS, true, false)
                    val line =
                        try {
                            AudioSystem.getSourceDataLine(format).also { it.open(format, BUFFER_BYTES); it.start() }
                        } catch (_: Exception) {
                            null
                        }
                    while (!cancelled) {
                        val state = mutableState.value
                        if (!state.playing) {
                            if (line?.isRunning == true) line.stop()
                            sleep(PAUSE_POLL_MS)
                            continue
                        }
                        if (line?.isRunning == false) line.start()
                        val frame = grabber.grabSamples() ?: break
                        val samples = frame.samples?.getOrNull(0) as? ShortBuffer ?: continue
                        val count = samples.remaining()
                        if (line != null) {
                            if (count * 2 > bytes.size) bytes = ByteArray(count * 2)
                            val gain = if (state.muted) 0f else state.volume * state.volume
                            var i = 0
                            while (i + 1 < count) {
                                val left = samples.get().toDouble() * gain
                                val right = samples.get().toDouble() * gain
                                writeSample(bytes, i, left)
                                writeSample(bytes, i + 1, right)
                                i += 2
                            }
                            line.write(bytes, 0, count * 2)
                        }
                    }
                    if (!cancelled) line?.drain()
                    line?.close()
                }
            } catch (error: Exception) {
                if (!cancelled) mutableState.update { it.copy(playing = false, error = "Audio can't be played right now") }
            }
        }

        private fun pumpVideo() {
            val renderer =
                when {
                    streams.hlsVideoUrl != null -> HlsVideoRenderer(streams.hlsVideoUrl)
                    video != null -> JavaCvVideoRenderer(video, scope, streams.durationMs, streams.videoMimeType)
                    else -> {
                        mutableState.update { it.copy(loading = false, playing = false, error = "This video can't be played right now") }
                        return
                    }
                }
            try {
                mutableState.update { it.copy(durationMs = renderer.durationMs, loading = false, height = streams.height, availableHeights = streams.availableHeights) }
                clockStartMs = System.currentTimeMillis()
                while (!cancelled) {
                    // Pause: freeze the frame and shift the clock on resume so nothing drifts.
                    if (!mutableState.value.playing) {
                        val pausedAt = System.currentTimeMillis()
                        while (!cancelled && !mutableState.value.playing) sleep(PAUSE_POLL_MS)
                        clockStartMs += System.currentTimeMillis() - pausedAt
                        if (cancelled) break
                    }
                    val frame = renderer.nextFrame()
                    if (frame == null) {
                        // A live HLS playlist returns null while it waits for the next segment
                        // (it has caught up to the live edge) — retry, don't treat it as EOF.
                        if (streams.isLiveHls) {
                            sleep(LIVE_RETRY_MS)
                            continue
                        }
                        break
                    }
                    // SABR re-loads start at the segment boundary that precedes the seek target — drop
                    // those frames (fast, <1s) so the video lands on the target, and the 0-based throttle
                    // below never sees the absolute/DVR timestamp (which would stall it).
                    if (!streams.isLiveHls && firstFrameTsMs < 0 && frame.timestampMs < seekTargetMs) {
                        continue
                    }
                    if (firstFrameTsMs < 0) firstFrameTsMs = frame.timestampMs
                    val relativeMs = frame.timestampMs - firstFrameTsMs
                    // Display position: SABR timestamps are absolute (a seek shows its real spot);
                    // live-HLS DVR timestamps are meaningless to the user, so show relative instead.
                    val displayMs = if (streams.isLiveHls) relativeMs else frame.timestampMs
                    // Throttle to the wall clock: play in real time, independent of audio readiness.
                    while (!cancelled && mutableState.value.playing) {
                        val elapsed = System.currentTimeMillis() - clockStartMs
                        val ahead = relativeMs - elapsed
                        if (ahead <= 0) break
                        sleep(min(SYNC_POLL_MS, ahead))
                    }
                    if (cancelled) break
                    if (mutableState.value.playing) {
                        mutableFrame.value = frame.image
                        mutablePosition.value = displayMs
                    }
                }
            } catch (error: Exception) {
                if (!cancelled) mutableState.update { it.copy(playing = false, error = "Video can't be played right now") }
            } finally {
                renderer.close()
            }
        }

        private fun writeSample(bytes: ByteArray, index: Int, value: Double) {
            val sample = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr BITS_PER_BYTE).toByte()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BITS = 16
        const val BITS_PER_BYTE = 8
        const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS / BITS_PER_BYTE
        const val BUFFER_BYTES = BYTES_PER_SECOND / 5
        const val US_PER_MS = 1000L
        const val PAUSE_POLL_MS = 30L
        const val SYNC_POLL_MS = 5L
        const val LIVE_RETRY_MS = 500L
        const val AUDIO_SEEK_THRESHOLD_MS = 500L
        /** Retries after the first resolve attempt before giving up (1s, 2s, 4s backoff). */
        const val MAX_RESOLVE_ATTEMPTS = 3
        const val RESOLVE_RETRY_BASE_MS = 1_000L
    }
}
