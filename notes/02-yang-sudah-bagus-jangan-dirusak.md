# 02 — Yang sudah bagus, jangan dirusak

Daftar **invarian**: keputusan dan mekanisme yang sudah dibayar mahal (bug, sesi
debugging, build CI) dan membuat Lyreon bekerja hari ini. Mengubah salah satunya
boleh, tetapi harus sadar dan beralasan — jangan sebagai efek samping refactor UI.

---

## 1. Keputusan produk: ANONIM, tanpa akun, tanpa server perantara

- `ServiceList.YouTube.setTokens()` **tidak pernah** dipanggil; tidak ada kode parse
  cookie, tidak ada `SAPISIDHASH`, tidak ada penyimpanan kredensial.
- Semua trafik on-device langsung ke Google (`youtubei/v1` + `googlevideo.com`) dan
  penyedia lirik; tidak ada backend Lyreon.
- Alasan lengkap: `docs/streaming-resilience.md` §3 (gesekan pengguna, risiko akun,
  permukaan privasi, beban dukungan).
- **Konsekuensi yang diterima sadar:** bila YouTube menutup semua celah anonim,
  pemutaran berhenti sampai tangga klien disesuaikan.

> Jangan menambah jalur login/cookie "untuk satu fitur saja". Dua fitur yang meminta
> itu (sinkronisasi akun, *listen together*) sudah ditandai **ditahan** di
> `05-peta-fitur-diinginkan.md` §konflik.

## 2. Lima lapisan ketahanan streaming

Urutan ini adalah alasan pemutaran bertahan saat YouTube mengubah kebijakan. Jangan
membongkar lapisannya tanpa membaca `docs/streaming-resilience.md`.

| # | Lapisan | Berkas | Invarian |
|---|---|---|---|
| 1 | Knob & bypass extractor | `yt/YouTubeRepository.kt`, `yt/innertube/PlayerClientLadder.kt` | `setLoadingTimeout(12)`, `setFetchDislike(false)`; extractor di-bypass 10 menit setelah 2× SABR beruntun |
| 2 | Tangga klien anonim (spesifikasi Meld) | `yt/innertube/PlayerClientLadder.kt`, `InnertubeRequest.kt` | `visionos` 0.1 → `android_vr` 1.65.10 → `android_vr_1_43_32` → `ipados` → `ios`; host `music.youtube.com`; `X-Goog-Visitor-Id` ke semua klien; STS hanya klien web/TV |
| 2b | **Validasi URL sebelum diputar** | `yt/innertube/StreamUrlValidator.kt` | `HEAD` + `Range` byte terakhir; 2xx/405 diterima, 403/410 ditolak → `REJECTED_BY_CDN`; IO diterima optimistis; manifest HLS di-probe tanpa `Range`; UA probe = UA klien pencetak URL |
| 3 | Pemutaran HLS | `player/PlayerManager.kt`, `player/ResolvingDataSource.kt`, `download/HlsFlatDownloader.kt` | `ResolvedAudio.isManifest` → `HlsRequiredException` → `MediaItem` ditukar ke m3u8 + MIME; track video dimatikan; `warmUpcoming()` menyiapkan lagu berikutnya |
| 4 | *Circuit breaker* berbasis bukti | `player/PlayerManager.kt` | `consecutiveFailures` di-reset **hanya** setelah posisi maju ≥ 8 dtk; rem burst >4 skip/30 dtk; antrean dipertahankan saat trip |
| 5 | Diagnostik & radar | `ui/screens/SettingsScreen.kt`, `ui/vm/ViewModels.kt`, `tools/ci/*.sh` | TES KONEKSI + SALIN DIAGNOSTIK + RESET; `visitor=`, `cdnRejected=`, `urlOnly=` wajib ada di laporan |

Invarian tambahan yang mudah terlewat:

- **`visitorData` wajib ada** dan asalnya dicatat (`sw.js` → `guide` → panen
  `responseContext`), TTL 12 jam, disegarkan setelah 3 respons ditolak beruntun.
- **Unduhan menolak URL `validated = false`** (`download/LyreonDownloadManager.kt`).
  Pemutaran boleh memakainya sebagai cadangan terakhir; unduhan tidak boleh menyimpan
  file terpotong diam-diam.
- **UA googlevideo hanya dari satu sumber**: `PlayerClientLadder.streamUserAgentFor(c, cver)`.
  Jangan menulis UA klien di tempat lain (pernah menyebabkan 403 massal).

## 3. Performa yang sudah diperjuangkan (jangan dikembalikan)

| Keputusan | Bukti di kode | Jangan |
|---|---|---|
| Animasi reveal per item **dimatikan** | `ui/components/Primitives.kt` → `RevealOnScroll` sekarang passthrough | menghidupkan lagi animasi per item di list panjang |
| Posisi lagu tidak menyebar ke seluruh tree | `ui/components/LyricsSheet.kt` → `rememberUpdatedState` + `derivedStateOf` | membaca `positionMs` di komposisi induk tiap frame |
| Lagu berikutnya disiapkan lebih dulu | `PlayerManager.warmUpcoming()` + `YouTubeRepository.cachedManifestFor()` | memindahkan resolusi ke UI thread |
| Resolusi stream blocking **di thread loader ExoPlayer** | `player/ResolvingDataSource.kt`, `resolveAudioBlocking` | menjadikannya `suspend` yang dipanggil dari komposisi |
| Cache URL ±6 jam + kunci anti-duplikasi request | `YouTubeRepository.streamCache`, `InnertubeFallback.streamCache` | menambah jalur resolve paralel tanpa cache |
| Kandidat format cadangan (m4a ↔ webm/opus) | `ResolvedAudio.fallbackUrl` | menghapus fallback saat merapikan model data |

## 4. Arsitektur & konvensi kode

- **DI manual lewat `core/ServiceLocator.kt`** (tidak ada Hilt/Koin). Semua repo
  `by lazy`: `db`, `library`, `settings`, `taste`, `youtube`, `lyrics`, `local`,
  `session`, `player`, `downloads`. Pertahankan — build cepat, alur mudah ditelusuri.
- **Room 2.8.4 + KSP** untuk DB lokal; **DataStore Preferences** untuk setelan
  (`data/settings/SettingsRepository.kt` → `LyreonSettings`). Jangan menambah
  SharedPreferences kedua.
- **`buildConfig` tidak aktif** (`buildFeatures { compose = true }` saja) → jangan
  menulis `BuildConfig.*`; pakai `android.os.Build.VERSION` / `packageInfo`.
- Dependensi kunci: Compose BOM `2026.01.01`, media3 `1.10.1` (exoplayer, session, ui,
  datasource-okhttp, **exoplayer-hls**), okhttp `5.1.0`, coil3 `3.3.0`, rhino `1.7.15`
  (decipher `base.js`), datastore `1.2.0`.
- **Media3 adalah `@UnstableApi`** — sudah di-opt-in lewat `freeCompilerArgs` di
  `app/build.gradle.kts`. Bila menambah modul, jangan lupa opt-in yang sama.
- Komentar & dokumentasi ditulis dalam Bahasa Indonesia, menjelaskan **kenapa**
  (bukan apa). Pertahankan gaya ini: komentar "kenapa" itulah yang menyelamatkan
  debugging jarak jauh.

## 5. Internasionalisasi

- 6 berkas `strings.xml`: `values` (id, default), `values-en`, `values-hi`, `values-ja`,
  `values-ms`, `values-zh`.
- Jumlah saat ini: **271** string default vs **260** di lima locale lain → ada
  **11 string belum diterjemahkan** (utang yang diketahui; jangan ditambah).
- Aturan: setiap string UI baru masuk ke **keenam** berkas dalam commit yang sama.
  Teks diagnostik/log boleh literal Indonesia di kode (bukan UI).
- Jangan memakai string literal di komposisi; jangan memakai `hardcoded` teks di
  `setContentDescription` tanpa resource.

## 6. CI & tooling

- Workflow build: `.github/workflows/android-build.yml` (JDK 21, SDK 36, Debug +
  Release APK, laporan error build, unggah artifact). **Satu-satunya verifikasi
  kompilasi yang tersedia** — sandbox tidak punya JDK/Android SDK.
- `.github/workflows/extractor-radar.yml` milik pemilik repo: token GitHub App di
  sesi ini **tidak punya izin `workflows`**, jadi jangan mendorong/mengubah berkas
  workflow. Kemampuan baru dikirim sebagai skrip di `tools/ci/` + dokumentasi.
- Skrip lokal: `tools/ci/extractor-radar.sh` (drift pin extractor),
  `tools/ci/meld-client-radar.sh` (drift spesifikasi klien vs Meld; saat ini sinkron,
  exit 0).
- Penandatanganan rilis pernah NPE dan sudah diperbaiki (commit `0f56a42`) — jangan
  "merapikan" blok signing tanpa alasan.

## 7. Diagnostik jarak jauh adalah fitur, bukan sisa debug

Pengguna melaporkan masalah dengan menempel teks SALIN DIAGNOSTIK. Formatnya
(`ladder:`, `hls:`, `probe:`, `lastGood=`, `visitor=`, `sabrTotal=`, baris
`client → VERDICT (ms) · detail`, `describeStreamUrl` yang **sudah disensor**:
tanpa `sig`/`signature`/`sparams`, `pot`/`n` hanya panjang) adalah antarmuka dukungan.
Bila mengubahnya: pertahankan nama kunci, jangan pernah mencetak materi tanda tangan
atau identitas pengguna, dan perbarui `docs/streaming-resilience.md` §5.

## 8. Tabel "jangan sentuh tanpa membaca"

| Berkas | Baca dulu |
|---|---|
| `yt/innertube/*.kt` | `docs/streaming-resilience.md` + `notes/01` §A |
| `player/PlayerManager.kt` | `notes/02` §2 lapisan 3–4 (HLS + breaker) |
| `player/ResolvingDataSource.kt` | `notes/01` §A5 (UA), §B5 (`validated`) |
| `download/HlsFlatDownloader.kt` | `docs/streaming-resilience.md` §7.4 |
| `ui/theme/{Color,Theme}.kt` | `notes/03` + `notes/04` |
| `app/build.gradle.kts` | `notes/02` §4 (opt-in UnstableApi, signing) |
