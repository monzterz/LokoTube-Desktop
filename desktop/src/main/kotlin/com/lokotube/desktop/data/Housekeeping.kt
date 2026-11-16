/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.data

import com.lokotube.desktop.auth.EdgeSignIn
import com.lokotube.desktop.playback.LyricsRepository
import com.lokotube.desktop.playback.LyricsTranslator
import com.lokotube.desktop.playback.SongCache
import java.io.File

/** Lyrics and translations kept: a song's are a few kilobytes, so this is a few megabytes at most. */
const val KEEP_FILES = 3000

/** Deletes all but the [count] files in [dir] used most lately. */
fun keepNewest(dir: File, count: Int) {
    dir.listFiles().orEmpty().filter(File::isFile).sortedByDescending(File::lastModified).drop(count).forEach(File::delete)
}

/**
 * What the app keeps on disk, looked after once at start, as a music app does: songs to play again
 * up to the size chosen in Settings, lyrics and covers for what was played lately, downloads as
 * long as the user wants them, and nothing left behind that has no use.
 */
object Housekeeping {
    fun run() {
        runCatching { SongCache.tidy() }
        runCatching { LyricsRepository.tidy() }
        runCatching { LyricsTranslator.tidy() }
        runCatching { EdgeSignIn.tidyProfile() }
    }
}
