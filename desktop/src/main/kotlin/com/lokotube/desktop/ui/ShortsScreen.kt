/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.VideoItem

/** Shorts feed from the home reel shelf. Empty for guests (personalized feed). */
@Composable
fun ShortsScreen(app: AppState) {
    val shorts by produceState<List<VideoItem>?>(null) {
        value = YouTube.homeShorts().getOrNull()
    }

    Column(Modifier.fillMaxSize().padding(start = Spacing.L, top = Spacing.S)) {
        Text("Shorts", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = Spacing.M))
        val feed = shorts
        when {
            feed == null -> Message("Loading…")
            feed.isEmpty() -> Message("No shorts yet — sign in for your feed")
            else ->
                ScrollingColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(end = Spacing.S, bottom = Spacing.XXL)) {
                    items(feed, key = { it.id }, contentType = { "short" }) { VideoRow(it, app) }
                }
        }
    }
}
