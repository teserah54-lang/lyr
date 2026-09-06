package com.lyreon.app.yt.innertube

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * SATU tempat berisi daftar klien InnerTube yang dicoba Lyreon, urut prioritas.
 *
 * ## Kenapa lapisan ini ada
 *
 * YouTube tidak mengumumkan klien mana yang boleh menerima `streamingData`
 * berisi URL langsung; kebijakannya bergeser terus (SABR digulirkan bertahap
 * sejak 2025, endpoint `ANDROID_VR` bahkan dihapus upstream pada Agustus 2026).
 * Tanpa lapisan ini, setiap perubahan kebijakan YouTube berarti membongkar kode
 * resolusi stream. Dengan lapisan ini, yang perlu diubah hanya **urutan/tabel di
 * bawah** — sisanya (deteksi SABR, diagnostik, pendinginan klien) otomatis ikut.
 *
 * Nilai-nilai di sini disalin dari extractor keluarga NewPipe yang paling mutakhir
 * per September 2026 (`InfinityLoop1308/PipePipeExtractor` v5.3.0, fork yang juga
 * menjadi hulu `MetrolistExtractor`), sehingga bentuk request-nya identik dengan
 * yang dipakai klien resmi:
 *
 * - `visionos`   — klien anonim default PipePipe sejak Agu 2026.
 * - `tv_simply`  — `TVHTML5_SIMPLY` (id 75), tanpa poToken.
 * - `tv_downgraded` — `TVHTML5` (id 7) dengan UA Cobalt.
 * - `mweb`       — butuh poToken (BotGuard); tanpa itu hampir pasti SABR-only,
 *                  jadi ditaruh paling akhir dan hanya untuk diagnostik.
 */
internal data class PlayerClientSpec(
    /** Kunci pendek untuk log/diagnostik ("visionos", "tv_simply", …). */
    val key: String,
    val clientName: String,
    /** Kosong = pakai versi web hasil scrape [InnertubeConfig]. */
    val clientVersion: String,
    /** Nilai header `X-YouTube-Client-Name`. */
    val clientId: String,
    val userAgent: String,
    val clientScreen: String = "WATCH",
    val platform: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int = 0,
    /**
     * Klien "mobile" (Android/iOS/visionOS) di hulu dikirim ke
     * `youtubei.googleapis.com` dengan query `&t=…&id=…`, bukan ke
     * `www.youtube.com/youtubei/v1`.
     */
    val mobileEndpoint: Boolean = false,
    val requiresPoToken: Boolean = false,
    val origin: String? = null,
    val referer: String? = null,
    /** URL pihak ketiga untuk klien embed. */
    val embedUrl: Boolean = false,
    val note: String = "",
)

/** Hasil pemeriksaan satu response player. */
internal enum class ClientVerdict {
    /** Ada `adaptiveFormats`/`formats` dengan `url` atau `signatureCipher`. */
    USABLE,

    /**
     * Response berisi format tetapi tanpa satu pun URL/cipher — tanda tangan
     * SABR (`serverAbrStreamingUrl`). Inilah penyebab loop "stream tidak
     * tersedia" di semua lagu.
     */
    SABR_ONLY,

    /** Hanya `hlsManifestUrl` — perlu pemutar HLS + (umumnya) sesi login. */
    HLS_ONLY,

    /** `playabilityStatus` != OK (LOGIN_REQUIRED, AGE_CHECK_REQUIRED, ERROR…). */
    PLAYABILITY_BLOCKED,

    /** Tidak ada `streamingData` sama sekali. */
    NO_STREAMING_DATA,

    /** `videoDetails.videoId` tidak cocok — YouTube mengganti response (anti-bot). */
    INVALID_RESPONSE,

    /** HTTP gagal / body bukan JSON. */
    TRANSPORT_ERROR,
}

/**
 * Urutan klien + memori jangka pendek: klien yang terakhir berhasil dinaikkan ke
 * depan, klien yang baru saja balas SABR-only didinginkan (tetap dicoba, hanya
 * digeser ke belakang) supaya satu lagu gagal tidak membuang waktu pada klien
 * yang jelas sedang ditutup YouTube.
 */
internal object PlayerClientLadder {

    private const val TAG = "PlayerClientLadder"

    /** Lama pendinginan klien yang balas SABR-only / transport error. */
    private const val COOLDOWN_MS = 3L * 60_000L

    /** Ukuran ring buffer diagnostik yang bisa dibaca dari layar Settings. */
    private const val DIAGNOSTIC_LIMIT = 40

    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36,gzip(gfe)"
    private const val MWEB_UA =
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
    private const val COBALT_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
    private const val PS_UA =
        "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    /**
     * Urutan default per September 2026. Pindahkan entri ke atas/bawah saat
     * YouTube mengubah kebijakan — tidak ada kode lain yang perlu disentuh.
     */
    private val SPECS: List<PlayerClientSpec> = listOf(
        PlayerClientSpec(
            key = "visionos",
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientId = "101",
            userAgent = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)",
            platform = "MOBILE",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            osName = "visionOS",
            osVersion = "25.6.0.23O471",
            mobileEndpoint = true,
            note = "klien anonim default PipePipe v5.3.0 (Agu 2026)",
        ),
        PlayerClientSpec(
            key = "tv_simply",
            clientName = "TVHTML5_SIMPLY",
            clientVersion = "1.0",
            clientId = "75",
            userAgent = WEB_UA,
            origin = "https://www.youtube.com",
            referer = "https://www.youtube.com/",
            note = "tanpa poToken, jarang kena SABR",
        ),
        PlayerClientSpec(
            key = "tv_downgraded",
            clientName = "TVHTML5",
            clientVersion = "5.20260114",
            clientId = "7",
            userAgent = COBALT_UA,
            origin = "https://www.youtube.com",
            referer = "https://www.youtube.com/",
            note = "TVHTML5 versi lawas (Cobalt)",
        ),
        PlayerClientSpec(
            key = "ios",
            clientName = "IOS",
            clientVersion = "20.03.02",
            clientId = "5",
            userAgent = "com.google.ios.youtube/20.03.02(iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X; US)",
            platform = "MOBILE",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iOS",
            osVersion = "18.1.0.22B83",
            mobileEndpoint = true,
            note = "bagus untuk HLS saat login",
        ),
        PlayerClientSpec(
            key = "android",
            clientName = "ANDROID",
            clientVersion = "21.03.36",
            clientId = "3",
            userAgent = "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip",
            platform = "MOBILE",
            osName = "Android",
            osVersion = "16",
            androidSdkVersion = 36,
            mobileEndpoint = true,
        ),
        PlayerClientSpec(
            key = "android_vr",
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 14) gzip",
            platform = "MOBILE",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34,
            mobileEndpoint = true,
            note = "endpoint dihapus upstream Agu 2026 — cadangan saja",
        ),
        PlayerClientSpec(
            key = "tv_embedded",
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientId = "85",
            userAgent = PS_UA,
            origin = "https://www.youtube.com",
            referer = "https://www.youtube.com/",
            embedUrl = true,
            note = "menembus sebagian video terbatas umur",
        ),
        PlayerClientSpec(
            key = "web_remix",
            clientName = "WEB_REMIX",
            clientVersion = "1.20260818.01.00",
            clientId = "67",
            userAgent = WEB_UA,
            origin = "https://music.youtube.com",
            referer = "https://music.youtube.com/",
            note = "YouTube Music web — paling sering kena SABR/poToken",
        ),
        PlayerClientSpec(
            key = "web",
            clientName = "WEB",
            clientVersion = "",
            clientId = "1",
            userAgent = WEB_UA,
            origin = "https://www.youtube.com",
            referer = "https://www.youtube.com/",
        ),
        PlayerClientSpec(
            key = "mweb",
            clientName = "MWEB",
            clientVersion = "",
            clientId = "2",
            userAgent = MWEB_UA,
            origin = "https://m.youtube.com",
            referer = "https://m.youtube.com/",
            requiresPoToken = true,
            note = "butuh poToken (BotGuard); tanpa itu hampir pasti SABR-only",
        ),
    )

    private val lastGood = AtomicReference<String?>(null)
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val diagnostics = ConcurrentLinkedDeque<String>()

    /**
     * [SimpleDateFormat] tidak thread-safe, sedangkan [note] bisa dipanggil dari
     * beberapa thread IO sekaligus — formatter dibuat per panggilan (jalur
     * diagnostik saja, bukan jalur panas).
     */
    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    @Volatile
    private var lastSabrAtMs: Long = 0L

    /** Total kejadian SABR-only sesi ini (extractor + tangga klien). */
    private val sabrTotal = AtomicInteger(0)

    /** Urutan coba: klien terakhir berhasil di depan, klien dingin di belakang. */
    fun ordered(): List<PlayerClientSpec> {
        val base = SPECS.toMutableList()
        lastGood.get()?.let { good ->
            val index = base.indexOfFirst { it.key == good }
            if (index > 0) base.add(0, base.removeAt(index))
        }
        val now = System.currentTimeMillis()
        val warm = ArrayList<PlayerClientSpec>(base.size)
        val cold = ArrayList<PlayerClientSpec>(4)
        base.forEach { spec ->
            if ((cooldownUntil[spec.key] ?: 0L) > now) cold += spec else warm += spec
        }
        return warm + cold
    }

    fun specOf(key: String): PlayerClientSpec? = SPECS.firstOrNull { it.key == key }

    /** Catat hasil satu percobaan klien (diagnostik + urutan adaptif). */
    fun note(key: String, verdict: ClientVerdict, elapsedMs: Long, detail: String = "") {
        if (verdict == ClientVerdict.USABLE) {
            lastGood.set(key)
            cooldownUntil.remove(key)
        }
        if (verdict == ClientVerdict.SABR_ONLY) {
            lastSabrAtMs = System.currentTimeMillis()
            sabrTotal.incrementAndGet()
            cooldownUntil[key] = lastSabrAtMs + COOLDOWN_MS
        }
        if (verdict == ClientVerdict.TRANSPORT_ERROR) {
            cooldownUntil[key] = System.currentTimeMillis() + COOLDOWN_MS
        }
        val suffix = if (detail.isBlank()) "" else " · $detail"
        push("${timestamp()} $key → $verdict (${elapsedMs}ms)$suffix")
        if (verdict == ClientVerdict.SABR_ONLY) {
            Log.w(
                TAG,
                "klien '$key' membalas SABR-only (tanpa URL stream). " +
                    "Total kejadian SABR sesi ini: ${sabrTotal.get()}",
            )
        }
    }

    /**
     * Extractor utama juga bisa membalas SABR-only — `YoutubeStreamExtractor`
     * melempar `ContentNotSupportedException("YouTube returned SABR-only streaming
     * data …")`. Dicatat di buku yang sama supaya [sabrPressureRecently] mencakup
     * kedua jalur (extractor maupun tangga klien InnerTube).
     */
    fun noteExtractorSabr(detail: String) {
        lastSabrAtMs = System.currentTimeMillis()
        sabrTotal.incrementAndGet()
        push("${timestamp()} extractor → SABR_ONLY · $detail")
        Log.w(TAG, "extractor membalas SABR-only ($detail) — total sesi ini: ${sabrTotal.get()}")
    }

    /**
     * True bila dalam [windowMs] terakhir ada klien yang membalas SABR-only —
     * dipakai PlayerManager untuk memilih pesan error yang tepat ("aktifkan akun
     * YouTube") alih-alih pesan generik.
     */
    fun sabrPressureRecently(windowMs: Long = 10L * 60_000L): Boolean =
        System.currentTimeMillis() - lastSabrAtMs < windowMs

    fun sabrCount(): Int = sabrTotal.get()

    /** Baris diagnostik terbaru (paling baru di depan) untuk layar Settings. */
    fun report(): List<String> = diagnostics.toList()

    fun push(line: String) {
        diagnostics.addFirst(line)
        while (diagnostics.size > DIAGNOSTIC_LIMIT) diagnostics.pollLast()
    }

    fun reset() {
        diagnostics.clear()
        cooldownUntil.clear()
        lastGood.set(null)
        lastSabrAtMs = 0L
        sabrTotal.set(0)
    }

    // ------------------------------------------------------------------
    // Inspeksi response
    // ------------------------------------------------------------------

    /**
     * Menilai satu response `/youtubei/v1/player`.
     *
     * Deteksi SABR mengikuti logika `YoutubeStreamExtractor.isSabrOnlyResponse()`
     * milik fork: format ada tetapi tidak satu pun membawa `url`,
     * `signatureCipher`, atau `cipher`.
     */
    fun inspect(root: JSONObject, videoId: String): ClientVerdict {
        val returnedId = root.optJSONObject("videoDetails")?.optString("videoId").orEmpty()
        if (returnedId.isNotBlank() && returnedId != videoId) return ClientVerdict.INVALID_RESPONSE

        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status.isNotBlank() && !status.equals("OK", ignoreCase = true)) {
            return ClientVerdict.PLAYABILITY_BLOCKED
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: return ClientVerdict.NO_STREAMING_DATA

        if (hasDirectStreamUrl(streamingData)) return ClientVerdict.USABLE

        if (!streamingData.optString("hlsManifestUrl").isNullOrBlank()) {
            return ClientVerdict.HLS_ONLY
        }

        val formatCount =
            (streamingData.optJSONArray("adaptiveFormats")?.length() ?: 0) +
                (streamingData.optJSONArray("formats")?.length() ?: 0)
        val sabrUrl = streamingData.optString("serverAbrStreamingUrl").orEmpty()
        return if (formatCount > 0 || sabrUrl.isNotBlank()) {
            ClientVerdict.SABR_ONLY
        } else {
            ClientVerdict.NO_STREAMING_DATA
        }
    }

    /** True bila ada format dengan URL langsung atau cipher yang bisa dipecahkan. */
    fun hasDirectStreamUrl(streamingData: JSONObject): Boolean {
        listOf("adaptiveFormats", "formats").forEach { key ->
            val array = streamingData.optJSONArray(key) ?: return@forEach
            for (i in 0 until array.length()) {
                val format = array.optJSONObject(i) ?: continue
                if (!format.optString("url").isNullOrBlank()) return true
                if (!format.optString("signatureCipher").isNullOrBlank()) return true
                if (!format.optString("cipher").isNullOrBlank()) return true
            }
        }
        return false
    }

    /** Alasan `playabilityStatus` (untuk pesan error yang bisa ditindaklanjuti). */
    fun playabilityReason(root: JSONObject): String {
        val status = root.optJSONObject("playabilityStatus") ?: return ""
        val reason = status.optString("reason").orEmpty()
        return "${status.optString("status").orEmpty()}${if (reason.isBlank()) "" else ": $reason"}"
    }
}
