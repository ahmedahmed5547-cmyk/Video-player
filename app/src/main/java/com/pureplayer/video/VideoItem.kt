package com.pureplayer.video

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.color.DynamicColors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

class PureVideoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Restore persisted app language on startup
        val savedLang = PlaybackStore.getAppLanguageTag(this)
        if (savedLang.isNotEmpty()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(savedLang))
        }
    }
}

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val duration: Long,
    val size: Long,
    val folderName: String
)

data class VideoFolder(
    val name: String,
    val videos: List<VideoItem>
)

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object PlaybackStore {
    private const val PREF_NAME = "pure_video_playback_store"
    private const val KEY_LAST_URI = "last_video_uri"
    private const val KEY_LAST_TITLE = "last_video_title"
    private const val KEY_LAST_POSITION = "last_video_position"
    private const val PREFIX_POS = "pos_"
    private const val PREFIX_SUB_PATH = "sub_cache_"
    private const val PREFIX_SUB_SYNC = "sub_sync_"

    // Global & Styling Keys
    private const val KEY_APP_LANG_TAG = "app_language_tag" // "" = system, "ar" = Arabic, "en" = English
    private const val KEY_SUB_LANGS_CSV = "preferred_sub_langs_csv" // e.g. "ar,en"
    private const val KEY_THEME_INDEX = "app_theme_index"
    private const val KEY_SUB_SIZE_SP = "sub_font_size_sp"
    private const val KEY_SUB_COLOR_INDEX = "sub_color_index"
    private const val KEY_SUB_BG_OPACITY = "sub_bg_opacity_pct" // 0..100
    private const val KEY_SUB_OUTLINE_PX = "sub_outline_px" // 0..10
    private const val KEY_SUB_X_PCT = "sub_x_percent" // -0.45f .. +0.45f (0f = centered horizontally)
    private const val KEY_SUB_Y_PCT = "sub_y_percent" // -0.82f .. +0.08f (0f = default bottom margin)
    private const val KEY_DEFAULT_ORIENTATION = "default_orientation_idx"
    private const val KEY_HDR_MODE = "hdr_color_mode_idx"

    fun saveProgress(context: Context, uri: Uri, title: String, positionMs: Long, durationMs: Long) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val uriStr = uri.toString()
        val editor = prefs.edit()

        if (durationMs > 10_000L && positionMs >= durationMs - 5_000L) {
            editor.remove(PREFIX_POS + uriStr)
        } else if (positionMs > 3_000L) {
            editor.putLong(PREFIX_POS + uriStr, positionMs)
            editor.putString(KEY_LAST_URI, uriStr)
            editor.putString(KEY_LAST_TITLE, title)
            editor.putLong(KEY_LAST_POSITION, positionMs)
        }
        editor.apply()
    }

    fun getSavedPosition(context: Context, uri: Uri): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(PREFIX_POS + uri.toString(), 0L)
    }

    fun getLastPlayedVideo(context: Context): Triple<Uri, String, Long>? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_LAST_URI, null) ?: return null
        val title = prefs.getString(KEY_LAST_TITLE, "Video") ?: "Video"
        val pos = prefs.getLong(PREFIX_POS + uriStr, prefs.getLong(KEY_LAST_POSITION, 0L))
        return Triple(Uri.parse(uriStr), title, pos)
    }

    // Per-Video Subtitle Cache & Sync Offset Persistence
    fun saveVideoSubtitleCachePath(context: Context, videoUri: Uri, cacheFilePath: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREFIX_SUB_PATH + videoUri.toString(), cacheFilePath).apply()
    }

    fun getVideoSubtitleCachePath(context: Context, videoUri: Uri): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREFIX_SUB_PATH + videoUri.toString(), null)

    fun saveVideoSubtitleSyncMs(context: Context, videoUri: Uri, syncOffsetMs: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putLong(PREFIX_SUB_SYNC + videoUri.toString(), syncOffsetMs).apply()
    }

    fun getVideoSubtitleSyncMs(context: Context, videoUri: Uri): Long =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(PREFIX_SUB_SYNC + videoUri.toString(), 0L)

    // App Language (Arabic / English / System Default)
    fun getAppLanguageTag(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_APP_LANG_TAG, "") ?: ""

    fun setAppLanguageTag(context: Context, tag: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_APP_LANG_TAG, tag).apply()
        val locales = if (tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    // Preferred Subtitle Languages (in Priority Order, e.g. ["ar", "en"])
    fun getPreferredSubtitleLanguages(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SUB_LANGS_CSV, "ar,en") ?: "ar,en"
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("ar", "en") }
    }

    fun setPreferredSubtitleLanguages(context: Context, codes: List<String>) {
        val csv = codes.ifEmpty { listOf("ar", "en") }.joinToString(",")
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUB_LANGS_CSV, csv).apply()
    }

    // Percentage-based Draggable Subtitle Coordinates (works across Portrait, Landscape & Fullscreen)
    fun getSubPositionPercentX(context: Context): Float =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getFloat(KEY_SUB_X_PCT, 0f)

    fun getSubPositionPercentY(context: Context): Float =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getFloat(KEY_SUB_Y_PCT, 0f)

    fun saveSubPositionPercent(context: Context, pctX: Float, pctY: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SUB_X_PCT, pctX.coerceIn(-0.48f, 0.48f))
            .putFloat(KEY_SUB_Y_PCT, pctY.coerceIn(-0.85f, 0.08f))
            .apply()
    }

    fun resetSubPosition(context: Context) {
        saveSubPositionPercent(context, 0f, 0f)
    }

    // Theme & Subtitle Styling
    fun getThemeIndex(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_THEME_INDEX, 0)

    fun setThemeIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_THEME_INDEX, index).apply()
    }

    fun getAccentColor(context: Context): Int {
        return when (getThemeIndex(context)) {
            0 -> Color.parseColor("#38BDF8")
            1 -> Color.parseColor("#FACC15")
            2 -> Color.parseColor("#34D399")
            3 -> Color.parseColor("#FB7185")
            else -> Color.parseColor("#A78BFA")
        }
    }

    fun getBackgroundColor(context: Context): Int {
        return when (getThemeIndex(context)) {
            1 -> Color.parseColor("#000000")
            else -> Color.parseColor("#0F172A")
        }
    }

    fun getSurfaceColor(context: Context): Int {
        return when (getThemeIndex(context)) {
            1 -> Color.parseColor("#121212")
            else -> Color.parseColor("#1E293B")
        }
    }

    fun getSubSizeSp(context: Context): Float =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getFloat(KEY_SUB_SIZE_SP, 22f)

    fun setSubSizeSp(context: Context, sizeSp: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SUB_SIZE_SP, sizeSp.coerceIn(12f, 46f)).apply()
    }

    fun getSubColorIndex(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_SUB_COLOR_INDEX, 0)

    fun setSubColorIndex(context: Context, idx: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_SUB_COLOR_INDEX, idx).apply()
    }

    fun getSubColorValue(context: Context): Int {
        return when (getSubColorIndex(context)) {
            1 -> Color.parseColor("#FACC15")
            2 -> Color.parseColor("#38BDF8")
            3 -> Color.parseColor("#4ADE80")
            else -> Color.WHITE
        }
    }

    fun getSubTypeface(context: Context): Typeface {
        return when (getSubColorIndex(context)) {
            1 -> Typeface.DEFAULT_BOLD
            2 -> Typeface.SERIF
            3 -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
    }

    fun getSubBgOpacityPct(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_SUB_BG_OPACITY, 65)

    fun setSubBgOpacityPct(context: Context, pct: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SUB_BG_OPACITY, pct.coerceIn(0, 100)).apply()
    }

    fun getSubBackgroundColor(context: Context): Int {
        val alpha = ((getSubBgOpacityPct(context) / 100.0) * 255).toInt().coerceIn(0, 255)
        return Color.argb(alpha, 0, 0, 0)
    }

    fun getSubOutlinePx(context: Context): Float =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getFloat(KEY_SUB_OUTLINE_PX, 4f)

    fun setSubOutlinePx(context: Context, px: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SUB_OUTLINE_PX, px.coerceIn(0f, 12f)).apply()
    }

    fun getDefaultOrientationIndex(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_DEFAULT_ORIENTATION, 1)

    fun setDefaultOrientationIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_DEFAULT_ORIENTATION, index).apply()
    }

    fun getHdrModeIndex(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_HDR_MODE, 0)

    fun setHdrModeIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_HDR_MODE, index).apply()
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

/**
 * Universal Subtitle Parser supporting .srt, .vtt (WebVTT), and .ass / .ssa
 * with intelligent Arabic charset detection (UTF-8, UTF-16LE/BE, Windows-1256, ISO-8859-6).
 */
object SubtitleParser {

    fun parseFromUri(context: Context, uri: Uri): List<SubtitleCue> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        return parseFromBytes(bytes)
    }

    fun parseFromFile(file: File): List<SubtitleCue> {
        if (!file.exists()) return emptyList()
        return parseFromBytes(file.readBytes())
    }

    fun decodeSubtitleBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // Check BOM for UTF-16
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, Charsets.UTF_16BE)
            }
        }

        // Try UTF-8 first
        val utf8Text = String(bytes, Charsets.UTF_8)
        if (!utf8Text.contains("\uFFFD")) {
            return utf8Text.removePrefix("\uFEFF")
        }

        // Fallback for legacy Arabic 8-bit encodings: Windows-1256 vs ISO-8859-6
        val win1256 = try { String(bytes, Charset.forName("windows-1256")) } catch (_: Exception) { utf8Text }
        val iso88596 = try { String(bytes, Charset.forName("ISO-8859-6")) } catch (_: Exception) { win1256 }

        val winArabicCount = win1256.count { it in '\u0600'..'\u06FF' }
        val isoArabicCount = iso88596.count { it in '\u0600'..'\u06FF' }

        return if (isoArabicCount > winArabicCount) iso88596 else win1256
    }

    fun parseFromBytes(bytes: ByteArray): List<SubtitleCue> {
        val content = decodeSubtitleBytes(bytes)
        if (content.isBlank()) return emptyList()

        val cues = mutableListOf<SubtitleCue>()
        val lines = content.lines()
        var i = 0
        // Matches both SRT (00:01:20,000 --> 00:01:23,500) and WebVTT (00:01:20.000 --> 00:01:23.500 or 01:20.000 --> 01:23.500)
        val srtVttRegex = Regex("^(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{2,3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{2,3})")

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.equals("WEBVTT", ignoreCase = true) || line.startsWith("NOTE ")) {
                i++
                continue
            }

            val timeMatch = srtVttRegex.find(line)
            if (timeMatch != null) {
                val startH = timeMatch.groupValues[1].ifEmpty { "0" }
                val startMs = toMs(startH, timeMatch.groupValues[2], timeMatch.groupValues[3], timeMatch.groupValues[4])
                val endH = timeMatch.groupValues[5].ifEmpty { "0" }
                val endMs = toMs(endH, timeMatch.groupValues[6], timeMatch.groupValues[7], timeMatch.groupValues[8])
                i++
                val textLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    val cleaned = lines[i].trim()
                        .replace(Regex("<[^>]*>"), "")
                        .replace(Regex("\\{[^}]*\\}"), "")
                    if (cleaned.isNotEmpty()) textLines.add(cleaned)
                    i++
                }
                if (textLines.isNotEmpty() && endMs > startMs) {
                    cues.add(SubtitleCue(startMs, endMs, textLines.joinToString("\n")))
                }
            } else if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val parts = line.substringAfter(":").split(",", limit = 10)
                if (parts.size >= 10) {
                    val startMs = parseAssTime(parts[1].trim())
                    val endMs = parseAssTime(parts[2].trim())
                    val rawText = parts[9]
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .replace(Regex("\\{[^}]*\\}"), "")
                        .trim()
                    if (rawText.isNotEmpty() && endMs > startMs) {
                        cues.add(SubtitleCue(startMs, endMs, rawText))
                    }
                }
                i++
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseAssTime(t: String): Long {
        val mainParts = t.split(":")
        if (mainParts.size != 3) return 0L
        val h = mainParts[0].toLongOrNull() ?: 0L
        val m = mainParts[1].toLongOrNull() ?: 0L
        val secParts = mainParts[2].split(".")
        val s = secParts[0].toLongOrNull() ?: 0L
        val cs = if (secParts.size > 1) (secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L) else 0L
        return ((h * 3600 + m * 60 + s) * 1000L) + cs
    }

    private fun toMs(h: String, m: String, s: String, msStr: String): Long {
        val hours = h.toLongOrNull() ?: 0L
        val minutes = m.toLongOrNull() ?: 0L
        val seconds = s.toLongOrNull() ?: 0L
        val millis = msStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60 + seconds) * 1000L) + millis
    }
}
