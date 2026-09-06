# 06 — Referensi Meld: peta repo, cara mengambil sumber, apa yang sudah diporting

Rujukan: [FrancescoGrazioso/Meld](https://github.com/FrancescoGrazioso/Meld) —
klien musik YouTube/Android (fork `MetrolistGroup/Metrolist`, 282 commit di depan
hulu, push terakhir 2026-09-05). Kita menirunya karena pengembangnya **mengukur di
perangkat** dan menuliskan hasil ukurnya di komentar kode — itu yang tidak dimiliki
dokumentasi kebijakan yt-dlp.

Skala repo (diambil 2026-09-06): **1.487 path**, **592 berkas Kotlin**, 11 modul:

| Modul | Berkas | Isi | Relevansi Lyreon |
|---|---|---|---|
| `app` | 1052 | UI, playback, db, widget, listentogether, eq | utama |
| `innertube` | 124 | klien InnerTube, model respons, `pages/` | **sudah diporting sebagian** |
| `fastlane` | 94 | metadata store (screenshot/deskripsi) | referensi rilis |
| `kizzy` | 35 | status Discord (RPC) | tidak relevan |
| `spotify` | 30 | integrasi Spotify | tidak relevan |
| `betterlyrics` | 21 | penyedia lirik | **menarik untuk fitur #8** |
| `lastfm` | 20 | scrobbling Last.fm | opsional |
| `kugou` | 16 | penyedia lirik KuGou | **menarik untuk fitur #8** |
| `lrclib` | 13 | penyedia lirik LrcLib | **menarik untuk fitur #8** |
| `shazamkit` | 12 | pengenalan lagu | opsional |
| `paxsenix` | 11 | penyedia lirik | opsional |

Dependensi kunci mereka: NewPipeExtractor (TeamNewPipe) v0.26.0, media3 1.7.1,
ktor+okhttp, kotlinx.serialization. **Lyreon berbeda**: media3 1.10.1, okhttp 5.1.0,
`org.json`, Room+KSP, Coil 3, Rhino. Jadi porting = terjemahkan, bukan salin-tempel.

---

## 1. ⚠️ Lisensi — baca ini sebelum menyalin apa pun

| | Lisensi |
|---|---|
| Lyreon (`LICENSE`) | **MIT** (Copyright (c) 2026 rixz-dev) |
| Meld (`LICENSE`) | **GPL-3.0** (diwarisi dari Metrolist/InnerTune) |

Konsekuensi nyata:

- **Menyalin kode GPL-3.0 ke dalam proyek MIT tidak diperbolehkan** tanpa membuat
  karya turunan itu tunduk pada GPL-3.0. Untuk sebuah APK, "karya turunan" biasanya
  dinilai satu kesatuan → praktis seluruh app harus jadi GPL-3.0.
- **Fakta, angka, dan metode tidak dilindungi hak cipta**: `clientVersion = "1.65.10"`,
  string User-Agent, nama header (`X-Goog-Visitor-Id`), hasil pengukuran (pratinjau
  ~1 MiB, gate per versi), dan *ide* "probe byte terakhir sebelum memutar" boleh
  dipakai dan dikutip dengan atribusi.
- **Ekspresi** (susunan kode, struktur fungsi, komentar panjang mereka) yang disalin
  mentah adalah masalah.

Tiga jalan yang tersedia (butuh **keputusan pemilik proyek**, bukan keputusan
implementasi):

1. **Tetap MIT + reimplementasi bersih (clean room).** Pakai Meld sebagai sumber
   *fakta*; tulis kode sendiri; atribusikan di README/NOTICE ("spesifikasi klien dan
   pendekatan validasi URL mengikuti pengukuran FrancescoGrazioso/Meld, GPL-3.0").
   Kutipan komentar mereka dibatasi sebagai sitasi dokumentasi, bukan ditempel sebagai
   KDoc kita. ← kondisi saat ini sebagian besar sudah begini, tetapi ada beberapa
   kutipan verbatim di KDoc `InnertubeConfig.kt` / `PlayerClientLadder.kt` yang
   sebaiknya dijadikan sitasi bertanda kutip + tautan (sudah begitu) atau dipindah ke
   `docs/`.
2. **Relisensi Lyreon ke GPL-3.0.** Paling bersih bila kita memang berniat terus
   meniru Meld (lirik KuGou/LrcLib, `SilenceDetectorAudioProcessor`, widget, EQ,
   dst. semuanya GPL). Konsekuensi: semua kontribusi & rilis tunduk copyleft.
3. **Campuran per-berkas** (file turunan berlisensi GPL-3.0, sisanya MIT). Secara
   teknis mungkin, secara hukum rapuh untuk satu APK, dan membingungkan kontributor.
   **Tidak disarankan.**

Keputusan apa pun yang diambil, tuliskan di `LICENSE`/`NOTICE` + README, dan catat di
berkas ini. Selama belum diputuskan: **jangan menyalin berkas Meld apa adanya** —
porting dilakukan sebagai reimplementasi dengan atribusi.

## 2. Cara mengambil sumber Meld dari lingkungan kerja ini

Sandbox memblokir HTML `github.com` dan TLS `googlevideo.com`/`youtube.com`, tetapi
`api.github.com` lewat `gh` berfungsi.

```bash
# 1) daftar seluruh berkas
gh api "repos/FrancescoGrazioso/Meld/git/trees/main?recursive=1" \
  --jq '.tree[].path' > /tmp/meld_files.txt

# 2) ambil satu berkas (PENTING: buang newline sebelum base64 -d,
#    kalau tidak akan gagal "base64: invalid input")
gh api "repos/FrancescoGrazioso/Meld/contents/<path>" --jq '.content' \
  | tr -d '\n' | base64 -d > /tmp/meld_x.kt

# 3) riwayat satu berkas (kapan nilai klien terakhir diubah)
gh api "repos/FrancescoGrazioso/Meld/commits?path=<path>&per_page=5" \
  --jq '.[] | "\(.sha[0:9]) \(.commit.committer.date) \(.commit.message | split("\n")[0])"'
```

`gh run view --log` sering gagal (EOF) → pakai
`gh api repos/{owner}/{repo}/actions/runs/{id}/jobs`.

## 3. Berkas Meld per domain (peta untuk porting berikutnya)

**Streaming / InnerTube (sudah kita pelajari habis)**
- `innertube/.../models/YouTubeClient.kt` — spesifikasi semua klien + KDoc pengukuran
  (gate per versi ANDROID_VR, kenapa buildId/cronet/packageName dihilangkan).
- `innertube/.../InnerTube.kt` — `ytClient()` (header), `player()` (body),
  `getSwJsData()`; `X-Goog-Visitor-Id` untuk semua klien.
- `innertube/.../YouTube.kt` — `visitorData()` (parse `sw.js_data`, regex `^Cg[t|s]`),
  `newPipePlayer()`.
- `app/.../utils/YTPlayerUtils.kt` (968 baris) — `STREAM_FALLBACK_CLIENTS`,
  `playerResponseForPlayback()`, `validateStatus()`, `describeStreamUrl()`,
  `findFormat()`, `findUrlOrNull()`; cascade logging satu baris.
- `app/.../utils/potoken/{PoTokenGenerator,PoTokenWebView,JavaScriptUtil}.kt` —
  poToken BotGuard via WebView (timeout 8 s). **Tidak kita porting** (lihat
  `docs/streaming-resilience.md` §7.1).
- `app/.../utils/sabr/EjsNTransformSolver.kt`, `utils/cipher/CipherDeobfuscator.kt` —
  n-transform & decipher. **Tidak kita porting**: tangga anonim tidak butuh
  (VISIONOS/ANDROID_VR/IOS mengirim URL polos). Lyreon punya `SignatureDecipher.kt`
  (Rhino) untuk kasus cipher saja.

**Pemutaran & audio (kandidat fitur #10, #12, #13)**
- `playback/audio/SilenceDetectorAudioProcessor.kt` → skip silence.
- `eq/EqualizerService.kt`, `eq/audio/CustomEqualizerAudioProcessor.kt`,
  `ui/screens/equalizer/{EqScreen,EQState,EQViewModel}.kt` → pola layar audio +
  AudioProcessor kustom (rujukan untuk normalisasi loudness).
- `playback/SleepTimer.kt` + `app/src/test/.../SleepTimerFadeTest.kt` → fade-out
  (Lyreon sudah punya timer, belum punya fade).
- `playback/MusicService.kt`, `constants/MediaSessionConstants.kt`,
  `playback/MediaLibrarySessionCallback.kt`.
- `playback/queues/{Queue,ListQueue,YouTubePlaylistQueue,YouTubeAlbumRadio,…}.kt`,
  `extensions/QueueExt.kt`, `models/PersistQueue.kt` → reorder/queue (#15).

**Lirik (fitur #8)**
- `ui/component/{Lyrics,LyricsLine,LyricsCommon,LyricsComponents,LyricsImageCard,
  LyricsBackgroundStyle}.kt` → tampilan + animasi baris.
- `lyrics/{LyricsHelper,LyricsEntry,KuGouLyricsProvider,LrcLibLyricsProvider,
  BetterLyricsProvider,LyricsPlusProvider}.kt` + modul `kugou/`, `lrclib/`,
  `betterlyrics/`, `paxsenix/`.
- `db/entities/LyricsEntity.kt`, `di/LyricsHelperEntryPoint.kt`.

**Tema (fitur #17 → `04-spesifikasi-tema-baru.md`)**
- `ui/theme/{Theme,Type,Font,PlayerColorExtractor,PlayerSliderColors}.kt`,
  `ui/screens/settings/ThemeScreen.kt`, `viewmodels/ThemeViewModel.kt`,
  `constants/Dimensions.kt`.

**Widget (fitur #16)**
- `widget/{MetrolistWidgetManager,MusicWidgetReceiver,TurntableWidgetReceiver,
  MusicRecognizerWidgetReceiver,MusicRecognizerWidgetService}.kt`.

**Halaman katalog (fitur #20)**
- `innertube/pages/{MoodAndGenres,ExplorePage,ArtistPage,ArtistItemsPage,AlbumPage,
  ChartsPage,NewReleaseAlbumPage,HomePage,RelatedPage,SearchPage,SearchSummaryPage}.kt`
- `ui/screens/{MoodAndGenresScreen,ExploreScreen,ChartsScreen,NewReleaseScreen,
  AlbumScreen}.kt`, `ui/screens/artist/*`, `ui/screens/library/*`.

**Akun (fitur #5, #9 — DITAHAN, lihat `05`)**
- `ui/screens/{LoginScreen,AccountScreen,ListenTogetherScreen}.kt`,
  `listentogether/{ListenTogetherManager,ListenTogetherClient,ListenTogetherServers,
  Protocol,MessageCodec,ListenTogetherActionReceiver}.kt`.

## 4. Apa yang sudah diporting ke Lyreon (commit `df7531a`, CI hijau)

| Dari Meld | Ke Lyreon | Bentuk porting |
|---|---|---|
| `YouTubeClient.kt` (VISIONOS 0.1, ANDROID_VR 1.65.10/1.43.32, IPADOS, IOS, TVHTML5, WEB*, WEB_CREATOR) | `yt/innertube/PlayerClientLadder.kt` | **fakta/spesifikasi** disalin; dijaga oleh `tools/ci/meld-client-radar.sh` |
| `STREAM_FALLBACK_CLIENTS` + aturan `loginRequired`-skip | `PlayerClientLadder.ordered(forPlayback=true)` + flag `loginRequired`/`useWebPoTokens`/`probeOnly` | reimplementasi |
| `InnerTube.ytClient()`/`player()` (host music.youtube.com, header, body ramping, STS per klien) | `yt/innertube/InnertubeRequest.kt` | reimplementasi (okhttp + org.json) |
| `YouTube.visitorData()` (`sw.js_data`, regex `^Cg[t|s]`) | `yt/innertube/InnertubeConfig.kt` | reimplementasi + cadangan `guide` + panen `responseContext` |
| `YTPlayerUtils.validateStatus()` + `describeStreamUrl()` | `yt/innertube/StreamUrlValidator.kt` | reimplementasi (probe + deskripsi tersensor) |
| `Format.isOriginal` (buang audio auto-dub) | `InnertubeFallback.pickAudio()` | reimplementasi (`audioTrack.audioIsAutoDubbed`) |
| Aturan "klien terakhir diterima tanpa validasi" | `fetchPlayer()` → `ResolvedAudio(validated = false)` | diadopsi + diperketat untuk unduhan |
| Cascade logging satu baris | `PlayerClientLadder.push()` + `attempts` | diadopsi idenya |

**Yang sengaja tidak diporting:** poToken/BotGuard, n-transform/EJS, SABR, NewPipe
signature deobfuscation, login/akun, modul Spotify/Last.fm/Kizzy/ShazamKit.

## 5. Cara memakai Meld sebagai rujukan harian

1. Lagu gagal massal → `bash tools/ci/meld-client-radar.sh` (drift spesifikasi).
2. Radar bersih tapi tetap gagal → baca `YTPlayerUtils.kt` HEAD Meld: KDoc di sana
   memuat pengukuran terbaru (klien mana 100% 206, mana yang pratinjau, mana yang mati
   server-side seperti `TVHTML5_SIMPLY_EMBEDDED_PLAYER`).
3. Mau fitur baru → cari berkasnya di §3, baca **KDoc-nya dulu** (sering berisi
   jebakan yang sudah mereka bayar), baru reimplementasi dengan gaya Lyreon.
4. Setiap porting baru → tambahkan baris di §4 (tanggal + commit) dan perbarui status
   di `05-peta-fitur-diinginkan.md`.
5. Sebelum menyalin kode: baca §1 (lisensi).
