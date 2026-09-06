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
 * berisi URL yang bisa di-GET; kebijakannya bergeser terus (SABR digulirkan
 * bertahap sejak 2025, poToken diwajibkan per klien, endpoint `ANDROID_VR`
 * ditutup Agustus 2026). Tanpa lapisan ini, setiap perubahan kebijakan YouTube
 * berarti membongkar kode resolusi stream. Dengan lapisan ini, yang perlu
 * diubah hanya **tabel di bawah** — deteksi SABR/DRM/HLS, diagnostik, dan
 * pendinginan klien ikut otomatis.
 *
 * ## Lyreon = ANONIM, tanpa cookie
 *
 * Keputusan produk: pengguna tidak pernah diminta menempel cookie akun. Karena
 * itu tangga ini disusun dari **kebijakan poToken per klien** (tabel "Current PO
 * Token enforcement" di yt-dlp PO Token Guide, revisi Juli 2026):
 *
 * | klien | poToken GVS | catatan |
 * |---|---|---|
 * | `visionos` | tidak | default anonim yt-dlp, tanpa JS player |
 * | `web_embedded` | tidak | hanya video yang boleh di-embed |
 * | `tv` / `tv_downgraded` | tidak | format sering DRM tanpa cookie; itag 18 kadang lolos |
 * | `tv_simply` | **ya** (HTTPS/DASH), HLS tidak | dipakai sebagai sumber manifest HLS |
 * | `web_safari` | **ya** (HTTPS), HLS tidak | Safari UA → format HLS pre-merged |
 * | `android_vr` | **ya** (HTTPS/DASH), HLS tidak | sejak 2026-08-17 semua format 403 |
 * | `web` / `web_remix` / `mweb` | **ya** | SABR-only / UNPLAYABLE tanpa poToken |
 * | `android` / `ios` | **ya** (GVS atau Player) | 403 tanpa poToken |
 *
 * Konsekuensinya: klien yang butuh poToken ([requiresPoToken]) **tidak dipakai
 * untuk memutar** — hanya dijalankan saat "Tes koneksi" supaya pengguna/developer
 * bisa melihat kebijakan YouTube bergeser. Jalur anonim yang nyata adalah
 * URL langsung dari klien bebas poToken **atau manifest HLS** (yang tidak butuh
 * poToken GVS), sehingga Lyreon mendukung pemutaran HLS.
 *
 * Nilai klien disalin dari `yt-dlp/yt-dlp` master (clientVersion Juli 2026) dan
 * `InfinityLoop1308/PipePipeExtractor` v5.3.0 — dua extractor anonim yang paling
 * teruji per September 2026.
 */
internal data class PlayerClientSpec(
    /** Kunci pendek untuk log/diagnostik ("visionos", "web_embedded", …). */
    val key: String,
    val clientName: String,
    /** Kosong = pakai versi web hasil scrape [InnertubeConfig]. */
    val clientVersion: String,
    /** Nilai header `X-YouTube-Client-Name`. */
    val clientId: String,
    val userAgent: String,
    /** Host API: `www.youtube.com`, atau `music.youtube.com` untuk WEB_REMIX. */
    val host: String = "www.youtube.com",
    val clientScreen: String = "WATCH",
    val platform: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int = 0,
    /**
     * Klien "mobile" gaya app (Android/iOS/visionOS varian app) di beberapa
     * extractor dikirim ke `youtubei.googleapis.com` dengan query `&t=…&id=…`.
     * yt-dlp sendiri selalu memakai `www.youtube.com`, jadi varian app hanya
     * dipakai sebagai pembanding diagnostik.
     */
    val mobileEndpoint: Boolean = false,
    /**
     * Butuh poToken (BotGuard) untuk URL langsung. Klien seperti ini TIDAK
     * dipakai untuk memutar (Lyreon anonim), hanya untuk "Tes koneksi".
     */
    val requiresPoToken: Boolean = false,
    /**
     * URL langsung klien ini tetap butuh poToken GVS, tetapi **manifest HLS-nya
     * tidak** — jadi bila response membawa `hlsManifestUrl`, manifest itu yang
     * dipilih lebih dulu (lihat `pickAudio` di [InnertubeFallback]).
     */
    val preferManifest: Boolean = false,
    /** False = URL sudah terbaca langsung, tak perlu decipher `base.js` (Rhino). */
    val needsJsPlayer: Boolean = true,
    val origin: String? = null,
    val referer: String? = null,
    /**
     * Nilai `context.thirdParty.embedUrl` untuk klien embed. yt-dlp memakai URL
     * non-YouTube apa pun (mereka pakai `https://www.reddit.com/`) sejak
     * perbaikan yt-dlp#14826 — memakai URL YouTube justru ditolak.
     */
    val embedUrlValue: String? = null,
    val note: String = "",
)

/** Hasil pemeriksaan satu response player. */
internal enum class ClientVerdict {
    /** Ada `adaptiveFormats`/`formats` dengan `url`/`signatureCipher` yang tidak ber-DRM. */
    USABLE,

    /**
     * Response berisi format tetapi tanpa satu pun URL/cipher — tanda tangan
     * SABR (`serverAbrStreamingUrl`). Inilah penyebab loop "stream tidak
     * tersedia" di semua lagu.
     */
    SABR_ONLY,

    /** Hanya `hlsManifestUrl` — bisa diputar karena Lyreon mendukung HLS. */
    HLS_ONLY,

    /**
     * Semua format terkunci DRM (`drmFamilies`). Khas klien TV anonim: YouTube
     * menuntut cookie "guest aktif" agar format tidak di-DRM.
     */
    DRM_ONLY,

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
 *
 * Selain itu ada **saklar bypass extractor**: `MetrolistExtractor` yang di-pin
 * memakai klien ANDROID_VR + WEB, keduanya sudah ditutup YouTube (403 / SABR).
 * Setelah gagal berulang dengan ciri SABR, extractor dilewati selama
 * [EXTRACTOR_BYPASS_MS] supaya tiap lagu tidak menunggu timeout extractor dulu.
 */
internal object PlayerClientLadder {

    private const val TAG = "PlayerClientLadder"

    /** Lama pendinginan klien yang balas SABR-only / transport error. */
    private const val COOLDOWN_MS = 3L * 60_000L

    /** Lama extractor utama dilewati setelah terbukti membalas SABR berulang. */
    private const val EXTRACTOR_BYPASS_MS = 10L * 60_000L

    /** Jumlah kegagalan SABR beruntun sebelum extractor di-bypass. */
    private const val EXTRACTOR_BYPASS_AFTER = 2

    /** Ukuran ring buffer diagnostik yang bisa dibaca dari layar Settings. */
    private const val DIAGNOSTIC_LIMIT = 40

    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36,gzip(gfe)"
    private const val SAFARI_MAC_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
    private const val VISIONOS_SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    private const val MWEB_UA =
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
    private const val COBALT_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
    private const val COBALT_LTS_UA =
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"

    /** Nilai `thirdParty.embedUrl` untuk klien embed (URL non-YouTube, lihat yt-dlp#14826). */
    private const val EMBED_URL = "https://www.reddit.com/"

    /**
     * Urutan default per September 2026 — **klien bebas poToken lebih dulu**,
     * lalu sumber manifest HLS, lalu klien yang butuh poToken (diagnostik saja).
     * Pindahkan entri ke atas/bawah saat YouTube mengubah kebijakan: tidak ada
     * kode lain yang perlu disentuh.
     */
    private val SPECS: List<PlayerClientSpec> = listOf(
        // ------------------------------------------------------------------
        // 1) Klien bebas poToken — jalur utama anonim
        // ------------------------------------------------------------------
        PlayerClientSpec(
            key = "visionos",
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientId = "101",
            userAgent = VISIONOS_SAFARI_UA,
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "26.5.23O471",
            origin = "https://www.youtube.com",
            needsJsPlayer = false,
            note = "default anonim yt-dlp: tanpa poToken & tanpa decipher JS",
        ),
        PlayerClientSpec(
            key = "web_embedded",
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "",
            clientId = "56",
            userAgent = WEB_UA,
            origin = "https://www.youtube.com",
            embedUrlValue = EMBED_URL,
            needsJsPlayer = true,
            note = "tanpa poToken; hanya video yang boleh di-embed",
        ),
        PlayerClientSpec(
            key = "tv_downgraded",
            clientName = "TVHTML5",
            clientVersion = "5.20260707",
            clientId = "7",
            userAgent = COBALT_UA,
            origin = "https://www.youtube.com",
            note = "TVHTML5 versi lawas (Cobalt) — sering lolos SABR",
        ),
        PlayerClientSpec(
            key = "tv",
            clientName = "TVHTML5",
            clientVersion = "7.20260707.07.00",
            clientId = "7",
            userAgent = COBALT_LTS_UA,
            origin = "https://www.youtube.com",
            note = "format sering DRM tanpa cookie; itag 18 kadang lolos",
        ),
        PlayerClientSpec(
            key = "android_vr",
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            needsJsPlayer = false,
            preferManifest = true,
            note = "HLS bebas poToken; sejak 2026-08-17 URL langsung 403",
        ),

        // ------------------------------------------------------------------
        // 2) Sumber manifest HLS (URL langsungnya butuh poToken, HLS tidak)
        // ------------------------------------------------------------------
        PlayerClientSpec(
            key = "web_safari",
            clientName = "WEB",
            clientVersion = "",
            clientId = "1",
            userAgent = SAFARI_MAC_UA,
            origin = "https://www.youtube.com",
            preferManifest = true,
            note = "Safari UA → HLS pre-merged (m3u8), GVS tanpa poToken",
        ),
        PlayerClientSpec(
            key = "tv_simply",
            clientName = "TVHTML5_SIMPLY",
            clientVersion = "1.0",
            clientId = "75",
            userAgent = WEB_UA,
            origin = "https://www.youtube.com",
            preferManifest = true,
            note = "HTTPS butuh poToken, HLS tidak",
        ),

        // ------------------------------------------------------------------
        // 3) Pembanding diagnostik (butuh poToken → TIDAK dipakai memutar)
        // ------------------------------------------------------------------
        PlayerClientSpec(
            key = "visionos_app",
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
            needsJsPlayer = false,
            note = "varian app-style (PipePipe) — pembanding bentuk request",
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
            requiresPoToken = true,
            preferManifest = true,
            note = "GVS/Player butuh poToken",
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
            requiresPoToken = true,
            note = "GVS/Player butuh poToken",
        ),
        PlayerClientSpec(
            key = "web",
            clientName = "WEB",
            clientVersion = "",
            clientId = "1",
            userAgent = WEB_UA,
            origin = "https://www.youtube.com",
            referer = "https://www.youtube.com/",
            requiresPoToken = true,
            note = "SABR-only tanpa poToken",
        ),
        PlayerClientSpec(
            key = "web_remix",
            clientName = "WEB_REMIX",
            clientVersion = "1.20260707.12.00",
            clientId = "67",
            userAgent = WEB_UA,
            host = "music.youtube.com",
            origin = "https://music.youtube.com",
            referer = "https://music.youtube.com/",
            requiresPoToken = true,
            note = "YouTube Music web — SABR-only tanpa poToken",
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
            note = "butuh poToken; tanpa itu 'The page needs to be reloaded'",
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

    /** Kegagalan extractor ber-ciri SABR yang beruntun (di-reset saat extractor sukses). */
    private val extractorSabrStreak = AtomicInteger(0)

    @Volatile
    private var extractorBypassUntilMs: Long = 0L

    /**
     * Urutan coba.
     *
     * @param forPlayback true untuk jalur pemutaran: klien yang butuh poToken
     *   dibuang karena tanpa poToken mereka pasti SABR-only/403 — mencoba mereka
     *   hanya menambah ~0,5 dtk latensi per lagu. false untuk "Tes koneksi"
     *   (diagnostik penuh, termasuk klien mati).
     */
    fun ordered(forPlayback: Boolean = true): List<PlayerClientSpec> {
        val base = SPECS.filter { !forPlayback || !it.requiresPoToken }.toMutableList()
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

    /** Semua klien (termasuk yang butuh poToken) — untuk laporan diagnostik. */
    fun allSpecs(): List<PlayerClientSpec> = SPECS

    /** Catat hasil satu percobaan klien (diagnostik + urutan adaptif). */
    fun note(key: String, verdict: ClientVerdict, elapsedMs: Long, detail: String = "") {
        if (verdict == ClientVerdict.USABLE || verdict == ClientVerdict.HLS_ONLY) {
            lastGood.set(key)
            cooldownUntil.remove(key)
        }
        if (verdict == ClientVerdict.SABR_ONLY || verdict == ClientVerdict.DRM_ONLY) {
            lastSabrAtMs = System.currentTimeMillis()
            cooldownUntil[key] = lastSabrAtMs + COOLDOWN_MS
        }
        if (verdict == ClientVerdict.SABR_ONLY) sabrTotal.incrementAndGet()
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
        if (verdict == ClientVerdict.DRM_ONLY) {
            Log.w(TAG, "klien '$key' membalas format ber-DRM (butuh cookie guest) — dilewati")
        }
    }

    /**
     * Extractor utama juga bisa membalas SABR-only — `YoutubeStreamExtractor`
     * melempar `ContentNotSupportedException("YouTube returned SABR-only streaming
     * data …")`. Dicatat di buku yang sama supaya [sabrPressureRecently] mencakup
     * kedua jalur, dan supaya extractor bisa di-bypass sementara.
     */
    fun noteExtractorSabr(detail: String) {
        lastSabrAtMs = System.currentTimeMillis()
        sabrTotal.incrementAndGet()
        val streak = extractorSabrStreak.incrementAndGet()
        if (streak >= EXTRACTOR_BYPASS_AFTER) {
            extractorBypassUntilMs = System.currentTimeMillis() + EXTRACTOR_BYPASS_MS
            push(
                "${timestamp()} extractor di-bypass ${EXTRACTOR_BYPASS_MS / 60_000} menit " +
                    "(SABR $streak× beruntun) — langsung ke tangga klien",
            )
            Log.w(TAG, "extractor di-bypass $EXTRACTOR_BYPASS_MS ms setelah $streak kegagalan SABR")
        } else {
            push("${timestamp()} extractor → SABR_ONLY · $detail")
        }
        Log.w(TAG, "extractor membalas SABR-only ($detail) — total sesi ini: ${sabrTotal.get()}")
    }

    /** Extractor utama berhasil → hentikan bypass dan reset streak. */
    fun noteExtractorSuccess() {
        if (extractorSabrStreak.get() > 0 || extractorBypassUntilMs > 0L) {
            push("${timestamp()} extractor pulih — bypass dicabut")
        }
        extractorSabrStreak.set(0)
        extractorBypassUntilMs = 0L
    }

    /**
     * True bila extractor utama sebaiknya dilewati (baru saja terbukti membalas
     * SABR-only berulang). Menghemat satu round-trip + timeout extractor per lagu.
     */
    fun extractorBypassed(): Boolean = System.currentTimeMillis() < extractorBypassUntilMs

    /**
     * True bila dalam [windowMs] terakhir ada klien yang membalas SABR-only —
     * dipakai PlayerManager untuk memilih pesan error yang tepat.
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
        extractorSabrStreak.set(0)
        extractorBypassUntilMs = 0L
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
        if (formatCount > 0 && allFormatsDrmLocked(streamingData)) return ClientVerdict.DRM_ONLY

        val sabrUrl = streamingData.optString("serverAbrStreamingUrl").orEmpty()
        return if (formatCount > 0 || sabrUrl.isNotBlank()) {
            ClientVerdict.SABR_ONLY
        } else {
            ClientVerdict.NO_STREAMING_DATA
        }
    }

    /** True bila ada format dengan URL langsung / cipher yang bisa dipecahkan, tanpa DRM. */
    fun hasDirectStreamUrl(streamingData: JSONObject): Boolean {
        listOf("adaptiveFormats", "formats").forEach { key ->
            val array = streamingData.optJSONArray(key) ?: return@forEach
            for (i in 0 until array.length()) {
                val format = array.optJSONObject(i) ?: continue
                if (isDrmLocked(format)) continue
                if (!format.optString("url").isNullOrBlank()) return true
                if (!format.optString("signatureCipher").isNullOrBlank()) return true
                if (!format.optString("cipher").isNullOrBlank()) return true
            }
        }
        return false
    }

    /** Format ber-DRM tidak bisa diputar tanpa lisensi Widevine → dianggap tak berguna. */
    fun isDrmLocked(format: JSONObject): Boolean {
        val families = format.optJSONArray("drmFamilies")
        if (families != null && families.length() > 0) return true
        if (format.optInt("drmTrackCount", 0) > 0) return true
        return format.optString("drmFamilies").isNotBlank() ||
            format.optString("drmTrackType").isNotBlank()
    }

    /** True bila ada format tetapi semuanya ber-DRM (khas klien TV anonim). */
    fun allFormatsDrmLocked(streamingData: JSONObject): Boolean {
        var total = 0
        var locked = 0
        listOf("adaptiveFormats", "formats").forEach { key ->
            val array = streamingData.optJSONArray(key) ?: return@forEach
            for (i in 0 until array.length()) {
                val format = array.optJSONObject(i) ?: continue
                total++
                if (isDrmLocked(format)) locked++
            }
        }
        return total > 0 && locked == total
    }

    /** URL manifest HLS (`hlsManifestUrl`) bila ada — jalur putar tanpa poToken GVS. */
    fun hlsManifest(root: JSONObject?): String? =
        root?.optJSONObject("streamingData")
            ?.optString("hlsManifestUrl")
            ?.takeIf { it.isNotBlank() }

    /** Alasan `playabilityStatus` (untuk pesan error yang bisa ditindaklanjuti). */
    fun playabilityReason(root: JSONObject): String {
        val status = root.optJSONObject("playabilityStatus") ?: return ""
        val reason = status.optString("reason").orEmpty()
        return "${status.optString("status").orEmpty()}${if (reason.isBlank()) "" else ": $reason"}"
    }

    /**
     * Ringkasan keadaan tangga untuk laporan yang bisa disalin pengguna
     * (Settings → KESEHATAN STREAM → SALIN DIAGNOSTIK).
     */
    fun snapshot(): String = buildString {
        append("ladder: playback=").append(ordered(true).joinToString(",") { it.key }).append('\n')
        append("probe=").append(ordered(false).joinToString(",") { it.key }).append('\n')
        append("lastGood=").append(lastGood.get() ?: "-").append('\n')
        append("sabrTotal=").append(sabrTotal.get())
            .append(" extractorBypassed=").append(extractorBypassed()).append('\n')
    }
}
