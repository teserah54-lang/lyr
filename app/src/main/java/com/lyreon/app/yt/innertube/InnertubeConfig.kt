package com.lyreon.app.yt.innertube

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Konfigurasi runtime InnerTube (kunci publik + versi client + visitorData).
 *
 * Nilai API key & client version bukan rahasia — keduanya dipajang oleh YouTube
 * di halaman mereka dan berubah sewaktu-waktu. Modul ini men-scrape-nya dari
 * halaman web YouTube sekali per sesi lalu men-cache-nya, supaya fallback tetap
 * hidup tanpa hardcode yang mudah basi.
 */
internal object InnertubeConfig {

    private const val TAG = "InnertubeConfig"

    // Cadangan bila halaman gagal di-scrape (kunci publik WEB_REMIX / TV / ANDROID yang dikenal).
    private const val FALLBACK_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val FALLBACK_WEB_VERSION = "2.20260818.01.00"
    private const val FALLBACK_ANDROID_VERSION = "19.45.38"

    @Volatile private var apiKey: String = FALLBACK_KEY
    @Volatile private var webVersion: String = FALLBACK_WEB_VERSION
    @Volatile private var androidVersion: String = FALLBACK_ANDROID_VERSION
    @Volatile private var visitorData: String? = null
    @Volatile private var playerJsUrl: String? = null
    @Volatile private var signatureTs: Int = 0

    private val fetched = ConcurrentHashMap.newKeySet<String>()

    /** Jeda minimum sebelum scrape ytcfg diulang setelah kegagalan. */
    private const val RETRY_AFTER_MS = 5L * 60_000L

    @Volatile private var scrapeOk = false
    @Volatile private var lastScrapeAtMs = 0L

    /**
     * Mengambil konfigurasi dari halaman YouTube. Aman dipanggil berulang.
     *
     * Bila scrape pernah GAGAL (jaringan putus saat start-up, YouTube mengubah
     * markup), dicoba lagi setelah [RETRY_AFTER_MS] — memakai nilai cadangan
     * selamanya berarti semua klien bisa ditolak YouTube hanya karena satu
     * kegagalan sesaat di awal sesi.
     */
    @Synchronized
    fun ensure(ioClient: okhttp3.OkHttpClient, userAgent: String) {
        if (scrapeOk) return
        val now = System.currentTimeMillis()
        if (lastScrapeAtMs > 0L && now - lastScrapeAtMs < RETRY_AFTER_MS) return
        lastScrapeAtMs = now
        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .header("User-Agent", userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            ioClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                apiKey = Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    ?.groupValues?.getOrNull(1) ?: apiKey
                webVersion = Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    ?.groupValues?.getOrNull(1) ?: webVersion
                playerJsUrl = Regex("\"PLAYER_JS_URL\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    ?.groupValues?.getOrNull(1)
                    ?: Regex("src=\"(/s/player/[^\"]+/base\\.js)\"").find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("src=\"(https://www\\.youtube\\.com/s/player/[^\"]+/base\\.js)\"").find(html)?.groupValues?.getOrNull(1)
                // `STS` di ytcfg = signatureTimestamp. Diperlukan klien yang URL-nya
                // masih ditandatangani base.js; tanpanya URL kerap langsung 403.
                signatureTs = Regex("\"STS\"\\s*:\\s*(\\d{4,6})").find(html)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: signatureTs
                scrapeOk = html.isNotBlank()
                if (scrapeOk) Log.i(TAG, "scrape ytcfg OK: versi=$webVersion sts=$signatureTs")
            }
        }.onFailure { e ->
            Log.w(TAG, "ensure() gagal scrape: ${e.message} — pakai nilai cadangan")
        }
    }

    /**
     * Buang `visitorData` tersimpan supaya permintaan berikutnya mengambil yang
     * baru. Dipanggil tangga klien setelah beberapa respons beruntun ditolak
     * (`UNPLAYABLE`, "The page needs to be reloaded", transport error): pola itu
     * sering berarti visitorData basi/tidak cocok dengan sesi yang memeriksa.
     */
    @Synchronized
    fun invalidateVisitor() {
        if (visitorData == null) return
        visitorData = null
        fetched.remove("visitor")
        Log.i(TAG, "visitorData dibuang — akan diambil ulang pada permintaan berikut")
    }

    /** Visitor data opsional (dari halaman) — membantu beberapa permintaan player. */
    fun ensureVisitorData(ioClient: okhttp3.OkHttpClient, userAgent: String) {
        if (visitorData != null || !fetched.add("visitor")) return
        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/guide?prettyPrint=false")
                .header("User-Agent", userAgent)
                .post(
                    InnertubeRequest.baseContextJson(webVersion)
                        .toRequestBody("application/json".toMediaType()),
                )
                .build()
            ioClient.newCall(req).execute().use { resp ->
                val body = org.json.JSONObject(resp.body?.string().orEmpty())
                visitorData = body.optString("visitorData").takeIf { it.isNotBlank() }
            }
        }.onFailure { /* non-fatal */ }
    }

    fun apiKey(): String = apiKey

    /**
     * signatureTimestamp (`STS`) hasil scrape ytcfg; null bila belum tersedia.
     * Dikirim sebagai `playbackContext.contentPlaybackContext.signatureTimestamp`
     * untuk klien yang URL stream-nya masih perlu di-decipher.
     */
    fun signatureTimestamp(): Int? = signatureTs.takeIf { it > 0 }
    fun webClientVersion(): String = webVersion
    fun androidClientVersion(): String = androidVersion
    fun visitor(): String? = visitorData
    fun baseJsUrl(): String? = playerJsUrl?.let { if (it.startsWith("//")) "https:$it" else it }
}
