/**
 * LOKOTUBE (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.music.ui.screens

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.lokotube.music.LocalDatabase
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.lokotube.innertube.models.AlbumItem
import com.lokotube.innertube.models.ArtistItem
import com.lokotube.innertube.models.PlaylistItem
import com.lokotube.innertube.models.SongItem
import com.lokotube.innertube.models.WatchEndpoint
import com.lokotube.innertube.models.YTItem
import com.lokotube.music.LocalNavController
import com.lokotube.music.LocalPlayerConnection
import com.lokotube.music.db.entities.Album
import com.lokotube.music.db.entities.Artist
import com.lokotube.music.db.entities.LocalItem
import com.lokotube.music.db.entities.Playlist
import com.lokotube.music.db.entities.Song
import com.lokotube.music.models.toMediaMetadata
import com.lokotube.music.playback.queues.YouTubeQueue
import com.lokotube.music.ui.component.LocalMenuState
import com.lokotube.music.ui.menu.AlbumMenu
import com.lokotube.music.ui.menu.ArtistMenu
import com.lokotube.music.ui.menu.SongMenu
import com.lokotube.music.ui.menu.YouTubeAlbumMenu
import com.lokotube.music.ui.menu.YouTubeArtistMenu
import com.lokotube.music.ui.menu.YouTubePlaylistMenu
import com.lokotube.music.ui.menu.YouTubeSongMenu

/**
 * Click and long-press behavior shared by every screen that lists songs, albums, artists and
 * playlists: tap plays (songs, as a radio) or opens the item; long-press opens its menu.
 */
@Stable
class ItemActions internal constructor(
    val open: (Any) -> Unit,
    val menu: (Any) -> Unit,
)

@Composable
fun rememberItemActions(): ItemActions {
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val database = LocalDatabase.current
    return remember(navController, playerConnection, menuState) {
        ItemActions(
            open = { item ->
                when (item) {
                    is SongItem -> {
                        if (item.id == playerConnection?.mediaMetadata?.value?.id) {
                            playerConnection?.openPlayer()
                        } else {
                            playerConnection?.playQueue(
                                YouTubeQueue(item.endpoint ?: WatchEndpoint(videoId = item.id), item.toMediaMetadata()),
                            )
                        }
                    }
                    is AlbumItem -> navController.navigate("album/${item.id}")
                    is ArtistItem -> navController.navigate("artist/${item.id}")
                    is PlaylistItem -> scope.launch {
                        // A pinned or found playlist that is in the library opens as that playlist,
                        // not as someone else's online copy (which showed empty / a follow heart).
                        val local = if (item.id.startsWith("LP")) null else database.playlistByBrowseId(item.id).first()
                        navController.navigate(
                            when {
                                item.id.startsWith("LP") -> "local_playlist/${item.id}"
                                local != null -> "local_playlist/${local.id}"
                                else -> "online_playlist/${item.id}"
                            },
                        )
                    }
                    is Song -> {
                        if (item.id == playerConnection?.mediaMetadata?.value?.id) {
                            playerConnection?.openPlayer()
                        } else {
                            playerConnection?.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                        }
                    }
                    is Album -> navController.navigate("album/${item.id}")
                    is Artist -> navController.navigate("artist/${item.id}")
                    is Playlist -> navController.navigate("local_playlist/${item.id}")
                }
            },
            menu = { item ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                menuState.show {
                    when (item) {
                        is SongItem -> YouTubeSongMenu(song = item, onDismiss = menuState::dismiss)
                        is AlbumItem -> YouTubeAlbumMenu(albumItem = item, onDismiss = menuState::dismiss)
                        is ArtistItem -> YouTubeArtistMenu(artist = item, onDismiss = menuState::dismiss)
                        is PlaylistItem -> YouTubePlaylistMenu(playlist = item, coroutineScope = scope, onDismiss = menuState::dismiss)
                        is Song -> SongMenu(originalSong = item, onDismiss = menuState::dismiss)
                        is Album -> AlbumMenu(originalAlbum = item, onDismiss = menuState::dismiss)
                        is Artist -> ArtistMenu(originalArtist = item, coroutineScope = scope, onDismiss = menuState::dismiss)
                        else -> menuState.dismiss()
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.itemClicks(
    actions: ItemActions,
    item: Any,
): Modifier =
    combinedClickable(
        onClick = { actions.open(item) },
        onLongClick = { actions.menu(item) },
    )

/** Whether a YouTube item is the one currently playing (song) or contains it (album). */
fun YTItem.isCurrent(
    mediaId: String?,
    albumId: String?,
): Boolean = id == mediaId || (this is AlbumItem && id == albumId)

fun LocalItem.isCurrent(
    mediaId: String?,
    albumId: String?,
): Boolean = id == mediaId || (this is Album && id == albumId)
