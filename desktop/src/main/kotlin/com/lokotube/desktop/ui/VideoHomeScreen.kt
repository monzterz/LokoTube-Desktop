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
import androidx.compose.foundation.lazy.LazyRow
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
    val live by produceState<List<VideoItem>?>(null) { value = YouTube.liveVideos().getOrNull() }
    val trending by produceState<List<VideoItem>?>(null) { value = YouTube.trendingVideos().getOrNull() }
    val recommended by produceState<List<VideoItem>?>(null) { value = YouTube.recommendedVideos().getOrNull() }

    ScrollingColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.L, top = Spacing.M, end = Spacing.L, bottom = Spacing.XXL),
    ) {
        item(key = "live") { VideoShelf("Live", live, app) }
        item(key = "trending") { VideoShelf("Trending", trending, app) }
        item(key = "recommended") { VideoShelf("Recommended", recommended, app) }
    }
}

/** A titled row of video cards that scrolls sideways, like YouTube's home shelves. */
@Composable
private fun VideoShelf(title: String, videos: List<VideoItem>?, app: AppState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(bottom = Spacing.M)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.S))
        when {
            videos == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            videos.isEmpty() -> Text("Nothing here", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            else ->
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    items(videos, key = { it.id }, contentType = { "video" }) { video ->
                        Box(Modifier.width(240.dp)) { VideoCard(video, app) }
                    }
                }
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
