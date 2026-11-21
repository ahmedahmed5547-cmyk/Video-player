## v90.13 (2026-11-21)
- **Theme Picker Redesign**: Top row now has 3 clean brightness options (Follow System, Light Mode, Dark Mode). Moved Luxury Dark into the theme palette below, enforced Android 12+ guard on Material You, kept dark-only themes coherent in light mode, and added safe legacy preference migration.
- **PiP Mini Player Headphone Action**: Added Headphone (Audio-Only) and Play/Pause `RemoteAction` buttons directly on the floating PiP window, guarded PiP-to-audio transition, and wired tapping the notification to reopen the video player at the exact same position.
- **Folder & Video Thumbnails Fix**: Wired 2x2 folder cover collage thumbnails + video count badge in `MediaAdapter.kt`, and upgraded `VideoThumbnailLoader` with disk caching, representative frame timestamps (~10% duration), and SD-card path fallback.

# Changelog — VEXO Player: IPTV and Video player (`com.pureplayer.video`)

## v90.12 (2026-11-21) — Audio Library, Android Auto, 2x2 Folder Covers, Material You, Sleep Timer & Audio-Only Mode
- **Phase 1 (Luxury Dark Fix):** Fixed missing `Luxury Dark` option in the Theme/Appearance picker (`APPEARANCE_LUXURY_DARK = 3`) with immediate application and persistence.
- **Phase 2 (Settings Cleanup):** Removed redundant `App Version` row from Settings/About (version already shown in header pill and Changelog).
- **Phase 3 (VLC-Style Audio Library):** Added `Audio` bottom tab (`Artists`, `Albums`, `Tracks`, `Genres`, `Playlists`), `AudioLibraryViewModel`, `READ_MEDIA_AUDIO`, and Room `MIGRATION_2_3` for persistent playlists.
- **Phase 4 (Android Auto):** Added Media3 `VexoPlaybackService` (`MediaLibraryService`), `res/xml/automotive_app_desc.xml`, browse tree (`Artists`, `Albums`, `Tracks`, `Genres`, `Playlists`, `Recently Played`), and voice search.
- **Phase 5 (2x2 Folder Covers):** Replaced generic folder icons with memory & disk cached 2x2 video frame collages plus video count badges in `VideoThumbnailLoader`.
- **Phase 6 (Full Material You & Monochrome Icon):** Enabled `DynamicColors` on Android 12+, added `System (Material You)` option, and created adaptive launcher icon with monochrome layer (`mipmap-anydpi-v26`).
- **Phase 7 (Enhanced Sleep Timer):** Added `SleepTimerManager` with 15/30/45/60/90 min presets, custom minutes, end-of-media mode, 15s volume fade-out, +5 min extend, and rotation/background persistence.
- **Phase 8 (VLC Equalizer & 200% Audio Boost):** Added `VexoAudioEffectsManager` supporting device-dynamic `Equalizer`, `BassBoost`, `Virtualizer`, `LoudnessEnhancer` (up to 200% with safety warning), 16 VLC presets, and custom preset persistence.
- **Phase 9 (Background Audio-Only Mode):** Added headphone toggle in `PlayerActivity` and Mini Player to convert video playback seamlessly into foreground background audio-only mode with notification, lock-screen controls, return-to-video button, and close (`X`) action.

## v90.11 (2026-11-20) — Luxury Dark Redesign, Foldable Support & Core Engineering Fixes
- **Luxury Dark UI/UX & Luxury Gold Theme:** Redesigned Player Settings and Playback UI with `#0A0A0F`/`#12121A` glassmorphism surfaces, hairline borders, thin-line vector icons, dynamic `BuildConfig.VERSION_NAME` version pill, marquee filename, compact subtitle glass pill, glowing SeekBar, tabular monospace timers, and selectable `Luxury Gold (#D4AF37)` theme.
- **Full Foldable & Adaptive Layout Support:** Integrated Jetpack WindowManager (`androidx.window:window:1.3.0`) supporting Flat Unfolded, Folded Cover (Z Fold/Flip), Tabletop (upper video / lower controls), and Book postures with hinge-safe subtitle/control placement and centered max-width dialogs (`580–620dp`).
- **Rotation Controls, VLC Aspect Engine & First-Install Fix:** Rebuilt bottom controls in `ConstraintLayout` with `WindowInsets`/`displayCutout`, ported VLC `changeSurfaceLayout()` (`VlcSurfaceLayoutMath`) eliminating Fit-to-Stretch cropping, and migrated scanning to `VideoLibraryViewModel` (`StateFlow`, `ContentObserver`, `MediaScannerConnection` fallback).
- **Android 15/16 Compliance & 4-Version Changelog:** Enabled `enableEdgeToEdge()`, removed deprecated bar colors and static manifest `screenOrientation`, upgraded to AGP `8.5.1` (Gradle `8.7`) + `libvlc-all:3.6.5` for 16 KB alignment, and updated Changelog display to show the latest 4 releases with full notes.

## v90.10 (2026-11-18) — Google Play Console Release Bundle (`versionCode = 9010`)
- **Fresh Play Console Version Code:** Incremented `versionCode` to `9010` (`versionName = "90.10"`) to resolve Play Console duplicate version code conflict.
- **Embedded Native Debug Symbols:** Configured `ndk { debugSymbolLevel = "FULL" }` for full crash/ANR symbolication inside the `.aab`.

## v90.09 (2026-11-18) — Android 16 (API Level 36) Google Play Target SDK Compliance
- **Target API Level 36 Upgrade:** Upgraded `compileSdk` and `targetSdk` from `34` to `36` (Android 16) to meet Google Play submission policy.
- **Build Compatibility:** Added `android.suppressUnsupportedCompileSdk=36` in `gradle.properties`.

## v90.08 (2026-11-18) — Official `VEXO Player` (`IPTV and Video player`) Store Identity & Privacy Integration
- **Unified Brand Identity:** Adopted primary name `VEXO Player` with secondary subtitle `IPTV and Video player` across toolbar, About dialog, Privacy Policy, and Google Play Store listing assets.
- **Official Developer Contact & Privacy Links:** Integrated developer contact email (`AHMEDAHMED5547@GMAIL.COM`) and public GitHub Privacy Policy URLs across all disclosures.
