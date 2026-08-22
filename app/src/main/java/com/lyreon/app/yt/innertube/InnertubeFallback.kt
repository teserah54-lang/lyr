package com.lyreon.app.yt.innertube

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
class InnertubeFallback {

    companion object {
        private const val TAG = "InnertubeFallback"
        // Semua client yang dicoba, urut dari yang paling tahan throttling & memberi stream langsung
        private val CLIENTS = listOf(
            Client.ANDROID_VR,
            Client.ANDROID_TESTSUITE,
            Client.IOS,
            Client.TV_EMBEDDED,
            Client.ANDROID,
            Client.WEB_REMIX,
            Client.WEB,
        )
    }

    private val http = LyreonHttp.extractClient
    private val ua = LyreonHttp.USER_AGENT
    private val streamCache = ConcurrentHashMap<String, ResolvedAudio>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Hapus stream yang sudah kadaluwarsa/403 dari cache (dipanggil saat error). */
    fun invalidate(videoId: String) {
        streamCache.remove(videoId)
    }

    /** Pastikan konfigurasi InnerTube sudah di-scrape (idempoten, thread-safe). */
    private fun ensureReady() {
        InnertubeConfig.ensure(http, ua)
        InnertubeConfig.ensureVisitorData(http, ua)
    }

    // ------------------------------------------------------------------
    // Resolusi stream / player
    // ------------------------------------------------------------------

    /** Blocking — aman dipanggil dari thread loader ExoPlayer / service unduhan. */
    fun resolveAudioBlocking(videoId: String, quality: AudioQuality): ResolvedAudio {
        ensureReady()
        val cached = streamCache[videoId]
        if (cached != null && cached.expiresAtMs - 60_000L > System.currentTimeMillis()) {
            return cached
        }
        val lock = locks.getOrPut(videoId) { Any() }
        synchronized(lock) {
            streamCache[videoId]?.let { return it }
            val resolved = fetchPlayer(videoId, quality) ?: throw IOException("InnerTube: tidak ada stream audio untuk $videoId")
            streamCache[videoId] = resolved
            return resolved
        }
    }

    private fun fetchPlayer(videoId: String, quality: AudioQuality): ResolvedAudio? {
        val visitor = InnertubeConfig.visitor()
        for (client in CLIENTS) {
            val version = if (client == Client.WEB) InnertubeConfig.webClientVersion()
            else if (client == Client.ANDROID) InnertubeConfig.androidClientVersion()
            else client.version
            try {
                val (url, req) = InnertubeRequest.player(client, version, videoId, visitor)
                val body = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use ""
                    resp.body?.string().orEmpty()
                }
                if (body.isBlank()) continue
                val root = JSONObject(body)
                val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                if (status.isNotEmpty() && !status.equals("OK", ignoreCase = true)) continue
                val sd = root.optJSONObject("streamingData") ?: continue
                val picked = pickAudio(sd, quality) ?: continue
                return picked
            } catch (e: Exception) {
                // coba client berikutnya
            }
        }
        return null
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
