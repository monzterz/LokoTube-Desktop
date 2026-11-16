/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.music.lyrics

import android.content.Context
import com.lokotube.innertube.YouTube
import com.lokotube.music.constants.EnableYouTubeSubtitleLyricsKey
import com.lokotube.music.utils.dataStore
import com.lokotube.music.utils.get

object YouTubeSubtitleLyricsProvider : LyricsProvider {
    override val name = "YouTube Subtitle"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableYouTubeSubtitleLyricsKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = YouTube.transcript(id)
}
