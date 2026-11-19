/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lokotube.desktop.data.SavedVideo
import com.lokotube.desktop.data.VideoDownloads
import com.lokotube.desktop.playback.DownloadProgress
import com.lokotube.desktop.playback.VideoPlayer
import com.lokotube.desktop.playback.VideoState
import com.lokotube.innertube.YouTube
import com.lokotube.innertube.models.VideoItem
import com.lokotube.innertube.models.Comment
import com.lokotube.innertube.models.WatchInfo
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** A YouTube video: the frame with its transport, an info strip, and Up next / Comments beside it. */
@Composable
fun VideoScreen(videoId: String, app: AppState) {
    val player = app.videoPlayer
    val state by player.state.collectAsState()
    val position by player.position.collectAsState()
    val frame by player.frame.collectAsState()
    val scope = rememberCoroutineScope()

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
        var info by remember(videoId) { mutableStateOf<WatchInfo?>(null) }
        var side by remember(videoId) { mutableStateOf(VideoSide.UpNext) }
        var comments by remember(videoId) { mutableStateOf<List<Comment>>(emptyList()) }
        var commentCount by remember(videoId) { mutableStateOf<String?>(null) }
        var commentToken by remember(videoId) { mutableStateOf<String?>(null) }
        var commentsLoading by remember(videoId) { mutableStateOf(false) }
        var commentsStarted by remember(videoId) { mutableStateOf(false) }

        LaunchedEffect(videoId) { info = YouTube.watchInfo(videoId).getOrNull() }

        // The comments section is a `next` continuation: the watch page only hands out the token.
        suspend fun loadComments(first: Boolean) {
            val token = if (first) info?.commentsToken else commentToken
            if (token.isNullOrBlank() || commentsLoading) return
            commentsLoading = true
            val page = YouTube.comments(token).getOrNull()
            if (page != null) {
                comments = if (first) page.comments else comments + page.comments
                commentCount = page.countText ?: commentCount
                commentToken = page.continuation
            }
            commentsLoading = false
        }
        LaunchedEffect(side, info?.commentsToken) {
            if (side == VideoSide.Comments && !commentsStarted && info != null) {
                commentsStarted = true
                loadComments(first = true)
            }
        }

        fun toggleSubscribe() {
            val current = info ?: return
            val channelId = current.channelId ?: return
            val target = !current.subscribed
            scope.launch {
                val result = YouTube.subscribeChannel(channelId, subscribe = target)
                if (result.isSuccess) {
                    info = current.copy(subscribed = target)
                    app.showToast(if (target) "Subscribed to ${current.channelName.orEmpty()}" else "Unsubscribed")
                } else {
                    app.showToast("Sign in to subscribe")
                }
            }
        }

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!app.videoFullscreen) {
                    VideoInfoBar(
                        info = info,
                        comments = commentCount,
                        onSubscribe = ::toggleSubscribe,
                        onComments = { side = VideoSide.Comments },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
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
            }
            if (!app.videoFullscreen) {
                VideoSidePanel(
                    side = side,
                    onSide = { side = it },
                    info = info,
                    related = related,
                    comments = comments,
                    commentCount = commentCount,
                    loading = commentsLoading,
                    hasMore = !commentToken.isNullOrBlank(),
                    onMore = { scope.launch { loadComments(first = false) } },
                    app = app,
                    modifier = Modifier.width(UP_NEXT_WIDTH).fillMaxHeight(),
                )
            }
        }
    }
}

/** "2,462,493 Comments" -> "2,462,493": the label already says what the number is. */
private fun commentCountLabel(count: String?): String? = count?.substringBefore(" Comments", count)?.trim()?.takeIf { it.isNotBlank() }

/** Which list sits beside the video. */
private enum class VideoSide { UpNext, Comments, Description }

/** Title, channel and subscribe, above the frame — the watch page's own header strip. */
@Composable
private fun VideoInfoBar(info: WatchInfo?, comments: String?, onSubscribe: () -> Unit, onComments: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(INFO_BAR).padding(horizontal = Spacing.M),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                info?.title.orEmpty().ifBlank { " " },
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    info?.channelName.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                info?.subscriberText?.let {
                    Text(" · $it", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
                }
            }
        }
        if (comments != null) {
            Text(
                commentCountLabel(comments).orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.clip(ErShape.Pill).clickable(onClick = onComments).handCursor().padding(horizontal = Spacing.M),
            )
        }
        Spacer(Modifier.width(Spacing.S))
        SubscribePill(subscribed = info?.subscribed == true, onClick = onSubscribe)
    }
}

@Composable
private fun SubscribePill(subscribed: Boolean, onClick: () -> Unit) {
    Text(
        if (subscribed) "Subscribed" else "Subscribe",
        style = MaterialTheme.typography.labelLarge,
        color = if (subscribed) Color.White else SUBSCRIBE_INK,
        modifier =
            Modifier
                .clip(ErShape.Pill)
                .background(if (subscribed) Color.White.copy(alpha = 0.10f) else VIDEO_ACCENT)
                .border(1.dp, if (subscribed) Color.White.copy(alpha = 0.35f) else Color.Transparent, ErShape.Pill)
                .clickable(onClick = onClick)
                .handCursor()
                .padding(horizontal = Spacing.L, vertical = Spacing.S),
    )
}

/** Up next / Comments, the two lists that belong beside the video. */
@Composable
private fun VideoSidePanel(
    side: VideoSide,
    onSide: (VideoSide) -> Unit,
    info: WatchInfo?,
    related: List<VideoItem>?,
    comments: List<Comment>,
    commentCount: String?,
    loading: Boolean,
    hasMore: Boolean,
    onMore: () -> Unit,
    app: AppState,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.background(colors.surface)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.L),
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.M, top = Spacing.S, end = Spacing.M),
        ) {
            SideTab("Up next", side == VideoSide.UpNext) { onSide(VideoSide.UpNext) }
            SideTab(commentCountLabel(commentCount)?.let { "Comments · $it" } ?: "Comments", side == VideoSide.Comments) { onSide(VideoSide.Comments) }
            SideTab("Description", side == VideoSide.Description) { onSide(VideoSide.Description) }
        }
        when (side) {
            VideoSide.UpNext ->
                when {
                    related == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(Spacing.M))
                    related.isEmpty() -> Text("Nothing here", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(Spacing.M))
                    else ->
                        ScrollingColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = Spacing.M, end = Spacing.M, top = Spacing.S, bottom = Spacing.XXL),
                        ) {
                            items(related, key = { it.id }, contentType = { "video" }) { VideoRow(it, app) }
                        }
                }
            VideoSide.Description ->
                ScrollingColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.M, end = Spacing.M, top = Spacing.S, bottom = Spacing.XXL),
                ) {
                    item(key = "description") {
                        Column {
                            info?.viewCount?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                            }
                            info?.publishedDate?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(Spacing.M))
                            // Selectable: the description carries links the user wants to copy.
                            SelectionContainer {
                                Text(
                                    info?.description?.takeIf { it.isNotBlank() } ?: "No description for this video.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurface,
                                )
                            }
                        }
                    }
                }
            VideoSide.Comments ->
                when {
                    loading && comments.isEmpty() ->
                        Text("Loading comments…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(Spacing.M))
                    comments.isEmpty() ->
                        Text("No comments yet", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(Spacing.M))
                    else ->
                        ScrollingColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = Spacing.M, end = Spacing.M, top = Spacing.S, bottom = Spacing.XXL),
                        ) {
                            items(comments, key = { it.id }, contentType = { "comment" }) { CommentRow(it) }
                            if (hasMore) {
                                item(key = "more") {
                                    Text(
                                        if (loading) "Loading…" else "Show more comments",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = VIDEO_ACCENT,
                                        modifier = Modifier.fillMaxWidth().clip(ErShape.Pill).clickable(enabled = !loading, onClick = onMore).handCursor().padding(Spacing.M),
                                    )
                                }
                            }
                        }
                }
        }
    }
}

@Composable
private fun SideTab(label: String, selected: Boolean, onClick: () -> Unit) {
    // The underline is drawn by the text itself: a fillMaxWidth() line inside a Row would stretch to
    // the whole sidebar and push the other tab out of view.
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .clip(ErShape.Small)
                .clickable(onClick = onClick)
                .handCursor()
                .drawBehind {
                    if (selected) {
                        drawLine(
                            VIDEO_ACCENT,
                            Offset(0f, size.height),
                            Offset(size.width, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
                .padding(bottom = Spacing.XS),
    )
}

/** One comment: avatar, author and when, the text, then its likes and replies. */
@Composable
private fun CommentRow(comment: Comment) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.S)) {
        Art(comment.avatar, Modifier.size(Size.IconButton).clip(CircleShape), CircleShape, pixels = 96)
        Spacer(Modifier.width(Spacing.S))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (comment.byCreator) Text(" · Creator", style = MaterialTheme.typography.labelSmall, color = VIDEO_ACCENT)
            }
            comment.publishedTime?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.XXS))
            Text(
                comment.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            if (comment.likes != null || comment.replies != null) {
                Spacer(Modifier.height(Spacing.XXS))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    comment.likes?.let {
                        Icon(icon("favorite_border"), null, tint = colors.onSurfaceVariant, modifier = Modifier.size(Spacing.M))
                        Spacer(Modifier.width(Spacing.XXS))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        Spacer(Modifier.width(Spacing.S))
                    }
                    comment.replies?.let {
                        Text("$it replies", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
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

        VideoDownloadButton(app, state.videoId, title = null, modifier = Modifier.size(Size.Control))

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

/**
 * Software decode holds real time at 1080p (measured: 1.00x, ~1 core); 1440p/4K fall behind the
 * clock on this class of machine, so they stay an explicit choice in the quality menu.
 */
private const val DEFAULT_HEIGHT = 1080

/** Width of the "Up next" / Comments sidebar next to the playing video. */
private val UP_NEXT_WIDTH = 340.dp

/** Height of the title/channel/subscribe strip above the frame. */
private val INFO_BAR = 56.dp

/** Ink on the teal Subscribe pill. */
private val SUBSCRIBE_INK = Color(0xFF062B24)

/** The dark theme's teal accent, for the play/pause and transport accents over the dark video. */
private val VIDEO_ACCENT = Color(0xFF2DD4BF)

/**
 * Saves the video to the user's Videos folder. One button, three states: press to download, a ring
 * while it runs (press again to cancel), then a check that opens the saved file.
 */
@Composable
internal fun VideoDownloadButton(
    app: AppState,
    videoId: String?,
    title: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    iconSize: Dp = Size.IconSmall,
) {
    val id = videoId ?: return
    val downloads by app.videoDownloader.state.collectAsState()
    val progress = downloads[id]
    val saved = VideoDownloads.find(id)
    val failure = progress?.failure
    LaunchedEffect(failure) {
        if (failure != null) app.showToast(failure)
    }
    val savedNow = progress?.file?.takeIf { progress.phase == DownloadProgress.Phase.DONE }
    LaunchedEffect(savedNow) {
        savedNow?.let { app.showToast("Saved to ${it.parentFile.name}/${it.name}") }
    }
    when {
        progress?.running == true -> {
            val fraction = progress.fraction
            Box(
                modifier.glass(CircleShape).handCursor().clickable { app.videoDownloader.cancel(id) },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { fraction },
                    color = VIDEO_ACCENT,
                    trackColor = Color.White.copy(alpha = 0.18f),
                    strokeWidth = Spacing.XXS,
                    modifier = Modifier.size(Size.Control - Spacing.S),
                )
                Text(
                    "${(fraction * 100).toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        saved != null ->
            SymbolButton("check", "Downloaded — open the file", modifier, tint = VIDEO_ACCENT, iconSize = iconSize, glass = true) {
                openSavedVideo(saved)
            }
        else ->
            SymbolButton("download", "Download", modifier, tint = tint, iconSize = iconSize, glass = true) {
                app.videoDownloader.start(id, title.orEmpty())
            }
    }
}

/** Hands a saved video to the system's player. */
fun openSavedVideo(video: SavedVideo) {
    runCatching { Desktop.getDesktop().open(File(video.path)) }
}
