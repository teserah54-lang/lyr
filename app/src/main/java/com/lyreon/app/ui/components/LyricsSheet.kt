/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.lyrics.LyricsResult
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.vm.LocalLyreon
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.LyreonScrimSheet

internal sealed interface LyricsUi {
    data object Loading : LyricsUi
    data class Ready(val result: LyricsResult) : LyricsUi
    data object Failed : LyricsUi
}

/**
 * Konten lirik mandiri (loading → sinkron/polos) — dipakai lembar bawah dan
 * mode lirik-di-sampul Now Playing. Latar belakang diserahkan ke pemanggil.
 */
@Composable
fun LyricsContent(
    track: LyreonTrack,
    positionMs: Long,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locator = LocalLyreon.current
    val ui by produceState<LyricsUi>(initialValue = LyricsUi.Loading, track.videoId) {
        value = LyricsUi.Loading
        value = runCatching { locator.lyrics.lyrics(track) }
            .fold(
                onSuccess = { LyricsUi.Ready(it) },
                onFailure = { LyricsUi.Failed },
            )
    }

    Box(modifier) {
        when (val state = ui) {
            LyricsUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LyreonCrimson)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.lyrics_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextMuted,
                    )
                }
            }

            LyricsUi.Failed -> LyricsMessage(stringResource(R.string.lyrics_error))

            is LyricsUi.Ready -> {
                val result = state.result
                when {
                    result.hasSynced -> SyncedLyrics(
                        result = result,
                        positionMs = positionMs,
                        onSeekMs = onSeekMs,
                    )
                    result.instrumental -> LyricsMessage(stringResource(R.string.lyrics_instrumental))
                    !result.plain.isNullOrBlank() -> PlainLyrics(result.plain)
                    else -> LyricsMessage(stringResource(R.string.lyrics_none))
                }
            }
        }
    }
}

/**
 * Lirik sinkron (LRCLIB) — baris aktif menyala mengikuti posisi lagu,
 * lembar auto-scroll menjaga baris aktif di tengah. Ketuk baris = seek.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    track: LyreonTrack,
    positionMs: Long,
    onSeekMs: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LyreonElevated,
        scrimColor = LyreonScrimSheet,
        contentColor = LyreonTextPrimary,
        shape = LyreonRadius.top(),
        dragHandle = {
            Spacer(
                Modifier
                    .padding(top = 10.dp)
                    .width(44.dp)
                    .height(2.dp)
                    .background(LyreonSurface),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().height(560.dp).padding(bottom = 16.dp)) {
            // Header
            Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.lyrics_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = LyreonCrimson,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LyreonTextPrimary,
                    maxLines = 1,
                )
            }

            LyricsContent(
                track = track,
                positionMs = positionMs,
                onSeekMs = onSeekMs,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.lyrics_source),
                style = MaterialTheme.typography.labelSmall,
                color = LyreonTextMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LyricsMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = LyreonTextMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SyncedLyrics(
    result: LyricsResult,
    positionMs: Long,
    onSeekMs: (Long) -> Unit,
) {
    val lines = result.synced
    val listState = rememberLazyListState()

    // PENTING: positionMs adalah Long biasa — derivedStateOf HANYA re-aktif pada
    // pembacaan snapshot state. Tanpa rememberUpdatedState, lambda remember(lines)
    // menangkap posisi lama di closure → highlight beku di baris pertama
    // (gejala: "jalan sekali lalu stuck, buka lagi jalan lalu stuck lagi").
    val currentPositionMs by rememberUpdatedState(positionMs)
    val activeIndex by remember(lines) {
        derivedStateOf {
            var idx = -1
            for (i in lines.indices) {
                if (lines[i].timeMs <= currentPositionMs + 120L) idx = i else break
            }
            idx
        }
    }

    // Auto-scroll: jaga baris aktif ±3 baris dari atas (aliran karaoke ala Apple Music)
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            runCatching {
                listState.animateScrollToItem((activeIndex - 3).coerceAtLeast(0), scrollOffset = 0)
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    ) {
        itemsIndexed(lines, key = { i, l -> "${l.timeMs}_$i" }) { index, line ->
            val active = index == activeIndex
            Text(
                text = line.text,
                style = if (active) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.bodyLarge,
                color = when {
                    active -> LyreonCrimson
                    index < activeIndex -> LyreonTextMuted
                    else -> LyreonTextSecondary
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeekMs(line.timeMs) }
                    .padding(vertical = if (active) 10.dp else 8.dp),
            )
        }
    }
}

@Composable
private fun PlainLyrics(plain: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(plain, style = MaterialTheme.typography.bodyLarge, color = LyreonTextSecondary)
    }
}
