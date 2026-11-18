/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.playback

import com.lokotube.desktop.AppFiles
import com.lokotube.desktop.data.Settings
import com.lokotube.innertube.YouTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrVideoStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.util.Properties

/** A playable audio URL and what it takes to read it. */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val client: String,
    val mimeType: String?,
    val loudnessDb: Double?,
)

/**
 * A playable video: the audio and video sources plus the quality ladder. YouTube's primary path is
 * SABR (segmented, adaptive) — [videoBootstrap]/[audioBootstrap] are the live stream sources; the
 * flat URL fields are the progressive fallback and metadata.
 */
data class ResolvedVideoStream(
    val audioUrl: String,
    val videoUrl: String?,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val availableHeights: List<Int>,
    val videoMimeType: String?,
    val videoCodecs: String?,
    val videoItag: Int?,
    val videoBitrate: Int?,
    val videoContentLengthBytes: Long?,
    val headers: Map<String, String>,
    val client: String,
    val mimeType: String?,
    val durationMs: Long,
    val videoBootstrap: SabrBootstrap?,
    val audioBootstrap: SabrBootstrap?,
    val maxVideoHeight: Int?,
) {
    val usesSabr: Boolean get() = videoBootstrap != null
    val selectedHeight: Int? get() = videoBootstrap?.selectedVideoHeight ?: videoHeight
}

/** Live, open SABR streams for a video, ready to be consumed by a renderer. */
data class VideoStreams(
    val video: SabrVideoStream?,
    val audio: SabrAudioStream?,
    val height: Int?,
    val availableHeights: List<Int>,
    val durationMs: Long,
    /** Container hint from YouTube (e.g. "video/mp4" for AVC/AV1, "video/webm" for VP9). */
    val videoMimeType: String?,
    /** Live streams use HLS manifests (video-only and audio-only), not SABR. */
    val hlsVideoUrl: String? = null,
    val hlsAudioUrl: String? = null,
) {
    val isLiveHls: Boolean get() = hlsVideoUrl != null
}

/** Finds stream URLs (and SABR bootstraps) through the same extractor the Android app uses. */
object StreamResolver {
    private data class Bundle(val generation: Long, val cipher: YouTubeCipherService, val extractor: InnerTubeExtractor)

    @Volatile private var current: Bundle? = null
    private val mutex = Mutex()

    private val logger = InnerTubeLogger { event -> Timber.d("${event.tag}: ${event.message} ${event.details}") }

    // The Android app runs BotGuard in a WebView; here the extractor sticks to the clients that
    // work without its token.
    private val noTokens =
        object : TokenProvider {
            override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)

            override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? = null

            override suspend fun close() = Unit
        }

    suspend fun resolve(videoId: String): ResolvedStream {
        val hints = ContentHints().withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true)
        val stream = extract(videoId, hints)
        return ResolvedStream(stream.audioUrl, stream.headers, stream.clientName, stream.mimeType, stream.loudnessDb)
    }

    /**
     * Finds the audio AND video of a video. [maxHeight] caps the resolution YouTube may select
     * (e.g. 1080); the default (null) picks the highest tier, which is far too heavy to decode in
     * software.
     */
    suspend fun resolveVideo(videoId: String, maxHeight: Int? = null): ResolvedVideoStream {
        val hints =
            ContentHints().copy(wantVideo = true, sabrFirst = true, maxVideoHeight = maxHeight)
                .withStreamCapabilities(allowHls = true, allowSabr = true, allowBoundedRange = true)
        val stream = extract(videoId, hints)
        return ResolvedVideoStream(
            audioUrl = stream.audioUrl,
            videoUrl = stream.videoUrl,
            videoWidth = stream.videoWidth,
            videoHeight = stream.videoHeight,
            availableHeights = stream.availableVideoHeights.orEmpty(),
            videoMimeType = stream.videoMimeType,
            videoCodecs = stream.videoCodecs,
            videoItag = stream.videoItag,
            videoBitrate = stream.videoBitrate,
            videoContentLengthBytes = stream.videoContentLengthBytes,
            headers = stream.headers,
            client = stream.clientName,
            mimeType = stream.mimeType,
            durationMs = stream.sabrVideoBootstrap?.durationMs ?: 0,
            videoBootstrap = stream.sabrVideoBootstrap,
            audioBootstrap = stream.sabrBootstrap,
            maxVideoHeight = maxHeight,
        )
    }

    /** Resolves a video and opens its SABR video+audio streams in one step. */
    suspend fun openVideo(videoId: String, maxHeight: Int? = null): VideoStreams {
        val resolved = resolveVideo(videoId, maxHeight)
        val httpClient = YouTube.extractionTransport().httpClient
        // Live broadcasts resolve to HLS manifests (m3u8) instead of SABR bootstraps.
        val isHls = resolved.mimeType == "application/x-mpegURL" || resolved.videoUrl?.contains("m3u8") == true
        return VideoStreams(
            video = if (isHls) null else resolved.videoBootstrap?.let { SabrVideoStream(httpClient, it) },
            audio = if (isHls) null else resolved.audioBootstrap?.let { SabrAudioStream(httpClient, it) },
            height = resolved.selectedHeight,
            availableHeights = resolved.availableHeights,
            durationMs = resolved.durationMs,
            videoMimeType = resolved.videoMimeType,
            hlsVideoUrl = if (isHls) resolved.videoUrl else null,
            hlsAudioUrl = if (isHls) resolved.audioUrl else null,
        )
    }

    private suspend fun extract(videoId: String, hints: ContentHints) =
        requireNotNull(
            bundle().extractor.extract(
                videoId = videoId,
                hints = hints,
                excludedClients = emptySet(),
                audioQuality = if (Settings.highQuality.value) AudioQuality.HIGH else AudioQuality.LOW,
                clientPlaybackNonce = generateClientPlaybackNonce(),
            ),
        ) { "No playable stream" }

    private suspend fun bundle(): Bundle {
        val transport = YouTube.extractionTransport()
        current?.takeIf { it.generation == transport.generation }?.let { return it }
        return mutex.withLock {
            val latest = YouTube.extractionTransport()
            current?.takeIf { it.generation == latest.generation }?.let { return@withLock it }
            runCatching { current?.cipher?.dispose() }
            val store = RemotePlayerConfigStore(latest.httpClient, ConfigFile, logger)
            val cipher = YouTubeCipherService(latest.httpClient, store, logger)
            val parser = YtConfigParserImpl(latest.httpClient, latest.innerTube, store, logger).withEmbeddedFallback()
            val extractor =
                InnerTubeExtractor(
                    configParser = parser,
                    cipherService = cipher,
                    innerTube = latest.innerTube,
                    tokenProvider = noTokens,
                    logger = logger,
                )
            Bundle(latest.generation, cipher, extractor).also { current = it }
        }
    }

    private fun YtConfigParser.withEmbeddedFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                try {
                    this@withEmbeddedFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    /** The player configuration cached between runs. */
    private object ConfigFile : PlayerConfigRepository {
        private const val SOURCE =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        private val file = File(AppFiles.root, "player_config.properties")
        private val values = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

        private fun put(key: String, value: String) {
            values.setProperty(key, value)
            file.outputStream().use { values.store(it, null) }
        }

        override val enabled = true
        override val sourceUrl = SOURCE
        override val defaultSourceUrl = SOURCE
        override var cachedJson: String
            get() = values.getProperty("json", "")
            set(value) = put("json", value)
        override var cachedAtMs: Long
            get() = values.getProperty("cached_at_ms", "0").toLong()
            set(value) = put("cached_at_ms", value.toString())
        override var cachedSourceUrl: String
            get() = values.getProperty("source_url", "")
            set(value) = put("source_url", value)
        override var cachedEtag: String
            get() = values.getProperty("etag", "")
            set(value) = put("etag", value)
    }
}
