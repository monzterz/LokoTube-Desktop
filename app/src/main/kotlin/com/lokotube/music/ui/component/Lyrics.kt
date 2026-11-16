/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lokotube.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lokotube.music.constants.ExperimentalLyricsKey
import com.lokotube.music.utils.rememberPreference
import com.lokotube.music.viewmodels.LyricsViewModel

@Composable
fun Lyrics(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    showLyrics: Boolean,
    translations: Map<String, String> = emptyMap(),
    lyricsViewModel: LyricsViewModel = hiltViewModel()
) {
    val (experimentalLyrics, _) = rememberPreference(key = ExperimentalLyricsKey, defaultValue = true)

    if (experimentalLyrics) {
        ExperimentalLyrics(
            sliderPositionProvider = sliderPositionProvider,
            modifier = modifier,
            showLyrics = showLyrics,
            translations = translations,
            lyricsViewModel = lyricsViewModel
        )
    } else {
        OriginalLyrics(
            sliderPositionProvider = sliderPositionProvider,
            modifier = modifier,
            showLyrics = showLyrics
        )
    }
}
