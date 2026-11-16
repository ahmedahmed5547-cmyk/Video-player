# Pure Video Player (`com.pureplayer.video`)

Flagship 4K Cinema & IPTV Media Player for Android (`minSdk 24` / `targetSdk 35`), built with Kotlin, Room, Paging 3, and LibVLC (`arm64-v8a`).

## Key Capabilities
- **Video Engine:** `HW+` / `HW` / `SW` live decoder switcher, HDR10/HLG tonemapping, YouTube-style Auto-PiP with instant audio stop on close, and split long-press gestures.
- **Audio Studio:** 7 acoustic sound profiles + 5-Band Graphic Equalizer & Pre-Amp Gain.
- **Subtitles:** Online subtitle search across 15 world languages, ASS/SSA/SRT/VTT parser (`UTF-8 BOM`, `UTF-16`, `Windows-1256`), draggable & pinch-to-zoom overlay, and millisecond sync.
- **IPTV Suite:** Xtream Codes API & M3U/M3U8 parser backed by Room + Paging 3 (`pure_video_iptv_room.db`), Favorites, Recent History, and Offline VOD Download.
- **Privacy & Themes:** `PBKDF2WithHmacSHA256` (`>= 100,000` iterations) + `AndroidKeyStore` (`AES-256-GCM`) Biometric Private Vault, Light/Dark/System themes, and 8 dynamic Material You palettes.

## Build Flavors & Secrets Setup
- **`play` flavor** (`bundlePlayRelease` / `assemblePlayRelease`): Google Play Console AAB bundle and APK without `REQUEST_INSTALL_PACKAGES` and without the in-app updater.
- **`github` flavor** (`assembleGithubRelease`): Direct-install Release APK with built-in GitHub updater and `REQUEST_INSTALL_PACKAGES` isolated in `app/src/github/AndroidManifest.xml`.
- **Mandatory Release Signing Secrets:** Configure `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in repository `Settings -> Secrets and variables -> Actions` (or `local.properties` for local builds).

## Developer
- **Ahmed M Moumen**
- **Current Version:** `v38.04` (`versionCode = 60`)
