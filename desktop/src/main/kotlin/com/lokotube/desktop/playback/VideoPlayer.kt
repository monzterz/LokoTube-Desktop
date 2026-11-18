/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.playback

import com.lokotube.desktop.data.Settings
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrVideoStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.awt.image.BufferedImage
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

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
 * Plays a YouTube video (audio + video) from its SABR streams. Audio drives the clock and is decoded
 * by FFmpeg to the sound device; video is decoded by a [VideoRenderer] and pushed to [frame] in step
 * with the audio. The renderer backend is swappable (JavaCV now, mpv later).
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
        mutablePosition.value = positionMs
        session?.seekTo = positionMs
    }

    fun seekBy(deltaMs: Long) {
        val state = mutableState.value
        seek((mutablePosition.value + deltaMs).coerceIn(0, state.durationMs.coerceAtLeast(0)))
    }

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

    private fun load(videoId: String, height: Int?) {
        loadJob?.cancel()
        session?.cancelled = true
        session = null
        mutablePosition.value = 0
        mutableFrame.value = null
        mutableState.update { it.copy(videoId = videoId, playing = true, loading = true, durationMs = 0, height = height, error = null) }
        loadJob =
            scope.launch(Dispatchers.IO) {
                val streams =
                    try {
                        StreamResolver.openVideo(videoId, height)
                    } catch (error: Exception) {
                        if (isActive) mutableState.update { it.copy(loading = false, playing = false, error = "This video can't be played right now") }
                        return@launch
                    }
                if (!isActive) return@launch
                session = Session(streams).also { it.start() }
            }
    }

    private inner class Session(private val streams: VideoStreams) : Thread("lokotube-video") {
        @Volatile var cancelled = false

        @Volatile var seekTo = NO_SEEK

        /** Written by the audio loop; the master clock the video follows. */
        @Volatile var audioClockMs = 0L

        override fun run() {
            val audio = streams.audio
            val audioThread = if (audio != null) thread(name = "lokotube-video-audio", isDaemon = true) { pumpAudio(audio) } else null
            val ticker = if (audio == null) thread(name = "lokotube-video-clock", isDaemon = true) { pumpWallClock() } else null
            try {
                pumpVideo(streams.video)
            } finally {
                audioThread?.join(1_000)
                ticker?.join(1_000)
                if (!cancelled) mutableState.update { it.copy(playing = false) }
            }
        }

        private fun pumpWallClock() {
            val start = System.currentTimeMillis()
            while (!cancelled) {
                if (mutableState.value.playing) audioClockMs = System.currentTimeMillis() - start
                Thread.sleep(CLOCK_TICK_MS)
            }
        }

        private fun pumpAudio(audio: SabrAudioStream) {
            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut, PIPE_BUFFER_BYTES)
            val producer =
                scope.launch(Dispatchers.IO) {
                    try {
                        audio.chunks().collect { chunk -> pipeOut.write(chunk.data) }
                    } finally {
                        runCatching { pipeOut.close() }
                    }
                }
            var bytes = ByteArray(BUFFER_BYTES)
            try {
                avutil.av_log_set_level(avutil.AV_LOG_QUIET)
                FFmpegFrameGrabber(pipeIn).use { grabber ->
                    grabber.sampleMode = FrameGrabber.SampleMode.SHORT
                    grabber.audioChannels = CHANNELS
                    grabber.sampleRate = SAMPLE_RATE
                    // Same as the video renderer: a pipe can't seek, so cap stream-info analysis.
                    grabber.setOption("analyzeduration", "500000")
                    grabber.setOption("probesize", "1000000")
                    grabber.start()
                    val format = AudioFormat(SAMPLE_RATE.toFloat(), BITS, CHANNELS, true, false)
                    // No audio device (headless/CI): decode-only, the clock still advances so video stays in sync.
                    val line =
                        try {
                            AudioSystem.getSourceDataLine(format).also { it.open(format, BUFFER_BYTES); it.start() }
                        } catch (_: Exception) {
                            null
                        }
                    var published = 0L
                    while (!cancelled) {
                        val target = seekTo
                        if (target != NO_SEEK) {
                            seekTo = NO_SEEK
                            line?.flush()
                            moveTo(grabber, target)
                        }
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
                        val now = System.currentTimeMillis()
                        if (now - published >= POSITION_PUBLISH_MS) {
                            published = now
                            val buffered = line?.let { (it.bufferSize - it.available()) * MS_PER_SECOND / BYTES_PER_SECOND } ?: 0L
                            audioClockMs = (grabber.timestamp / US_PER_MS - buffered).coerceAtLeast(0)
                            mutablePosition.value = audioClockMs
                        }
                    }
                    if (!cancelled) line?.drain()
                    line?.close()
                }
            } catch (error: Exception) {
                if (!cancelled) mutableState.update { it.copy(playing = false, error = "Audio can't be played right now") }
            } finally {
                producer.cancel()
            }
        }

        private fun pumpVideo(video: SabrVideoStream?) {
            if (video == null) return
            val renderer = JavaCvVideoRenderer(video, scope)
            try {
                mutableState.update { it.copy(durationMs = renderer.durationMs, loading = false, height = streams.height, availableHeights = streams.availableHeights) }
                while (!cancelled) {
                    val frame = renderer.nextFrame() ?: break
                    // Audio-clock-master sync: wait until the audio has caught up to this frame.
                    while (!cancelled && frame.timestampMs - audioClockMs > SYNC_LEAD_MS) sleep(SYNC_POLL_MS)
                    if (cancelled) break
                    if (audioClockMs - frame.timestampMs > MAX_LAG_MS) continue // frame too old, drop
                    mutableFrame.value = frame.image
                }
            } catch (error: Exception) {
                if (!cancelled) mutableState.update { it.copy(playing = false, error = "Video can't be played right now") }
            } finally {
                renderer.close()
            }
        }

        private fun moveTo(grabber: FFmpegFrameGrabber, targetMs: Long) {
            // A pipe can't seek; a real seek re-opens the session. Best effort for now.
            grabber.setTimestamp(targetMs * US_PER_MS)
        }

        private fun writeSample(bytes: ByteArray, index: Int, value: Double) {
            val sample = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr BITS_PER_BYTE).toByte()
        }
    }

    private companion object {
        const val NO_SEEK = -1L
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BITS = 16
        const val BITS_PER_BYTE = 8
        const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS / BITS_PER_BYTE
        const val BUFFER_BYTES = BYTES_PER_SECOND / 5
        const val US_PER_MS = 1000L
        const val MS_PER_SECOND = 1000L
        const val PAUSE_POLL_MS = 30L
        const val POSITION_PUBLISH_MS = 40L
        const val CLOCK_TICK_MS = 40L
        const val SYNC_LEAD_MS = 40L
        const val SYNC_POLL_MS = 5L
        const val MAX_LAG_MS = 500L
        const val PIPE_BUFFER_BYTES = 1 shl 20
    }
}
