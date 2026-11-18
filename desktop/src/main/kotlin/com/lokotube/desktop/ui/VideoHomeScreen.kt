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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.VideoItem

/** A dedicated section for regular YouTube videos, separate from the music Home. */
@Composable
fun VideoHomeScreen(app: AppState) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val results by produceState<List<VideoItem>?>(null, submitted) {
        value = if (submitted.isBlank()) null else YouTube.searchVideos(submitted).getOrNull()
    }
    val home by produceState<List<VideoItem>?>(null) {
        value = YouTube.homeVideos().getOrNull()
    }

    Column(Modifier.fillMaxSize().padding(start = Spacing.L, top = Spacing.S)) {
        Text("Video", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = Spacing.M))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search YouTube videos") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
            modifier = Modifier.fillMaxWidth().padding(end = Spacing.L),
        )
        Spacer(Modifier.height(Spacing.M))
        val found = results
        val feed = home
        if (submitted.isNotBlank()) {
            when {
                found == null -> Message("Searching…")
                found.isEmpty() -> Message("Nothing found")
                else ->
                    ScrollingColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(end = Spacing.S, bottom = Spacing.XXL)) {
                        items(found, key = { it.id }, contentType = { "video" }) { VideoRow(it, app) }
                    }
            }
        } else {
            when {
                feed == null -> Message("Loading…")
                feed.isEmpty() -> Message("No feed yet — sign in for your home feed, or search above")
                else ->
                    ScrollingColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(end = Spacing.S, bottom = Spacing.XXL)) {
                        items(feed, key = { it.id }, contentType = { "video" }) { VideoRow(it, app) }
                    }
            }
        }
    }
}

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
