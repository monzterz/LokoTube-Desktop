/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lokotube.desktop.playback.VideoPlayer
import com.lokotube.desktop.playback.VideoState
import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.VideoItem
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** A YouTube video: the decoded frame on the left, an "Up next" sidebar on the right (hidden in fullscreen). */
@Composable
fun VideoScreen(videoId: String, app: AppState) {
    val player = app.videoPlayer
    val state by player.state.collectAsState()
    val position by player.position.collectAsState()
    val frame by player.frame.collectAsState()

    LaunchedEffect(videoId) {
        if (state.videoId != videoId) player.play(videoId, height = DEFAULT_HEIGHT)
    }
    // During AnimatedContent's cross-fade both the old and new VideoScreen coexist briefly; the old
    // one's onDispose fires *after* the new one's play(). So only stop the player when this screen's
    // video is still the active one — i.e. we're really leaving the video, not handing off to another.
    DisposableEffect(videoId) {
        onDispose {
            if (player.state.value.videoId == videoId) player.stop()
        }
    }

    // The video is inherently dark; the transport uses the dark theme's glass so its controls match
    // the music player bar (same teal play/pause, thin seek bar and frosted strip).
    LokotubeTheme(forceDark = true) {
        val hazeState = rememberHazeState()
        val related by produceState<List<VideoItem>?>(null, videoId) {
            value = YouTube.relatedVideos(videoId).getOrNull()
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
                Box(Modifier.fillMaxSize().hazeSource(hazeState), contentAlignment = Alignment.Center) {
                    val image = frame
                    val error = state.error
                    if (image != null) {
                        Image(
                            bitmap = image.toComposeImageBitmap(),
                            contentDescription = "Video",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().clickable { player.toggle() },
                        )
                    } else {
                        Box(Modifier.fillMaxSize().clickable { player.toggle() }, contentAlignment = Alignment.Center) {
                            when {
                                error != null -> Text(error, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                state.loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                else -> Text("Play", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                VideoControls(player, state, position, hazeState, app, Modifier.align(Alignment.BottomCenter))
            }
            if (!app.videoFullscreen) {
                UpNextSidebar(related, app, Modifier.width(UP_NEXT_WIDTH).fillMaxHeight())
            }
        }
    }
}

/** The YouTube-style "Up next" list shown to the right of the playing video. */
@Composable
private fun UpNextSidebar(videos: List<VideoItem>?, app: AppState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.background(colors.surface)) {
        Text(
            "Up next",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = Spacing.M, top = Spacing.M, end = Spacing.M, bottom = Spacing.S),
        )
        when {
            videos == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.M))
            videos.isEmpty() -> Text("Nothing here", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.M))
            else ->
                ScrollingColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.M, end = Spacing.M, top = Spacing.S, bottom = Spacing.XXL),
                ) {
                    items(videos, key = { it.id }, contentType = { "video" }) { VideoRow(it, app) }
                }
        }
    }
}

@Composable
private fun VideoControls(player: VideoPlayer, state: VideoState, position: Long, hazeState: HazeState, app: AppState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    var qualityOpen by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val edge = Color.White.copy(alpha = 0.14f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        modifier =
            modifier
                .fillMaxWidth()
                .height(Size.PlayerBar)
                .hazeBlur(input = HazeInput.Backdrop(hazeState), style = frostedStyle(dark = true))
                .drawBehind { drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
                .padding(horizontal = Spacing.L),
    ) {
        IconButton(onClick = player::toggle, modifier = Modifier.size(Size.IconButton).glass(CircleShape).handCursor()) {
            if (state.loading) {
                CircularProgressIndicator(strokeWidth = Spacing.XXS, color = VIDEO_ACCENT, modifier = Modifier.size(Size.IconButton / 2))
            } else {
                Icon(
                    icon(if (state.playing) "pause" else "play"),
                    if (state.playing) "Pause" else "Play",
                    tint = VIDEO_ACCENT,
                    modifier = Modifier.size(Size.IconButton / 2),
                )
            }
        }
        val max = state.durationMs.coerceAtLeast(1).toFloat()
        val shown = dragPosition ?: position.toFloat()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(formatTime(shown.toLong()), style = MaterialTheme.typography.bodySmall, color = Color.White)
            Spacer(Modifier.width(Spacing.S))
            ThinSlider(
                value = shown.coerceIn(0f, max) / max,
                onChange = { dragPosition = it * max },
                onFinished = {
                    dragPosition?.let { player.seek(it.toLong()) }
                    dragPosition = null
                },
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.S))
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
        SymbolButton(
            if (state.muted) "volume_off" else "volume_up",
            if (state.muted) "Unmute" else "Mute",
            Modifier.size(Size.Control),
            tint = Color.White,
            iconSize = Size.IconSmall,
            glass = true,
        ) { player.toggleMute() }

        Box {
            SymbolButton("settings", "Quality", Modifier.size(Size.Control), tint = Color.White, iconSize = Size.IconSmall, glass = true) { qualityOpen = true }
            DropdownMenu(expanded = qualityOpen, onDismissRequest = { qualityOpen = false }, shape = ErShape.Medium, containerColor = colors.surfaceContainerHighest) {
                state.availableHeights.forEach { height ->
                    DropdownMenuItem(
                        text = { Text(if (height >= 2160) "$height (4K)" else "${height}p") },
                        onClick = {
                            qualityOpen = false
                            player.setHeight(height)
                        },
                    )
                }
            }
        }
        SymbolButton(
            "fullscreen",
            if (app.videoFullscreen) "Exit full screen" else "Full screen",
            Modifier.size(Size.Control),
            tint = Color.White,
            iconSize = Size.IconSmall,
            glass = true,
        ) { app.toggleVideoFullscreen() }
    }
}

/** Software VP9/AVC decode is heavy; start at 720p and let the user raise it in the quality menu. */
private const val DEFAULT_HEIGHT = 720

/** Width of the "Up next" sidebar next to the playing video. */
private val UP_NEXT_WIDTH = 340.dp

/** The dark theme's teal accent, for the play/pause and transport accents over the dark video. */
private val VIDEO_ACCENT = Color(0xFF2DD4BF)
