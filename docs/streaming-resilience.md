# Ketahanan streaming Lyreon (anonim, tanpa akun)

Dokumen ini menjelaskan **mengapa lagu bisa gagal diputar**, **apa yang Lyreon
lakukan**, dan **bagaimana menyesuaikannya saat YouTube mengubah kebijakan lagi**.
Ditulis untuk dibaca ulang enam bulan dari sekarang, saat gejalanya muncul lagi
dan ingatan tentang penyebabnya sudah hilang.

---

## 1. Gejala

Semua lagu gagal resolve → pemutar melompat dari satu lagu ke lagu berikutnya
tanpa henti ("loop skip"). Di log muncul:

```
ContentNotSupportedException: YouTube returned SABR-only streaming data without
usable stream URLs. Try logging in to get HLS fallback streams.
```

dan/atau, dari diagnostik dalam app (Settings → Kesehatan stream):

```
tv_embedded → PLAYABILITY_BLOCKED · ERROR: YouTube is no longer supported in this application or device.
web_remix   → PLAYABILITY_BLOCKED · UNPLAYABLE: Video unavailable
web         → PLAYABILITY_BLOCKED · UNPLAYABLE: Video unavailable
mweb        → PLAYABILITY_BLOCKED · UNPLAYABLE: The page needs to be reloaded.
```

Penting: ini **bukan** kegagalan per-video. Ia kebijakan server yang berlaku
untuk satu kelas permintaan, jadi begitu kena, seluruh katalog kena.

## 2. Akar masalah (per September 2026)

YouTube menutup akses anonim lewat tiga mekanisme yang saling menumpuk:

1. **SABR** (*server-side adaptive bitrate*). Response `youtubei/v1/player`
   berisi daftar `adaptiveFormats` **tanpa satu pun field `url`/`signatureCipher`**,
   plus `streamingData.serverAbrStreamingUrl`. URL-nya tidak ada untuk di-GET:
   klien resmi mengambil chunk lewat protokol SABR (protobuf, chunk dinamis).
   Extractor gaya NewPipe melempar `ContentNotSupportedException`.
2. **poToken (Proof of Origin)**. Sebagian klien wajib mengirim token atestasi
   BotGuard, kalau tidak: `403` di `googlevideo.com`, atau `UNPLAYABLE` sejak
   dari response player. Token diikat ke `visitorData`/sesi **dan ke videoId**,
   jadi tidak bisa dipanen sekali lalu dipakai ulang.
3. **DRM untuk klien TV**. Tanpa cookie "guest aktif", format dari klien
   `TVHTML5` dikembalikan terkunci DRM (`drmFamilies`) — ada URL, tapi tidak
   bisa diputar tanpa lisensi Widevine.

Yang tersisa untuk klien **anonim** adalah celah-celah sempit, dan celah itu
bergeser tiap beberapa bulan. Peta terakhir (yt-dlp PO Token Guide, revisi
Juli 2026 — sumber paling teruji karena dipakai jutaan request/hari):

| klien | poToken GVS | yang benar-benar bisa dipakai anonim |
|---|---|---|
| `visionos` | **tidak** | URL langsung, tanpa decipher JS — default anonim yt-dlp |
| `web_embedded` | **tidak** | URL langsung, hanya video yang boleh di-embed |
| `tv` / `tv_downgraded` | **tidak** | sering DRM; kadang itag 18 lolos |
| `web_safari` | ya (HTTPS) | **manifest HLS** (m3u8) — HLS tidak butuh poToken GVS |
| `tv_simply` | ya (HTTPS/DASH) | **manifest HLS** |
| `android_vr` | ya (HTTPS/DASH) | **manifest HLS**; sejak 2026-08-17 semua format 403 |
| `web` / `web_remix` / `mweb` | ya | SABR-only atau `UNPLAYABLE` |
| `android` / `ios` | ya (GVS/Player) | 403 tanpa poToken |

**Kesimpulan yang membentuk desain Lyreon:** untuk tetap anonim, hanya ada dua
barang yang bisa diputar — (a) URL langsung dari klien bebas poToken, dan
(b) **manifest HLS**. Karena itu dukungan HLS bukan opsional; ia separuh dari
strategi anonim.

## 3. Keputusan produk: ANONIM, tanpa cookie

Lyreon **tidak** meminta, menyimpan, atau mengirim cookie akun YouTube.
`ServiceList.YouTube.setTokens()` sengaja tidak pernah dipanggil.

Alasan:

- **Gesekan pengguna.** Mengambil cookie butuh browser desktop, mode incognito
  (agar sesi tidak invalid), menyalin header `Cookie` utuh, dan memahami bahwa
  `SAPISID` wajib ada. Itu terlalu banyak untuk sebuah pemutar musik, dan
  sebagian besar pengguna akan menyerah di tengah jalan.
- **Risiko akun.** Cookie adalah kredensial penuh. Menyimpannya di aplikasi
  pihak ketiga memindahkan risiko pembajakan/penangguhan akun ke pengguna.
- **Permukaan privasi.** Tanpa cookie, tidak ada data identitas yang bisa bocor
  dari perangkat, terkirim ke log, atau terbawa ke permintaan pihak ketiga.
- **Beban dukungan.** "Cookie saya ditolak / akun saya kena peringatan" adalah
  kelas tiket yang tidak perlu ada.

Konsekuensi yang diterima secara sadar: bila suatu saat YouTube menutup **semua**
celah anonim (SABR + poToken di semua klien, HLS pun dikunci), Lyreon kehilangan
kemampuan memutar sampai tangga klien disesuaikan atau dukungan SABR/poToken
ditambahkan (§7). Itu risiko yang dipilih, bukan yang diabaikan.

> Kode login (parse cookie, SAPISIDHASH, `setTokens`) pernah diimplementasikan di
> branch `arena/01a0727b-lyr` commit `73aa177`, lalu **dihapus** pada keputusan
> ini. Bila perlu dihidupkan lagi, commit itu adalah rujukannya.

## 4. Pertahanan berlapis

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 1. Knob & bypass extractor                                                │
│    setLoadingTimeout(12) · setFetchDislike(false)                         │
│    extractor di-bypass 10 menit setelah 2× SABR beruntun (hemat latensi)  │
├──────────────────────────────────────────────────────────────────────────┤
│ 2. Tangga klien ANONIM (PlayerClientLadder)                               │
│    visionos → web_embedded → tv_downgraded → tv → android_vr              │
│      → web_safari(HLS) → tv_simply(HLS)                                   │
│    + pembanding diagnostik: visionos_app, ios, android, web, web_remix,   │
│      mweb (butuh poToken → tidak dipakai memutar)                         │
│    deteksi SABR-only / HLS-only / DRM / playability · urutan adaptif ·    │
│    cooldown 3 menit · signatureTimestamp (STS) dari ytcfg                 │
├──────────────────────────────────────────────────────────────────────────┤
│ 3. Pemutaran HLS (media3-exoplayer-hls)                                   │
│    ResolvedAudio.isManifest → HlsRequiredException → MediaItem ditukar    │
│    ke URL m3u8 + MIME → HlsMediaSource · track video dimatikan (hemat)    │
│    lagu berikutnya disiapkan lebih dulu oleh warmUpcoming()               │
│    unduhan: HlsFlatDownloader (init + segmen → satu file, tanpa FFmpeg)   │
├──────────────────────────────────────────────────────────────────────────┤
│ 4. Circuit breaker berbasis bukti                                         │
│    reset HANYA setelah posisi maju ≥ 8 s · rem burst (>4 skip/30 s)       │
│    saat trip: antrean DIPERTAHANKAN · pesan actionable · radio ditahan    │
├──────────────────────────────────────────────────────────────────────────┤
│ 5. Diagnostik & radar                                                     │
│    Tes koneksi (verdict + ms per klien) · SALIN DIAGNOSTIK · RESET        │
│    logcat: LyreonStreamHealth / PlayerClientLadder / InnertubeFallback    │
│    tools/ci/extractor-radar.sh (+ workflow bila izin tersedia)            │
└──────────────────────────────────────────────────────────────────────────┘
```

### Berkas kunci

| Berkas | Peran |
|---|---|
| `yt/innertube/PlayerClientLadder.kt` | tabel klien, verdict, cooldown, bypass extractor, diagnostik |
| `yt/innertube/InnertubeRequest.kt` | bentuk request per klien (endpoint, header, body, STS) |
| `yt/innertube/InnertubeConfig.kt` | scrape API key, versi web, `visitorData`, `base.js`, `STS` |
| `yt/innertube/InnertubeFallback.kt` | menelusuri tangga → `ResolvedAudio` (URL langsung atau manifest) |
| `yt/YouTubeRepository.kt` | extractor utama → fallback; `StreamProbe`; cache URL |
| `player/ResolvingDataSource.kt` | resolusi malas `lyreon://`; melempar `HlsRequiredException` |
| `player/PlayerManager.kt` | `StreamHealth`, breaker, penukaran item ke HLS, warm-up antrean |
| `ui/screens/SettingsScreen.kt` | panel Kesehatan stream (tes, salin, reset) |
| `download/HlsFlatDownloader.kt` | manifest HLS → satu file (untuk unduhan offline) |
| `tools/ci/extractor-radar.sh` | cek drift pin extractor dari mesin lokal |

## 5. Runbook: "semua lagu gagal lagi"

1. **Baca dulu, jangan tebak.** Settings → KESEHATAN STREAM → **TES KONEKSI**,
   lalu **SALIN DIAGNOSTIK** dan simpan teksnya.
   - Banyak `SABR_ONLY` → YouTube menutup klien anonim; naikkan klien yang masih
     `USABLE` di `PlayerClientLadder.SPECS` (satu-satunya tempat yang diubah).
   - `HLS_ONLY` / `manifest HLS ada` → normal, Lyreon memutarnya. Bila tetap
     gagal, periksa apakah `media3-exoplayer-hls` masih ada di `build.gradle.kts`
     dan apakah penukaran `MediaItem` terjadi (log `track '…' beralih ke manifest HLS`).
   - `DRM_ONLY` → khas klien TV; jangan dihitung sebagai kemenangan.
   - `PLAYABILITY_BLOCKED: UNPLAYABLE / needs to be reloaded` → klien itu butuh
     poToken. Sudah benar bila ia hanya muncul di diagnostik, bukan di jalur putar.
     Bila pola ini muncul pada klien yang seharusnya bebas poToken, curigai
     `visitorData` basi: setelah 3 respons terblokir beruntun tangga klien
     membuangnya dan mengambil yang baru (baris `visitorData disegarkan` di
     riwayat). Kalau masih berlanjut, paksa scrape ulang dengan **RESET** di
     panel yang sama.
   - `TRANSPORT_ERROR HTTP 403/429` → signature/poToken/rate-limit, bukan SABR.
2. **Cek extractor.** Baris `Extractor:` di laporan yang sama menunjukkan apakah
   `MetrolistExtractor` masih menghasilkan stream audio. Bila ia terus membalas
   SABR, ladder otomatis mem-bypass-nya 10 menit (terlihat di riwayat).
3. **Sinkronkan dengan upstream.**
   ```bash
   bash tools/ci/extractor-radar.sh
   REPO=InfinityLoop1308/PipePipeExtractor bash tools/ci/extractor-radar.sh
   REPO=TeamNewPipe/NewPipeExtractor      bash tools/ci/extractor-radar.sh
   ```
   Pin `MetrolistExtractor` tidak bergerak sejak 2026-06-22 — jadi perbaikan
   hampir selalu datang dari **tangga klien milik kita**, bukan dari fork.
4. **Ambil bentuk request terbaru.** Bila sebuah klien mulai diblokir, bandingkan
   spec kita dengan `INNERTUBE_CLIENTS` di `yt_dlp/extractor/youtube/_base.py`
   (clientName, clientVersion, userAgent, deviceModel, osVersion, host).
   Perbedaan kecil di sini adalah penyebab paling umum verdict `UNPLAYABLE`.
5. **Jangan menambal sekali lalu lupa.** Setiap perubahan YouTube = satu entri di
   riwayat di bawah + satu baris di `PlayerClientLadder` bila urutan berubah.

### Saring logcat

```bash
adb logcat -s LyreonStreamHealth PlayerClientLadder InnertubeFallback InnertubeConfig
```

| Tag | Yang dicatat |
|---|---|
| `LyreonStreamHealth` | kegagalan `#n/3`, breaker **TERBUKA**, breaker **di-reset** + bukti kemajuan, `track … beralih ke manifest HLS` |
| `PlayerClientLadder` | verdict per klien (`visionos → USABLE (412ms)`), total SABR, bypass extractor, `extractor: N stream HLS saja` |
| `InnertubeFallback` | klien yang akhirnya memberi audio, dan alasan lengkap saat semua gagal |
| `InnertubeConfig` | hasil scrape API key/versi/`visitorData`/`STS`; dicoba ulang 5 menit bila scrape awal gagal |

Pola yang menandakan loop lama sudah tertangani: beberapa baris `gagal #1..#3`
lalu satu baris `breaker TERBUKA` — bukan puluhan skip tanpa akhir.

### Riwayat penyesuaian

| Tanggal | Perubahan YouTube | Penyesuaian Lyreon |
|---|---|---|
| 2025 → 2026 | SABR digulirkan bertahap ke klien anonim | deteksi SABR-only + tangga klien |
| 2026-06 | poToken diikat ke videoId (tidak bisa dipanen) | klien butuh-poToken dikeluarkan dari jalur putar |
| 2026-07 | `web_safari` hanya memberi HLS ke sebagian sesi | dukungan pemutaran HLS (lapisan 3) |
| 2026-08 | `tv_simply` gagal tanpa poToken saat signed-out | dipakai sebagai sumber manifest HLS, bukan URL langsung |
| 2026-08-17 | `android_vr` v1.65.10: semua format 403 | diturunkan ke cadangan, `preferManifest = true` |
| 2026-08 | `TVHTML5_SIMPLY_EMBEDDED_PLAYER` (id 85) mati: *"YouTube is no longer supported in this application or device"* | dibuang, diganti `WEB_EMBEDDED_PLAYER` (id 56) + `thirdParty.embedUrl` non-YouTube |
| 2026-09 | `web`/`web_remix`/`mweb` membalas `UNPLAYABLE` tanpa poToken | ditandai `requiresPoToken` → diagnostik saja |
| 2026-09 | Bentuk `visionos` PipePipe (UA app-style, endpoint googleapis) tidak membalas stream | diganti bentuk yt-dlp: UA Safari desktop, `RealityDevice17,1`, osVersion `26.5.23O471`, host `www.youtube.com` |
| 2026-09 | Lagu yang hanya punya HLS tidak bisa diunduh sama sekali | `HlsFlatDownloader`: segmen disatukan jadi satu file fMP4/TS + verifikasi wadah |

## 6. Privasi

- Tidak ada cookie, token akun, atau identitas pengguna yang dikirim ke YouTube.
  Yang dikirim: `visitorData` anonim (diperoleh dari YouTube sendiri), versi
  klien, dan header perangkat yang meniru klien resmi.
- Semua permintaan keluar dari perangkat langsung ke domain Google
  (`youtube.com`, `youtubei.googleapis.com`, `googlevideo.com`). Tidak ada server
  Lyreon, tidak ada proksi, tidak ada telemetri.
- Diagnostik (riwayat 40 baris, hasil tes koneksi) hanya hidup di memori proses
  dan hanya memuat videoId + verdict + latensi. Tombol **SALIN DIAGNOSTIK**
  menyalin teks itu ke papan klip atas tindakan eksplisit pengguna.
- `returnyoutubedislikeapi.com` **tidak** pernah dipanggil (`setFetchDislike(false)`).

## 7. Pekerjaan lanjutan (belum dikerjakan, dengan alasannya)

### 7.1 poToken / BotGuard
Menghasilkan poToken berarti menjalankan attestation BotGuard. Jalur yang
dipakai ekosistem: WebView (`PoTokenWebView` ala ReVanced/Morphe) atau engine JS
di luar proses (yt-dlp memakai `ejs` + Deno/Node). Keduanya menambah permukaan
keamanan dan kompleksitas besar, dan token tetap harus diikat per videoId.
Selama masih ada klien bebas poToken (visionos, web_embedded) atau manifest HLS,
ini bukan prioritas. Bila suatu hari menjadi satu-satunya jalan, mulailah dari
WebView terisolasi + cache token per (visitorData, videoId), bukan dari
mengirim data ke server pihak ketiga.

### 7.2 Pemutaran SABR native
Protokol SABR = manifest protobuf + chunk yang diminta dinamis
(`serverAbrStreamingUrl`). PipePipeExtractor v5.3.0 sudah mendukungnya, tetapi
menuntut tiga hal sekaligus: **toolchain Java 25** (CI Lyreon JDK 21; class file
v69 tidak bisa dibaca compiler yang lebih tua), **WebView JS engine**
(`NewPipe.checkWebViewAvailable()`), dan **DataSource SABR sendiri** di sisi
media3 (dukungan media3 mereka dilepas). Migrasi ini masuk akal hanya bila
semua jalur anonim lain sudah mati.

### 7.3 Naik ke PipePipeExtractor
Terhalang tiga syarat di §7.2. Sebagai catatan hidup: HEAD mereka 2026-09-03,
push terakhir 2026-09-05 — aktif, jadi opsi ini tetap layak dipantau lewat
`tools/ci/extractor-radar.sh`.

### 7.4 ~~Unduhan dari manifest HLS~~ — SELESAI
`HlsFlatDownloader` mengambil init segment + seluruh segmen lalu menyatukannya
menjadi satu file (fMP4 → `.mp4`, MPEG-TS → `.ts`), dengan verifikasi bait
pertama supaya berkas rusak tidak pernah disimpan. Tanpa FFmpeg: segmen HLS
memang bisa disambung apa adanya.

Catatan mutu: HLS YouTube umumnya *muxed* (video+audio dalam satu varian), jadi
file hasil unduhan lagu HLS-only lebih besar daripada unduhan audio murni
(bisa 4–8×). Varian ber-bandwidth terendah yang masih layak (≥ 400 kbps) dipilih
untuk menekan ukuran. Bila ini terasa mahal, alternatifnya adalah menolak unduhan
untuk lagu HLS-only dan memberi pesan yang jelas — keputusan produk, bukan teknis.

## 8. Kebijakan pin extractor

- Pin **commit SHA**, bukan branch: `main` fork bisa bergerak dan mengubah
  perilaku tanpa jejak di riwayat kita.
- Naikkan pin hanya bila (a) ada perbaikan yang kita butuhkan, dan (b) CI hijau.
  Fork `MetrolistGroup/MetrolistExtractor` **tidak punya tag**, jadi referensi
  JitPack harus SHA atau nama branch.
- Setelah menaikkan: jalankan **TES KONEKSI**, bandingkan verdict per klien
  dengan sebelum, dan tambahkan satu baris di tabel riwayat §5.
- Radar drift: `bash tools/ci/extractor-radar.sh` (lokal, kapan saja) dan workflow
  `.github/workflows/extractor-radar.yml` (aktif, tiap Senin 02:17 UTC — membuka issue
  berlabel `extractor-drift` bila pin tertinggal). Berkas workflow hanya bisa diubah
  dari akun dengan izin `workflows`, bukan dari token agen.
