/** LOKOTUBE (C) 2026 — SPIKE (throwaway): cek update live dari GitHub + pilih aset utk OS ini. */
package com.lokotube.desktop.playback

import com.lokotube.desktop.data.Updates
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UpdatesLiveSpikeTest {
    @Test
    fun latestReleaseFromGitHub() {
        runBlocking {
            val latest = Updates.latest().getOrThrow()
            println("current=${Updates.currentVersion} latest=${latest.version} name=${latest.name}")
            println("assets=${latest.assets.map { it.name }}")
            val asset = latest.assetForThisSystem()
            println("asset for this system=${asset?.name} (${asset?.sizeBytes} bytes)")
            println("notes head: ${latest.notes.take(100).replace("\n", " / ")}")
            check(latest.version.isNotBlank()) { "versi rilis kosong" }
            check(latest.assets.isNotEmpty()) { "tidak ada aset" }
            check(asset != null && asset.name.contains("appimage", ignoreCase = true)) { "aset Linux bukan AppImage: ${asset?.name}" }
            check(Updates.isNewer("99.0.0", latest.version)) { "perbandingan versi salah" }
        }
    }
}
