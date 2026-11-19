/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.data

import com.lokotube.desktop.BuildSecrets
import com.lokotube.desktop.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One file attached to a release. */
data class UpdateAsset(val name: String, val url: String, val sizeBytes: Long)

/** A published release, as GitHub describes it. */
data class UpdateInfo(
    val version: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val assets: List<UpdateAsset>,
) {
    /** The file that suits this machine: AppImage on Linux (a .deb as the fallback), .exe on Windows, .dmg on a Mac. */
    fun assetForThisSystem(): UpdateAsset? {
        val suffixes =
            when {
                Os.isLinux -> listOf(".appimage", ".deb")
                Os.isWindows -> listOf(".exe", ".msi")
                else -> listOf(".dmg")
            }
        val arm = System.getProperty("os.arch").orEmpty().let { it.contains("aarch64") || it.contains("arm") }
        for (suffix in suffixes) {
            val candidates = assets.filter { it.name.lowercase().endsWith(suffix) }
            if (candidates.isEmpty()) continue
            if (Os.isMac && candidates.size > 1) {
                val wanted = candidates.firstOrNull { asset ->
                    val name = asset.name.lowercase()
                    if (arm) name.contains("arm") else name.contains("intel") || name.contains("x64")
                }
                if (wanted != null) return wanted
            }
            return candidates.first()
        }
        return null
    }
}

/**
 * Checks GitHub for a newer release and, on a Linux AppImage install, downloads it next to the running
 * one and repoints the `LokoTube.AppImage` symlink — the app itself keeps running from the old file
 * until it is restarted.
 */
object Updates {
    private const val REPO = "monzterz/LokoTube-Desktop"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** Where a self-update lands: next to the running AppImage, or ~/Applications. */
    private val installDir: File
        get() {
            val appPath = System.getProperty("jpackage.app-path").orEmpty()
            val parent = if (appPath.isNotBlank()) File(appPath).parentFile else null
            if (parent != null && parent.isDirectory) return parent
            return File(System.getProperty("user.home"), "Applications").apply { mkdirs() }
        }

    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    /** The running version: the packaged one, a build-time constant, or the dev override. */
    val currentVersion: String
        get() =
            System.getenv("LOKOTUBE_VERSION")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }
                ?: BuildSecrets.VERSION

    suspend fun latest(): Result<UpdateInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request.Builder()
                        .url(LATEST)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "LokoTube")
                        .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
                    val release = json.decodeFromString<Release>(body)
                    UpdateInfo(
                        version = release.tagName.removePrefix("v"),
                        name = release.name ?: release.tagName,
                        notes = release.body.orEmpty(),
                        pageUrl = release.htmlUrl,
                        assets = release.assets.map { UpdateAsset(it.name, it.browserDownloadUrl, it.size) },
                    )
                }
            }
        }

    /** True when [remote] is a later release than [current] ("0.10.0" > "0.9.9"). */
    fun isNewer(remote: String, current: String): Boolean {
        val a = remote.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    /**
     * Downloads [asset] and, for an AppImage, points the stable symlink at it. Returns the saved file;
     * other platforms are handed to the browser (the release page) by the caller.
     */
    suspend fun download(asset: UpdateAsset, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = File(installDir, asset.name)
            val part = File(target.parentFile, "${target.name}.part")
            val request = Request.Builder().url(asset.url).header("User-Agent", "LokoTube").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                val body = response.body
                val total = if (asset.sizeBytes > 0) asset.sizeBytes else body.contentLength()
                part.delete()
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not save ${target.name}")
            if (target.name.contains(".appimage", ignoreCase = true)) {
                target.setExecutable(true)
                // The running AppImage keeps its own mount, so the swap only takes effect on restart.
                val stable = File(target.parentFile, "LokoTube.AppImage")
                runCatching { stable.delete(); java.nio.file.Files.createSymbolicLink(stable.toPath(), target.toPath()) }
                // Older downloads would otherwise pile up at ~114 MB each. Never touch the file this
                // process is running from, even though Linux would let us.
                val running = File(System.getProperty("jpackage.app-path").orEmpty()).absoluteFile
                target.parentFile
                    ?.listFiles { file ->
                        // The stable symlink points at the new file, so it must not be swept away.
                        file.isFile &&
                            !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                            file.name.endsWith(".AppImage", ignoreCase = true) &&
                            file != target &&
                            file.absoluteFile != running
                    }
                    .orEmpty()
                    .forEach { old -> runCatching { old.delete() } }
            }
            onProgress(1f)
            target
        }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0,
    )
}