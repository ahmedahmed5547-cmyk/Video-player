# Privacy Policy — VEXO Player: IPTV and Video player (`com.pureplayer.video`)
**Last Updated:** November 18, 2026  
**Developer:** Ahmed M Moumen  
**Contact Email:** [AHMEDAHMED5547@GMAIL.COM](mailto:ahmedahmed5547@gmail.com)

---

## 1. Overview
**VEXO Player — IPTV and Video player** (`com.pureplayer.video`) is a local media and playlist player for Android. We respect your privacy and operate on a strict **100% local-first privacy model**. We do not collect, store on external servers, sell, or share any personal or sensitive user data.

## 2. Permissions Used & Purpose
- **`READ_MEDIA_VIDEO` / `READ_MEDIA_VISUAL_USER_SELECTED` / `READ_EXTERNAL_STORAGE` (maxSdkVersion 32):** Required as core functionality to index, organize, and play video files and folders stored locally on the user's device. Media file lists never leave the device.
- **`USE_BIOMETRIC` / `USE_FINGERPRINT`:** Used strictly on-device via `AndroidKeyStore` to authenticate access to user-locked folders in the Private Vault. Biometric data is handled exclusively by the Android OS hardware and is never accessed, transmitted, or stored by the app.
- **`INTERNET` & `ACCESS_NETWORK_STATE`:** Used only when the user explicitly streams a user-provided network URL/playlist or searches for subtitles from public subtitle APIs.

## 3. Data Storage, Encryption & Backup Exclusion
- **Private Vault PIN:** Hashed locally using `PBKDF2WithHmacSHA256` with `>= 100,000` iterations and a 16-byte random cryptographic salt.
- **Playlist Credentials:** Encrypted locally using `AES-256-GCM` backed by hardware `AndroidKeyStore` (`pure_player_iptv_cred_aes_v1`).
- **Cloud Backup Exclusion:** All sensitive databases, caches, and preferences are explicitly excluded from Google Cloud Auto-Backup and device-to-device transfer in `backup_rules.xml` and `data_extraction_rules.xml`.

## 4. Content Disclaimer
VEXO Player (IPTV and Video player) does not provide, host, bundle, or distribute any media content or playlists. Users are solely responsible for the local videos or playlists they choose to play.

## 5. Contact Us
If you have any questions or inquiries regarding this Privacy Policy or data protection practices, please contact the developer directly at:  
**Email:** [AHMEDAHMED5547@GMAIL.COM](mailto:ahmedahmed5547@gmail.com)
