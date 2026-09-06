package com.lyreon.app.yt

import android.util.Log
import org.json.JSONArray
import org.schabi.newpipe.extractor.ServiceList
import java.security.MessageDigest

/**
 * Identitas YouTube (cookie akun) untuk seluruh jalur ekstraksi Lyreon.
 *
 * ## Kenapa file ini ada
 *
 * Sejak 2025 YouTube menggulirkan **SABR** (server-side adaptive bitrate) untuk
 * klien tanpa identitas. Response `/youtubei/v1/player` yang biasanya berisi
 * `streamingData.adaptiveFormats[].url` kini hanya berisi sesi SABR
 * (`serverAbrStreamingUrl`) → extractor melempar:
 *
 * ```
 * ContentNotSupportedException: "YouTube returned SABR-only streaming data without
 * usable stream URLs. Try logging in to get HLS fallback streams."
 * ```
 *
 * Di fork extractor yang dipakai Lyreon (`MetrolistExtractor`, commit 3cd3341,
 * Jun 2026) percabangannya persis di `YoutubeStreamExtractor.onFetchPage()`:
 *
 * ```java
 * if (StringUtils.isBlank(ServiceList.YouTube.getTokens())) {
 *     androidCall = fetchAndroidVRJsonPlayer(...);   // jalur ANONIM → rawan SABR-only
 * } else {
 *     safariCall  = fetchSafariJsonPlayer(...);      // jalur LOGIN  → HLS fallback
 * }
 * ```
 *
 * Artinya: selama `ServiceList.YouTube.setTokens()` tidak pernah dipanggil, app
 * selalu lewat jalur anonim yang sedang digempur SABR. Objek ini adalah satu-satunya
 * tempat yang memegang identitas itu — extractor (via `setTokens`) maupun jalur
 * cadangan InnerTube milik Lyreon (via [authHeaders]).
 *
 * ## Keamanan
 *
 * Cookie = kredensial. Nilai mentah hanya disimpan di DataStore privat aplikasi
 * (lihat `AccountRepository`), tidak pernah dikirim ke server selain Google, dan
 * tidak pernah dicatat ke log (yang dicatat hanya jumlah cookie & ada/tidaknya
 * SAPISID).
 */
object YouTubeAccount {

    private const val TAG = "YouTubeAccount"

    /**
     * Batas tunggu extractor dalam detik. Default fork = **5 s** dan
     * `onFetchPage()` menunggu player response dengan busy-wait sepanjang nilai
     * itu (`ServiceList.YouTube.getLoadingTimeout()`). Di jaringan seluler yang
     * padat 5 s sering terpotong → `streamingData == null` → app salah
     * menyimpulkan "stream tidak tersedia" lalu melompat ke lagu berikutnya
     * (bahan bakar loop). 12 s cukup longgar tanpa terasa lambat.
     */
    private const val LOADING_TIMEOUT_SEC = 12

    /**
     * Wajib: fork membangun header `Authorization: SAPISIDHASH …` dari cookie
     * `SAPISID`/`__Secure-3PAPISID` (`YoutubeParsingHelper.getAuthorizationHeader`).
     * Bila tidak ada, ekstraksi justru **melempar** `ExtractionException("Failed to
     * get authorization header")` — lebih buruk daripada mode anonim. Karena itu
     * cookie tanpa SAPISID ditolak mentah-mentah oleh [apply].
     */
    private val SAPISID_KEYS = listOf("SAPISID", "__Secure-3PAPISID")

    /** Cookie sesi yang menandakan login penuh (dipakai untuk laporan ke pengguna). */
    private val SESSION_KEYS = listOf(
        "SID", "HSID", "SSID", "APISID",
        "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO",
    )

    private val ALLOWED_DOMAIN_MARKERS = listOf("youtube.com", "google.com")

    @Volatile
    private var current: String? = null

    @Volatile
    private var gen: Long = 0L

    /** True bila identitas login sedang terpasang di extractor. */
    val isLoggedIn: Boolean get() = !current.isNullOrBlank()

    /**
     * Naik setiap identitas berubah. Dipakai untuk membuang cache URL stream:
     * URL yang di-resolve dalam mode anonim tidak boleh dipakai ulang setelah
     * login (dan sebaliknya), karena keduanya berasal dari klien berbeda.
     */
    val generation: Long get() = gen

    /** Cookie header yang sedang aktif (untuk jalur InnerTube Lyreon). */
    val cookieHeader: String? get() = current

    enum class Problem {
        /** Input kosong. */
        EMPTY,

        /** Tidak ada satu pun pasangan `name=value` yang bisa dibaca. */
        UNRECOGNIZED,

        /** Cookie terbaca tapi tanpa SAPISID → extractor akan gagal total. */
        NO_SAPISID,
    }

    data class Validation(
        val cookieCount: Int,
        val hasSapisid: Boolean,
        val missingSessionKeys: List<String>,
        val problem: Problem?,
    ) {
        val usable: Boolean get() = problem == null && hasSapisid && cookieCount > 0
    }

    // ------------------------------------------------------------------
    // Parsing: terima 3 format ekspor cookie yang umum
    // ------------------------------------------------------------------

    /**
     * Membaca cookie dari:
     *  1. JSON export (Cookie-Editor, "Get cookies.txt LOCALLY" mode JSON),
     *  2. Netscape `cookies.txt` (7 kolom dipisah tab, termasuk baris `#HttpOnly_`),
     *  3. Header mentah `Cookie: a=b; c=d` / `a=b; c=d` / satu pasangan per baris.
     *
     * Hanya cookie domain YouTube/Google yang dipertahankan.
     */
    fun parsePairs(raw: String): List<Pair<String, String>> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        // (1) JSON export ------------------------------------------------
        if (text.startsWith("[")) {
            val fromJson = runCatching {
                val arr = JSONArray(text)
                val out = ArrayList<Pair<String, String>>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").orEmpty()
                    val value = o.optString("value").orEmpty()
                    val domain = o.optString("domain").orEmpty()
                    if (name.isBlank() || value.isBlank()) continue
                    if (domain.isNotBlank() && !domainAllowed(domain)) continue
                    out += name to value
                }
                out
            }.getOrDefault(emptyList())
            if (fromJson.isNotEmpty()) return dedupe(fromJson)
        }

        // (2) Netscape cookies.txt ---------------------------------------
        val netscape = ArrayList<Pair<String, String>>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val isHttpOnly = trimmed.startsWith("#HttpOnly_")
            if (trimmed.startsWith("#") && !isHttpOnly) continue
            val cleaned = if (isHttpOnly) trimmed.removePrefix("#HttpOnly_") else trimmed
            val fields = cleaned.split("\t")
            if (fields.size < 7) continue
            val domain = fields[0]
            val name = fields[5].trim()
            val value = fields[6].trim()
            if (name.isBlank() || value.isBlank()) continue
            if (!domainAllowed(domain)) continue
            netscape += name to value
        }
        if (netscape.isNotEmpty()) return dedupe(netscape)

        // (3) Header / pasangan name=value --------------------------------
        val body = text
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
        val pairs = body
            .split(';', '\n')
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
        return dedupe(pairs)
    }

    /** Pasangan cookie → satu string header `Cookie:` siap pakai. */
    fun normalize(raw: String): String =
        parsePairs(raw).joinToString("; ") { "${it.first}=${it.second}" }

    private fun domainAllowed(domain: String): Boolean =
        ALLOWED_DOMAIN_MARKERS.any { domain.contains(it, ignoreCase = true) }

    private fun dedupe(pairs: List<Pair<String, String>>): List<Pair<String, String>> {
        // Nama cookie yang muncul dua kali (mis. domain .youtube.com & youtube.com):
        // nilai terakhir yang menang — itu yang paling baru diekspor.
        val map = LinkedHashMap<String, String>(pairs.size)
        pairs.forEach { (name, value) -> map[name] = value }
        return map.entries.map { it.key to it.value }
    }

    // ------------------------------------------------------------------
    // Validasi
    // ------------------------------------------------------------------

    fun validate(raw: String): Validation {
        if (raw.isBlank()) return Validation(0, false, emptyList(), Problem.EMPTY)
        val pairs = parsePairs(raw)
        if (pairs.isEmpty()) return Validation(0, false, emptyList(), Problem.UNRECOGNIZED)
        val names = pairs.map { it.first }.toSet()
        val hasSapisid = SAPISID_KEYS.any { names.contains(it) }
        val missing = SESSION_KEYS.filterNot { names.contains(it) }
        return Validation(
            cookieCount = pairs.size,
            hasSapisid = hasSapisid,
            missingSessionKeys = missing,
            problem = if (hasSapisid) null else Problem.NO_SAPISID,
        )
    }

    // ------------------------------------------------------------------
    // Penerapan ke extractor
    // ------------------------------------------------------------------

    /**
     * Memasang (atau melepas) identitas akun di extractor.
     *
     * @return true bila mode login aktif setelah pemanggilan ini.
     */
    fun apply(rawCookie: String?): Boolean {
        val header = rawCookie
            ?.takeIf { it.isNotBlank() }
            ?.let { normalize(it) }
            ?.takeIf { it.isNotBlank() }

        if (header != null) {
            val validation = validate(header)
            if (!validation.usable) {
                // PENTING: memasang cookie tanpa SAPISID membuat fork melempar
                // ExtractionException di setiap ekstraksi. Lebih aman tetap anonim.
                Log.w(
                    TAG,
                    "Cookie ditolak (${validation.problem}) — tetap mode anonim " +
                        "agar ekstraksi tidak rusak total",
                )
                setTokens("")
                if (current != null) {
                    current = null
                    gen++
                }
                return false
            }
        }

        val changed = header != current
        setTokens(header.orEmpty())
        current = header
        if (changed) gen++
        Log.i(
            TAG,
            if (header != null) {
                "Mode login AKTIF — extractor pakai jalur Safari/HLS (generation=$gen)"
            } else {
                "Mode anonim AKTIF — extractor pakai jalur AndroidVR (generation=$gen)"
            },
        )
        return header != null
    }

    private fun setTokens(value: String) {
        runCatching { ServiceList.YouTube.setTokens(value) }
            .onFailure {
                Log.w(TAG, "setTokens() gagal: ${it.javaClass.simpleName}: ${it.message}")
            }
    }

    /**
     * Knob extractor yang perlu disetel sekali setelah `NewPipe.init()`.
     * Semua dibungkus `runCatching` supaya tetap kompatibel bila fork
     * menghapus/mengganti API ini di versi berikutnya.
     */
    fun tune() {
        runCatching { ServiceList.YouTube.setLoadingTimeout(LOADING_TIMEOUT_SEC) }
            .onFailure { Log.w(TAG, "setLoadingTimeout gagal: ${it.message}") }
        // returnyoutubedislikeapi.com adalah pihak ketiga yang tidak dibutuhkan
        // Lyreon (audio-only, tidak menampilkan dislike) — satu request kurang
        // per pemutaran, satu sumber kegagalan/stall kurang.
        runCatching { ServiceList.YouTube.setFetchDislike(false) }
            .onFailure { Log.w(TAG, "setFetchDislike gagal: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // Header untuk jalur InnerTube milik Lyreon (InnertubeFallback)
    // ------------------------------------------------------------------

    /**
     * Meniru `YoutubeParsingHelper.addLoggedInHeaders()` milik fork: Cookie +
     * `Authorization: SAPISIDHASH …` + `X-Origin` + `DNT`. Kosong bila anonim.
     */
    fun authHeaders(): Map<String, String> {
        val cookie = current ?: return emptyMap()
        val out = LinkedHashMap<String, String>(4)
        out["Cookie"] = cookie
        authorizationHeader()?.let { out["Authorization"] = it }
        out["X-Origin"] = "https://www.youtube.com"
        out["DNT"] = "1"
        return out
    }

    /**
     * `SAPISIDHASH <timestamp>_<sha1(timestamp + " " + SAPISID + " " + origin)>`
     * — skema yang sama dipakai YouTube web dan fork extractor.
     */
    fun authorizationHeader(): String? {
        val cookie = current ?: return null
        val sapisid = cookieValue(cookie, "SAPISID")
            ?: cookieValue(cookie, "__Secure-3PAPISID")
            ?: return null
        val timestamp = System.currentTimeMillis() / 1000L
        val hash = sha1Hex("$timestamp $sapisid https://www.youtube.com") ?: return null
        return "SAPISIDHASH ${timestamp}_$hash"
    }

    private fun cookieValue(header: String, name: String): String? =
        header.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    private fun sha1Hex(input: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Ringkasan aman untuk log/UI — tanpa nilai cookie apa pun. */
    fun describe(): String {
        val cookie = current ?: return "anonim"
        val validation = validate(cookie)
        return "login · ${validation.cookieCount} cookie · SAPISID " +
            (if (validation.hasSapisid) "✓" else "✗")
    }
}
