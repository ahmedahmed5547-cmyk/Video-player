package com.pureplayer.video

import android.content.Context
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat
import android.graphics.Typeface
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinCryptoHelper {
    private const val PREFIX = "v2:"
    const val MIN_ITERATIONS = 100_000
    private const val DEFAULT_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun isHashedFormat(stored: String): Boolean = stored.startsWith(PREFIX)

    fun hashPin(pin: String, iterations: Int = DEFAULT_ITERATIONS): String {
        require(iterations >= MIN_ITERATIONS) { "PBKDF2 iterations must be >= $MIN_ITERATIONS" }
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2(pin.toCharArray(), salt, iterations)
        return "$PREFIX$iterations:${bytesToHex(salt)}:${bytesToHex(hash)}"
    }

    fun verifyPinHash(inputPin: String, stored: String): Boolean {
        return try {
            if (!stored.startsWith(PREFIX)) return false
            val parts = stored.split(":")
            if (parts.size != 4) return false
            val iter = parts[1].toIntOrNull() ?: return false
            if (iter < MIN_ITERATIONS) return false
            val salt = hexToBytes(parts[2]) ?: return false
            if (salt.size < SALT_BYTES) return false
            val expectedHash = hexToBytes(parts[3]) ?: return false
            val actualHash = pbkdf2(inputPin.toCharArray(), salt, iter)
            MessageDigest.isEqual(expectedHash, actualHash)
        } catch (e: Exception) {
            runCatching { Log.w("PinCryptoHelper", "PIN hash verification failed: ${e.javaClass.simpleName}") }
            false
        }
    }

    fun verifyAndMigratePin(
        inputPin: String,
        storedValue: String,
        onMigratedHash: (String) -> Unit
    ): Boolean {
        val trimmedInput = inputPin.trim()
        if (storedValue.isBlank() || trimmedInput.isEmpty()) return false
        if (isHashedFormat(storedValue)) {
            return verifyPinHash(trimmedInput, storedValue)
        }
        val matched = MessageDigest.isEqual(
            storedValue.toByteArray(Charsets.UTF_8),
            trimmedInput.toByteArray(Charsets.UTF_8)
        )
        if (matched) {
            onMigratedHash(hashPin(trimmedInput, DEFAULT_ITERATIONS))
        }
        return matched
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
            val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun pbkdf2(chars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(chars, salt, iterations, KEY_LENGTH_BITS)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }
}

object PlaybackStore {
    private const val PREF_NAME = "pure_video_playback_store"
    private const val KEY_LAST_URI = "last_video_uri"
    private const val KEY_LAST_TITLE = "last_video_title"
    private const val KEY_LAST_POSITION = "last_video_position"
    private const val PREFIX_POS = "pos_"
    private const val PREFIX_SUB_PATH = "sub_cache_"
    private const val PREFIX_SUB_SYNC = "sub_sync_"

    private const val KEY_APP_LANG_TAG = "app_language_tag"
    private const val KEY_SUB_LANGS_CSV = "preferred_sub_langs_csv"
    private const val KEY_THEME_INDEX = "app_theme_index"
    private const val KEY_APPEARANCE_MODE = "app_appearance_mode_v1"
    private const val KEY_APPEARANCE_MIGRATED_V2 = "app_appearance_migrated_v2"
    private const val KEY_FIRST_SCAN_DONE = "vexo_first_library_scan_done_v1"
    private const val KEY_SUB_POS_LOCKED = "vexo_sub_position_locked_v1"
    private const val PREFIX_SAVED_SUB_URI = "vexo_saved_sub_uri_"
    private const val KEY_AUTO_TRANSLATE_ENABLED = "vexo_auto_translate_enabled_v1"
    private const val KEY_AUTO_TRANSLATE_ASK = "vexo_auto_translate_ask_before_v1"
    private const val KEY_AUTO_TRANSLATE_TARGET_LANG = "vexo_auto_translate_target_lang_v1"
    private const val KEY_AUTO_TRANSLATE_PROVIDER = "vexo_auto_translate_provider_v1"
    private const val KEY_AUTO_TRANSLATE_API_KEY = "vexo_auto_translate_api_key_v1"
    private const val KEY_AUTO_TRANSLATE_ARABIC_STYLE = "vexo_auto_translate_arabic_style_v1"
    private const val KEY_SUB_BILINGUAL = "vexo_sub_bilingual_v1"
    private const val KEY_APP_ICON_STYLE = "vexo_app_icon_style_v1"
    private const val KEY_LAST_ACTIVE_FOLDER = "last_active_folder_name_v1"
    private const val KEY_AUDIO_PRESET_MODE = "audio_eq_preset_mode_v1"
    private const val KEY_AUDIO_PREAMP_DB = "audio_eq_preamp_db_v1"
    private const val KEY_AUDIO_BANDS_CSV = "audio_eq_5bands_csv_v1"
    private const val KEY_DECODER_MODE_IDX = "video_decoder_mode_idx_v1"
    private const val KEY_AUTO_PIP_ENABLED = "auto_pip_on_exit_enabled_v1"
    private const val KEY_ALLOW_UNTRUSTED_SSL = "iptv_allow_untrusted_ssl_v1"
    private const val PREFIX_META_TS = "meta_ts_"
    private const val MAX_STORED_VIDEO_KEYS = 500
    private const val MAX_KEY_AGE_MS = 90L * 24L * 60L * 60L * 1000L
    private const val KEY_SUB_SIZE_SP = "sub_size_sp"
    private const val KEY_SUB_COLOR_IDX = "sub_color_index"
    private const val KEY_SUB_BG_OPACITY = "sub_bg_opacity_pct"
    private const val KEY_SUB_TEXT_OPACITY = "sub_text_opacity_pct"
    private const val KEY_SUB_OUTLINE_PX = "sub_outline_px"
    private const val KEY_SUB_FONT_IDX = "sub_font_index"
    private const val KEY_SUB_POS_PCT_X = "sub_pos_pct_x"
    private const val KEY_SUB_POS_PCT_Y = "sub_pos_pct_y"
    private const val KEY_DEFAULT_ORIENTATION = "default_orientation_idx"
    private const val KEY_HDR_MODE_IDX = "hdr_mode_idx"
    private const val KEY_PRIVACY_PIN = "privacy_pin_code"
    private const val KEY_PIN_FAILED_ATTEMPTS = "privacy_pin_failed_attempts_v1"
    private const val KEY_PIN_LOCKOUT_UNTIL_MS = "privacy_pin_lockout_until_ms_v1"
    private const val MAX_PIN_FAILED_ATTEMPTS = 5
    private const val PIN_LOCKOUT_DURATION_MS = 30_000L
    private const val KEY_PRIVACY_BIOMETRIC = "privacy_biometric_enabled"
    private const val KEY_BIOMETRIC_PWD_VERIFIED = "privacy_bio_pwd_verified"
    private const val KEY_LOCKED_FOLDERS = "privacy_locked_folders_set"
    private const val KEY_FOLDER_FILTER_MODE = "folder_filter_mode"
    private const val KEY_ALLOWED_FOLDERS = "allowed_visible_folders_set"
    private const val KEY_GITHUB_REPO = "github_repo_slug"
    private const val KEY_MEDIA_LAYOUT_GRID = "media_layout_is_grid"
    private const val KEY_SORT_ORDER_MODE = "media_sort_order_mode"
    private const val KEY_OPENED_VIDEO_URIS = "opened_video_uris_set"
        private const val KEY_KNOWN_VIDEO_IDS = "known_video_ids_set"
    private const val KEY_NEW_VIDEO_IDS = "newly_discovered_video_ids_set"
    private const val PREFIX_FOLDER_LAST_URI = "folder_last_played_uri_"
    private const val KEY_RESUME_ASK_MODE = "video_resume_ask_mode_v1" 

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveProgress(context: Context, uri: Uri, title: String, positionMs: Long, durationMs: Long) {
        markVideoOpened(context, uri)
        val uriKey = uri.toString()
        val now = System.currentTimeMillis()
        val safePos = if (durationMs > 0 && positionMs >= durationMs - 4000L) 0L else positionMs
        prefs(context).edit()
            .putLong(PREFIX_POS + uriKey, safePos)
            .putLong(PREFIX_META_TS + uriKey, now)
            .putString(KEY_LAST_URI, uriKey)
            .putString(KEY_LAST_TITLE, title)
            .putLong(KEY_LAST_POSITION, safePos)
            .apply()
    }

    fun savePosition(context: Context, uri: Uri, posMs: Long) {
        val uriKey = uri.toString()
        prefs(context).edit()
            .putLong(PREFIX_POS + uriKey, posMs)
            .putLong(PREFIX_META_TS + uriKey, System.currentTimeMillis())
            .apply()
    }

    fun getLastPlayedUri(context: Context): Uri? = getLastPlayedVideo(context)?.first

    fun applyAppearanceMode(context: Context) {
        val nightMode = when (getAppearanceMode(context)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun applySavedAppLanguage(context: Context) {
        val tag = getAppLanguageTag(context)
        val localeList = if (tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun getSavedPosition(context: Context, uri: Uri): Long {
        return prefs(context).getLong(PREFIX_POS + uri.toString(), 0L)
    }

    fun getLastPlayedVideo(context: Context): Triple<Uri, String, Long>? {
        val p = prefs(context)
        val uriStr = p.getString(KEY_LAST_URI, null) ?: return null
        val title = p.getString(KEY_LAST_TITLE, "Video") ?: "Video"
        val pos = p.getLong(KEY_LAST_POSITION, 0L)
        if (pos <= 2000L) return null
        return Triple(Uri.parse(uriStr), title, pos)
    }

    fun saveVideoSubtitleCachePath(context: Context, videoUri: Uri, path: String) {
        val uriKey = videoUri.toString()
        prefs(context).edit()
            .putString(PREFIX_SUB_PATH + uriKey, path)
            .putLong(PREFIX_META_TS + uriKey, System.currentTimeMillis())
            .apply()
    }

    fun getVideoSubtitleCachePath(context: Context, videoUri: Uri): String? {
        return prefs(context).getString(PREFIX_SUB_PATH + videoUri.toString(), null)
    }

    fun saveVideoSubtitleSyncMs(context: Context, videoUri: Uri, offsetMs: Long) {
        prefs(context).edit().putLong(PREFIX_SUB_SYNC + videoUri.toString(), offsetMs).apply()
    }

    fun getVideoSubtitleSyncMs(context: Context, videoUri: Uri): Long {
        return prefs(context).getLong(PREFIX_SUB_SYNC + videoUri.toString(), 0L)
    }

    fun getAppLanguageTag(context: Context): String =
        prefs(context).getString(KEY_APP_LANG_TAG, "") ?: ""

    fun setAppLanguageTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_APP_LANG_TAG, tag).apply()
        val localeList = if (tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun getPreferredSubtitleLanguages(context: Context): List<String> {
        val devLang = Locale.getDefault().language.lowercase(Locale.ROOT).ifBlank { "ar" }
        val defaultCsv = if (devLang != "ar" && devLang != "en") "$devLang,ar,en" else "ar,en"
        val csv = prefs(context).getString(KEY_SUB_LANGS_CSV, defaultCsv) ?: defaultCsv
        return csv.split(",").map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.ifEmpty { listOf(devLang, "ar", "en").distinct() }
    }

    fun setPreferredSubtitleLanguages(context: Context, langs: List<String>) {
        val clean = langs.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.ifEmpty { listOf("ar", "en") }
        prefs(context).edit().putString(KEY_SUB_LANGS_CSV, clean.joinToString(",")).apply()
    }

    const val APPEARANCE_DARK = 0
    const val APPEARANCE_LIGHT = 1
    const val APPEARANCE_SYSTEM = 2
    private const val LEGACY_APPEARANCE_LUXURY_DARK = 3

    const val THEME_AMOLED_GOLD = 1
    const val THEME_MATERIAL_YOU = 5
    const val THEME_LUXURY_GOLD = 8
    const val THEME_LUXURY_DARK = 9

    fun isDarkOnlyTheme(themeIndex: Int): Boolean {
        return themeIndex == THEME_AMOLED_GOLD || themeIndex == THEME_LUXURY_DARK
    }

    fun isMaterialYouSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun isMaterialYouSelected(context: Context): Boolean {
        return isMaterialYouSupported() && getThemeIndex(context) == THEME_MATERIAL_YOU
    }

    fun migrateLegacyAppearanceIfNeeded(context: Context) {
        val p = prefs(context)
        val rawMode = p.getInt(KEY_APPEARANCE_MODE, APPEARANCE_DARK)
        if (rawMode == LEGACY_APPEARANCE_LUXURY_DARK) {
            p.edit()
                .putInt(KEY_APPEARANCE_MODE, APPEARANCE_DARK)
                .putInt(KEY_THEME_INDEX, THEME_LUXURY_DARK)
                .putBoolean(KEY_APPEARANCE_MIGRATED_V2, true)
                .apply()
            return
        }
        if (!p.getBoolean(KEY_APPEARANCE_MIGRATED_V2, false)) {
            val editor = p.edit().putBoolean(KEY_APPEARANCE_MIGRATED_V2, true)
            if (rawMode == APPEARANCE_SYSTEM && isMaterialYouSupported()) {
                editor.putInt(KEY_THEME_INDEX, THEME_MATERIAL_YOU)
            }
            editor.apply()
        }
    }

    // 0 = Dark, 1 = Light, 2 = Follow System (brightness only)
    fun getAppearanceMode(context: Context): Int {
        migrateLegacyAppearanceIfNeeded(context)
        return prefs(context).getInt(KEY_APPEARANCE_MODE, APPEARANCE_DARK).coerceIn(0, 2)
    }

    fun setAppearanceMode(context: Context, mode: Int) {
        prefs(context).edit()
            .putInt(KEY_APPEARANCE_MODE, mode.coerceIn(0, 2))
            .putBoolean(KEY_APPEARANCE_MIGRATED_V2, true)
            .apply()
        applyAppearanceMode(context)
    }

    fun isRequestedBrightnessLight(context: Context): Boolean {
        return when (getAppearanceMode(context)) {
            APPEARANCE_LIGHT -> true
            APPEARANCE_SYSTEM -> {
                val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMask != Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }

    fun isLightThemeActive(context: Context): Boolean {
        if (isDarkOnlyTheme(getThemeIndex(context))) return false
        return isRequestedBrightnessLight(context)
    }

    fun getThemeIndex(context: Context): Int {
        migrateLegacyAppearanceIfNeeded(context)
        val idx = prefs(context).getInt(KEY_THEME_INDEX, 0).coerceIn(0, 9)
        if (idx == THEME_MATERIAL_YOU && !isMaterialYouSupported()) return 0
        return idx
    }

    fun setThemeIndex(context: Context, index: Int) {
        val safeIdx = index.coerceIn(0, 9)
        if (safeIdx == THEME_MATERIAL_YOU && !isMaterialYouSupported()) return
        prefs(context).edit().putInt(KEY_THEME_INDEX, safeIdx).apply()
    }

    fun getDynamicSystemAccentHex(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val resId = if (isLightThemeActive(context)) {
                    android.R.color.system_accent1_600
                } else {
                    android.R.color.system_accent1_300
                }
                val c = ContextCompat.getColor(context, resId)
                return String.format(Locale.US, "#%06X", 0xFFFFFF and c)
            } catch (e: Exception) { Log.w("PlaybackStore", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
        return if (isLightThemeActive(context)) "#4F46E5" else "#818CF8"
    }

    fun getAccentColor(context: Context): Int {
        val isLight = isLightThemeActive(context)
        return when (getThemeIndex(context)) {
            1 -> Color.parseColor("#F59E0B")
            2 -> Color.parseColor(if (isLight) "#059669" else "#10B981")
            3 -> Color.parseColor(if (isLight) "#E11D48" else "#F43F5E")
            4 -> Color.parseColor(if (isLight) "#7C3AED" else "#A855F7")
            5 -> Color.parseColor(getDynamicSystemAccentHex(context))
            6 -> Color.parseColor(if (isLight) "#EA580C" else "#FB923C")
            7 -> Color.parseColor(if (isLight) "#0D9488" else "#14B8A6")
            8 -> Color.parseColor(if (isLight) "#B4861C" else "#D4AF37")
            9 -> Color.parseColor("#D4AF37")
            else -> Color.parseColor(if (isLight) "#0284C7" else "#38BDF8")
        }
    }

    fun getBackgroundColor(context: Context): Int {
        val isLight = isLightThemeActive(context)
        val idx = getThemeIndex(context)
        if (idx == THEME_MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ContextCompat.getColor(
                    context,
                    if (isLight) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900
                )
            } catch (_: Exception) {}
        }
        if (isLight) {
            return Color.parseColor("#F1F5F9")
        }
        return when (idx) {
            1 -> Color.parseColor("#000000")
            9 -> Color.parseColor("#07070B")
            else -> Color.parseColor("#0A0A0F")
        }
    }

    fun getSurfaceColor(context: Context): Int {
        val isLight = isLightThemeActive(context)
        val idx = getThemeIndex(context)
        if (idx == THEME_MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ContextCompat.getColor(
                    context,
                    if (isLight) android.R.color.system_neutral1_0 else android.R.color.system_neutral1_800
                )
            } catch (_: Exception) {}
        }
        if (isLight) {
            return Color.parseColor("#FFFFFF")
        }
        return when (idx) {
            1 -> Color.parseColor("#111111")
            9 -> Color.parseColor("#111118")
            else -> Color.parseColor("#12121A")
        }
    }

    fun getPrimaryTextColor(context: Context): Int {
        return if (isLightThemeActive(context)) Color.parseColor("#0F172A") else Color.WHITE
    }

    fun getSecondaryTextColor(context: Context): Int {
        return if (isLightThemeActive(context)) Color.parseColor("#475569") else Color.parseColor("#94A3B8")
    }

    fun getLastActiveFolderName(context: Context): String? {
        return prefs(context).getString(KEY_LAST_ACTIVE_FOLDER, null)
    }

    fun setLastActiveFolderName(context: Context, folderName: String) {
        if (folderName.isNotBlank()) {
            prefs(context).edit().putString(KEY_LAST_ACTIVE_FOLDER, folderName).apply()
        }
    }

    // Audio Preset Modes:
    // 0 = Standard (Flat), 1 = Cinematic, 2 = Concert Hall, 3 = Voice Clarity,
    // 4 = Deep Bass, 5 = Live Stadium, 6 = Night Mode, 7 = Custom Manual EQ
    fun getAudioPresetMode(context: Context): Int =
        prefs(context).getInt(KEY_AUDIO_PRESET_MODE, 0).coerceIn(0, 7)

    fun setAudioPresetMode(context: Context, mode: Int) {
        val clamped = mode.coerceIn(0, 7)
        val editor = prefs(context).edit().putInt(KEY_AUDIO_PRESET_MODE, clamped)
        if (clamped != 7) {
            val (preAmp, bands) = getPresetCurveForMode(clamped)
            editor.putFloat(KEY_AUDIO_PREAMP_DB, preAmp)
            editor.putString(KEY_AUDIO_BANDS_CSV, bands.joinToString(","))
        }
        editor.apply()
    }

    fun getPresetCurveForMode(mode: Int): Pair<Float, FloatArray> {
        return when (mode) {
            1 -> Pair(2.5f, floatArrayOf(6.0f, 4.5f, -1.0f, 3.5f, 5.0f))   // Cinematic Surround
            2 -> Pair(1.5f, floatArrayOf(3.5f, 3.0f, 1.5f, 2.5f, 4.5f))    // Concert Hall
            3 -> Pair(3.0f, floatArrayOf(-3.0f, -1.5f, 4.0f, 7.5f, 3.0f))  // Voice & Dialogue Clarity
            4 -> Pair(2.0f, floatArrayOf(8.5f, 6.5f, 0.0f, 1.5f, 2.0f))    // Deep Bass Boost
            5 -> Pair(2.5f, floatArrayOf(3.0f, 2.0f, 3.5f, 5.0f, 4.0f))    // Live Stadium
            6 -> Pair(4.0f, floatArrayOf(-5.0f, -2.5f, 3.5f, 6.0f, -1.0f)) // Night Comfort Mode
            else -> Pair(0.0f, floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)) // Standard (Flat)
        }
    }

    fun getAudioPreAmpDb(context: Context): Float =
        prefs(context).getFloat(KEY_AUDIO_PREAMP_DB, 0.0f).coerceIn(-10.0f, 12.0f)

    fun setAudioPreAmpDb(context: Context, db: Float) {
        prefs(context).edit()
            .putFloat(KEY_AUDIO_PREAMP_DB, db.coerceIn(-10.0f, 12.0f))
            .putInt(KEY_AUDIO_PRESET_MODE, 7)
            .apply()
    }

    fun getAudioEqBandsDb(context: Context): FloatArray {
        val raw = prefs(context).getString(KEY_AUDIO_BANDS_CSV, null)
        if (raw.isNullOrBlank()) {
            return getPresetCurveForMode(getAudioPresetMode(context)).second
        }
        val parts = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
        return if (parts.size == 5) {
            FloatArray(5) { idx -> parts[idx].coerceIn(-15.0f, 15.0f) }
        } else {
            getPresetCurveForMode(getAudioPresetMode(context)).second
        }
    }

    fun setAudioEqBandsDb(context: Context, bands: FloatArray, preAmpDb: Float) {
        val safeBands = FloatArray(5) { idx -> (bands.getOrNull(idx) ?: 0f).coerceIn(-15.0f, 15.0f) }
        prefs(context).edit()
            .putString(KEY_AUDIO_BANDS_CSV, safeBands.joinToString(","))
            .putFloat(KEY_AUDIO_PREAMP_DB, preAmpDb.coerceIn(-10.0f, 12.0f))
            .putInt(KEY_AUDIO_PRESET_MODE, 7)
            .apply()
    }


    // 0 = HW+ (Default), 1 = HW, 2 = SW (Software CPU)
    fun getDecoderModeIndex(context: Context): Int =
        prefs(context).getInt(KEY_DECODER_MODE_IDX, 0).coerceIn(0, 2)

    fun setDecoderModeIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_DECODER_MODE_IDX, index.coerceIn(0, 2)).apply()
    }

    fun isAutoPipEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_PIP_ENABLED, true)

    fun setAutoPipEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_PIP_ENABLED, enabled).apply()
    }

    fun resetAudioEqualizerToStandard(context: Context) {
        prefs(context).edit()
            .putInt(KEY_AUDIO_PRESET_MODE, 0)
            .putFloat(KEY_AUDIO_PREAMP_DB, 0.0f)
            .putString(KEY_AUDIO_BANDS_CSV, "0.0,0.0,0.0,0.0,0.0")
            .apply()
    }


    fun getSubSizeSp(context: Context): Float =
        prefs(context).getFloat(KEY_SUB_SIZE_SP, 22f).coerceIn(12f, 46f)

    fun setSubSizeSp(context: Context, sizeSp: Float) {
        prefs(context).edit().putFloat(KEY_SUB_SIZE_SP, sizeSp.coerceIn(12f, 46f)).apply()
    }

    fun getSubColorIndex(context: Context): Int = prefs(context).getInt(KEY_SUB_COLOR_IDX, 0)
    fun setSubColorIndex(context: Context, idx: Int) {
        prefs(context).edit().putInt(KEY_SUB_COLOR_IDX, idx).apply()
    }

    fun getSubTextOpacityPct(context: Context): Int =
        prefs(context).getInt(KEY_SUB_TEXT_OPACITY, 100).coerceIn(20, 100)

    fun setSubTextOpacityPct(context: Context, pct: Int) {
        prefs(context).edit().putInt(KEY_SUB_TEXT_OPACITY, pct.coerceIn(20, 100)).apply()
    }

    fun getSubColorValue(context: Context): Int {
        val baseColor = when (getSubColorIndex(context)) {
            1 -> Color.parseColor("#FACC15")
            2 -> Color.parseColor("#38BDF8")
            3 -> Color.parseColor("#4ADE80")
            else -> Color.WHITE
        }
        val alpha = ((getSubTextOpacityPct(context) / 100.0) * 255).toInt().coerceIn(50, 255)
        return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
    }

    fun getSubBgOpacityPct(context: Context): Int =
        prefs(context).getInt(KEY_SUB_BG_OPACITY, 55).coerceIn(0, 100)

    fun setSubBgOpacityPct(context: Context, pct: Int) {
        prefs(context).edit().putInt(KEY_SUB_BG_OPACITY, pct.coerceIn(0, 100)).apply()
    }

    fun getSubBackgroundColor(context: Context): Int {
        val pct = getSubBgOpacityPct(context)
        val alpha = ((pct / 100.0) * 255).toInt().coerceIn(0, 255)
        return Color.argb(alpha, 0, 0, 0)
    }

    fun getSubOutlinePx(context: Context): Float =
        prefs(context).getFloat(KEY_SUB_OUTLINE_PX, 4f).coerceIn(0f, 12f)

    fun setSubOutlinePx(context: Context, px: Float) {
        prefs(context).edit().putFloat(KEY_SUB_OUTLINE_PX, px.coerceIn(0f, 12f)).apply()
    }

    fun getSubTypeface(context: Context): Typeface {
        return when (prefs(context).getInt(KEY_SUB_FONT_IDX, 0)) {
            1 -> Typeface.SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }

    fun saveSubPositionPercent(context: Context, pctX: Float, pctY: Float) {
        prefs(context).edit()
            .putFloat(KEY_SUB_POS_PCT_X, pctX)
            .putFloat(KEY_SUB_POS_PCT_Y, pctY)
            .apply()
    }

    fun getSubPositionPercentX(context: Context): Float =
        prefs(context).getFloat(KEY_SUB_POS_PCT_X, 0f)

    fun getSubPositionPercentY(context: Context): Float =
        prefs(context).getFloat(KEY_SUB_POS_PCT_Y, 0f)

    fun resetSubPosition(context: Context) {
        prefs(context).edit()
            .putFloat(KEY_SUB_POS_PCT_X, 0f)
            .putFloat(KEY_SUB_POS_PCT_Y, 0f)
            .apply()
    }

    fun getDefaultOrientationIndex(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_ORIENTATION, 1).coerceIn(0, 2)

    fun setDefaultOrientationIndex(context: Context, idx: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_ORIENTATION, idx.coerceIn(0, 2)).apply()
    }

    fun hasPrivacyPin(context: Context): Boolean =
        !prefs(context).getString(KEY_PRIVACY_PIN, "").isNullOrBlank()

    fun getPinLockoutRemainingSeconds(context: Context): Int {
        val untilMs = prefs(context).getLong(KEY_PIN_LOCKOUT_UNTIL_MS, 0L)
        val diffMs = untilMs - System.currentTimeMillis()
        return if (diffMs > 0L) ((diffMs + 999L) / 1000L).toInt() else 0
    }

    fun verifyPrivacyPin(context: Context, inputPin: String): Boolean {
        if (getPinLockoutRemainingSeconds(context) > 0) return false
        val sp = prefs(context)
        val saved = sp.getString(KEY_PRIVACY_PIN, "") ?: ""
        val ok = PinCryptoHelper.verifyAndMigratePin(inputPin, saved) { newHash ->
            sp.edit().putString(KEY_PRIVACY_PIN, newHash).apply()
        }
        if (ok) {
            sp.edit()
                .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
                .putLong(KEY_PIN_LOCKOUT_UNTIL_MS, 0L)
                .apply()
        } else {
            val attempts = sp.getInt(KEY_PIN_FAILED_ATTEMPTS, 0) + 1
            val ed = sp.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, attempts)
            if (attempts >= MAX_PIN_FAILED_ATTEMPTS) {
                ed.putLong(KEY_PIN_LOCKOUT_UNTIL_MS, System.currentTimeMillis() + PIN_LOCKOUT_DURATION_MS)
                ed.putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            }
            ed.apply()
        }
        return ok
    }

    fun setPrivacyPin(context: Context, newPin: String) {
        val trimmed = newPin.trim()
        if (trimmed.length < 4) return
        val hashed = PinCryptoHelper.hashPin(trimmed)
        prefs(context).edit()
            .putString(KEY_PRIVACY_PIN, hashed)
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCKOUT_UNTIL_MS, 0L)
            .apply()
    }

    fun clearPrivacyPinAndLocks(context: Context) {
        prefs(context).edit()
            .remove(KEY_PRIVACY_PIN)
            .remove(KEY_LOCKED_FOLDERS)
            .remove(KEY_PIN_FAILED_ATTEMPTS)
            .remove(KEY_PIN_LOCKOUT_UNTIL_MS)
            .putBoolean(KEY_BIOMETRIC_PWD_VERIFIED, false)
            .apply()
    }

    fun isAllowUntrustedSsl(context: Context): Boolean {
        val allowed = prefs(context).getBoolean(KEY_ALLOW_UNTRUSTED_SSL, false)
        XtreamCodesClient.allowUntrustedSslFallback = allowed
        return allowed
    }

    fun setAllowUntrustedSsl(context: Context, allow: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_UNTRUSTED_SSL, allow).apply()
        XtreamCodesClient.allowUntrustedSslFallback = allow
    }

    fun pruneOldVideoKeys(context: Context) {
        try {
            val sp = prefs(context)
            val all = sp.all
            val now = System.currentTimeMillis()
            val lastUri = sp.getString(KEY_LAST_URI, "") ?: ""

            val trackedPrefixes = listOf(PREFIX_POS, PREFIX_SUB_PATH, PREFIX_SUB_SYNC)
            val entriesBySuffix = linkedMapOf<String, Long>()

            for ((k, _) in all) {
                val matchedPrefix = trackedPrefixes.firstOrNull { k.startsWith(it) } ?: continue
                val suffix = k.removePrefix(matchedPrefix)
                if (lastUri.isNotEmpty() && suffix == lastUri) continue
                val ts = sp.getLong(PREFIX_META_TS + suffix, now)
                entriesBySuffix[suffix] = ts
            }

            val toRemoveSuffixes = mutableSetOf<String>()
            for ((suffix, ts) in entriesBySuffix) {
                if (now - ts > MAX_KEY_AGE_MS) {
                    toRemoveSuffixes.add(suffix)
                }
            }
            if (entriesBySuffix.size - toRemoveSuffixes.size > MAX_STORED_VIDEO_KEYS) {
                val remaining = entriesBySuffix.entries
                    .filter { it.key !in toRemoveSuffixes }
                    .sortedBy { it.value }
                val excess = remaining.size - MAX_STORED_VIDEO_KEYS
                for (i in 0 until excess) {
                    toRemoveSuffixes.add(remaining[i].key)
                }
            }

            if (toRemoveSuffixes.isNotEmpty()) {
                val ed = sp.edit()
                for (suffix in toRemoveSuffixes) {
                    ed.remove(PREFIX_POS + suffix)
                    ed.remove(PREFIX_SUB_PATH + suffix)
                    ed.remove(PREFIX_SUB_SYNC + suffix)
                    ed.remove(PREFIX_META_TS + suffix)
                }
                ed.apply()
            }
        } catch (e: Exception) {
            Log.w("PlaybackStore", "Failed to prune old video keys", e)
        }
    }

    fun isBiometricPasswordVerified(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC_PWD_VERIFIED, false)

    fun setBiometricPasswordVerified(context: Context, verified: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_PWD_VERIFIED, verified).apply()
    }

    fun isBiometricEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRIVACY_BIOMETRIC, true)

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRIVACY_BIOMETRIC, enabled).apply()
    }

    

    fun isSubtitlePositionLocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUB_POS_LOCKED, false)

    fun setSubtitlePositionLocked(context: Context, locked: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUB_POS_LOCKED, locked).apply()
    }

    fun saveVideoSubtitleUri(context: Context, videoUri: Uri, subUri: String, label: String = "") {
        prefs(context).edit()
            .putString(PREFIX_SAVED_SUB_URI + videoUri.toString().hashCode(), subUri)
            .putString(PREFIX_SAVED_SUB_URI + "lbl_" + videoUri.toString().hashCode(), label)
            .apply()
    }

    fun getVideoSubtitleUri(context: Context, videoUri: Uri): Pair<String, String>? {
        val u = prefs(context).getString(PREFIX_SAVED_SUB_URI + videoUri.toString().hashCode(), null) ?: return null
        if (u.isBlank()) return null
        val lbl = prefs(context).getString(PREFIX_SAVED_SUB_URI + "lbl_" + videoUri.toString().hashCode(), "") ?: ""
        return u to lbl
    }

    fun isAutoTranslateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_TRANSLATE_ENABLED, true)

    fun setAutoTranslateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_TRANSLATE_ENABLED, enabled).apply()
    }

    fun isAskBeforeTranslating(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_TRANSLATE_ASK, false)

    fun setAskBeforeTranslating(context: Context, ask: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_TRANSLATE_ASK, ask).apply()
    }

    fun getAutoTranslateTargetLang(context: Context): String =
        prefs(context).getString(KEY_AUTO_TRANSLATE_TARGET_LANG, "ar") ?: "ar"

    fun setAutoTranslateTargetLang(context: Context, langCode: String) {
        prefs(context).edit().putString(KEY_AUTO_TRANSLATE_TARGET_LANG, langCode.lowercase().trim()).apply()
    }

    // Providers: "free_google", "google_cloud", "deepl", "openai_llm"
    fun getAutoTranslateProvider(context: Context): String =
        prefs(context).getString(KEY_AUTO_TRANSLATE_PROVIDER, "free_google") ?: "free_google"

    fun setAutoTranslateProvider(context: Context, provider: String) {
        prefs(context).edit().putString(KEY_AUTO_TRANSLATE_PROVIDER, provider).apply()
    }

    fun getAutoTranslateApiKey(context: Context): String =
        prefs(context).getString(KEY_AUTO_TRANSLATE_API_KEY, "") ?: ""

    fun setAutoTranslateApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_AUTO_TRANSLATE_API_KEY, apiKey.trim()).apply()
    }

    // "msa" (Modern Standard Arabic) or "simple"
    fun getArabicTranslationStyle(context: Context): String =
        prefs(context).getString(KEY_AUTO_TRANSLATE_ARABIC_STYLE, "msa") ?: "msa"

    fun setArabicTranslationStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_AUTO_TRANSLATE_ARABIC_STYLE, style).apply()
    }

    fun isBilingualSubtitleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUB_BILINGUAL, false)

    fun setBilingualSubtitleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUB_BILINGUAL, enabled).apply()
    }

    fun getAppIconStyle(context: Context): String =
        prefs(context).getString(KEY_APP_ICON_STYLE, "default") ?: "default"

    fun setAppIconStyle(context: Context, styleId: String) {
        prefs(context).edit().putString(KEY_APP_ICON_STYLE, styleId).apply()
    }

    fun isFirstLibraryScanDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIRST_SCAN_DONE, false)

    fun setFirstLibraryScanDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_FIRST_SCAN_DONE, done).apply()
    }

    fun setFolderLocked(context: Context, folderName: String, locked: Boolean) {
        val updated = getLockedFolders(context).toMutableSet()
        if (locked) updated.add(folderName) else updated.remove(folderName)
        setLockedFolders(context, updated)
    }

    fun getLockedFolders(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LOCKED_FOLDERS, emptySet())?.toSet() ?: emptySet()

    fun setLockedFolders(context: Context, folders: Set<String>) {
        prefs(context).edit().putStringSet(KEY_LOCKED_FOLDERS, folders.toSet()).apply()
    }

    fun isFolderLocked(context: Context, folderName: String): Boolean =
        getLockedFolders(context).contains(folderName)

    fun getFolderFilterMode(context: Context): Int =
        prefs(context).getInt(KEY_FOLDER_FILTER_MODE, 0).coerceIn(0, 2)

    fun setFolderFilterMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_FOLDER_FILTER_MODE, mode.coerceIn(0, 2)).apply()
    }

    fun getAllowedFolders(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWED_FOLDERS, emptySet())?.toSet() ?: emptySet()

    fun setAllowedFolders(context: Context, folders: Set<String>) {
        prefs(context).edit().putStringSet(KEY_ALLOWED_FOLDERS, folders.toSet()).apply()
    }

    fun getGitHubRepo(context: Context): String =
        prefs(context).getString(KEY_GITHUB_REPO, "ahmedahmed5547-cmyk/Video-player") ?: "ahmedahmed5547-cmyk/Video-player"

    fun setGitHubRepo(context: Context, repoSlug: String) {
        prefs(context).edit().putString(KEY_GITHUB_REPO, repoSlug.trim()).apply()
    }

    fun isGridLayoutEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MEDIA_LAYOUT_GRID, false)

    fun setGridLayoutEnabled(context: Context, isGrid: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEDIA_LAYOUT_GRID, isGrid).apply()
    }

    fun getSortOrderMode(context: Context): Int =
        prefs(context).getInt(KEY_SORT_ORDER_MODE, 0).coerceIn(0, 6)

    fun setSortOrderMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_SORT_ORDER_MODE, mode.coerceIn(0, 6)).apply()
    }

    fun markVideoOpened(context: Context, uri: Uri) {
        val p = prefs(context)
        val opened = p.getStringSet(KEY_OPENED_VIDEO_URIS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val uriStr = uri.toString()
        if (opened.add(uriStr)) {
            p.edit().putStringSet(KEY_OPENED_VIDEO_URIS, opened).apply()
        }
    }

    fun hasVideoBeenOpened(context: Context, uri: Uri): Boolean {
        val opened = prefs(context).getStringSet(KEY_OPENED_VIDEO_URIS, emptySet()) ?: emptySet()
        return opened.contains(uri.toString())
    }

    fun setLastPlayedVideoInFolder(context: Context, folderName: String, uri: Uri) {
        if (folderName.isBlank()) return
        prefs(context).edit()
            .putString(PREFIX_FOLDER_LAST_URI + folderName, uri.toString())
            .apply()
    }

    fun getLastPlayedVideoInFolder(context: Context, folderName: String): String? {
        if (folderName.isBlank()) return null
        return prefs(context).getString(PREFIX_FOLDER_LAST_URI + folderName, null)
    }

    /**
     * 0 = Ask every time (default)
     * 1 = Always Resume from where stopped automatically
     * 2 = Always Start from Beginning (00:00) automatically
     */
    fun getResumeAskMode(context: Context): Int =
        prefs(context).getInt(KEY_RESUME_ASK_MODE, 0).coerceIn(0, 2)

    fun setResumeAskMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_RESUME_ASK_MODE, mode.coerceIn(0, 2)).apply()
    }

    fun syncDiscoveredVideosForNewBadge(context: Context, currentVideoIds: Set<Long>) {
        val p = prefs(context)
        val currentStrSet = currentVideoIds.map { it.toString() }.toSet()
        val known = p.getStringSet(KEY_KNOWN_VIDEO_IDS, null)
        if (known == null) {
            // First scan on install: record baseline IDs so only recently added or future videos get NEW badge
            p.edit()
                .putStringSet(KEY_KNOWN_VIDEO_IDS, currentStrSet)
                .putStringSet(KEY_NEW_VIDEO_IDS, emptySet())
                .apply()
        } else {
            val newlyAdded = p.getStringSet(KEY_NEW_VIDEO_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            for (idStr in currentStrSet) {
                if (idStr !in known) {
                    newlyAdded.add(idStr)
                }
            }
            val updatedKnown = known.toMutableSet().apply { addAll(currentStrSet) }
            p.edit()
                .putStringSet(KEY_KNOWN_VIDEO_IDS, updatedKnown)
                .putStringSet(KEY_NEW_VIDEO_IDS, newlyAdded)
                .apply()
        }
    }

    fun isVideoNewBadgeVisible(context: Context, video: VideoItem): Boolean {
        if (hasVideoBeenOpened(context, video.uri)) return false
        val p = prefs(context)
        val newlyDiscovered = p.getStringSet(KEY_NEW_VIDEO_IDS, emptySet()) ?: emptySet()
        if (newlyDiscovered.contains(video.id.toString())) return true
        // Also mark as NEW if added to the device within the last 48 hours and never opened yet
        val nowSec = System.currentTimeMillis() / 1000L
        return video.dateAddedSec > 0L && (nowSec - video.dateAddedSec) in 0L..(48L * 3600L)
    }

    fun getHdrModeIndex(context: Context): Int =
        prefs(context).getInt(KEY_HDR_MODE_IDX, 0).coerceIn(0, 2)

    fun setHdrModeIndex(context: Context, idx: Int) {
        prefs(context).edit().putInt(KEY_HDR_MODE_IDX, idx.coerceIn(0, 2)).apply()
    }

    fun formatTime(timeMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(timeMs).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}

