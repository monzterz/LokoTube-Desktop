/**
 * LOKOTUBE (C) 2026 — SPIKE (throwaway): Task 0.2 — buktiin FFmpeg bisa decode byte SABR.
 * Bukan bagian rilis. Konsumsi SabrVideoStream/SabrAudioStream -> tulis ke file -> FFmpeg decode.
 */
package com.lokotube.desktop.playback

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
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrVideoStream
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import org.junit.Test
import java.io.File

class VideoRenderSpikeTest {
    private val logger = InnerTubeLogger { event -> println("ITX ${event.tag}: ${event.message}") }

    private val noTokens =
        object : TokenProvider {
            override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)
            override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? = null
            override suspend fun close() = Unit
        }

    private val configRepo =
        object : PlayerConfigRepository {
            override val enabled = true
            override val sourceUrl = "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
            override val defaultSourceUrl = sourceUrl
            override var cachedJson: String = ""
            override var cachedAtMs: Long = 0
            override var cachedSourceUrl: String = ""
            override var cachedEtag: String = ""
        }

    private suspend fun extractor(): InnerTubeExtractor {
        val transport = YouTube.extractionTransport()
        val store = RemotePlayerConfigStore(transport.httpClient, configRepo, logger)
        val cipher = YouTubeCipherService(transport.httpClient, store, logger)
        val parser = YtConfigParserImpl(transport.httpClient, transport.innerTube, store, logger)
        return InnerTubeExtractor(
            configParser = parser,
            cipherService = cipher,
            innerTube = transport.innerTube,
            tokenProvider = noTokens,
            logger = logger,
        )
    }

    @Test
    fun renderSpike() {
        runBlocking {
            val videoId = "dQw4w9WgXcQ" // video YouTube publik populer
            val hints = ContentHints().copy(wantVideo = true, sabrFirst = true)
                .withStreamCapabilities(allowHls = true, allowSabr = true, allowBoundedRange = true)

            println("=== resolve video ===")
            val stream = extractor().extract(
                videoId = videoId,
                hints = hints,
                excludedClients = emptySet(),
                audioQuality = AudioQuality.LOW,
                clientPlaybackNonce = generateClientPlaybackNonce(),
            ) ?: error("No playable stream")

            val videoBootstrap = stream.sabrVideoBootstrap
            val audioBootstrap = stream.sabrBootstrap
            println("videoBootstrap null? ${videoBootstrap == null}")
            println("audioBootstrap null? ${audioBootstrap == null}")
            println("videoUrl (progressive) : ${stream.videoUrl?.take(80)}")
            println("videoItag / height    : ${stream.videoItag} / ${stream.videoHeight}")
            println("audioItag             : ${audioBootstrap?.audioFormat?.itag}")

            val transport = YouTube.extractionTransport()
            val httpClient = transport.httpClient

            // --- Video stream ---
            println("=== konsumsi SabrVideoStream ===")
            val videoChunks = mutableListOf<SabrChunk>()
            if (videoBootstrap != null) {
                val vStream = SabrVideoStream(httpClient, videoBootstrap)
                val collected = vStream.chunks().take(120).toList()
                videoChunks.addAll(collected)
                val init = videoChunks.filter { it.isInitialization }
                val media = videoChunks.filter { !it.isInitialization }
                println("video chunks: ${videoChunks.size} (init=${init.size}, media=${media.size})")
                println("init sizes   : ${init.map { it.data.size }}")
                println("media sizes  : ${media.take(5).map { it.data.size }} ...")
                println("seqNumbers   : ${media.take(8).map { it.sequenceNumber }}")
                val totalBytes = videoChunks.sumOf { it.data.size }
                println("total bytes  : $totalBytes")
            }

            // --- Audio stream ---
            println("=== konsumsi SabrAudioStream ===")
            val audioChunks = mutableListOf<SabrChunk>()
            if (audioBootstrap != null) {
                val aStream = SabrAudioStream(httpClient, audioBootstrap)
                val collected = aStream.chunks().take(60).toList()
                audioChunks.addAll(collected)
                val init = audioChunks.filter { it.isInitialization }
                val media = audioChunks.filter { !it.isInitialization }
                println("audio chunks: ${audioChunks.size} (init=${init.size}, media=${media.size})")
                println("init sizes  : ${init.map { it.data.size }}")
                println("total bytes : ${audioChunks.sumOf { it.data.size }}")
            }

            // --- Tulis ke file + decode pakai FFmpeg ---
            if (videoChunks.isNotEmpty()) {
                val vf = File.createTempFile("spike-video", ".webm")
                vf.outputStream().use { out ->
                    // init segment dulu, lalu media sesuai urutan sequence
                    videoChunks.sortedWith(compareBy({ !it.isInitialization }, { it.sequenceNumber ?: 0 }))
                        .forEach { out.write(it.data) }
                }
                println("=== FFmpeg decode VIDEO (${vf.path}, ${vf.length()} bytes) ===")
                try {
                    FFmpegFrameGrabber(vf).use { g ->
                        g.start()
                        println("video: ${g.imageWidth}x${g.imageHeight} @ ${g.frameRate} fps, codec=${g.videoCodecName}")
                        var n = 0
                        while (g.grab() != null && n < 60) n++
                        println("decoded frames: $n")
                    }
                } catch (e: Exception) {
                    println("VIDEO DECODE FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                }
            }

            if (audioChunks.isNotEmpty()) {
                val af = File.createTempFile("spike-audio", ".webm")
                af.outputStream().use { out ->
                    audioChunks.sortedWith(compareBy({ !it.isInitialization }, { it.sequenceNumber ?: 0 }))
                        .forEach { out.write(it.data) }
                }
                println("=== FFmpeg decode AUDIO (${af.path}, ${af.length()} bytes) ===")
                try {
                    FFmpegFrameGrabber(af).use { g ->
                        g.sampleMode = FrameGrabber.SampleMode.SHORT
                        g.start()
                        println("audio: ${g.sampleRate} Hz, ${g.audioChannels} ch, codec=${g.audioCodecName}")
                        var n = 0
                        while (g.grabSamples() != null && n < 60) n++
                        println("audio sample frames: $n")
                    }
                } catch (e: Exception) {
                    println("AUDIO DECODE FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                }
            }

            check(videoChunks.isNotEmpty() && audioChunks.isNotEmpty()) { "tidak ada chunk video/audio" }
        }
    }
}
