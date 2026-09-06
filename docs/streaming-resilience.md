# Ketahanan Streaming YouTube (Lyreon)

> Status: **September 2026** · Berkas ini adalah catatan operasional, bukan dokumentasi API.
> Isinya: akar masalah loop "stream tidak tersedia", apa yang sudah dipasang di kode,
> dan runbook bila YouTube mengubah kebijakan lagi.

---

## 1. Gejala

Semua lagu gagal beruntun → pesan "stream tidak tersedia" → auto-skip → lagu berikutnya
gagal dengan cara yang sama → loop sampai antrean habis. Bukan per-video: **global**.

Exception yang muncul dari extractor (ditemukan juga di `classes5.dex` build rilis):

```
org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException:
  YouTube returned SABR-only streaming data without usable stream URLs.
  Try logging in to get HLS fallback streams.
    at ...YoutubeStreamExtractor.onFetchPage(YoutubeStreamExtractor.java:1407)
    at ...StreamInfo.getInfo(StreamInfo.java:93)
```

## 2. Akar masalah (dikonfirmasi dari sumber fork yang di-pin)

Lyreon memakai `com.github.MetrolistGroup:MetrolistExtractor:3cd334185d68d4e5e057dcb9046191b76e4e19ef`
(commit `3cd3341`, 2026-06-22 = HEAD branch `main` fork itu per September 2026).

Di `YoutubeStreamExtractor.onFetchPage()` fork tersebut:

```java
// baris ~1322
if (StringUtils.isBlank(ServiceList.YouTube.getTokens())) {
    androidCall = fetchAndroidVRJsonPlayer(contentCountry, localization, videoId);   // ANONIM
} else {
    safariCall  = fetchSafariJsonPlayer(contentCountry, localization, videoId);      // LOGIN
}
...
// baris ~1396 dan ~1404
if (playerResponse == null) {
    throw new ContentNotSupportedException("YouTube returned SABR-only streaming data "
        + "without usable stream URLs. Try logging in to get HLS fallback streams.");
}
if (streamType != StreamType.LIVE_STREAM && isSabrOnlyResponse()
        && getHlsManifestUrlFromStreamingData().isEmpty()) {
    throw new ContentNotSupportedException(...);   // pesan yang sama
}
```

Tiga fakta penting:

1. **`ServiceList.YouTube.setTokens()` tidak pernah dipanggil dari kode app** (sebelum
   perbaikan ini). Akibatnya extractor selalu masuk cabang anonim
   (`ANDROID_VR` + `WEB` tanpa login) — persis klien yang sedang dipaksa YouTube ke SABR.
2. **Jalur penyelamatnya ada di cabang login.** `getHlsManifestUrlFromStreamingData()`
   hanya membaca `safariStreamingData`, dan `safariStreamingData` hanya terisi oleh
   `fetchSafariJsonPlayer()` — yang hanya dipanggil bila tokens terisi. Jadi pesan
   "Try logging in to get HLS fallback streams" memang literal, bukan basa-basi.
3. `isSabrOnlyResponse()` = ada `adaptiveFormats` tetapi **tidak satu pun** membawa
   `url`, `signatureCipher`, atau `cipher`. Itulah tanda tangan SABR: YouTube mengirim
   daftar format + `serverAbrStreamingUrl`, tanpa URL yang bisa di-GET.

Faktor pendukung (bukan akar, tapi memperparah):

| Temuan | Efek |
|---|---|
| `StreamingService.loadingTimeout` default **5 detik** (`onFetchPage` menunggu dengan busy-wait sepanjang nilai ini) | Di jaringan seluler padat, player response belum tiba saat timeout → `streamingData == null` → dianggap "tidak tersedia" |
| `isFetchDislike()` default **true** → request ke `returnyoutubedislikeapi.com` per ekstraksi | Pihak ketiga yang tidak dipakai Lyreon (audio-only), menambah latensi & satu sumber kegagalan |
| `YoutubeParsingHelper.addLoggedInHeaders()` membangun `Authorization: SAPISIDHASH …` dari cookie `SAPISID`/`__Secure-3PAPISID` | Memasang cookie **tanpa** SAPISID membuat ekstraksi melempar `ExtractionException("Failed to get authorization header")` — lebih parah daripada anonim |

## 3. Lanskap upstream per September 2026

| Proyek | Versi/commit terkini | Catatan |
|---|---|---|
| `TeamNewPipe/NewPipeExtractor` | **v0.26.5** (2026-08-15) | poToken sudah masuk (PR #1272): `YoutubeStreamExtractor.setPoTokenProvider(PoTokenProvider)`, `PoTokenResult`. Tidak punya API login/cookie ala fork. |
| `InfinityLoop1308/PipePipeExtractor` | **v5.3.0** (tag `760225a26a`, 2026-08-24; commit s.d. 2026-09-03) | Hulu dari fork Metrolist. **SABR diimplementasikan penuh**: `services/youtube/sabr/*` (session, protocol decoder, UMP reader, parser indeks segmen MP4/WebM), poToken (`YoutubePoTokenResult`), klien bisa dipilih lewat `NewPipe.getYoutubePlayerClient()` (`visionos` default anonim, `tv_simply`, `tv_downgraded`, `web`, `mweb`), fallback muxed `android_reel`. Butuh **WebView JS engine** (`NewPipe.checkWebViewAvailable()`) dan **toolchain Java 25** (`jitpack.yml: openjdk25`). |
| `MetrolistGroup/MetrolistExtractor` | `main` = `3cd3341` (**2026-06-22**) | Yang di-pin Lyreon. Tidak ada paket `sabr/` di branch mana pun (`main`, `dev`, `master`, `extractor`) → **tidak punya dukungan SABR native**; satu-satunya jalan keluar adalah cabang login. |
| Peristiwa relevan | `2a9a92fb66` (2026-08-23) "chore: remove Android VR extraction endpoint" | Sinyal bahwa `ANDROID_VR` sedang dipensiunkan — di tangga klien Lyreon posisinya sudah diturunkan. |
| `c82d00303b` (2026-08-11) | "dev: rewrite SABR and drop media3 support" | SABR di hulu **tidak** menyediakan DataSource media3 siap pakai: stream SABR ditandai `DeliveryMethod.SABR`, `isUrl = false`, `content = serverAbrStreamingUrl`, `setDeliveryMethodInfo(YoutubeSabrInfo)`. **Aplikasi wajib menjalankan `YoutubeSabrSession.requestOnce(...)` sendiri.** |

Kesimpulan strategis: menaikkan dependency ke PipePipeExtractor v5.3.0 **bukan** sekadar
ganti satu baris Gradle (lihat §7). Selama belum ke sana, pertahanan Lyreon harus hidup
di lapisan identitas + tangga klien + circuit breaker.

## 4. Yang sekarang terpasang di kode

```
┌─ 1. Knob extractor ────────────────────────────────────────────────────────┐
│ YouTubeAccount.tune() → setLoadingTimeout(12), setFetchDislike(false)      │
├─ 2. Identitas (prioritas #1) ──────────────────────────────────────────────┤
│ Settings → cookie akun → AccountRepository (DataStore privat)              │
│   → YouTubeAccount.apply() → ServiceList.YouTube.setTokens(cookie)         │
│   → YouTubeRepository.applyAccount() → invalidateAll() (cache URL dibuang) │
│   validasi: SAPISID/__Secure-3PAPISID wajib, tanpa itu DITOLAK             │
├─ 3. Tangga klien (abstraksi) ──────────────────────────────────────────────┤
│ PlayerClientLadder: visionos → tv_simply → tv_downgraded → ios → android   │
│   → android_vr → tv_embedded → web_remix → web → mweb                      │
│ deteksi SABR-only/HLS-only/playability, urutan adaptif (last-good dulu),   │
│ cooldown 3 menit untuk klien yang baru balas SABR-only                     │
├─ 4. Circuit breaker (prioritas #2) ────────────────────────────────────────┤
│ reset HANYA setelah bukti kemajuan: posisi maju ≥ 8 s untuk track yang sama│
│ rem kedua: burst limiter (>4 skip dalam 30 s → trip)                       │
│ saat trip: antrean DIPERTAHANKAN, pesan actionable, health = TRIPPED       │
│ radio auto-extend dinonaktifkan selama health != OK                        │
├─ 5. Diagnostik ────────────────────────────────────────────────────────────┤
│ Settings → "Tes koneksi" = StreamProbe: hasil extractor + seluruh tangga   │
│   klien (verdict & ms per klien) + riwayat 40 kejadian terakhir            │
│ CI: tools/ci/extractor-radar.yml (cek drift pin — perlu disalin ke         │
│     .github/workflows/ agar aktif)                                         │
└────────────────────────────────────────────────────────────────────────────┘
```

Berkas kunci:

| Berkas | Peran |
|---|---|
| `app/src/main/java/com/lyreon/app/yt/YouTubeAccount.kt` | Parsing/normalisasi/validasi cookie, `setTokens()`, SAPISIDHASH, knob extractor |
| `app/src/main/java/com/lyreon/app/data/settings/AccountRepository.kt` | Penyimpanan kredensial (DataStore `lyreon_account`) |
| `app/src/main/java/com/lyreon/app/yt/innertube/PlayerClientLadder.kt` | Tabel klien + deteksi SABR + urutan adaptif + diagnostik |
| `app/src/main/java/com/lyreon/app/yt/innertube/InnertubeRequest.kt` | `playerFromSpec()` — bentuk request per klien (mobile vs web endpoint) |
| `app/src/main/java/com/lyreon/app/yt/innertube/InnertubeFallback.kt` | Menuruni tangga klien, `StreamUnavailableException`, `probeBlocking()` |
| `app/src/main/java/com/lyreon/app/player/PlayerManager.kt` | `StreamHealth`, breaker berbasis kemajuan, `retryAfterFix()` |

## 5. Runbook: "semua lagu gagal lagi"

1. **Baca dulu, jangan tebak.** Settings → KESEHATAN STREAM → **TES KONEKSI**.
   Lihat verdict per klien:
   - Banyak `SABR_ONLY` → YouTube menutup klien anonim. Naikkan klien yang masih
     `USABLE` ke atas `PlayerClientLadder.SPECS` (satu-satunya tempat yang diubah),
     atau aktifkan akun (langkah 3).
   - `HLS_ONLY` di mana-mana → yang tersisa hanya manifest HLS; lihat §6.
   - `PLAYABILITY_BLOCKED` dengan `LOGIN_REQUIRED` → butuh identitas.
   - `TRANSPORT_ERROR HTTP 403/429` → masalah signature/poToken/rate-limit, bukan SABR.
2. **Cek extractor.** Baris `Extractor:` di laporan yang sama menunjukkan apakah
   `MetrolistExtractor` masih menghasilkan stream audio; bila pesannya
   `ContentNotSupportedException: … SABR-only …`, itu konfirmasi server-side.
3. **Pasang cookie akun** (Settings → AKUN YOUTUBE → TEMPEL COOKIE / IMPOR FILE).
   Cookie harus mengandung `SAPISID` atau `__Secure-3PAPISID`; selain itu ditolak
   (dan Lyreon sengaja tetap anonim) supaya tidak merusak seluruh ekstraksi.
   Setelah tersimpan, cache URL dibuang otomatis dan extractor pindah ke cabang
   `fetchSafariJsonPlayer`.
4. **Sinkronkan dengan upstream.** Bandingkan pin di `app/build.gradle.kts` dengan
   HEAD fork (workflow `tools/ci/extractor-radar.yml` melakukan ini otomatis tiap
   minggu setelah disalin ke `.github/workflows/`, dan
   membuka issue bila pin tertinggal).
5. **Jangan menambal sekali lalu lupa.** Setiap perubahan YouTube = satu entri di
   bagian riwayat di bawah + satu baris di `PlayerClientLadder` bila urutan berubah.

### Saring logcat

Layar diagnostik menutupi hampir semua kasus, tetapi untuk laporan bug dari
pengguna lain, logcat memberi urutan kejadian yang persis:

```bash
adb logcat -s LyreonStreamHealth PlayerClientLadder YouTubeAccount InnertubeFallback
```

| Tag | Yang dicatat |
|---|---|
| `LyreonStreamHealth` | tiap kegagalan (`gagal #n/3 track=… errorCode=… sabr=…`), breaker **TERBUKA**, dan breaker **di-reset** beserta bukti kemajuan posisinya |
| `PlayerClientLadder` | verdict per klien (`visionos → SABR_ONLY (412ms)`), total kejadian SABR sesi ini, dan `extractor: N stream HLS saja` |
| `YouTubeAccount` | hasil validasi cookie (jumlah pasangan, ada/tidaknya SAPISID) — **tanpa** nilai cookie |
| `InnertubeFallback` | tangga klien yang dipakai saat extractor utama gagal |

Pola yang menandakan loop lama sudah tertangani: beberapa baris `gagal #1..#3`
lalu satu baris `breaker TERBUKA` — bukan puluhan skip tanpa akhir.

### Riwayat penyesuaian

| Tanggal | Perubahan | Sebab |
|---|---|---|
| 2026-09-06 | Lapisan identitas (cookie login), tangga klien + deteksi SABR, breaker berbasis kemajuan, diagnostik, radar CI | SABR-only global → loop skip di semua lagu |

## 6. Pekerjaan lanjutan (sengaja belum dikerjakan)

### 6.1 Pemutaran HLS
Cabang login bisa mengembalikan **hanya** manifest HLS. ExoPlayer di Lyreon memakai
`ProgressiveMediaSource`, jadi m3u8 tidak bisa diputar langsung; saat ini stream
semacam itu **disaring** (`YouTubeRepository.isHlsStream`) dan dilaporkan di
diagnostik sebagai `HLS_ONLY`, bukan diserahkan ke pemutar lalu error.

Untuk mengaktifkannya:
1. Tambah `androidx.media3:media3-exoplayer-hls` (versi = `media3` yang dipakai).
2. Tandai `ResolvedAudio.isHls` + `mimeType = application/x-mpegURL`.
3. MIME type harus sudah diketahui **sebelum** `MediaItem` dibuat
   (`MediaItem.Builder().setMimeType(...)`), padahal resolusi sekarang malas
   (`lyreon://audio/{id}` diselesaikan di dalam `ResolvingDataSource.open()`).
   Pilih salah satu: (a) pre-resolve saat `playQueue()` untuk item pertama +
   `warmUp()` untuk berikutnya, atau (b) `MediaSource.Factory` kustom yang
   melakukan resolusi non-blocking lalu `HlsMediaSource`/`ProgressiveMediaSource`.
   Jangan memblokir jaringan di `createMediaSource()` (jalan di thread playback).
4. Jalur unduhan (`LyreonDownloadManager`) perlu penanganan setara.

### 6.2 poToken (BotGuard)
`mweb`/`web` tanpa poToken hampir selalu SABR-only. Upstream menyediakan seam-nya
(`PoTokenProvider` / `YoutubeStreamExtractor.setPoTokenProvider`), tetapi token-nya
harus **dihasilkan**: attestasi BotGuard (WebView + `BgUtils`) untuk pengunjung
(logged-out, terikat `visitorData`) atau sesi akun (logged-in, terikat dataSyncId).
Tanpa generator nyata, memasang provider kosong tidak memberi apa pun — jadi tidak
dipasang setengah jalan.

### 6.3 Naik ke PipePipeExtractor v5.3.0 (SABR native)
Empat syarat keras, semuanya di luar satu baris Gradle:

1. **Java 25.** Fork itu memakai toolchain 25 (`jitpack.yml: openjdk25`) → class file
   versi 69. CI Lyreon memakai JDK 21 + AGP 9.1; javac 21 tidak bisa membaca
   bytecode 69. Perlu migrasi JDK (CI + devcontainer + `compileOptions`).
2. **Koordinat & modul.** Group JitPack `com.github.InfinityLoop1308.PipePipeExtractor`
   dengan modul `extractor` (+ `timeago-parser`). Build JitPack untuk commit terkini
   berstatus `ok`, tetapi versi harus di-pin per commit, bukan per branch.
3. **WebView JS engine.** `onFetchPage()` memanggil `NewPipe.checkWebViewAvailable()`;
   signature/n-param didekode lewat WebView, bukan Rhino. Jalur `SignatureDecipher`
   (Rhino) milik Lyreon perlu disandingkan atau diganti.
4. **Integrasi SABR di sisi app.** Stream SABR datang sebagai `DeliveryMethod.SABR`
   dengan `isUrl = false` + `YoutubeSabrInfo`. Lyreon harus menulis `DataSource`
   ExoPlayer yang menjalankan `YoutubeSabrSession.requestOnce(...)`, menghormati
   backoff (`getBackoffRemainingMs()`), merakit segmen (parser indeks MP4/WebM sudah
   disediakan), dan menangani rotasi attestation. Ini pekerjaan terbesar — dan
   justru yang membuat playback tahan SABR tanpa login.

Urutan yang disarankan: 6.1 (murah, langsung menolong pengguna login) → 6.3 (strategis)
→ 6.2 (hanya bila 6.3 belum dilakukan, karena v5.3.0 sudah membawa poToken sendiri).

## 7. Kebijakan pin dependency (anti-rot)

- Pin **per commit**, bukan per branch/tag mengambang: `MetrolistExtractor:<sha>`.
- Workflow `tools/ci/extractor-radar.yml` (mingguan + manual) membandingkan pin dengan HEAD fork
  → salin ke `.github/workflows/` untuk mengaktifkannya
  dan membuka issue bila tertinggal, lengkap dengan daftar commit baru — jadi
  "ketinggalan patch upstream" terlihat sendiri, bukan menunggu lagu gagal.
- Setiap kenaikan pin wajib melewati **TES KONEKSI** sebelum rilis, dan dicatat di
  tabel riwayat §5.

## 8. Catatan privasi & legal

- Cookie akun adalah kredensial penuh: disimpan di DataStore privat aplikasi, hanya
  dikirim ke domain Google, tidak pernah dicatat ke log (log hanya memuat jumlah
  cookie dan ada/tidaknya SAPISID), dan tidak pernah lewat server Lyreon.
- Fitur ini opsional. Tanpa akun aplikasi tetap berjalan selama YouTube masih
  memberi URL langsung ke klien anonim.
- Gunakan akun yang risikonya siap Anda tanggung; permintaan otomatis bervolume tinggi
  dengan identitas login berpotensi memicu pembatasan akun.
- Seperti tertulis di README: proyek tidak berafiliasi dengan YouTube/Google, dan
  pemakaian tunduk pada ketentuan layanan di wilayah masing-masing.
