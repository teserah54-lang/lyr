/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.screens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.model.formatMs
import com.lyreon.app.player.PlayerManager
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.Artwork
import com.lyreon.app.ui.components.LyricsContent
import com.lyreon.app.ui.components.LyreonPlayButton
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonRose
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonMotion
import com.lyreon.app.ui.theme.lyreonTween
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.LyreonScrimSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerState: PlayerUiState,
    player: PlayerManager,
    isLiked: Boolean,
    isDownloaded: Boolean,
    videoMode: Boolean,
    controller: Player?,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onCycleRepeat: () -> Unit,
    onLike: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onSleepTimer: () -> Unit,
    onShare: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onSetVideoMode: (Boolean) -> Unit,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = playerState.currentTrack
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }

    // Posisi/durasi dari flow khusus (ticker 500ms) — layar ini memang satu-satunya
    // konsumen asli posisi, jadi wajar bila ia yang recompose tiap tick.
    val pos by player.position.collectAsStateWithLifecycle()

    // Foto kanal uploader lagu berjalan (lazy per lagu; null → monogram huruf)
    val locator = com.lyreon.app.ui.vm.LocalLyreon.current
    val artistAvatarUrl by androidx.compose.runtime.produceState<String?>(
        initialValue = null,
        track?.videoId,
    ) {
        value = null
        val t = track
        if (t != null && !t.isLocal) {
            // 1) avatar uploader dari halaman video (paling presisi per lagu);
            // 2) kalau kosong, fallback ke profil kanal berdasar nama artis,
            //    sehingga foto artis hampir selalu tampil (bukan monogram).
            val byVideo = runCatching { locator.youtube.uploaderAvatar(t.videoId) }.getOrNull()
            value = byVideo?.takeIf { it.isNotBlank() } ?: runCatching {
                locator.youtube.channelProfile(t.artist)?.first
            }.getOrNull()
        }
    }

    val progress = if (pos.durationMs > 0) {
        pos.positionMs.toFloat() / pos.durationMs.toFloat()
    } else 0f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LyreonBackground),
    ) {
        val wide = maxWidth >= 700.dp

        // Latar statis: gradien gelap dengan sentuhan crimson — tanpa
        // overlay gambar full-screen (penyebab jank saat halaman dibuka).
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        LyreonCrimson.copy(alpha = 0.12f),
                        LyreonBackground.copy(alpha = 0.4f),
                        LyreonBackground,
                    ),
                ),
            ),
        )

        if (track == null) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.np_empty_title), style = MaterialTheme.typography.titleMedium, color = LyreonTextSecondary)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.np_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = LyreonTextMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.action_close),
                    style = MaterialTheme.typography.labelMedium,
                    color = LyreonCrimson,
                    modifier = Modifier
                        .border(1.dp, LyreonCrimson)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            return@BoxWithConstraints
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 48.dp else 24.dp)
                .widthIn(max = 560.dp)
                .align(Alignment.Center),
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.action_close), tint = LyreonTextPrimary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.np_kicker), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
                    Text(
                        when {
                            playerState.isBuffering -> stringResource(R.string.np_loading_stream)
                            track.isLocal -> stringResource(R.string.np_local_file)
                            videoMode -> stringResource(R.string.np_video)
                            else -> stringResource(R.string.np_audio_only)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = LyreonTextMuted,
                    )
                }
                if (track.isLocal) {
                    // File lokal: tidak ada link YouTube untuk dibagikan
                    Spacer(Modifier.size(48.dp))
                } else {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.np_share), tint = LyreonTextPrimary)
                    }
                }
            }

            // Segmented MUSIK | VIDEO ala iOS — hanya untuk track YouTube
            if (!track.isLocal) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(50))
                        .background(LyreonSurface.copy(alpha = 0.6f))
                        .padding(4.dp),
                ) {
                    ModePill(
                        label = stringResource(R.string.np_music),
                        selected = !videoMode,
                        onClick = { onSetVideoMode(false) },
                    )
                    Spacer(Modifier.width(4.dp))
                    ModePill(
                        label = stringResource(R.string.np_video),
                        selected = videoMode,
                        onClick = { onSetVideoMode(true) },
                    )
                }
            }

            Spacer(Modifier.height(if (wide) 24.dp else 14.dp))

            // Kanvas utama: artwork (mode musik) atau PlayerView (mode video)
            if (videoMode && controller != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(LyreonSurface),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = true
                                keepScreenOn = true
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { view -> view.player = controller },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                ArtworkOrLyrics(
                    track = track,
                    isDownloaded = isDownloaded,
                    showLyrics = showLyrics,
                    positionMs = pos.positionMs,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    onSeekMs = onSeekMs,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(22.dp))

            // Meta: judul + artis (dengan foto kanal) di kiri, hati di kanan
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = LyreonTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Bar artis: foto profil kanal YouTube + nama uploader
                    val artistName = track.artist.ifBlank { stringResource(R.string.common_youtube_music) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArtistAvatar(name = artistName, url = artistAvatarUrl)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            artistName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LyreonTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onLike) {
                    Icon(
                        if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(if (isLiked) R.string.np_like_remove else R.string.np_like_add),
                        tint = if (isLiked) LyreonCrimson else LyreonTextSecondary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Text(
                stringResource(R.string.np_audio_queue, playerState.currentIndex + 1, playerState.queue.size),
                style = MaterialTheme.typography.labelSmall,
                color = LyreonTextMuted,
            )

            Spacer(Modifier.height(18.dp))

            // Seek
            Slider(
                value = if (dragging) sliderValue else progress.coerceIn(0f, 1f),
                onValueChange = {
                    dragging = true
                    sliderValue = it
                },
                onValueChangeFinished = {
                    onSeekFraction(sliderValue)
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = LyreonTextPrimary,
                    activeTrackColor = LyreonCrimson,
                    inactiveTrackColor = LyreonSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatMs(if (dragging) (sliderValue * pos.durationMs).toLong() else pos.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = LyreonTextSecondary,
                )
                Text(formatMs(pos.durationMs), style = MaterialTheme.typography.labelSmall, color = LyreonTextSecondary)
            }

            Spacer(Modifier.height(16.dp))

            // Kontrol utama
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { onShuffle(!playerState.shuffleEnabled) }) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = stringResource(R.string.common_shuffle),
                        tint = if (playerState.shuffleEnabled) LyreonCrimson else LyreonTextSecondary,
                    )
                }
                IconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(R.string.np_previous),
                        tint = LyreonTextPrimary,
                        modifier = Modifier.size(38.dp),
                    )
                }
                LyreonPlayButton(
                    isPlaying = playerState.isPlaying,
                    onClick = onToggle,
                    size = 84.dp,
                )
                IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.np_next),
                        tint = LyreonTextPrimary,
                        modifier = Modifier.size(38.dp),
                    )
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        when (playerState.repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                            else -> Icons.Filled.Repeat
                        },
                        contentDescription = stringResource(R.string.np_repeat),
                        tint = if (playerState.repeatMode != Player.REPEAT_MODE_OFF) LyreonCrimson else LyreonTextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // Aksi sekunder: lirik · unduh · playlist · timer · antrean
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryAction(
                    icon = Icons.Filled.Lyrics,
                    label = stringResource(R.string.np_lyrics),
                    active = showLyrics,
                    onClick = { showLyrics = !showLyrics },
                )
                if (!track.isLocal) {
                    SecondaryAction(
                        icon = Icons.Filled.Download,
                        label = stringResource(R.string.np_download),
                        active = isDownloaded,
                        onClick = onDownload,
                    )
                }
                SecondaryAction(
                    icon = Icons.Filled.PlaylistAdd,
                    label = stringResource(R.string.common_playlist_caps),
                    active = false,
                    onClick = onAddToPlaylist,
                )
                SecondaryAction(
                    icon = Icons.Filled.Bedtime,
                    label = if (playerState.sleepDeadlineMs != null) {
                        val mins = ((playerState.sleepDeadlineMs - System.currentTimeMillis()) / 60000L).coerceAtLeast(0)
                        stringResource(R.string.np_timer_left, mins)
                    } else stringResource(R.string.np_timer),
                    active = playerState.sleepDeadlineMs != null,
                    onClick = onSleepTimer,
                )
                SecondaryAction(
                    icon = Icons.Filled.QueueMusic,
                    label = stringResource(R.string.np_queue),
                    active = false,
                    onClick = { showQueue = true },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ---- Lembar Antrean ----
    if (showQueue) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
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
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.queue_title, playerState.queue.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = LyreonCrimson,
                        modifier = Modifier.weight(1f),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    itemsIndexed(playerState.queue, key = { i, t -> "${t.videoId}_$i" }) { index, item ->
                        val active = index == playerState.currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (active) LyreonSurface.copy(alpha = 0.5f) else LyreonElevated)
                                .clickable { onPlayAt(index) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "%02d".format(index + 1),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) LyreonCrimson else LyreonTextMuted,
                                modifier = Modifier.width(28.dp),
                            )
                            Artwork(url = item.thumbnailUrl, title = item.title, size = 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = LyreonTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    item.artist.ifBlank { stringResource(R.string.common_youtube) },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LyreonTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (active) {
                                Text(stringResource(R.string.common_playing), style = MaterialTheme.typography.labelSmall, color = LyreonCrimson)
                            } else {
                                IconButton(onClick = { onRemoveQueueItem(index) }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.queue_remove),
                                        tint = LyreonTextMuted,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pill ala segmented control iOS. */
@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) LyreonCrimson else LyreonSurface.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) LyreonTextPrimary else LyreonTextSecondary,
        )
    }
}

@Composable
private fun SecondaryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) LyreonCrimson else LyreonTextPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) LyreonCrimson else LyreonTextSecondary,
        )
    }
}

/**
 * Kanvas sampul ⇄ lirik (ketuk untuk berganti, fade 450ms):
 *  - Sampul: bingkai persegi sedikit memanjang ke bawah (~1:1,06), gambar
 *    MENGISI penuh areanya (crop tengah), kualitas terjaga lewat rantai
 *    varian ytimg: maxres → sd → hq.
 *  - Lirik: artwork yang sama menjadi latar, diblur (API 31+) + digelapkan
 *    bergradasi supaya teks lirik paling menonjol.
 */
@Composable
private fun ArtworkOrLyrics(
    track: LyreonTrack,
    isDownloaded: Boolean,
    showLyrics: Boolean,
    positionMs: Long,
    onToggleLyrics: () -> Unit,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // Persegi nyaris 1:1, sedikit lebih tinggi (lebih elegan dari 16:9)
            .aspectRatio(0.94f)
            .clip(RoundedCornerShape(20.dp))
            .background(LyreonSurface)
            .clickable(onClick = onToggleLyrics),
    ) {
        Crossfade(targetState = showLyrics, animationSpec = lyreonTween(LyreonMotion.deliberate), label = "art_lyrics") { lyrics ->
            if (!lyrics) {
                Box(Modifier.fillMaxSize()) {
                    if (track.thumbnailUrl.isNotBlank()) {
                        RichArtwork(
                            url = track.thumbnailUrl,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(LyreonSurface))
                    }
                    if (isDownloaded) {
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LyreonRose)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.common_offline), style = MaterialTheme.typography.labelSmall, color = LyreonBackground)
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    // Latar lirik = artwork yang sama, diblur
                    if (track.thumbnailUrl.isNotBlank()) {
                        RichArtwork(
                            url = track.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                                        Modifier.blur(32.dp)
                                    } else {
                                        // Pra-API 31: tanpa RenderEffect — redupkan saja
                                        Modifier.alpha(0.22f)
                                    },
                                ),
                        )
                    }
                    // Skrim gradasi → teks lirik jauh lebih menonjol dari thumbnail
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.55f),
                                    Color.Black.copy(alpha = 0.72f),
                                ),
                            ),
                        ),
                    )
                    LyricsContent(
                        track = track,
                        positionMs = positionMs,
                        onSeekMs = onSeekMs,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Gambar artwork dengan rantai kualitas ytimg (maxres 1280×720 → sd 640×480 →
 * hq 480×360) — maxres tidak tersedia di semua video, jadi turun otomatis saat
 * 404. Non-ytimg (file lokal, dsb) ditampilkan apa adanya. Selalu ContentScale.Crop.
 */
@Composable
private fun RichArtwork(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val candidates = remember(url) { ytimgCandidates(url) }
    var index by remember(url) { androidx.compose.runtime.mutableIntStateOf(0) }
    AsyncImage(
        model = candidates[index.coerceAtMost(candidates.lastIndex)],
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        onError = {
            // Varian tak tersedia (404) → turun ke kualitas berikutnya
            if (index < candidates.lastIndex) index++
        },
        modifier = modifier,
    )
}

/** Rantai URL varian ytimg terbesar → terkecil untuk videoId dari URL gambar. */
private fun ytimgCandidates(url: String): List<String> =
    if (url.contains("i.ytimg.com") && url.contains("/vi/")) {
        val vid = url.substringAfter("/vi/").substringBefore("/")
        listOf(
            "https://i.ytimg.com/vi/$vid/maxresdefault.jpg",
            "https://i.ytimg.com/vi/$vid/sddefault.jpg",
            "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
            url, // jaga-jaga: sumber asli (mungkin varian lain)
        )
    } else {
        listOf(url)
    }

/** Foto kanal artis (bulat) — fallback monogram huruf pertama. */
@Composable
private fun ArtistAvatar(
    name: String,
    url: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(LyreonSurface),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = LyreonCrimson,
            )
        }
    }
}
