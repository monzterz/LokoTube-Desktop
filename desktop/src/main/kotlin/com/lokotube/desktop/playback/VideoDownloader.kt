/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.playback

import com.lokotube.desktop.data.SavedVideo
import com.lokotube.desktop.data.VideoDownloads
import com.lokotube.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** How far a video download has got. */
data class DownloadProgress(
    val videoId: String,
    val title: String,
    val phase: Phase = Phase.RESOLVING,
    /** 0..1 over the whole job (video, then audio, then the mux). */
    val fraction: Float = 0f,
    val file: File? = null,
    val failure: String? = null,
) {
    enum class Phase { RESOLVING, DOWNLOADING, SAVING, DONE, FAILED }

    val running: Boolean get() = phase == Phase.RESOLVING || phase == Phase.DOWNLOADING || phase == Phase.SAVING
}

/**
 * Saves a video to the user's Videos folder: both SABR tracks are streamed to temp files, then
 * [Muxer] joins them without re-encoding. Progress is reported per video id, so the player's
 * download button can spin, and a finished download is remembered by [VideoDownloads].
 */
class VideoDownloader(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val state: StateFlow<Map<String, DownloadProgress>> = mutableState.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /** The progress for [videoId], or null when nothing was started this session. */
    fun progressOf(videoId: String): DownloadProgress? = mutableState.value[videoId]

    fun start(videoId: String, title: String, height: Int? = DEFAULT_HEIGHT) {
        if (jobs[videoId]?.isActive == true) return
        publish(DownloadProgress(videoId = videoId, title = title))
        val job = scope.launch(Dispatchers.IO) { run(videoId, title, height) }
        jobs[videoId] = job
        job.invokeOnCompletion {
            jobs.remove(videoId)
            // Keep the finished state around briefly (the button shows a check, or the error), then
            // forget it — the saved file itself is remembered in VideoDownloads.
            scope.launch {
                delay(DONE_STATE_MS)
                mutableState.update { it - videoId }
            }
        }
    }

    fun cancel(videoId: String) {
        jobs.remove(videoId)?.cancel()
        mutableState.update { it - videoId }
    }

    private fun publish(progress: DownloadProgress) = mutableState.update { it + (progress.videoId to progress) }

    private fun current(videoId: String, title: String) = mutableState.value[videoId] ?: DownloadProgress(videoId, title)

    private suspend fun run(videoId: String, title: String, height: Int?) {
        // The player only knows the id; the file wants a readable name.
        val name = title.ifBlank { fetchTitle(videoId) }
        var videoTemp: File? = null
        var audioTemp: File? = null
        try {
            val streams = StreamResolver.openVideo(videoId, height)
            if (streams.isLiveHls) throw IOException("A live stream can't be saved")
            val video = streams.video ?: throw IOException("This video has no downloadable stream")
            publish(current(videoId, name).copy(phase = DownloadProgress.Phase.DOWNLOADING, fraction = 0.02f))

            videoTemp = File.createTempFile("lokotube-v-", ".track")
            copy(video.bytes(), videoTemp, streams.videoBytes) { done ->
                val fraction = if (streams.videoBytes != null) done else min(0.8f, done)
                publish(current(videoId, name).copy(fraction = 0.02f + 0.83f * fraction))
            }
            streams.audio?.let { audio ->
                audioTemp = File.createTempFile("lokotube-a-", ".track")
                copy(audio.bytes(), audioTemp, null) { done ->
                    publish(current(videoId, name).copy(fraction = 0.85f + 0.10f * min(1f, done)))
                }
            }

            publish(current(videoId, name).copy(phase = DownloadProgress.Phase.SAVING, fraction = 0.97f))
            val target = VideoDownloads.newFile(name, videoId, Muxer.extensionFor(streams.videoMimeType))
            val mux = Muxer.remux(videoTemp, audioTemp, target)
            if (!mux.ok) throw IOException(mux.error ?: "Could not put the sound and picture back together")
            target.setLastModified(System.currentTimeMillis())
            VideoDownloads.add(
                SavedVideo(id = videoId, title = name, path = target.absolutePath, bytes = target.length(), savedAt = System.currentTimeMillis()),
            )
            publish(DownloadProgress(videoId, name, DownloadProgress.Phase.DONE, 1f, target))
        } catch (cancelled: CancellationException) {
            mutableState.update { it - videoId }
            throw cancelled
        } catch (error: Exception) {
            publish(DownloadProgress(videoId, name, DownloadProgress.Phase.FAILED, 0f, null, error.message ?: "Could not download this video"))
        } finally {
            videoTemp?.delete()
            audioTemp?.delete()
        }
    }

    private suspend fun fetchTitle(videoId: String): String =
        runCatching { YouTube.videoDetail(videoId).getOrNull()?.title }.getOrNull().orEmpty().ifBlank { videoId }

    /**
     * Streams [flow] into [file], reporting how much of it is done. Without a known [expected] size
     * (the audio track never states one) the fraction creeps up with the bytes written instead.
     */
    private suspend fun copy(
        flow: Flow<ByteArray>,
        file: File,
        expected: Long?,
        onProgress: (Float) -> Unit,
    ) {
        var written = 0L
        file.outputStream().use { out ->
            flow.collect { chunk ->
                currentCoroutineContext().ensureActive()
                out.write(chunk)
                written += chunk.size
                onProgress(if (expected != null && expected > 0) (written.toFloat() / expected).coerceIn(0f, 1f) else min(1f, written / UNKNOWN_SIZE_BYTES))
            }
        }
    }

    private companion object {
        /** Downloads keep full 1080p: nothing is decoded here, only copied. */
        const val DEFAULT_HEIGHT = 1080

        /** Used to fake progress for a track that doesn't state its length. */
        const val UNKNOWN_SIZE_BYTES = 6f * 1024 * 1024

        /** How long a finished/failed state stays in the map for the UI to show it. */
        const val DONE_STATE_MS = 6_000L
    }
}
