# Pure Video Player (`com.pureplayer.video`)

Flagship 4K Cinema & Media Player for Android (`minSdk 24` / `targetSdk 35` / `compileSdk 35`), built with Kotlin, Room, and LibVLC (`arm64-v8a`).

## Key Capabilities
- **Video Engine:** `HW+` / `HW` / `SW` live decoder switcher, HDR10/HLG tonemapping, YouTube-style Auto-PiP with instant audio stop on close, and split long-press gestures.
- **Audio Studio:** 7 acoustic sound profiles + 5-Band Graphic Equalizer & Pre-Amp Gain.
- **Subtitles:** Online subtitle search across 15 world languages, draggable & pinch-to-zoom overlay, ASS/SSA/SRT/VTT support, and millisecond sync.
- **Playlist Player (M3U / Xtream):** Pure media player for user-supplied M3U/M3U8 and Xtream playlists backed by Room SQLite (`pure_video_iptv_room.db`) with schema migrations and background coroutines. Does not provide or bundle any media content.
- **Privacy & Themes:** `PBKDF2WithHmacSHA256` (`>= 100,000` iterations) + brute-force lockout + `AndroidKeyStore` Biometric Private Vault, Light/Dark/System themes, and 8 dynamic Material You palettes.

## Build Flavors & Secrets Setup
- **`play` flavor** (`bundlePlayRelease`): Google Play Console AAB bundle without `REQUEST_INSTALL_PACKAGES` and without the in-app updater.
- **`github` flavor** (`assembleGithubRelease`): Direct-install Release APK with built-in GitHub updater.
- **Signing Configuration:** Provide `KEYSTORE_PATH` (or `KEYSTORE_BASE64` in CI), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` via GitHub Secrets, environment variables, or `local.properties`.

## Developer
- **Ahmed M Moumen**
- **Current Version:** `v38.04` (`versionCode = 60`)
