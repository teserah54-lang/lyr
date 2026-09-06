/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.lyrics

import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.yt.LyreonHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Satu baris lirik dari berkas LRC (time-synced). */
data class LrcLine(val timeMs: Long, val text: String)

/** Hasil lirik untuk satu lagu. */
data class LyricsResult(
    val synced: List<LrcLine>,
    val plain: String?,
    val instrumental: Boolean,
) {
    val hasSynced: Boolean get() = synced.isNotEmpty()
    val isEmpty: Boolean get() = !hasSynced && plain.isNullOrBlank() && !instrumental
}

/** Parser format LRC: `[mm:ss.xx]teks` (duplikat timestamp disatukan). */
object LrcParser {
    private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]")

    fun parse(raw: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        raw.lines().forEach { line ->
            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    3 -> fracRaw.toLongOrNull() ?: 0L
                    else -> 0L
                }
                lines.add(LrcLine(timeMs = (min * 60L + sec) * 1000L + frac, text = text))
            }
        }
        return lines.sortedBy { it.timeMs }.distinctBy { it.timeMs to it.text }
    }
}

/**
 * Lirik dari LRCLIB (https://lrclib.net) — gratis, tanpa API key.
 * Strategi: GET /api/get (exact match) → 404 → GET /api/search (fuzzy).
 */
class LyricsRepository {

    private val client get() = LyreonHttp.extractClient
    private val cache = ConcurrentHashMap<String, LyricsResult>()

    suspend fun lyrics(track: LyreonTrack): LyricsResult = withContext(Dispatchers.IO) {
        cache[track.videoId]?.let { return@withContext it }
        val result = runCatching { fetch(track) }.getOrElse { LyricsResult(emptyList(), null, false) }
        cache[track.videoId] = result
        result
    }

    private fun fetch(track: LyreonTrack): LyricsResult {
        val (title, artist) = cleanForSearch(track)
        val duration = track.durationSec.coerceAtLeast(0L)

        // 1) Exact
        val exactUrl = buildString {
            append("https://lrclib.net/api/get?track_name=").append(enc(title))
            if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
            if (duration > 0) append("&duration=").append(duration)
        }
        get(exactUrl)?.let { body ->
            if (!body.startsWith("{")) return@let
            val json = JSONObject(body)
            toResult(json)?.let { return it }
        }

        // 2) Fuzzy search — pilih kandidat SYNCED dengan durasi PALING DEKAT ke
        //    durasi track. Mengambil hit synced pertama begitu saja sering kena
        //    versi live/remix/edit → timestamp-nya bergeser ("lirik gak singkron").
        val q = if (artist.isBlank()) title else "$title $artist"
        val searchUrl = "https://lrclib.net/api/search?q=" + enc(q)
        val body = get(searchUrl) ?: return LyricsResult(emptyList(), null, false)
        if (!body.startsWith("[")) return LyricsResult(emptyList(), null, false)
        val arr = org.json.JSONArray(body)
        var bestSynced: LyricsResult? = null
        var bestSyncedDiff = Long.MAX_VALUE
        var bestPlain: LyricsResult? = null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val itemDuration = item.optDouble("duration", 0.0).toLong()
            val synced = item.optString("syncedLyrics")
            if (!synced.isNullOrBlank()) {
                val res = toResult(item) ?: continue
                val diff = if (duration > 0 && itemDuration > 0) {
                    kotlin.math.abs(itemDuration - duration)
                } else Long.MAX_VALUE / 2
                if (diff < bestSyncedDiff) {
                    bestSyncedDiff = diff
                    bestSynced = res
                }
                if (diff <= 3L) break // cukup presisi — hentikan lebih awal
            }
            if (bestPlain == null && !item.optString("plainLyrics").isNullOrBlank()) {
                bestPlain = toResult(item)
            }
        }
        // Terima synced bila durasi cocok (±10 dtk) atau durasi tak diketahui;
        // kalau terlalu jauh → hampir pasti versi salah, pakai plain dulu.
        if (bestSynced != null && (duration <= 0 || bestSyncedDiff <= 10L)) return bestSynced
        return bestPlain ?: bestSynced ?: LyricsResult(emptyList(), null, false)
    }

    private fun toResult(json: JSONObject): LyricsResult? {
        val instrumental = json.optBoolean("instrumental", false)
        val syncedRaw = json.optString("syncedLyrics")
        val plainRaw = json.optString("plainLyrics")
        val synced = if (syncedRaw.isNullOrBlank()) emptyList() else LrcParser.parse(syncedRaw)
        val plain = plainRaw?.takeIf { it.isNotBlank() }
        if (synced.isEmpty() && plain == null && !instrumental) return null
        return LyricsResult(synced = synced, plain = plain, instrumental = instrumental)
    }

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Lyreon Android (github.com/rixz-dev/Lyra)")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body.string()
        }
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Bersihkan judul khas YouTube ("(Official Music Video)", "[Lyric]", dll). */
    private fun cleanForSearch(track: LyreonTrack): Pair<String, String> {
        var title = track.title
        var artist = track.artist

        // Banyak judul YouTube berbentuk "Artis - Judul"
        if (artist.isBlank() && title.contains(" - ")) {
            val parts = title.split(" - ", limit = 2)
            artist = parts[0].trim()
            title = parts.getOrElse(1) { title }.trim()
        }
        val cleaned = title
            .replace(Regex("\\((?i)(official|music|lyric|audio|video|mv|hd|4k)[^)]*\\)"), "")
            .replace(Regex("\\[(?i)(official|music|lyric|audio|video|mv|hd|4k)[^]]*]"), "")
            .replace(Regex("(?i)\\s*[-–—]\\s*(official|music video|lyric video|audio)$"), "")
            .trim()
        return (cleaned.ifBlank { title }) to artist.replace(Regex("(?i)\\s*-\\s*topic$"), "").trim()
    }
}
