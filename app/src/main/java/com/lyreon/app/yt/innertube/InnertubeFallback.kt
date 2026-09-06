package com.lyreon.app.yt.innertube

import android.util.Log
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.settings.AudioQuality
import com.lyreon.app.yt.LyreonHttp
import com.lyreon.app.yt.ResolvedAudio
import com.lyreon.app.yt.innertube.InnertubeRequest.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fallback ekstraksi gaya InnerTube — POST langsung ke `youtubei/v1` Google
 * (tanpa NewPipe/Metrolist) untuk stream/player, pencarian, dan playlist.
 *
 * Dipakai sebagai cadangan ("fallback utama") ketika MetrolistExtractor gagal
 * atau mengembalikan stream kosong. Semua akses jaringan on-device langsung ke
 * Google (`youtubei/v1` + CDN `googlevideo.com`) — tanpa server perantara.
 */
/**
 * Kegagalan resolusi stream yang membawa **alasan**, bukan cuma pesan generik.
 *
 * Dibawa naik sampai `PlayerManager` supaya pesan ke pengguna bisa membedakan
 * "YouTube sedang menutup akses anonim (SABR/poToken)" dari "video ini memang
 * diblokir/dihapus" — dua hal yang membutuhkan tindakan berbeda. Lyreon anonim
 * (tanpa cookie), jadi pesan tidak pernah menyuruh pengguna login.
 */
class StreamUnavailableException(
    message: String,
    /** Setidaknya satu klien membalas data SABR tanpa URL. */
    val sabrOnly: Boolean,
    /** Hanya `hlsManifestUrl` yang tersedia. */
    val hlsOnly: Boolean,
    /** Alasan `playabilityStatus` terakhir, mis. "LOGIN_REQUIRED: …". */
    val playability: String,
    /** Ringkasan `klien=VERDICT` dari seluruh percobaan. */
    val attempts: List<String>,
    /** Semua format yang ada terkunci DRM (khas klien TV anonim). */
    val drmOnly: Boolean = false,
) : IOException(message)

/** Satu percobaan klien dalam laporan [InnertubeFallback.probeBlocking]. */
data class ProbeAttempt(
    val client: String,
    val verdict: String,
    val elapsedMs: Long,
    val detail: String,
)

/**
 * Laporan diagnostik resolusi satu video — dibaca dari Settings → "Tes koneksi".
 * Inilah jawaban untuk "bagian mana persisnya yang perlu di-patch": terlihat
 * klien mana yang masih memberi URL, mana yang sudah SABR-only.
 */
data class LadderProbe(
    val videoId: String,
    val usableClient: String?,
    val audioUrlFound: Boolean,
    /** Klien yang memberi `hlsManifestUrl` (bisa diputar — Lyreon mendukung HLS). */
    val manifestClient: String?,
    val sabrOnly: Boolean,
    val hlsOnly: Boolean,
    val drmOnly: Boolean,
    val totalMs: Long,
    val attempts: List<ProbeAttempt>,
) {
    /** Sukses = ada URL audio langsung ATAU manifest HLS yang bisa diputar. */
    val success: Boolean get() = audioUrlFound || manifestClient != null
}

class InnertubeFallback {

    companion object {
        private const val TAG = "InnertubeFallback"

        /** MIME manifest HLS — dipakai `DefaultMediaSourceFactory` memilih `HlsMediaSource`. */
        const val HLS_MIME = "application/x-mpegurl"
    }

    private val http = LyreonHttp.extractClient
    private val ua = LyreonHttp.USER_AGENT
    private val streamCache = ConcurrentHashMap<String, ResolvedAudio>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Hapus stream yang sudah kadaluwarsa/403 dari cache (dipanggil saat error). */
    fun invalidate(videoId: String) {
        streamCache.remove(videoId)
    }

    /**
     * Buang SEMUA cache stream — dipakai tombol "Tes koneksi" dan pemulihan
     * manual, supaya percobaan berikutnya benar-benar memukul YouTube lagi.
     */
    fun invalidateAll() {
        streamCache.clear()
    }

    /** Pastikan konfigurasi InnerTube sudah di-scrape (idempoten, thread-safe). */
    private fun ensureReady() {
        InnertubeConfig.ensure(http, ua)
        InnertubeConfig.ensureVisitorData(http, ua)
    }

    // ------------------------------------------------------------------
    // Resolusi stream / player — lewat tangga klien (PlayerClientLadder)
    // ------------------------------------------------------------------

    /**
     * Blocking — aman dipanggil dari thread loader ExoPlayer / service unduhan.
     *
     * @param allowManifest false untuk unduhan (`DownloadManager` hanya bisa
     *   menulis file progresif), sehingga manifest HLS tidak pernah dipilih.
     */
    fun resolveAudioBlocking(
        videoId: String,
        quality: AudioQuality,
        allowManifest: Boolean = true,
    ): ResolvedAudio {
        ensureReady()
        val cached = streamCache[videoId]
        if (cached != null && cached.expiresAtMs - 60_000L > System.currentTimeMillis() &&
            (allowManifest || !cached.isManifest)
        ) {
            return cached
        }
        val lock = locks.getOrPut(videoId) { Any() }
        synchronized(lock) {
            streamCache[videoId]?.let { if (allowManifest || !it.isManifest) return it }
            val resolved = fetchPlayer(videoId, quality, allowManifest)
            // Manifest tidak di-cache untuk jalur unduhan supaya permintaan
            // pemutaran berikutnya tetap bisa memakainya dari resolve ulang.
            if (allowManifest || !resolved.isManifest) streamCache[videoId] = resolved
            return resolved
        }
    }

    /**
     * Turun sepanjang tangga klien sampai ada yang memberi audio yang bisa
     * diputar — URL langsung lebih dulu, manifest HLS sebagai jalur kedua
     * (Lyreon memutar HLS lewat `media3-exoplayer-hls`).
     *
     * Melempar [StreamUnavailableException] bila semua klien gagal, dengan alasan
     * yang bisa ditindaklanjuti (SABR-only / DRM / playability) — bukan pesan kosong.
     */
    private fun fetchPlayer(
        videoId: String,
        quality: AudioQuality,
        allowManifest: Boolean = true,
    ): ResolvedAudio {
        val visitor = InnertubeConfig.visitor()
        val webVersion = InnertubeConfig.webClientVersion()
        val sts = InnertubeConfig.signatureTimestamp()

        // Snapshot urutan sekali: cooldown bisa berubah di tengah loop dan kita
        // ingin satu lintasan yang konsisten. `forPlayback = true` membuang klien
        // yang butuh poToken — tanpa poToken mereka pasti SABR-only/403.
        val ladder = PlayerClientLadder.ordered(forPlayback = true)
        val attempts = ArrayList<String>(ladder.size)
        var sawSabr = false
        var sawHls = false
        var sawDrm = false
        var playability = ""
        var manifest: ResolvedAudio? = null
        var manifestClient: String? = null

        for (spec in ladder) {
            val startedAt = System.currentTimeMillis()
            val attempt = try {
                attemptClient(spec, videoId, visitor, webVersion, sts)
            } catch (e: Exception) {
                ClientAttempt(ClientVerdict.TRANSPORT_ERROR, null, e.javaClass.simpleName)
            }

            var verdict = attempt.verdict
            var detail = attempt.detail
            var picked: ResolvedAudio? = null
            val streamingData = attempt.root?.optJSONObject("streamingData")
            val hlsUrl = PlayerClientLadder.hlsManifest(attempt.root)

            when (verdict) {
                ClientVerdict.USABLE -> {
                    // URL langsung ada. Klien bertanda `preferManifest` URL-nya
                    // tetap butuh poToken GVS (403 saat di-GET), sedangkan manifest
                    // HLS-nya tidak — jadi manifest dipilih lebih dulu di sana.
                    val progressive = streamingData?.let { pickAudio(it, quality) }
                    picked = when {
                        spec.preferManifest && hlsUrl != null && allowManifest ->
                            manifestAudio(videoId, hlsUrl, progressive?.url)
                        progressive != null -> progressive
                        hlsUrl != null && allowManifest -> manifestAudio(videoId, hlsUrl, null)
                        else -> null
                    }
                    if (picked == null) {
                        verdict = ClientVerdict.NO_STREAMING_DATA
                        detail = "tanpa format audio yang bisa dipakai"
                    } else if (picked.isManifest) {
                        detail = "manifest HLS"
                    }
                }
                ClientVerdict.HLS_ONLY -> {
                    sawHls = true
                    if (hlsUrl != null && manifest == null && allowManifest) {
                        manifest = manifestAudio(videoId, hlsUrl, null)
                        manifestClient = spec.key
                        detail = "manifest HLS"
                    }
                    // Jangan langsung pulang: klien berikutnya mungkin memberi URL
                    // audio langsung yang lebih hemat kuota daripada HLS muxed.
                }
                ClientVerdict.SABR_ONLY -> sawSabr = true
                ClientVerdict.DRM_ONLY -> {
                    sawDrm = true
                    if (hlsUrl != null && manifest == null && allowManifest) {
                        manifest = manifestAudio(videoId, hlsUrl, null)
                        manifestClient = spec.key
                    }
                }
                ClientVerdict.PLAYABILITY_BLOCKED -> playability = detail
                else -> Unit
            }

            val elapsed = System.currentTimeMillis() - startedAt
            attempts += "${spec.key}=${verdict.name}"
            PlayerClientLadder.note(spec.key, verdict, elapsed, detail)

            if (picked != null) {
                PlayerClientLadder.push(
                    "audio OK via '${spec.key}' (${elapsed}ms)" +
                        if (picked.isManifest) " · HLS" else "",
                )
                Log.i(
                    TAG,
                    "resolve $videoId OK via klien '${spec.key}' dalam ${elapsed}ms" +
                        if (picked.isManifest) " (manifest HLS)" else "",
                )
                return picked
            }
        }

        // Tidak ada URL langsung, tetapi ada manifest HLS → tetap bisa diputar.
        manifest?.let { m ->
            PlayerClientLadder.push("audio OK via '$manifestClient' · manifest HLS (jalur kedua)")
            Log.i(TAG, "resolve $videoId lewat manifest HLS dari klien '$manifestClient'")
            return m
        }

        val reason = buildString {
            append("InnerTube: tidak ada klien anonim yang memberi stream audio untuk $videoId")
            if (sawSabr) append(" · SABR-only terdeteksi")
            if (sawDrm) append(" · format DRM")
            if (sawHls) append(" · hanya HLS")
            if (playability.isNotBlank()) append(" · $playability")
        }
        Log.w(TAG, "$reason (percobaan: ${attempts.joinToString()})")
        throw StreamUnavailableException(
            message = reason,
            sabrOnly = sawSabr,
            hlsOnly = sawHls,
            playability = playability,
            attempts = attempts,
            drmOnly = sawDrm,
        )
    }

    /**
     * Manifest HLS sebagai [ResolvedAudio]. `media3-exoplayer-hls` memutarnya;
     * `PlayerManager` menukar `MediaItem` ke URL ini + mimeType m3u8.
     *
     * @param progressiveFallback URL audio langsung (bila ada) sebagai cadangan
     *   saat manifest kedaluwarsa — dipakai `ResolvingDataSource` seperti
     *   kandidat format kedua.
     */
    private fun manifestAudio(
        videoId: String,
        hlsUrl: String,
        progressiveFallback: String?,
    ): ResolvedAudio = ResolvedAudio(
        videoId = videoId,
        url = hlsUrl,
        mimeType = HLS_MIME,
        suffix = "m3u8",
        bitrateKbps = 0,
        expiresAtMs = parseExpire(hlsUrl),
        fallbackUrl = progressiveFallback?.takeUnless { it.isBlank() || it == hlsUrl },
        isManifest = true,
    )

    private class ClientAttempt(
        val verdict: ClientVerdict,
        val root: JSONObject?,
        val detail: String,
    )

    private fun attemptClient(
        spec: PlayerClientSpec,
        videoId: String,
        visitor: String?,
        webVersion: String,
        sts: Int?,
    ): ClientAttempt {
        val request = InnertubeRequest.playerFromSpec(spec, videoId, visitor, webVersion, sts)
        val body = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                return ClientAttempt(ClientVerdict.TRANSPORT_ERROR, null, "HTTP ${resp.code}")
            }
            resp.body?.string().orEmpty()
        }
        if (body.isBlank()) return ClientAttempt(ClientVerdict.TRANSPORT_ERROR, null, "body kosong")
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return ClientAttempt(ClientVerdict.TRANSPORT_ERROR, null, "body bukan JSON")
        val verdict = PlayerClientLadder.inspect(root, videoId)
        val detail = if (verdict == ClientVerdict.PLAYABILITY_BLOCKED) {
            PlayerClientLadder.playabilityReason(root)
        } else {
            ""
        }
        return ClientAttempt(verdict, root, detail)
    }

    /**
     * Jalankan seluruh tangga klien untuk satu video dan laporkan hasilnya —
     * dipakai tombol "Tes koneksi" di Settings. Tidak memakai cache supaya
     * hasilnya selalu kondisi jaringan saat ini.
     */
    fun probeBlocking(videoId: String, quality: AudioQuality): LadderProbe {
        ensureReady()
        val startedAt = System.currentTimeMillis()
        val visitor = InnertubeConfig.visitor()
        val webVersion = InnertubeConfig.webClientVersion()
        val sts = InnertubeConfig.signatureTimestamp()

        // Diagnostik menjalankan SELURUH klien (termasuk yang butuh poToken dan
        // yang sudah mati) supaya pergeseran kebijakan YouTube terlihat.
        val ladder = PlayerClientLadder.ordered(forPlayback = false)
        val attempts = ArrayList<ProbeAttempt>(ladder.size)
        var usableClient: String? = null
        var manifestClient: String? = null
        var audioFound = false
        var sawSabr = false
        var sawHls = false
        var sawDrm = false

        for (spec in ladder) {
            val clientStartedAt = System.currentTimeMillis()
            val attempt = try {
                attemptClient(spec, videoId, visitor, webVersion, sts)
            } catch (e: Exception) {
                ClientAttempt(ClientVerdict.TRANSPORT_ERROR, null, e.javaClass.simpleName)
            }
            val elapsed = System.currentTimeMillis() - clientStartedAt
            val hlsUrl = PlayerClientLadder.hlsManifest(attempt.root)
            var detail = attempt.detail
            if (hlsUrl != null && attempt.verdict != ClientVerdict.USABLE) detail = "manifest HLS ada"
            attempts += ProbeAttempt(spec.key, attempt.verdict.name, elapsed, detail)
            PlayerClientLadder.note(spec.key, attempt.verdict, elapsed, detail)

            when (attempt.verdict) {
                ClientVerdict.SABR_ONLY -> sawSabr = true
                ClientVerdict.DRM_ONLY -> sawDrm = true
                ClientVerdict.HLS_ONLY -> {
                    sawHls = true
                    if (hlsUrl != null) manifestClient = manifestClient ?: spec.key
                }
                ClientVerdict.USABLE -> {
                    usableClient = usableClient ?: spec.key
                    val streamingData = attempt.root?.optJSONObject("streamingData")
                    if (streamingData != null && pickAudio(streamingData, quality) != null) {
                        audioFound = true
                    }
                    if (hlsUrl != null) manifestClient = manifestClient ?: spec.key
                }
                else -> Unit
            }
            if (hlsUrl != null) sawHls = true
        }

        return LadderProbe(
            videoId = videoId,
            usableClient = usableClient,
            audioUrlFound = audioFound,
            manifestClient = manifestClient,
            sabrOnly = sawSabr,
            hlsOnly = sawHls,
            drmOnly = sawDrm,
            totalMs = System.currentTimeMillis() - startedAt,
            attempts = attempts,
        )
    }

    private fun pickAudio(sd: JSONObject, quality: AudioQuality): ResolvedAudio? {
        val formats = sd.optJSONArray("adaptiveFormats") ?: sd.optJSONArray("formats") ?: return null
        val baseJs = InnertubeConfig.baseJsUrl()?.let { fetchBaseJs(it) }

        data class Candidate(val url: String, val mime: String, val suffix: String, val bitrate: Int)

        val candidates = ArrayList<Candidate>(formats.length())
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType").orEmpty()
            if (!mime.startsWith("audio/")) continue
            // Format ber-DRM tidak bisa diputar tanpa lisensi Widevine.
            if (PlayerClientLadder.isDrmLocked(f)) continue
            var url = f.optString("url").orEmpty()
            if (url.isBlank()) {
                val cipher = f.optString("signatureCipher").ifBlank { f.optString("cipher") }
                url = resolveCipher(cipher, baseJs) ?: continue
            }
            if (url.isBlank()) continue
            val bitrate = f.optInt("bitrate", 0)
            candidates.add(
                Candidate(
                    url = url,
                    mime = mime,
                    suffix = suffixOf(mime),
                    bitrate = bitrate,
                ),
            )
        }
        if (candidates.isEmpty()) return null

        fun bitrateOf(c: Candidate) = if (c.bitrate > 0) c.bitrate else -1
        val chosen = when (quality) {
            AudioQuality.HIGH -> candidates.filter { bitrateOf(it) > 0 }
                .maxWithOrNull(compareBy<Candidate> { bitrateOf(it) }.thenBy { if (it.suffix == "m4a") 1 else 0 })
            AudioQuality.DATA_SAVER -> candidates.filter { bitrateOf(it) > 0 }
                .minWithOrNull(compareBy<Candidate> { bitrateOf(it) }.thenBy { if (it.suffix == "m4a") 0 else 1 })
            AudioQuality.BALANCED -> candidates.filter { bitrateOf(it) > 0 }
                .minWithOrNull(compareBy<Candidate> { kotlin.math.abs(it.bitrate - 128_000) }
                    .thenBy { if (it.suffix == "m4a") -1 else 0 })
        } ?: candidates.first()

        val bitrateKbps = (chosen.bitrate.takeIf { it > 0 } ?: 128_000) / 1000
        val fallbackUrl = candidates
            .filter { it !== chosen && it.suffix != chosen.suffix }
            .minByOrNull { kotlin.math.abs(it.bitrate - chosen.bitrate) }
            ?.url
            ?.takeUnless { it.isBlank() || it == chosen.url }

        return ResolvedAudio(
            videoId = "", // diisi pemanggil bila perlu
            url = chosen.url,
            mimeType = chosen.mime,
            suffix = chosen.suffix,
            bitrateKbps = bitrateKbps,
            expiresAtMs = parseExpire(chosen.url),
            fallbackUrl = fallbackUrl,
        )
    }

    /** Menyelesaikan `signatureCipher`/`cipher` menjadi URL final. */
    private fun resolveCipher(cipher: String, baseJs: String?): String? {
        if (cipher.isBlank()) return null
        val params = parseQuery(cipher)
        val url = params["url"] ?: return null
        var s = params["s"] ?: return url
        val sp = params["sp"] ?: "sig"
        if (baseJs != null) {
            s = SignatureDecipher.decipherS(baseJs, s) ?: return null
        }
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep$sp=${java.net.URLEncoder.encode(s, "UTF-8")}"
    }

    private fun suffixOf(mime: String): String = when {
        mime.contains("opus") || mime.contains("webm") -> "webm"
        mime.contains("m4a") || mime.contains("mp4") || mime.contains("aac") -> "m4a"
        mime.contains("mp3") -> "mp3"
        else -> "m4a"
    }

    private fun parseExpire(url: String): Long {
        val exp = Regex("[?&]expire=(\\d{9,})").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (exp != null && exp > 0) exp * 1000L
        else System.currentTimeMillis() + 5L * 60L * 60L * 1000L
    }

    private fun parseQuery(qs: String): Map<String, String> {
        val map = HashMap<String, String>()
        val body = qs.substringAfter("?", qs)
        body.split("&").forEach {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) map[kv[0]] = java.net.URLDecoder.decode(kv[1], "UTF-8")
        }
        return map
    }

    // "" = sentinel gagal (ConcurrentHashMap tak boleh menyimpan null).
    private val baseJsCache = ConcurrentHashMap<String, String>()
    private fun fetchBaseJs(url: String): String? =
        baseJsCache.getOrPut(url) {
            runCatching {
                http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", ua).get().build())
                    .execute().use { it.body?.string() }
            }.getOrNull().orEmpty()
        }.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------
    // Rekomendasi / radio (endpoint /next — watch next)
    // ------------------------------------------------------------------

    /** Lagu terkait ("watch next") untuk radio — fallback bila Metrolist gagal. */
    suspend fun related(videoId: String, limit: Int = 25): List<LyreonTrack> =
        withContext(Dispatchers.IO) {
            ensureReady()
            runCatching {
                val req = InnertubeRequest.next(
                    Client.WEB,
                    InnertubeConfig.webClientVersion(),
                    videoId,
                    InnertubeConfig.visitor(),
                )
                val root = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    JSONObject(resp.body?.string().orEmpty())
                }
                parseRelated(root, limit)
            }.getOrDefault(emptyList())
        }

    private fun parseRelated(root: JSONObject, limit: Int): List<LyreonTrack> {
        val out = ArrayList<LyreonTrack>(limit)
        val results = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnWatchNextResults")
            ?.optJSONObject("secondaryResults")
            ?.optJSONObject("secondaryResults")
            ?.optJSONArray("results") ?: return out

        fun textOf(node: JSONObject?, key: String): String {
            if (node == null) return ""
            val runs = node.optJSONArray("runs") ?: return node.optString("simpleText")
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            return sb.toString()
        }

        for (i in 0 until results.length()) {
            val vr = results.optJSONObject(i)?.optJSONObject("compactVideoRenderer") ?: continue
            val vid = vr.optString("videoId").orEmpty()
            if (vid.isBlank() || vid.length !in 6..20) continue
            val title = textOf(vr.optJSONObject("title"), "text").ifBlank { continue }
            val thumb = vr.optJSONObject("thumbnail")?.optJSONObject("thumbnails")
                ?.optJSONArray("thumbnails")
                ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else "" }
                .orEmpty()
            val length = textOf(vr.optJSONObject("lengthText"), "simpleText")
            val duration = parseDuration(length)
            val artist = vr.optJSONObject("longBylineText")?.let { textOf(it, "runs") }.orEmpty()
            out += LyreonTrack(vid, title, artist, durationSec = duration, thumbnailUrl = thumb)
            if (out.size >= limit) return out
        }
        return out
    }

    // ------------------------------------------------------------------
    // Pencarian
    // ------------------------------------------------------------------

    suspend fun search(query: String, filter: com.lyreon.app.data.model.SearchFilter, limit: Int = 30): List<LyreonTrack> =
        withContext(Dispatchers.IO) {
            ensureReady()
            runCatching {
                val params = searchParams(filter)
                val req = InnertubeRequest.search(Client.WEB, InnertubeConfig.webClientVersion(), query, params, InnertubeConfig.visitor())
                val root = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    JSONObject(resp.body?.string().orEmpty())
                }
                parseSearch(root, limit)
            }.getOrDefault(emptyList())
        }

    private fun searchParams(filter: com.lyreon.app.data.model.SearchFilter): String? = when (filter) {
        com.lyreon.app.data.model.SearchFilter.SONGS -> "EgWKAQIIAWoKEAoQCRADEAA%3D"
        com.lyreon.app.data.model.SearchFilter.VIDEOS -> "EgWKAQIQAWoKEAoQCRADEAA%3D"
        com.lyreon.app.data.model.SearchFilter.ALBUMS -> "EgWKAQIQAWoKEAoQCRADEAA%3D"
        com.lyreon.app.data.model.SearchFilter.PLAYLISTS -> "EgWKAQIQAWoKEAoQCRADEAA%3D"
        com.lyreon.app.data.model.SearchFilter.PODCASTS -> null
        com.lyreon.app.data.model.SearchFilter.ALL -> null
    }

    private fun parseSearch(root: JSONObject, limit: Int): List<LyreonTrack> {
        val out = ArrayList<LyreonTrack>(limit)
        val contents = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return out

        fun textOf(node: JSONObject?, key: String): String {
            if (node == null) return ""
            val runs = node.optJSONArray("runs") ?: return node.optString("simpleText")
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            return sb.toString()
        }

        for (s in 0 until contents.length()) {
            val itemSection = contents.optJSONObject(s)
                ?.optJSONObject("itemSectionRenderer")
                ?.optJSONArray("contents") ?: continue
            for (c in 0 until itemSection.length()) {
                val vr = itemSection.optJSONObject(c)?.optJSONObject("videoRenderer") ?: continue
                val videoId = vr.optString("videoId").orEmpty()
                if (videoId.isBlank() || videoId.length !in 6..20) continue
                val title = textOf(vr.optJSONObject("title"), "text").ifBlank { continue }
                val thumb = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else "" }
                    .orEmpty()
                val duration = parseDuration(textOf(vr.optJSONObject("lengthText"), "simpleText"))
                val artist = vr.optJSONObject("ownerText")?.let { textOf(it, "runs") }.orEmpty()
                out += LyreonTrack(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    durationSec = duration,
                    thumbnailUrl = thumb,
                )
                if (out.size >= limit) return out
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Playlist
    // ------------------------------------------------------------------

    suspend fun playlist(playlistId: String, limit: Int = 200): List<LyreonTrack> =
        withContext(Dispatchers.IO) {
            ensureReady()
            runCatching {
                val req = InnertubeRequest.playlist(Client.WEB, InnertubeConfig.webClientVersion(), playlistId, InnertubeConfig.visitor())
                val root = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    JSONObject(resp.body?.string().orEmpty())
                }
                parsePlaylist(root, limit)
            }.getOrDefault(emptyList())
        }

    private fun parsePlaylist(root: JSONObject, limit: Int): List<LyreonTrack> {
        val out = ArrayList<LyreonTrack>(limit)
        val contents = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs") ?: return out

        fun textOf(node: JSONObject?, key: String): String {
            if (node == null) return ""
            val runs = node.optJSONArray("runs") ?: return node.optString("simpleText")
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            return sb.toString()
        }

        for (t in 0 until contents.length()) {
            val sections = contents.optJSONObject(t)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: continue
            for (s in 0 until sections.length()) {
                val items = sections.optJSONObject(s)
                    ?.optJSONObject("musicPlaylistShelfRenderer")
                    ?.optJSONArray("contents") ?: sections.optJSONObject(s)
                    ?.optJSONObject("playlistVideoListRenderer")
                    ?.optJSONArray("contents") ?: continue
                for (i in 0 until items.length()) {
                    val row = items.optJSONObject(i) ?: continue
                    val vr = row.optJSONObject("playlistVideoRenderer") ?: continue
                    val videoId = vr.optString("videoId").orEmpty()
                    if (videoId.isBlank() || videoId.length !in 6..20) continue
                    val title = textOf(vr.optJSONObject("title"), "text").ifBlank { continue }
                    val thumb = vr.optJSONObject("thumbnail")?.optJSONObject("thumbnails")
                        ?.optJSONArray("thumbnails")
                        ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else "" }
                        .orEmpty()
                    val length = vr.optJSONObject("lengthSeconds")?.optString("simpleText").orEmpty()
                        .takeIf { it.isNotBlank() } ?: textOf(vr.optJSONObject("lengthText"), "simpleText")
                    val duration = length.toLongOrNull() ?: parseDuration(length)
                    val artist = vr.optJSONObject("shortBylineText")?.let { textOf(it, "runs") }.orEmpty()
                    out += LyreonTrack(videoId, title, artist, durationSec = duration, thumbnailUrl = thumb)
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    private fun parseDuration(raw: String): Long {
        if (raw.isBlank()) return 0L
        val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    /** Id playlist dari URL (watch?list= / playlist?list=). */
    fun playlistIdOf(url: String): String =
        Regex("[?&]list=([a-zA-Z0-9_-]{6,})").find(url)?.groupValues?.get(1)
            ?: url.substringAfter("/playlist?list=").substringBefore("&")
            ?: url.substringAfter("list=").substringBefore("&")

    fun isPlaylistUrl(url: String): Boolean =
        url.contains("youtube.com") && (url.contains("list=") || url.contains("/playlist"))
}
