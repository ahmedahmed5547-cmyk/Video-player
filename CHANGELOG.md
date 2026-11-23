## v90.20 (2026-11-22)
- **Bug Fix — Unified Player Gesture State Machine**: Replaced legacy `GestureDetector` with pure-Kotlin `PlayerGestureStateMachine` enforcing strict axis locking (Brightness / Volume / Seek), long-press cancellation on movement, exact playback-speed restoration across release/cancel/multi-touch/lifecycle events, preview-then-commit horizontal seek, ±10s double-tap, 24dp edge-zone & controls exclusion, and boundary haptics.
- **Bug Fix — Status Bar Overlap**: Preserved system-bar `WindowInsets` alongside TV overscan padding in `MainActivity` and re-applied insets and system bars across `onResume`, `onWindowFocusChanged`, `onConfigurationChanged`, and exiting `PlayerActivity`.
- **Bug Fix — Smart Subtitle Touch Handling**: Restricted subtitle drag/pinch capture to the real rendered text bounds (`+12dp` pad); outside taps and quick taps (`<220ms`) pass through to toggle Play/Pause reliably; added **Lock Subtitle Position** toggle.
- **Feature — Automatic Subtitle Download & Batch Translation**: Added `SubtitleAutoTranslator` with SRT/VTT/ASS timing & tag preservation, context-aware batch translation (Google Cloud, DeepL, OpenAI/LLM, and built-in fallback), Arabic RTL & punctuation normalization, disk caching, cancellable progress dialog, clear auto-translated labels, and full Settings UI.
- **Feature — Subtitle Enhancements**: Added Quick Subtitle Sync (`-0.5s`, `-0.1s`, `0.0s`, `+0.1s`, `+0.5s`), per-video subtitle label & path memory, and **Retranslate** option.

## v90.19 (2026-11-22)
- **IPTV Source Instant Sync Fix**: Fixed a stale in-memory cache bug where newly added IPTV playlists/movie libraries saved to Room but did not appear until restarting the app. Moved the sources cache directly into `IptvRepository` so every add/edit/delete updates immediately with zero-lag tab switching.

## v90.17 (2026-11-22)
- **Tablet & Large-Screen Settings Fix**: Resolved the ~75dp narrow strip bug on 10" tablets and large displays caused by applying full-screen horizontal padding (`getMenuHorizontalPaddingDp`) inside a container already capped at `computeDialogMaxWidthDp`. Settings and menus now render cleanly centered (600dp Medium / 680dp Expanded, full-width on Compact phones) with live rotation & split-screen resize adaptation.

## v90.16 (2026-11-22)
- **One-Time Delete Permission (All Files Access)**: Added `MANAGE_EXTERNAL_STORAGE` flow with a single explanation dialog on Android 11+; once granted, Delete, Copy, Move, and Vault operations run silently via direct File APIs + MediaStore sync with zero repeated popups (falling back to one batched `createDeleteRequest` if declined).
- **Smart Conflict-Only Copy/Move Dialog**: Copy/Move now runs silently when no filename conflict exists; the conflict dialog appears only for actual collisions (Replace / Keep both `name (1).ext` / Skip + "Apply to all remaining conflicts" checkbox), with silent same-folder Move skip and auto-rename same-folder Copy.
- **Instant Launch Without Startup Spinner**: Persisted `first_scan_done` flag so normal launches hydrate instantly from cache with zero loading/refresh circle and silent background refresh (spinner appears only on fresh-install first scan or manual pull-to-refresh).

## v90.15 (2026-11-22)
- **Unified Selection Mode (Main Library & Vault)**: Removed old long-press popup menus; long-pressing any video or folder now enters multi-selection mode with contextual top bar, Select All, mixed multi-select, single-video Details (including codec & modified date), atomic Copy/Move with Skip/Replace/Keep Both duplicate handling, rotation persistence, Back-exits-selection-first, and Vault `FLAG_SECURE` + `.nomedia` protection.
- **Single-Prompt Batch Delete**: Fixed repeated Android 11+ delete permission prompts by attempting direct delete for app-owned files and sending a single unified `MediaStore.createDeleteRequest` for all remaining URIs.
- **Zero-Lag Video <-> IPTV Switching**: Eliminated lag and disappearing video folders by hosting IPTV in its own dedicated persistent `iptvRecyclerView`/`iptvAdapter` and isolating background `MediaStore` updates from IPTV.
- **Battery Optimization & Saver Mode**: Stopped background player UI tickers in `onStop()`, debounced `MediaStore` `ContentObserver` scans, paused IPTV background sync in Battery Saver, added Battery Saver settings (manual + auto on System Power Save or <= threshold), lifecycle-aware player battery indicator, and one-shot 15%/5% low-battery notices.

## v90.14 (2026-11-21)
- **Persistent Bottom Navigation Bar**: Hosted Audio Library in-place above the fixed bottom bar so all 4 tabs (Video, Audio, Private, IPTV) share one persistent bar with zero flicker or recreation, per-tab scroll/folder/search state preservation, and re-tap to scroll to top or pop to root.
- **Smooth Tab Switching (Video -> IPTV)**: Eliminated main-thread stutter by caching IPTV sources off the main thread, cancelling pending `VideoThumbnailLoader` jobs on tab leave, disabling reset entrance animations on tab switch, enabling `setHasFixedSize(true)`, and deferring IPTV auto-refresh until after the first frame.
- **Uniform Theme Picker Segmented Control**: Replaced uneven top chips with a uniform 48dp Material 3 Segmented Control (`System` | `Light` | `Dark`) with crisp vector icons, single-line auto-sizing labels, and full Material You / Luxury Dark / RTL support.
- **Changelog Header Cleanup**: Removed the duplicated top version pill on the Changelog screen while keeping version numbers inside each entry.

## v90.13 (2026-11-21)
- **Icon-Only Material 3 Bottom Navigation**: Replaced truncated bottom-bar text labels with consistent 24dp filled/outlined Material vector icons (Video, Audio, Private, IPTV), themed pill indicator with smooth selection animation, >=48dp touch targets, TalkBack contentDescriptions, and long-press tooltips.
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
