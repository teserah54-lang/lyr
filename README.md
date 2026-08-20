<div align="center">

![LYREON](docs/branding/lyreon-banner.png)

### *Hear What Words Can't Say*

**LYREON** adalah aplikasi musik Android *audio-only* untuk YouTube Music — pencarian penuh, playlist, unduhan offline, pemutaran latar (background), dan rekomendasi radio otomatis, dibalut tema gelap‑crimson.

`Kotlin` · `Jetpack Compose` · `Media3 ExoPlayer` · `MetrolistExtractor` · `Room`

[Donasi pengembang ☕](https://saweria.co/riznotdev) · [Website](https://achfarizy.biz.id)

</div>

---

## 📖 Tentang

LYREON memutar **hanya audio** dari YouTube Music (Opus/WebM atau AAC/M4A) sehingga lebih hemat kuota dibanding memutar video. Semua akses konten melalui *extractor* open source ([MetrolistExtractor], fork terpelihara dari NewPipeExtractor) — **tanpa API resmi YouTube dan tanpa akun**.

> ⚠️ **Disclaimer:** Proyek ini tidak berafiliasi dengan YouTube/Google. Pengguna bertanggung jawab mematuhi ketentuan layanan di wilayah masing‑masing. LYREON hanya untuk pemutaran pribadi.

## ✨ Fitur Utama

| # | Fitur | Detail |
|---|-------|--------|
| 1 | **Pencarian YouTube Music penuh** | Filter LAGU / VIDEO / ALBUM / PLAYLIST, paging "muat lebih banyak" |
| 2 | **Saran pencarian real‑time** | Autocomplete dengan *debounce* 280 ms |
| 3 | **Pemutaran audio‑only** | Hanya stream audio — hemat kuota |
| 4 | **Background playback** | `MediaSessionService` + notifikasi media (lockscreen, Bluetooth, Android Auto) |
| 5 | **Antrean pintar** | Play next, add to queue, hapus, sheet antrean |
| 6 | **Playlist lokal** | Buat/rename/hapus/urutkan, tersimpan di Room |
| 7 | **Unduhan offline** | Foreground service + notifikasi progres, tersimpan di storage eksternal |
| 8 | **Putar offline otomatis** | Lagu terunduh diputar dari file lokal (0 kuota) |
| 9 | **Favorit (liked)** | Tersinkron di semua layar |
| 10 | **Riwayat dengar** | Recently played + play‑count |
| 11 | **Radio otomatis** | Antrean habis → lanjut ke terkait (bisa dimatikan di Settings) |
| 12 | **Home dinamis** | Quick picks, "Lanjutkan Mendengar", Genre Reel |
| 13 | **Editorial Archive** | Kurasi desain → kueri musik nyata |
| 14 | **Sleep timer** | 5–90 menit + indikator sisa waktu |
| 15 | **Shuffle & Repeat + kualitas audio** | Terbaik / Seimbang / Hemat data |
| 16 | **Restore sesi + deep link** | Antrean pulih saat app dibuka; terima link `youtu.be` / `youtube.com/watch` |

*Bonus:* tema Gelap/Kertas/Sistem, reduce‑motion, retry otomatis saat URL kedaluwarsa, snackbar error.

## 🧱 Tech Stack

| Kategori | Teknologi |
|----------|-----------|
| Bahasa | **Kotlin 2.4** (K2), JVM target 17 |
| UI | **Jetpack Compose** (BOM `2026.01.01`), Material 3 |
| Player | **AndroidX Media3 1.10.1** — `ExoPlayer` + `MediaSession` (background) |
| Extractor | **MetrolistExtractor** (`com.github.MetrolistGroup:MetrolistExtractor`) — fork NewPipeExtractor, plus **fallback InnerTube** langsung ke Google (`youtubei/v1`) bila extractor utama gagal |
| Jaringan | **OkHttp 5.1**, **Coil 3** (gambar), `StreamingDataSource` kustom untuk resolve stream lazily |
| Persistence | **Room 2.8** (SQLite), **DataStore** (preferensi/settings + profil selera) |
| Navigasi | **Navigation Compose** |
| Build | **Gradle 9.x** (via `gradle-wrapper.properties`), **AGP 9.3**, **JDK 21** |

> **Mengapa fork extractor?** Upstream NewPipeExtractor v0.25+ tidak lagi ter‑publish benar di JitPack (modul kosong → 404). Ekosistem (Metrolist dkk.) bermigrasi ke fork terpelihara ini; API identik: `org.schabi.newpipe.extractor.*`.

## 🗺️ Arsitektur

```
┌─ UI (Compose) ─┬─ Screens: Home / Search / Library / Archive / Offline / Settings / NowPlaying
│                └─ Components: MiniPlayerBar, LyricsSheet, GenreReel, LyreonPlayButton …
│
├─ PlayerManager ─┬─ MediaController ⇄ PlaybackService (Media3 MediaSessionService, background)
│   (state)       ├─ queue / radio auto‑extend / sleep timer / restore sesi
│                 └─ antrean bebas duplikat (cap per judul‑inti via MusicTextAnalyzer)
│
├─ Repositories ─┬─ YouTubeRepository (pencarian, metadata, stream, charts artis)
│                 ├─ TasteRepository (profil selera + diversePick)
│                 ├─ LibraryRepository (Room: playlist, riwayat, liked, unduhan)
│                 └─ LocalMusicRepository (MediaStore + SAF, thumbnail album art)
│
└─ Data ────────── Room DB · DataStore · model (LyreonTrack, SearchFilter, …)
```

**State scoping (anti‑lag):** `PlayerUiState` (track/queue/isPlaying — frekuensi rendah) dipisah dari `PlayerPosition` (posisi/durasi — ticker 500 ms). Hanya `MiniPlayerBar` & `NowPlayingScreen` yang mengoleksi flow posisi, sehingga layar lain tidak *recompose* tiap tick.

**Pembersihan duplikat:** pencarian & radio menggunakan `MusicTextAnalyzer.coreTitle()` (strip modifier "speed up/reverb/tiktok" + kurung) untuk membatasi varian judul‑sama (reupload channel berbeda) di hasil & antrean.

## 📁 Struktur Project

```
app/src/main/
├─ java/com/lyreon/app/
│  ├─ MainActivity.kt            # root Compose + NavHost
│  ├─ LyreonApp.kt               # Application + init NewPipe
│  ├─ core/                      # ServiceLocator, LocaleHelper
│  ├─ data/
│  │  ├─ db/                     # Room (Daos, Entities, Database)
│  │  ├─ model/                  # LyreonTrack, SearchFilter, LyreonArchive
│  │  ├─ taste/                  # MusicTextAnalyzer, TasteRepository (profil selera)
│  │  ├─ settings/               # SettingsRepository
│  │  └─ LibraryRepository.kt
│  ├─ download/                  # DownloadService, LyreonDownloadManager
│  ├─ local/                     # LocalMusicRepository (musik di storage)
│  ├─ lyrics/                    # LyricsRepository
│  ├─ player/                    # PlayerManager, PlaybackService, ResolvingDataSource
│  ├─ ui/                        # components/, screens/, theme/, vm/
│  └─ yt/                        # YouTubeRepository, OkHttpDownloader
│     └─ innertube/              # fallback InnerTube (player/search/playlist) + decipher JS (Rhino)
├─ res/                          # mipmap (icon), drawable, values, xml
└─ AndroidManifest.xml
```

## 🚀 Persiapan & Build

### Prasyarat
- **JDK 21** (AGP 9.x membutuhkannya).
- **Android SDK** Platform & Build‑Tools **36** (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`).
- **Gradle 9.x** — versi diambil otomatis dari `gradle/wrapper/gradle-wrapper.properties` (tidak perlu instal manual).

### Clone
```bash
git clone https://github.com/rixz-dev/Lyreon.git
cd Lyreon
```

### Build Debug APK
```bash
./gradlew :app:assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
```

### Build Release APK (R8)
```bash
./gradlew :app:assembleRelease
# hasil: app/build/outputs/apk/release/app-release.apk
```

### Signing release APK
Keystore **tidak di‑commit** (material privat, lihat `.gitignore`). Tanpa keystore, `assembleRelease` menghasilkan APK **tidak ditandatangani** yang harus ditandatangani manual sebelum distribusi:

```bash
# 1) Buat keystore sendiri (sekali saja)
keytool -genkeypair -v -keystore upload.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 -alias upload

# 2) Tandatangani APK hasil build
apksigner sign --ks upload.keystore \
  --out app-release-signed.apk app/build/outputs/apk/release/app-release.apk
```

Atau letakkan `debug.keystore` di root repo (password `android`, alias `androiddebugkey`) agar otomatis dipakai — build akan mendeteksinya dan menandatangani debug **dan** release.

### Menghindari peringatan Google Play Protect (instalasi side‑load)
Play Protect menilai aplikasi berdasarkan **tanda tangan (signature) & reputasi**, bukan isi kode.
Oleh karena itu hal yang bisa dilakukan **di sisi build/kode** agar instalasi lebih bersih:

1. **Gunakan keystore release yang unik & stabil.** Jangan bagikan keystore atau menandatangani
   dengan *debug key* untuk distribusi. Signature yang sama di semua rilis membantu Play Protect
   mengenali aplikasi sebagai "pengembang yang sama".
2. **Konsisten menandatangani setiap update** dengan keystore yang sama — APK yang ganti‑ganti
   signature justru dicurigai sebagai aplikasi berbeda.
3. **Target SDK tinggi** — sudah `targetSdk = 36` (Android 16). Menjaga target SDK terkini adalah
   salah satu sinyal keamanan yang dinilai.
4. **Izin seminimal mungkin** — daftar izin di `AndroidManifest.xml` sudah minimal & hanya untuk
   fungsi nyata (streaming, background play, unduhan, musik lokal). Jangan menambah izin tak perlu.
5. **Kode bebas dari perilaku mencurigakan** — tidak ada pemuatan kode dari sumber tak dikenal,
   tidak ada *hook* sistem, tidak meminta izin berlebihan.

> Catatan realistis: meski semua poin di atas sudah dipenuhi, **tidak ada jaminan kode** untuk
> menonaktifkan peringatan Play Protect pada side‑load. "Unknown developer" kadang tetap muncul
> satu kali saat instal pertama; bila Play Protect benar‑benar mem‑blokir, jalur yang dijamin adalah
> verifikasi resmi (Play Console / pengajuan tinjauan Play Protect), bukan sesuatu yang bisa dipaksa
> lewat kode.

## ⚙️ CI (GitHub Actions)

Workflow build otomatis ada di **`.github/workflows/android-build.yml`** (trigger: `push` ke `main`/`arena/*` dan PR). Ia:
1. Setup JDK 21 + Android SDK 36.
2. Build **Debug** lalu **Release** (`assembleRelease` dengan R8).
3. Upload kedua APK sebagai *artifact* (retensi 30 hari).
4. Bila build gagal, ringkasan error otomatis diposting sebagai komentar commit.

Jalankan manual lewat tab **Actions → Android Build → Run workflow** (atau `gh run watch` via CLI).

## 🎵 Izin & Musik Lokal

Aplikasi bisa memutar **file musik di penyimpanan perangkat** (mp3, m4a, aac, flac, ogg, wav, …) lewat dua jalur:
- **MediaStore** — indeks audio bawaan Android (filter longgar: membuang hanya nada dering/notifikasi/alarm/rekaman).
- **Folder kustom (SAF, `ACTION_OPEN_DOCUMENT_TREE`)** — untuk menembus folder ber‑`.nomedia` (mis. WhatsApp/Telegram).

Izin: Android 13+ → `READ_MEDIA_AUDIO`; ≤12 → `READ_EXTERNAL_STORAGE`. Thumbnail diambil dari *embedded album art* (`MediaMetadataRetriever`) dan di‑cache di `cacheDir/local_art/`.

## 📝 Lirik & Radio

- **Lirik** diambil lewat `LyricsRepository` dan ditampilkan sinkron di `LyricsSheet`.
- **Radio otomatis**: saat antrean hampir habis (`maybeExtendQueue`) atau lagu berakhir (`onEnded`), `PlayerManager` mengambil related + (berselang) query persona, lalu memilih lewat `TasteRepository.diversePick()` — bebas duplikat judul‑sama.

## 🧩 Catatan & Keterbatasan

- **Fragilitas extractor:** YouTube sesekali mengubah layout/skema stream → sebagian lagu bisa "tak tersedia". App sudah melakukan retry (termasuk resolve ulang saat token basi), fallback ke kandidat format beda, **dan fallback InnerTube** (langsung ke `youtubei/v1`) untuk stream/pencarian/playlist bila Metrolist gagal.
- **Charts artis** (tab Search/Home) mengandalkan YouTube Music Charts + profil kanal; bila gagal, digunakan kurasi per‑wilayah agar kartu tidak kosong.
- **Legalitas:** hanya untuk pemutaran pribadi; bukan pengganti layanan berlangganan resmi.

## 🤝 Kontribusi

1. Fork → buat branch fitur (`git checkout -b feat/...`).
2. Pastikan `./gradlew :app:assembleDebug` lolos.
3. PR ke `main` dengan deskripsi jelas.

Isu extractor sebaiknya dilaporkan ke upstream [MetrolistExtractor]; isu UI/logika ke repo ini.

## 📜 Lisensi

Proyek ini dirilis di bawah **MIT License** — lihat file [`LICENSE`](LICENSE).

```
LYREON © rixz-dev — Hear What Words Can't Say.
```

[MetrolistExtractor]: https://github.com/MetrolistGroup/MetrolistExtractor
