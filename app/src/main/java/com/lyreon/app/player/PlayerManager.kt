package com.lyreon.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.lyreon.app.R
import com.lyreon.app.core.ServiceLocator
import com.lyreon.app.data.model.LyreonTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val connected: Boolean = false,
    val currentTrack: LyreonTrack? = null,
    val queue: List<LyreonTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sleepDeadlineMs: Long? = null,
    val snackbarEvents: Int = 0,
)

/**
 * State frekuensi-tinggi (ticker 500ms) — dipisah dari [PlayerUiState] agar
 * layar yang hanya butuh track/isPlaying TIDAK ikut recompose tiap tick.
 * Hanya MiniPlayerBar & NowPlayingScreen yang mengoleksi flow ini.
 */
data class PlayerPosition(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Satu pintu UI ↔ pemutar. Menghubungkan MediaController ke PlaybackService,
 * menyinkronkan state, mengelola antrean, sleep timer, restore sesi,
 * pencatatan riwayat, dan "radio" otomatis dari lagu terkait.
 */
class PlayerManager(
    private val context: Context,
    private val locator: ServiceLocator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    // Posisi/durasi — satu-satunya state yang berubah tiap tick 500ms
    private val _position = MutableStateFlow(PlayerPosition())
    val position: StateFlow<PlayerPosition> = _position.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    // Pop-up donasi: menyala setiap pengguna 10× berganti lagu secara manual
    // (pilih lagu / next-prev — pergantian otomatis saat lagu habis tidak dihitung)
    private val _donateRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val donateRequests: SharedFlow<Unit> = _donateRequests.asSharedFlow()
    private var trackSwitchCount = 0

    // Rangkaian kegagalan stream beruntun — bila terlalu sering, stop total
    private var consecutiveFailures = 0

    // Mode video: true = render permukaan video di NowPlaying (stream muxed ≤ 720p)
    private val _videoMode = MutableStateFlow(false)
    val videoMode: StateFlow<Boolean> = _videoMode.asStateFlow()

    /** Controller untuk diikat ke PlayerView (mode video). */
    val mediaController: MediaController? get() = controller

    private var controller: MediaController? = null
    private val registry = mutableMapOf<String, LyreonTrack>()
    private val errorRetries = mutableMapOf<String, Int>()

    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var saveJob: Job? = null
    private var restored = false

    /** Perintah play yang datang sebelum controller tersambung (cold start). */
    private var pendingAction: (() -> Unit)? = null

    init {
        connectController()
    }

    // ------------------------------------------------------------------
    // Koneksi controller
    // ------------------------------------------------------------------

    private fun connectController() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val mc = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = mc
            mc.addListener(listener)
            _state.update { it.copy(connected = true) }
            syncFromPlayer(mc)
            val pending = pendingAction
            pendingAction = null
            if (pending != null) {
                pending.invoke()
            } else {
                restoreSessionIfNeeded()
            }
            startTicker()
        }, MoreExecutors.directExecutor())
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                _state.value.currentTrack?.let { t ->
                    scope.launch { locator.library.recordPlay(t) }
                }
            }
            scheduleSave()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
            }
            if (playbackState == Player.STATE_READY) {
                consecutiveFailures = 0
            }
            if (playbackState == Player.STATE_ENDED) {
                onEnded()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            // ALGORITMA SELERA — catat lagu yang baru ditinggalkan beserta
            // rasio dengarnya (skip cepat ≈ sinyal lemah, didengar habis ≈ kuat)
            run {
                val prev = tasteActiveTrack
                if (prev != null) {
                    val durMs = prev.durationSec * 1000L
                    val posMs = _position.value.positionMs
                    val ratio = if (durMs > 0L) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0.5f
                    val finished = ratio >= 0.6f || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    scope.launch { locator.taste.recordPlay(prev, if (finished) ratio.coerceAtLeast(0.6f) else ratio) }
                }
            }
            syncFromPlayer(c)
            tasteActiveTrack = _state.value.currentTrack
            errorRetries.clear()
            warmUpcoming(c)
            maybeExtendQueue(c)
            scheduleSave()
            if (c.isPlaying) {
                _state.value.currentTrack?.let { t ->
                    scope.launch { locator.library.recordPlay(t) }
                }
            }
            // Hitung hanya pergantian manual (bukan auto-advance saat lagu selesai)
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                trackSwitchCount++
                if (trackSwitchCount >= 10) {
                    trackSwitchCount = 0
                    _donateRequests.tryEmit(Unit)
                }
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(controller ?: return)
        }

        override fun onPlayerError(error: PlaybackException) {
            val track = _state.value.currentTrack
            val id = track?.videoId.orEmpty()
            if (id.isNotBlank()) locator.youtube.invalidate(id)
            val tries = errorRetries.getOrDefault(id, 0)
            errorRetries[id] = tries + 1
            val c = controller ?: return
            when {
                // Satu kesempatan ulang per track (n-sig/pot basi sering sembuh)
                tries < 1 && id.isNotBlank() -> {
                    emit("Memuat ulang stream…")
                    runCatching {
                        c.seekToDefaultPosition()
                        c.prepare()
                        c.play()
                    }
                }
                // Loncat ke track berikutnya — tapi jika gagal beruntun, STOP total
                c.hasNextMediaItem() && consecutiveFailures < 3 -> {
                    consecutiveFailures++
                    emit("Track dilewati (stream tidak tersedia)")
                    runCatching {
                        c.seekToNextMediaItem()
                        c.prepare()
                        c.play()
                    }
                }
                else -> {
                    emit(context.getString(R.string.stream_stopped_unavailable))
                    runCatching {
                        c.pause()
                        c.stop()
                        c.clearMediaItems()
                    }
                    _state.update { it.copy(queue = emptyList(), currentIndex = -1, currentTrack = null) }
                    _position.value = PlayerPosition()
                    consecutiveFailures = 0
                    errorRetries.clear()
                }
            }
        }
    }

    private fun onEnded() {
        // Lagu terakhir antrean selesai penuh → sinyal selera terkuat
        _state.value.currentTrack?.let { t ->
            scope.launch { locator.taste.recordPlay(t, 1f) }
        }
        val current = _state.value.currentTrack ?: return
        if (current.isLocal) return // lagu lokal: tidak ada "radio" lagu terkait
        scope.launch {
            val autoplay = locator.settings.settings.first().autoplayRelated
            if (!autoplay) return@launch
            emit("Membuka radio: lagu terkait…")
            val related = withContext(Dispatchers.IO) { locator.youtube.relatedOf(current.videoId) }
            if (related.isEmpty()) return@launch
            val existing = HashSet<String>()
            controller?.let { c -> for (i in 0 until c.mediaItemCount) existing.add(c.getMediaItemAt(i).mediaId) }
            existing += current.videoId
            val diverse = locator.taste.diversePick(
                candidates = related,
                limit = 20,
                existingIds = existing,
                seedTitle = current.title,
                seedArtist = current.artist,
            )
            if (diverse.isEmpty()) return@launch
            appendAll(diverse)
            val c = controller ?: return@launch
            runCatching {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            }
        }
    }

    // ------------------------------------------------------------------
    // Sinkronisasi state dari player
    // ------------------------------------------------------------------

    private fun syncFromPlayer(c: MediaController) {
        val count = c.mediaItemCount
        val queue = mutableListOf<LyreonTrack>()
        for (i in 0 until count) {
            val id = c.getMediaItemAt(i).mediaId
            registry[id]?.let { queue.add(it) }
        }
        val index = c.currentMediaItemIndex.takeIf { it in 0 until count } ?: -1
        val currentId = c.currentMediaItem?.mediaId
        val duration = c.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: registry[currentId]?.durationSec?.times(1000L) ?: 0L

        _state.update {
            it.copy(
                queue = queue,
                currentIndex = index,
                currentTrack = currentId?.let(registry::get),
                shuffleEnabled = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
            )
        }
        _position.update {
            it.copy(positionMs = c.currentPosition.coerceAtLeast(0L), durationMs = duration)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null) {
                    // HANYA flow posisi yang dipompa di sini — layar lain tidak terganggu.
                    // isBuffering sudah ditangani listener onPlaybackStateChanged.
                    _position.update {
                        it.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0L),
                            durationMs = c.duration.takeIf { d -> d > 0 && d != C.TIME_UNSET }
                                ?: it.durationMs,
                        )
                    }
                }
                delay(500L)
            }
        }
    }

    private fun warmUpcoming(c: MediaController) {
        val count = c.mediaItemCount
        if (count == 0) return
        val ids = mutableListOf<String>()
        val idx = c.currentMediaItemIndex
        if (idx + 1 < count) ids.add(c.getMediaItemAt(idx + 1).mediaId)
        if (idx + 2 < count) ids.add(c.getMediaItemAt(idx + 2).mediaId)
        ids.removeAll { it.startsWith(LyreonTrack.LOCAL_ID_PREFIX) } // lokal tidak perlu warm-up stream
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching { locator.youtube.warmUp(*ids.toTypedArray()) }
        }
    }

    // ------------------------------------------------------------------
    // Antrean tanpa habis: mendekati ujung antrean → tambah 20 lagu terkait
    // ------------------------------------------------------------------

    private var extendJob: Job? = null

    /** Lagu yang sedang dicatat ke profil selera (diperbarui tiap transisi). */
    private var tasteActiveTrack: com.lyreon.app.data.model.LyreonTrack? = null

    /** Penghitung ekstensi antrean — genap/ganjil untuk menyelingi sumber kandidat. */
    private var extendCount = 0

    private fun maybeExtendQueue(c: MediaController) {
        val count = c.mediaItemCount
        if (count == 0) return
        // Perpanjang LEBIH AWAL (sisa ≤ 5, bukan 3) agar radio tidak pernah jeda
        // di tengah lagu — ala antrean kontinu yang selalu penuh di depan.
        val remaining = count - 1 - c.currentMediaItemIndex
        if (remaining > 5) return
        if (extendJob?.isActive == true) return
        val current = _state.value.currentTrack ?: return
        if (current.isLocal) return // lagu lokal tidak punya lagu terkait

        extendJob = scope.launch {
            val autoplay = locator.settings.settings.first().autoplayRelated
            if (!autoplay) return@launch
            val existing = HashSet<String>(count + 8)
            for (i in 0 until c.mediaItemCount) existing.add(c.getMediaItemAt(i).mediaId)

            // Sumber 1: radio YouTube dari lagu berjalan + panen konteks benihnya
            val bundle = withContext(Dispatchers.IO) {
                runCatching { locator.youtube.bundle(current.videoId) }.getOrNull()
            }
            if (bundle != null) {
                locator.taste.recordTags(bundle.seedTags, bundle.seedHashtags, bundle.seedCategory)
            }
            val candidates = ArrayList<com.lyreon.app.data.model.LyreonTrack>(64)
            bundle?.related?.let(candidates::addAll)
            // Jika Metrolist gagal/related kosong → pakai relatedOf (punya fallback InnerTube)
            if (candidates.isEmpty()) {
                candidates += withContext(Dispatchers.IO) {
                    runCatching { locator.youtube.relatedOf(current.videoId) }.getOrDefault(emptyList())
                }
            }

            // Sumber 2 (berselang): query PERSONA — hashtag/token dari selera pengguna
            // (mis. "sadvibes speed up reverb") sehingga antrean ikut berkonteks,
            // bukan hanya mengekor artis/judul lagu terakhir.
            extendCount++
            if (extendCount % 2 == 0) {
                val query = locator.taste.personalityQuery()
                if (query != null) {
                    candidates += withContext(Dispatchers.IO) {
                        locator.youtube.searchTracks(query, limit = 16)
                    }
                }
            }

            val additions = locator.taste.diversePick(
                candidates = candidates,
                limit = 20,
                existingIds = existing,
                seedTitle = current.title,
                seedArtist = current.artist,
            )
            if (additions.isNotEmpty()) {
                appendAll(additions)
                emit(context.getString(R.string.queue_extended, additions.size))
            }
        }
    }

    // ------------------------------------------------------------------
    // Sesi (restore antrean)
    // ------------------------------------------------------------------

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1200L)
            val c = controller ?: return@launch
            val st = _state.value
            locator.session.save(
                queue = st.queue,
                index = st.currentIndex.coerceAtLeast(0),
                positionMs = runCatching { c.currentPosition }.getOrDefault(0L),
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
            )
        }
    }

    private fun restoreSessionIfNeeded() {
        if (restored) return
        restored = true
        scope.launch {
            val saved = locator.session.load() ?: return@launch
            if (saved.queue.isEmpty()) return@launch
            saved.queue.forEach { registry[it.videoId] = it }
            val c = controller ?: return@launch
            if (c.mediaItemCount != 0) return@launch
            runCatching {
                c.setMediaItems(
                    saved.queue.map(::toMediaItem),
                    saved.index.coerceIn(0, saved.queue.lastIndex),
                    saved.positionMs.coerceAtLeast(0L),
                )
                c.shuffleModeEnabled = saved.shuffle
                c.repeatMode = saved.repeatMode
                c.prepare()
                // playWhenReady false — pulihkan tapi tidak autoplay
                syncFromPlayer(c)
            }
        }
    }

    // ------------------------------------------------------------------
    // Perintah publik
    // ------------------------------------------------------------------

    fun playQueue(tracks: List<LyreonTrack>, startIndex: Int = 0, autoplay: Boolean = true) {
        if (tracks.isEmpty()) return
        tracks.forEach { registry[it.videoId] = it }
        val c = controller ?: run {
            pendingAction = { playQueue(tracks, startIndex, autoplay) }
            return
        }
        c.setMediaItems(tracks.map(::toMediaItem), startIndex.coerceIn(0, tracks.lastIndex), 0L)
        c.prepare()
        if (autoplay) c.play()
        syncFromPlayer(c)
    }

    fun appendAll(tracks: List<LyreonTrack>) {
        val c = controller ?: return
        // Bebas duplikat judul-inti (reupload speed-up/reverb/TikTok dsb): judul
        // yang sama dengan yang sudah antre tidak dimasukkan lagi — menjaga antrean
        // tetap variatif tanpa mengisi slot dengan varian judul yang sama.
        val existingTitles = HashSet<String>()
        for (i in 0 until c.mediaItemCount) {
            registry[c.getMediaItemAt(i).mediaId]?.title?.let {
                existingTitles.add(com.lyreon.app.data.taste.MusicTextAnalyzer.coreTitle(it))
            }
        }
        tracks.forEach { t ->
            val core = com.lyreon.app.data.taste.MusicTextAnalyzer.coreTitle(t.title)
            if (core.isNotBlank() && existingTitles.contains(core)) return@forEach
            existingTitles.add(core)
            registry[t.videoId] = t
            c.addMediaItem(toMediaItem(t))
        }
        syncFromPlayer(c)
    }

    fun playNext(track: LyreonTrack) {
        registry[track.videoId] = track
        val c = controller ?: return
        val idx = (c.currentMediaItemIndex + 1).coerceAtLeast(0)
        if (c.mediaItemCount == 0) {
            playQueue(listOf(track))
        } else {
            c.addMediaItem(idx, toMediaItem(track))
            emit("Diputar setelah ini: ${track.title}")
        }
        syncFromPlayer(c)
    }

    fun addToQueue(track: LyreonTrack) {
        registry[track.videoId] = track
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playQueue(listOf(track), autoplay = false)
        } else {
            c.addMediaItem(toMediaItem(track))
        }
        emit("Ditambahkan ke antrean: ${track.title}")
        syncFromPlayer(c)
    }

    fun toggle() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED -> {
                c.prepare()
                c.play()
            }
            else -> {
                if (c.mediaItemCount == 0 && _state.value.queue.isNotEmpty()) {
                    val q = _state.value.queue
                    playQueue(q, 0)
                } else c.play()
            }
        }
    }

    fun next() = controller?.let { c ->
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
        else if (c.repeatMode == Player.REPEAT_MODE_ALL) c.seekTo(0, 0L)
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000L) {
            c.seekTo(0)
        } else if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
        } else {
            c.seekTo(0)
        }
    }

    fun seekTo(ms: Long) = controller?.seekTo(ms.coerceAtLeast(0L)) ?: Unit

    fun seekToFraction(fraction: Float) {
        val d = _position.value.durationMs
        if (d > 0) seekTo((fraction.coerceIn(0f, 1f) * d).toLong())
    }

    fun seekForward() = controller?.seekForward() ?: Unit
    fun seekBack() = controller?.seekBack() ?: Unit

    fun setShuffle(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
        syncFromPlayer(controller ?: return)
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        syncFromPlayer(c)
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.seekTo(index, 0L).also { c.play() }
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.removeMediaItem(index)
            syncFromPlayer(c)
        }
    }

    fun move(from: Int, to: Int) {
        val c = controller ?: return
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
            c.moveMediaItem(from, to)
            syncFromPlayer(c)
        }
    }

    fun clearQueue() {
        controller?.clearMediaItems()
        _state.update {
            it.copy(queue = emptyList(), currentIndex = -1, currentTrack = null)
        }
        _position.value = PlayerPosition()
    }

    fun stop() {
        controller?.stop()
    }

    // ------------------------------------------------------------------
    // Sleep timer
    // ------------------------------------------------------------------

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepDeadlineMs = deadline) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _state.update { it.copy(sleepDeadlineMs = null) }
            emit("Sleep timer: pemutaran dijeda.")
        }
        emit("Sleep timer $minutes menit aktif.")
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        if (_state.value.sleepDeadlineMs != null) emit("Sleep timer dibatalkan.")
        _state.update { it.copy(sleepDeadlineMs = null) }
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    private fun emit(message: String) {
        _events.tryEmit(message)
    }

    // ------------------------------------------------------------------
    // Mode VIDEO ↔ AUDIO (posisi playback dijaga kontinu)
    // ------------------------------------------------------------------

    fun setVideoMode(enabled: Boolean) {
        scope.launch {
            val c = controller ?: return@launch
            val track = _state.value.currentTrack ?: return@launch
            if (track.isLocal) return@launch // file lokal tidak punya mode video YouTube
            if (enabled == _videoMode.value) return@launch
            val pos = c.currentPosition.coerceAtLeast(0L)

            if (enabled) {
                emit(context.getString(R.string.video_loading))
                val url = withContext(Dispatchers.IO) {
                    locator.youtube.resolveVideoMuxed(track.videoId)
                }
                if (url.isNullOrBlank()) {
                    emit(context.getString(R.string.video_unavailable))
                    return@launch
                }
                val item = MediaItem.Builder()
                    .setMediaId(track.videoId)
                    .setUri(Uri.parse(url))
                    .setMediaMetadata(toMediaItem(track).mediaMetadata)
                    .build()
                runCatching {
                    c.replaceMediaItem(c.currentMediaItemIndex, item)
                    c.prepare()
                    c.seekTo(c.currentMediaItemIndex, pos)
                    c.play()
                }
                _videoMode.value = true
            } else {
                // Balik ke audio-only (resolusi malas via skema lyreon://audio/)
                runCatching {
                    c.replaceMediaItem(c.currentMediaItemIndex, toMediaItem(track))
                    c.prepare()
                    c.seekTo(c.currentMediaItemIndex, pos)
                    c.play()
                }
                _videoMode.value = false
            }
        }
    }

    private fun toMediaItem(t: LyreonTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(t.videoId)
            .setUri(
                // Lagu lokal: file di penyimpanan perangkat (diputar langsung
                // via DefaultDataSource). Lagu YouTube: skema lyreon://audio.
                if (t.isLocal) {
                    com.lyreon.app.local.LocalMusicRepository.uriForVideoId(t.videoId)
                        ?: ResolvingDataSource.uriOf(t.videoId)
                } else {
                    ResolvingDataSource.uriOf(t.videoId)
                },
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album.ifBlank { null })
                    .setArtworkUri(t.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    fun release() {
        ticker?.cancel()
        sleepJob?.cancel()
        saveJob?.cancel()
        controller?.release()
    }
}
