# Pure Video Player (`com.pureplayer.video`)

Flagship 4K Cinema & IPTV Media Player for Android (`minSdk 24` / `targetSdk 34`), built with Kotlin and LibVLC (`arm64-v8a`).

## Key Capabilities
- **Video Engine:** `HW+` / `HW` / `SW` live decoder switcher, HDR10/HLG tonemapping, YouTube-style Auto-PiP with instant audio stop on close, and split long-press gestures.
- **Audio Studio:** 7 acoustic sound profiles + 5-Band Graphic Equalizer & Pre-Amp Gain.
- **Subtitles:** Online subtitle search across 15 world languages, draggable & pinch-to-zoom overlay, and millisecond sync.
- **IPTV Suite:** Xtream Codes API & M3U/M3U8 parser backed by indexed SQLite (`pure_video_iptv.db`), Favorites, Recent History, and Offline VOD Download.
- **Privacy & Themes:** `PBKDF2WithHmacSHA256` + `AndroidKeyStore` Biometric Private Vault, Light/Dark/System themes, and 8 dynamic Material You palettes.

## Build Flavors & Secrets Setup
- **`play` flavor** (`bundlePlayRelease`): Google Play Console AAB bundle without `REQUEST_INSTALL_PACKAGES` and without the in-app updater.
- **`github` flavor** (`assembleGithubRelease`): Direct-install Release APK with built-in GitHub updater.
- **GitHub Secrets (Recommended):** Configure `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in repository Settings -> Secrets and variables -> Actions. Never commit `.keystore` files or `local.properties`.

## Developer
- **Ahmed M Moumen**
- **Current Version:** `v38.01` (`versionCode = 57`)
