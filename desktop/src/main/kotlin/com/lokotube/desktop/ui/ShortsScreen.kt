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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lokotube.desktop.playback.VideoPlayer
import com.lokotube.desktop.playback.VideoState
import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.VideoItem
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

/**
 * Shorts: a vertical, snap-scrolling pager. One [VideoPlayer] is reused for every page, so moving to
 * the next Short restarts playback on the new video while the previous one stops; the next Short's
 * streams are resolved in the background so the switch is quick.
 */
@Composable
fun ShortsScreen(app: AppState) {
    val feed by produceState<List<VideoItem>?>(null) { value = YouTube.shortsFeed().getOrNull() }
    // Shorts are inherently dark, like the video player.
    LokotubeTheme(forceDark = true) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val items = feed
            when {
                items == null -> CircularProgressIndicator(color = SHORTS_ACCENT, modifier = Modifier.align(Alignment.Center))
                items.isEmpty() -> Message("No Shorts right now")
                else -> ShortsPager(items, app)
            }
        }
    }
}

@Composable
private fun ShortsPager(feed: List<VideoItem>, app: AppState) {
    val player = app.videoPlayer
    val pagerState = rememberPagerState(pageCount = { feed.size })
    val scope = rememberCoroutineScope()
    val state by player.state.collectAsState()
    val position by player.position.collectAsState()
    val frame by player.frame.collectAsState()

    // Play whatever page the pager settles on; nothing starts before that (no overlap on a flick).
    LaunchedEffect(pagerState.settledPage, feed) {
        val item = feed.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        if (player.state.value.videoId != item.id) player.play(item.id, SHORTS_HEIGHT)
        feed.getOrNull(pagerState.settledPage + 1)?.let { player.prefetch(it.id, SHORTS_HEIGHT) }
    }
    // Autoplay starts muted (the web player does too); the user's choice then sticks.
    LaunchedEffect(Unit) { player.setMuted(true) }

    // Leaving Shorts stops the player — unless what is playing is no longer ours (the cross-fade
    // hand-off to the video screen), mirroring VideoScreen's guard.
    val playingId by rememberUpdatedState(feed.getOrNull(pagerState.currentPage)?.id)
    DisposableEffect(Unit) {
        onDispose { if (player.state.value.videoId == playingId) player.stop() }
    }

    fun go(delta: Int) {
        val target = (pagerState.currentPage + delta).coerceIn(0, feed.lastIndex)
        if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
    }

    // The window's key handler drives the pager through this hook (arrow keys, Page Up/Down).
    DisposableEffect(Unit) {
        app.shortsNavigate = ::go
        onDispose { app.shortsNavigate = null }
    }

    // Roll on to the next Short when the current one ends, like the web player. Keyed on the
    // player's own state (a StateFlow is invisible to snapshotFlow): `playing` flipping to false at
    // the end of the file is the trigger.
    LaunchedEffect(state.playing, state.videoId, state.durationMs, pagerState.settledPage) {
        val page = pagerState.settledPage
        val item = feed.getOrNull(page) ?: return@LaunchedEffect
        val ended = !state.playing && state.videoId == item.id && state.durationMs > 0 && position >= state.durationMs - 300
        if (ended && page < feed.lastIndex) pagerState.animateScrollToPage(page + 1)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The video keeps its 9:16 shape at the window's height, and the actions sit right next to
        // it (the web player's layout) instead of drifting to the window edge on a wide monitor.
        val videoWidth = (maxHeight * SHORTS_ASPECT).coerceAtMost(maxWidth * 0.6f)
        val current = feed.getOrNull(pagerState.currentPage)
        Art(current?.thumbnail, Modifier.fillMaxSize().alpha(0.16f), shape = ErShape.Large, pixels = 720)
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            VerticalPager(state = pagerState, modifier = Modifier.width(videoWidth).fillMaxHeight()) { page ->
                val item = feed[page]
                val active = page == pagerState.currentPage
                ShortsPage(
                    item = item,
                    active = active,
                    frame = if (active) frame else null,
                    state = if (active) state else null,
                    position = if (active) position else 0L,
                    onToggle = { if (active) player.toggle() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.S),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = Spacing.M),
            ) {
                SymbolButton("expand_less", "Previous Short", Modifier.size(Size.Control), tint = Color.White, glass = true) { go(-1) }
                SymbolButton("expand_more", "Next Short", Modifier.size(Size.Control), tint = Color.White, glass = true) { go(1) }
                SymbolButton(
                    if (state.muted) "volume_off" else "volume_up",
                    if (state.muted) "Unmute" else "Mute",
                    Modifier.size(Size.Control),
                    tint = Color.White,
                    glass = true,
                ) { player.toggleMute() }
            }
        }
        Text(
            "${pagerState.currentPage + 1} / ${feed.size}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.L),
        )
    }
}

@Composable
private fun ShortsPage(
    item: VideoItem,
    active: Boolean,
    frame: BufferedImage?,
    state: VideoState?,
    position: Long,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black).clip(ErShape.Large).clickable(enabled = active) { onToggle() }) {
        // The vertical thumbnail stands in until decoded frames arrive, so a page is never just a
        // black rectangle while its stream is still opening.
        Art(item.thumbnail, Modifier.fillMaxSize(), ErShape.Large, pixels = 720)
        if (frame != null) {
            Image(
                bitmap = frame.toComposeImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val current = state
        when {
            current?.error != null ->
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Text(
                        current.error,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.L),
                    )
                }
            active && current?.loading == true ->
                CircularProgressIndicator(color = SHORTS_ACCENT, modifier = Modifier.align(Alignment.Center))
            active && current?.playing == false && frame != null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SymbolButton("play", "Play", Modifier.size(Size.PlayButton), tint = Color.White, glass = true, onClick = onToggle)
                }
        }

        // A soft scrim under the caption: white on a bright frame (a wardrobe, a white wall) is
        // otherwise unreadable.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(SCRIM_HEIGHT)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
        )

        // Title and view count over the video's own lower third.
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = Spacing.M, bottom = Spacing.M, end = Spacing.M),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.viewCountText?.let {
                Spacer(Modifier.height(Spacing.XXS))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            }
        }

        // One thin progress line: Shorts are far too short for a seek bar.
        val duration = current?.durationMs ?: 0L
        if (active && duration > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)) {
                Box(Modifier.fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).height(3.dp).background(SHORTS_ACCENT))
            }
        }
    }
}

/** How much of the page height the caption's scrim covers. */
private const val SCRIM_HEIGHT = 0.32f

/** Vertical video: 9:16. */
private const val SHORTS_ASPECT = 9f / 16f

/** Software decode at 1080p+ is heavy; Shorts are small, so 720p is plenty. */
private const val SHORTS_HEIGHT = 720

/** The dark theme's teal accent, for the progress line and spinner. */
private val SHORTS_ACCENT = Color(0xFF2DD4BF)
