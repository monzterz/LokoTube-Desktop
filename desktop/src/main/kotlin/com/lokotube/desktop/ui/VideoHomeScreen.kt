/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.VideoItem

/** A dedicated section for regular YouTube videos, separate from the music Home. */
@Composable
fun VideoHomeScreen(app: AppState) {
    val home by produceState<List<VideoItem>?>(null) {
        value = YouTube.homeVideos().getOrNull()
    }
    val feed = home
    when {
        feed == null -> Message("Loading…")
        feed.isEmpty() -> Message("No feed yet — sign in for your home feed, or search above")
        else ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                contentPadding = PaddingValues(start = Spacing.L, top = Spacing.M, end = Spacing.L, bottom = Spacing.XXL),
                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
                verticalArrangement = Arrangement.spacedBy(Spacing.M),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(feed, key = { it.id }, contentType = { "video" }) { VideoCard(it, app) }
            }
    }
}

/** Results for a video search from the top bar, as a list like YouTube's search page. */
@Composable
fun VideoSearchScreen(query: String, app: AppState) {
    val results by produceState<List<VideoItem>?>(null, query) {
        value = YouTube.searchVideos(query).getOrNull()
    }
    val found = results
    when {
        found == null -> Message("Searching…")
        found.isEmpty() -> Message("Nothing found")
        else ->
            Column(Modifier.fillMaxSize().padding(start = Spacing.L, top = Spacing.M, end = Spacing.L)) {
                Text("Results for “$query”", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = Spacing.M))
                ScrollingColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(end = Spacing.S, bottom = Spacing.XXL)) {
                    items(found, key = { it.id }, contentType = { "video" }) { VideoRow(it, app) }
                }
            }
    }
}

/** A YouTube-desktop-style card: 16:9 thumbnail, then title, channel and "views · time ago". */
@Composable
private fun VideoCard(video: VideoItem, app: AppState) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().clickable { app.go(Route.Video(video.id)) }.handCursor(),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(ErShape.Medium)) {
            Art(video.thumbnail, Modifier.fillMaxSize(), ErShape.Medium, pixels = 360)
            video.duration?.let { duration ->
                Text(
                    formatTime(duration * 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Spacing.XS)
                            .clip(ErShape.Small)
                            .background(Color.Black.copy(alpha = 0.8f))
                            .padding(horizontal = Spacing.S, vertical = Spacing.XXS),
                )
            }
        }
        Spacer(Modifier.height(Spacing.S))
        Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!video.channel.isNullOrBlank()) {
            Text(video.channel, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val meta = listOfNotNull(video.viewCountText, video.publishedText).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A YouTube-search-style row: thumbnail on the left, details on the right. */
@Composable
internal fun VideoRow(video: VideoItem, app: AppState) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { app.go(Route.Video(video.id)) }
                .handCursor()
                .padding(end = Spacing.L, bottom = Spacing.M),
    ) {
        Box(Modifier.width(168.dp).height(94.dp).clip(ErShape.Medium)) {
            Art(video.thumbnail, Modifier.fillMaxSize(), ErShape.Medium, pixels = 240)
            video.duration?.let { duration ->
                Text(
                    formatTime(duration * 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Spacing.XS)
                            .clip(ErShape.Small)
                            .background(Color.Black.copy(alpha = 0.8f))
                            .padding(horizontal = Spacing.S, vertical = Spacing.XXS),
                )
            }
        }
        Spacer(Modifier.width(Spacing.M))
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Spacing.XXS))
            if (!video.channel.isNullOrBlank()) {
                Text(video.channel, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val meta = listOfNotNull(video.viewCountText, video.publishedText).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
