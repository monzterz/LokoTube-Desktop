# LokoTube Desktop — Rencana Eksekusi

Tujuan: versi desktop LokoTube (Windows utama; macOS/Linux ikut) dengan UI bergaya LokoTube
+ fitur LokoTube, dipadukan fitur Sonara. Strategi = REBASE (opsi B), bukan migrasi.

## Basis & arsitektur (sudah diverifikasi)
- Basis: fork Sonara (github.com/rashiiddsr/sonara), yang sendiri berbasis Metrolist
  (github.com/MetrolistGroup/Metrolist). GPL-3.0.
- Direktori kerja: ~/lokotube-desktop (clone shallow Sonara).
- Pola "satu UI, dua platform" (source-for-source):
  - `innertube/` = klien InnerTube (package com.metrolist.innertube), dipakai Android & desktop.
  - `app/` = UI Android LENGKAP (package com.metrolist.music). SEMUA fitur di sini.
  - `desktop/` = build Gradle TERPISAH (desktop/settings.gradle.kts) yang menyertakan ulang
    source `app` (srcDir "../app/src/main/kotlin") + `innertube`, lalu menambahkan:
    - Stub API Android: desktop/src/main/kotlin/{android/*, timber/*}.
    - Pengganti player: com.metrolist.music.utils.InnerTubeXPlayer (FFmpeg), ganti Media3.
    - Entry point com.ermusic.desktop.MainKt + media keys + window + installer (jpackage).
- Build desktop: `./gradlew -p desktop compileKotlin --no-daemon` (baseline SUDAH SUKSES 5m4s).
  Installer Windows (MSI/EXE) hanya bisa dibangun di Windows / GitHub Actions (workflow sudah ada).
- InnerTubeX = mesin streaming cross-platform (com.github.MetrolistGroup:innertubex, dari JitPack).
  Bisa dipakai lokal via gradle property `useMavenLocalInnerTubeX`.

## Fase
1. [DONE] Clone + verifikasi build baseline.
2. [DONE] Rename penuh: package com.metrolist.*/com.ermusic.* → com.lokotube.* (532 file),
   applicationId → com.lokotube.android, namespace com.lokotube.music/innertube, app_name + string
   brand → "LokoTube", class MetrolistWidgetManager→LokotubeWidgetManager & SonaraCast→LokotubeCast,
   User-Agent → LokoTube-Android. Verified: desktop compile SUCCESS + app assembleDebug SUCCESS.
3. [DONE] Restyle UI ke gaya LokoTube (light flat modern, teal #0d9488, ikon Bootstrap bukan
   emoji). Color.kt light flat (EmberDeep 0xFF0D9488), desktop LokotubeTheme disamakan, ikon
   Material vector (0 emoji). Dark glassy DITOLAK user.
4. [TODO] Port fitur LokoTube ke basis Sonara:
   - Multi-channel (accounts_list + context.user.onBehalfOfUser) — lihat skill lokotube.
   - Blokir channel.
   - Halaman artis musik.
   - SponsorBlock (cek: Sonara mungkin sudah punya setara).
5. [PARTIAL] Branding nama "LokoTube" SUDAH di seluruh surface user-facing (window title/tray/
   welcome, MPRIS, jpackage vendor/description/menuGroup/bundleID/dockName, data dir, keychain,
   StartAtLogin, desktop entry lokotube-LokoTube, make_appimage.sh, widget & crash string).
   BELUM: icon/logo (logo_mark.xml & ic_launcher masih artwork Sonara) → icon.ico/icon.icns +
   banner WiX. About "Creator" masih Rashid (butuh kontak Rizal). github_releases_url masih
   MetrolistGroup (butuh repo LokoTube).
6. [TODO] Rilis desktop (Windows MSI via GitHub Actions; macOS DMG; Linux deb/AppImage).

## Pitfall rename (terpecahkan)
- `com.metrolist.innertubex` = package library EKSTERNAL InnerTubeX (bukan kode Sonara) — JANGAN
  ikut di-rename. Setelah sed `com.metrolist`→`com.lokotube`, wajib revert `com.lokotube.innertubex`
  → `com.metrolist.innertubex` (41 referensi).
- Path berbentuk SLASH (`com/metrolist`, `com/ermusic/...`) TIDAK kena sed `com\.metrolist` (pakai
  titik) — cek manual: desktop/build.gradle.kts `from("../app/src/main/kotlin/com/metrolist")` (task
  Sync) + path generated secrets `com/ermusic/desktop/BuildSecrets.kt`.
- Room schema: folder `app/schemas/<FQCN-database>` memakai nama package LAMA. Setelah rename,
  KSP gagal "Schema 'N.json' not found" — pindahkan folder schema lama ke nama baru
  (konten JSON TIDAK berisi package, hanya nama folder yang berubah).
- Stale generated file: `desktop/build/generated/secrets/` menyimpan BuildSecrets lama (com.ermusic)
  → redeclaration. Fix: rm -rf desktop/build lalu rebuild.

## Catatan penting
- Package Sonara: app=com.metrolist.music (applicationId com.ermusic), desktop=com.ermusic.desktop.
  Belum di-rename ke com.lokotube.* — PUTUSKAN dulu apakah perlu rename penuh (heavy) atau
  cukup nama tampilan (LokoTube) saja. Rekomendasi awal: nama tampilan dulu, rename package
  menunggu fase stabil.
- secrets/translator.sealed = kunci AI lirik yang di-seal (AES-GCM). JANGAN commit kunci asli;
  mekanisme seal sudah ada (local.properties override). Untuk LokoTube, pertimbangkan buang fitur
  AI translation atau pakai kunci sendiri.
- Atribusi: Sonara/Metrolist GPL-3.0 — atribusi asli WAJIB dipertahankan (sama seperti LokoTube→LePlay).
- update upstream: JANGAN pasang auto-sync; proyek ini fork mandiri.

## Langkah berikutnya (sesi berikutnya)
- Verifikasi build setelah branding (compileKotlin desktop).
- Restyle tema (fase 3): ganti LightPalette ke estetika light flat LokoTube, pastikan
  warna aksen konsisten teal #0d9488, ikon Bootstrap.
- Putuskan rename package com.ermusic → com.lokotube (atau tunda).
