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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import com.lokotube.desktop.playback.VideoState
import kotlin.math.roundToLong

/** A YouTube video: the decoded frame full-bleed, with transport controls over it. */
@Composable
fun VideoScreen(videoId: String, app: AppState) {
    val player = app.videoPlayer
    val state by player.state.collectAsState()
    val position by player.position.collectAsState()
    val frame by player.frame.collectAsState()

    LaunchedEffect(videoId) {
        if (state.videoId != videoId) player.play(videoId, height = DEFAULT_HEIGHT)
    }
    DisposableEffect(videoId) {
        onDispose { player.stop() }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val image = frame
            if (image != null) {
                Image(
                    bitmap = image.toComposeImageBitmap(),
                    contentDescription = "Video",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clickable { player.toggle() },
                )
            } else {
                Box(Modifier.fillMaxSize().clickable { player.toggle() }, contentAlignment = Alignment.Center) {
                    val error = state.error
                    when {
                        error != null -> Text(error, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        state.loading -> CircularProgressIndicator(color = Color.White)
                        else -> Text("Play", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        VideoControls(player, state, position)
    }
}

@Composable
private fun VideoControls(player: com.lokotube.desktop.playback.VideoPlayer, state: VideoState, position: Long) {
    val colors = MaterialTheme.colorScheme
    var qualityOpen by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        modifier = Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = Spacing.L, vertical = Spacing.S),
    ) {
        SymbolButton(
            if (state.playing) "pause" else "play",
            if (state.playing) "Pause" else "Play",
            Modifier.size(Size.IconButton),
            tint = Color.White,
        ) { player.toggle() }

        Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelMedium)

        Slider(
            value = dragPosition ?: position.toFloat(),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { player.seek(it.toLong()) }
                dragPosition = null
            },
            valueRange = 0f..(state.durationMs.coerceAtLeast(1)).toFloat(),
            modifier = Modifier.weight(1f),
        )

        Text(formatTime(state.durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)

        SymbolButton(
            "volume_up",
            if (state.muted) "Unmute" else "Mute",
            Modifier.size(Size.Control),
            tint = Color.White,
        ) { player.toggleMute() }

        Box {
            SymbolButton("settings", "Quality", Modifier.size(Size.Control), tint = Color.White) { qualityOpen = true }
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
    }
}

/** Software VP9 decode is heavy; start at 720p and let the user raise it in the quality menu. */
private const val DEFAULT_HEIGHT = 720
