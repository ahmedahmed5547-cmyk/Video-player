package com.pureplayer.video

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.color.DynamicColors
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        VideoThumbnailLoader.trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        VideoThumbnailLoader.clearAll()
    }
}

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val duration: Long,
    val size: Long,
    val folderName: String,
    val width: Int = 0,
    val height: Int = 0,
    val path: String = ""
) {
    fun getResolutionLabel(): String {
        if (width <= 0 || height <= 0) return "HD"
        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height)
        val badge = when {
            maxDim >= 3800 || minDim >= 2100 -> "4K UHD"
            maxDim >= 2500 || minDim >= 1400 -> "2K QHD"
            maxDim >= 1900 || minDim >= 1000 -> "1080p FHD"
            maxDim >= 1200 || minDim >= 700 -> "720p HD"
            maxDim >= 800 || minDim >= 480 -> "480p SD"
            else -> "${width}x${height}"
        }
        return "$badge (${width}×${height})"
    }
}

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
    private const val KEY_SUB_BG_OPACITY = "sub_bg_opacity_pct"
    private const val KEY_SUB_TEXT_OPACITY = "sub_text_opacity_pct" // 0..100
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

    fun getSubTextOpacityPct(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_SUB_TEXT_OPACITY, 100)

    fun setSubTextOpacityPct(context: Context, pct: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SUB_TEXT_OPACITY, pct.coerceIn(20, 100)).apply()
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

enum class MatchQuality(val score: Int) {
    HASH_MATCH(100),
    EXACT_TITLE(80),
    FUZZY_TITLE(50)
}

data class SubtitleSearchRequest(
    val rawTitle: String,
    val cleanQuery: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val movieHash: String?,
    val fileByteSize: Long,
    val imdbId: String?,
    val preferredLanguages: List<String>
)

data class SubtitleResultItem(
    val id: String,
    val title: String,
    val providerName: String,
    val languageCode: String,
    val downloadUrl: String,
    val formatExt: String,
    val matchQuality: MatchQuality,
    val rating: Double,
    val downloadCount: Int
)

sealed class SubtitleSearchOutcome {
    data class Success(
        val results: List<SubtitleResultItem>,
        val autoAppliedFile: File?,
        val autoAppliedCues: List<SubtitleCue>,
        val bestItem: SubtitleResultItem
    ) : SubtitleSearchOutcome()

    data class Error(val errorType: ErrorType) : SubtitleSearchOutcome()

    enum class ErrorType {
        NO_INTERNET,
        TIMEOUT,
        RATE_LIMIT,
        CORRUPTED_FILE,
        NOT_FOUND
    }
}

interface SubtitleProvider {
    val name: String
    suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem>
}

/**
 * Provider 1: Stremio OpenSubtitles v3 Native JSON Provider (100% Free, Zero API Key Required!)
 * Uses resolved IMDb ID + Video Hash to fetch direct .srt download links in Arabic, English, etc.
 */
class StremioOpenSubtitlesV3Provider : SubtitleProvider {
    override val name: String = "OpenSubtitles-v3"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val imdb = request.imdbId ?: return@withContext emptyList()

        val iso3ToIso2 = mapOf(
            "ara" to "ar", "ar" to "ar",
            "eng" to "en", "en" to "en",
            "fre" to "fr", "fra" to "fr", "fr" to "fr",
            "spa" to "es", "es" to "es",
            "tur" to "tr", "tr" to "tr",
            "hin" to "hi", "hi" to "hi",
            "urd" to "ur", "ur" to "ur"
        )

        val isSeries = request.season != null && request.episode != null
        val typeSegment = if (isSeries) "series" else "movie"
        val idSegment = if (isSeries) "${imdb}:${request.season}:${request.episode}" else imdb

        val queryParams = buildList {
            if (!request.movieHash.isNullOrEmpty()) add("videoHash=${request.movieHash}")
            if (request.fileByteSize > 0) add("videoSize=${request.fileByteSize}")
            add("filename=${URLEncoder.encode(request.rawTitle, "UTF-8")}")
        }.joinToString("&")

        val endpoints = listOf(
            "https://opensubtitles-v3.strem.io/subtitles/$typeSegment/$idSegment/$queryParams.json",
            "https://opensubtitles-v3.strem.io/subtitles/$typeSegment/$idSegment.json"
        )

        for (urlStr in endpoints) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    connectTimeout = 6500
                    readTimeout = 6500
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.trim().startsWith("{")) {
                        val json = JSONObject(body)
                        val subs = json.optJSONArray("subtitles") ?: JSONArray()
                        for (i in 0 until subs.length()) {
                            val obj = subs.getJSONObject(i)
                            val rawLang = obj.optString("lang", "").lowercase(Locale.ROOT)
                            val iso2 = iso3ToIso2[rawLang] ?: rawLang.take(2)
                            if (!request.preferredLanguages.any { it.equals(iso2, ignoreCase = true) }) continue

                            val dlUrl = obj.optString("url")
                            val subId = obj.optString("id", "stremio_$i")
                            val isHashMatch = obj.optString("m") == "h"
                            if (dlUrl.isNotEmpty()) {
                                val epTag = if (isSeries) " S%02dE%02d".format(Locale.US, request.season, request.episode) else ""
                                results.add(
                                    SubtitleResultItem(
                                        id = "stv3_$subId",
                                        title = "${request.cleanQuery}$epTag (${iso2.uppercase()} #$i)",
                                        providerName = name,
                                        languageCode = iso2,
                                        downloadUrl = dlUrl,
                                        formatExt = "srt",
                                        matchQuality = if (isHashMatch) MatchQuality.HASH_MATCH else MatchQuality.EXACT_TITLE,
                                        rating = if (isHashMatch) 10.0 else 9.5,
                                        downloadCount = 2000 - (i * 10)
                                    )
                                )
                            }
                        }
                        if (results.isNotEmpty()) break
                    }
                }
            } catch (_: Exception) {}
        }
        results
    }
}

/**
 * Provider 2: OpenSubtitles REST API (Uses official X-User-Agent header + 64-bit File Hash + IMDb ID + Query)
 */
class OpenSubtitlesProvider : SubtitleProvider {
    override val name: String = "OpenSubtitles-REST"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val iso3Map = mapOf(
            "ar" to "ara", "en" to "eng", "fr" to "fre",
            "es" to "spa", "tr" to "tur", "hi" to "hin", "ur" to "urd"
        )

        for (lang in request.preferredLanguages) {
            val subLangId = iso3Map[lang] ?: "eng"
            val encodedQuery = URLEncoder.encode(request.cleanQuery, "UTF-8")
            val endpoints = mutableListOf<Pair<String, MatchQuality>>()

            if (!request.movieHash.isNullOrEmpty() && request.fileByteSize > 0) {
                endpoints.add(
                    "https://rest.opensubtitles.org/search/moviebytesize-${request.fileByteSize}/moviehash-${request.movieHash}/sublanguageid-$subLangId" to MatchQuality.HASH_MATCH
                )
            }
            if (!request.imdbId.isNullOrEmpty()) {
                val numericImdb = request.imdbId.removePrefix("tt")
                if (request.season != null && request.episode != null) {
                    endpoints.add(
                        "https://rest.opensubtitles.org/search/episode-${request.episode}/imdbid-$numericImdb/season-${request.season}/sublanguageid-$subLangId" to MatchQuality.EXACT_TITLE
                    )
                } else {
                    endpoints.add(
                        "https://rest.opensubtitles.org/search/imdbid-$numericImdb/sublanguageid-$subLangId" to MatchQuality.EXACT_TITLE
                    )
                }
            }
            endpoints.add(
                "https://rest.opensubtitles.org/search/query-$encodedQuery/sublanguageid-$subLangId" to MatchQuality.EXACT_TITLE
            )

            for ((urlStr, quality) in endpoints) {
                try {
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        // Official X-User-Agent required by rest.opensubtitles.org
                        setRequestProperty("X-User-Agent", "TemporaryUserAgent")
                        setRequestProperty("User-Agent", "TemporaryUserAgent")
                        if (BuildConfig.OPENSUBTITLES_API_KEY.isNotEmpty()) {
                            setRequestProperty("Api-Key", BuildConfig.OPENSUBTITLES_API_KEY)
                        }
                        connectTimeout = 6000
                        readTimeout = 6000
                    }
                    val code = conn.responseCode
                    if (code == 429) throw IllegalStateException("RATE_LIMIT")
                    if (code in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        if (body.trim().startsWith("[")) {
                            val arr = JSONArray(body)
                            for (i in 0 until minOf(arr.length(), 10)) {
                                val obj = arr.getJSONObject(i)
                                val dlUrl = obj.optString("SubDownloadLink").ifEmpty { obj.optString("ZipDownloadLink") }
                                val fileName = obj.optString("SubFileName", "${request.cleanQuery}.srt")
                                val fmt = obj.optString("SubFormat", "srt")
                                val downloads = obj.optString("SubDownloadsCnt", "0").toIntOrNull() ?: 0
                                val score = obj.optString("SubRating", "0.0").toDoubleOrNull() ?: 0.0
                                if (dlUrl.isNotEmpty()) {
                                    results.add(
                                        SubtitleResultItem(
                                            id = "os_${obj.optString("IDSubtitleFile", i.toString())}",
                                            title = fileName,
                                            providerName = name,
                                            languageCode = lang,
                                            downloadUrl = dlUrl,
                                            formatExt = fmt,
                                            matchQuality = quality,
                                            rating = score,
                                            downloadCount = downloads
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        results
    }
}

/**
 * Provider 3: Podnapisi Public XML/ZIP Subtitle Provider (No API Key Required)
 */
class PodnapisiProvider : SubtitleProvider {
    override val name: String = "Podnapisi"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val encodedQuery = URLEncoder.encode(request.cleanQuery, "UTF-8")
        val sParam = if (request.season != null) "&sTS=${request.season}" else ""
        val eParam = if (request.episode != null) "&sTE=${request.episode}" else ""

        for (lang in request.preferredLanguages) {
            try {
                val urlStr = "https://www.podnapisi.net/subtitles/search/old?sXML=1&sL=$lang&sK=$encodedQuery$sParam$eParam"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Accept", "application/xml, text/xml")
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                if (conn.responseCode == 429) throw IllegalStateException("RATE_LIMIT")
                if (conn.responseCode in 200..299) {
                    val xml = conn.inputStream.bufferedReader().use { it.readText() }
                    val pidRegex = Regex("<pid>([^<]+)</pid>")
                    val titleRegex = Regex("<title>([^<]+)</title>")
                    val releaseRegex = Regex("<release>([^<]*)</release>")
                    val downloadsRegex = Regex("<downloads>(\\d+)</downloads>")

                    val pids = pidRegex.findAll(xml).map { it.groupValues[1] }.toList()
                    val titles = titleRegex.findAll(xml).map { it.groupValues[1] }.toList()
                    val releases = releaseRegex.findAll(xml).map { it.groupValues[1] }.toList()
                    val downloads = downloadsRegex.findAll(xml).map { it.groupValues[1].toIntOrNull() ?: 0 }.toList()

                    for (i in 0 until minOf(pids.size, 8)) {
                        val pid = pids[i]
                        val t = releases.getOrNull(i)?.ifBlank { titles.getOrNull(i) } ?: request.cleanQuery
                        val dlCount = downloads.getOrNull(i) ?: 50
                        results.add(
                            SubtitleResultItem(
                                id = "pod_$pid",
                                title = t,
                                providerName = name,
                                languageCode = lang,
                                downloadUrl = "https://www.podnapisi.net/subtitles/$pid/download",
                                formatExt = "zip",
                                matchQuality = MatchQuality.EXACT_TITLE,
                                rating = 8.8,
                                downloadCount = dlCount
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        results
    }
}

/**
 * Provider 4: YIFY Subtitles Direct Movie Provider (Uses IMDb ID to fetch direct .zip/.srt links, zero API key!)
 */
class YifySubtitlesProvider : SubtitleProvider {
    override val name: String = "YIFY-Subtitles"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val imdb = request.imdbId ?: return@withContext emptyList()

        val langNameMap = mapOf(
            "ar" to "arabic", "en" to "english", "fr" to "french",
            "es" to "spanish", "tr" to "turkish", "hi" to "hindi", "ur" to "urdu"
        )

        val domains = listOf("https://yts-subs.com", "https://yifysubtitles.ch")
        for (domain in domains) {
            try {
                val conn = (URL("$domain/movie-imdb/$imdb").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                if (conn.responseCode in 200..299) {
                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                    val rowRegex = Regex("<tr[^>]*>.*?sub-lang\">([^<]+)</span>.*?href=\"(/subtitles/[^\"]+)\".*?</tr>", RegexOption.DOT_MATCHES_ALL)
                    for (match in rowRegex.findAll(html)) {
                        val langLabel = match.groupValues[1].trim().lowercase(Locale.ROOT)
                        val subPagePath = match.groupValues[2].trim()
                        val matchedCode = langNameMap.entries.firstOrNull { it.value.equals(langLabel, ignoreCase = true) }?.key ?: continue
                        if (!request.preferredLanguages.contains(matchedCode)) continue

                        val zipSlug = subPagePath.removePrefix("/subtitles/")
                        val directZipUrl = "$domain/subtitle/$zipSlug.zip"
                        val readableTitle = zipSlug.replace("-", " ")
                        results.add(
                            SubtitleResultItem(
                                id = "yify_$zipSlug",
                                title = readableTitle,
                                providerName = name,
                                languageCode = matchedCode,
                                downloadUrl = directZipUrl,
                                formatExt = "zip",
                                matchQuality = MatchQuality.EXACT_TITLE,
                                rating = 9.0,
                                downloadCount = 400
                            )
                        )
                        if (results.size >= 8) break
                    }
                    if (results.isNotEmpty()) break
                }
            } catch (_: Exception) {}
        }
        results
    }
}

/**
 * Provider 5: SubDL API & Public NextJS Data Provider
 */
class SubdlProvider : SubtitleProvider {
    override val name: String = "SubDL"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val encodedQuery = URLEncoder.encode(request.cleanQuery, "UTF-8")
        val langCsv = request.preferredLanguages.joinToString(",") { it.uppercase(Locale.ROOT) }
        val apiKey = BuildConfig.SUBDL_API_KEY

        val apiUrl = if (apiKey.isNotEmpty()) {
            "https://api.subdl.com/api/v1/subtitles?api_key=$apiKey&film_name=$encodedQuery&languages=$langCsv&subs_per_page=10"
        } else if (!request.imdbId.isNullOrEmpty()) {
            "https://api.subdl.com/api/v1/subtitles?imdb_id=${request.imdbId}&languages=$langCsv"
        } else {
            "https://api.subdl.com/api/v1/subtitles?film_name=$encodedQuery&languages=$langCsv"
        }

        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            connectTimeout = 6000
            readTimeout = 6000
        }

        if (conn.responseCode == 429) throw IllegalStateException("RATE_LIMIT")
        if (conn.responseCode in 200..299) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.trim().startsWith("{")) {
                val root = JSONObject(body)
                val subs = root.optJSONArray("subtitles") ?: JSONArray()
                for (i in 0 until minOf(subs.length(), 10)) {
                    val sub = subs.getJSONObject(i)
                    val rawUrl = sub.optString("url")
                    val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "https://dl.subdl.com$rawUrl"
                    val lang = sub.optString("language", request.preferredLanguages.firstOrNull() ?: "ar").lowercase(Locale.ROOT).take(2)
                    val releaseName = sub.optString("release_name", request.cleanQuery)
                    if (rawUrl.isNotEmpty()) {
                        results.add(
                            SubtitleResultItem(
                                id = "subdl_$i",
                                title = releaseName,
                                providerName = name,
                                languageCode = lang,
                                downloadUrl = fullUrl,
                                formatExt = "zip",
                                matchQuality = MatchQuality.EXACT_TITLE,
                                rating = 9.2,
                                downloadCount = 500 - (i * 20)
                            )
                        )
                    }
                }
            }
        }
        results
    }
}

/**
 * Provider 6: Addic7ed / Gestdown Community TV Series Provider
 */
class Addic7edCommunityProvider : SubtitleProvider {
    override val name: String = "Addic7ed-TV"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val encodedQuery = URLEncoder.encode(request.cleanQuery, "UTF-8")
        val urlStr = "https://api.gestdown.info/shows/search/$encodedQuery"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PureVideoPlayer/7.0")
            connectTimeout = 5000
            readTimeout = 5000
        }
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.trim().startsWith("{")) {
                val json = JSONObject(body)
                val shows = json.optJSONArray("shows")
                if (shows != null && shows.length() > 0) {
                    val show = shows.getJSONObject(0)
                    val showId = show.optString("id")
                    val showName = show.optString("name", request.cleanQuery)
                    val season = request.season ?: 1
                    val ep = request.episode ?: 1
                    for (lang in request.preferredLanguages) {
                        results.add(
                            SubtitleResultItem(
                                id = "addic_${showId}_${season}_${ep}_$lang",
                                title = "$showName S${season}E${ep}",
                                providerName = name,
                                languageCode = lang,
                                downloadUrl = "https://api.gestdown.info/subtitles/get/$showId/$season/$ep/$lang",
                                formatExt = "srt",
                                matchQuality = MatchQuality.FUZZY_TITLE,
                                rating = 8.0,
                                downloadCount = 100
                            )
                        )
                    }
                }
            }
        }
        results
    }
}

object SubtitleSearchEngine {

    private val providers: List<SubtitleProvider> = listOf(
        StremioOpenSubtitlesV3Provider(),
        OpenSubtitlesProvider(),
        PodnapisiProvider(),
        YifySubtitlesProvider(),
        SubdlProvider(),
        Addic7edCommunityProvider()
    )

    /**
     * Resolves IMDb ID automatically without any API key using Cinemeta Public Catalog API.
     */
    private fun resolveImdbIdKeyless(cleanQuery: String, isSeries: Boolean, year: Int?): String? {
        val catalogTypes = if (isSeries) listOf("series", "movie") else listOf("movie", "series")
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        for (catType in catalogTypes) {
            try {
                val url = "https://v3-cinemeta.strem.io/catalog/$catType/top/search=$encoded.json"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    connectTimeout = 4500
                    readTimeout = 4500
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val metas = json.optJSONArray("metas") ?: continue
                    if (metas.length() > 0) {
                        if (year != null) {
                            for (i in 0 until metas.length()) {
                                val m = metas.getJSONObject(i)
                                val releaseInfo = m.optString("releaseInfo", "")
                                if (releaseInfo.contains(year.toString())) {
                                    val id = m.optString("id")
                                    if (id.startsWith("tt")) return id
                                }
                            }
                        }
                        val firstId = metas.getJSONObject(0).optString("id")
                        if (firstId.startsWith("tt")) return firstId
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun buildSearchRequest(
        context: Context,
        videoUri: Uri,
        rawTitle: String,
        preferredLanguages: List<String>,
        customSearchQuery: String? = null
    ): SubtitleSearchRequest {
        val withoutExt = rawTitle.substringBeforeLast(".")
        val sxeMatch = Regex("[Ss](\\d{1,2})[._\\-\\s]*[Ee](\\d{1,2})").find(withoutExt)
            ?: Regex("(\\d{1,2})x(\\d{1,2})").find(withoutExt)
        val season = sxeMatch?.groupValues?.get(1)?.toIntOrNull()
        val episode = sxeMatch?.groupValues?.get(2)?.toIntOrNull()

        val yearMatch = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(withoutExt)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val baseBeforeEpisode = if (sxeMatch != null) {
            withoutExt.substring(0, sxeMatch.range.first)
        } else if (yearMatch != null) {
            withoutExt.substring(0, yearMatch.range.first)
        } else {
            withoutExt
        }

        val cleaned = (customSearchQuery?.takeIf { it.isNotBlank() } ?: baseBeforeEpisode)
            .replace(Regex("[._\\-\\[\\]()]"), " ")
            .replace(
                Regex(
                    "\\b(2160p|1080p|720p|480p|4K|8K|UHD|HDR|HDR10|DV|Dolby|BluRay|BRRip|BDRip|WEB|WEBDL|WEBRip|HDTV|x264|x265|H264|H265|HEVC|AV1|10bit|AAC|AC3|EAC3|DTS|Atmos|5\\.1|7\\.1|YTS|YIFY|RARBG|PSA|GalaxyRG|AMZN|NF)\\b",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { withoutExt }

        val (hash, size) = computeOpenSubtitlesHashAndSize(context, videoUri)
        val imdbId = resolveImdbIdKeyless(cleaned, isSeries = (season != null), year = year)

        return SubtitleSearchRequest(
            rawTitle = rawTitle,
            cleanQuery = cleaned,
            year = year,
            season = season,
            episode = episode,
            movieHash = hash,
            fileByteSize = size,
            imdbId = imdbId,
            preferredLanguages = preferredLanguages
        )
    }

    private fun computeOpenSubtitlesHashAndSize(context: Context, uri: Uri): Pair<String?, Long> {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                if (size < 131072L) return Pair(null, size.coerceAtLeast(0L))
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val chunkSize = 65536
                    val headBuf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
                    val tailBuf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
                    channel.read(headBuf, 0L)
                    channel.read(tailBuf, size - chunkSize)
                    headBuf.flip()
                    tailBuf.flip()

                    var hash = size
                    while (headBuf.remaining() >= 8) {
                        hash += headBuf.long
                    }
                    while (tailBuf.remaining() >= 8) {
                        hash += tailBuf.long
                    }
                    Pair(String.format(Locale.US, "%016x", hash), size)
                }
            } ?: Pair(null, 0L)
        } catch (_: Exception) {
            Pair(null, 0L)
        }
    }

    /**
     * Queries all 6 SubtitleProviders in parallel using Coroutines async/awaitAll + withTimeout.
     * Ranks results by MatchQuality (Hash > Exact > Fuzzy), Language Priority, and Rating/Downloads.
     * Automatically downloads, extracts, and caches the #1 best subtitle in app storage!
     */
    suspend fun searchAndAutoDownload(
        context: Context,
        videoUri: Uri,
        rawTitle: String,
        customSearchQuery: String? = null
    ): SubtitleSearchOutcome = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(context)) {
            return@withContext SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.NO_INTERNET)
        }

        val preferredLangs = PlaybackStore.getPreferredSubtitleLanguages(context)
        val request = buildSearchRequest(context, videoUri, rawTitle, preferredLangs, customSearchQuery)

        var anyTimeout = false
        var anyRateLimit = false

        val providerLists = coroutineScope {
            providers.map { provider ->
                async {
                    try {
                        withTimeout(8500L) {
                            provider.search(request)
                        }
                    } catch (_: TimeoutCancellationException) {
                        anyTimeout = true
                        emptyList<SubtitleResultItem>()
                    } catch (e: Exception) {
                        if (e.message == "RATE_LIMIT") anyRateLimit = true
                        emptyList<SubtitleResultItem>()
                    }
                }
            }.awaitAll()
        }

        val allResults = providerLists.flatten().distinctBy { it.downloadUrl }
        if (allResults.isEmpty()) {
            val err = when {
                anyRateLimit -> SubtitleSearchOutcome.ErrorType.RATE_LIMIT
                anyTimeout -> SubtitleSearchOutcome.ErrorType.TIMEOUT
                else -> SubtitleSearchOutcome.ErrorType.NOT_FOUND
            }
            return@withContext SubtitleSearchOutcome.Error(err)
        }

        val ranked = allResults.sortedWith(
            compareByDescending<SubtitleResultItem> { it.matchQuality.score }
                .thenBy { item ->
                    val idx = preferredLangs.indexOfFirst { it.equals(item.languageCode, ignoreCase = true) }
                    if (idx == -1) 999 else idx
                }
                .thenByDescending { it.rating }
                .thenByDescending { it.downloadCount }
        )

        // Try downloading & caching starting from top-ranked result (automatic fallback if #1 link fails)
        for (candidate in ranked.take(6)) {
            val downloaded = downloadAndCacheSubtitle(context, candidate)
            if (downloaded != null) {
                val (file, cues) = downloaded
                if (cues.isNotEmpty()) {
                    PlaybackStore.saveVideoSubtitleCachePath(context, videoUri, file.absolutePath)
                    return@withContext SubtitleSearchOutcome.Success(
                        results = ranked,
                        autoAppliedFile = file,
                        autoAppliedCues = cues,
                        bestItem = candidate
                    )
                }
            }
        }

        SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.CORRUPTED_FILE)
    }

    suspend fun downloadAndCacheSubtitle(
        context: Context,
        item: SubtitleResultItem
    ): Pair<File, List<SubtitleCue>>? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, "subtitles_cache").apply { mkdirs() }
        val keyHash = MessageDigest.getInstance("MD5")
            .digest(item.downloadUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cachedFile = File(cacheDir, "sub_${item.languageCode}_$keyHash.srt")

        if (cachedFile.exists() && cachedFile.length() > 32L) {
            val cachedCues = SubtitleParser.parseFromFile(cachedFile)
            if (cachedCues.isNotEmpty()) {
                return@withContext Pair(cachedFile, cachedCues)
            }
        }

        try {
            val conn = (URL(item.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("X-User-Agent", "TemporaryUserAgent")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val rawBytes = conn.inputStream.use { readBytesBounded(it, 8 * 1024 * 1024) }
            if (rawBytes.isEmpty()) return@withContext null

            val extractedBytes = extractSubtitleBytesFromArchiveIfNeeded(rawBytes)
            val cues = SubtitleParser.parseFromBytes(extractedBytes)
            if (cues.isNotEmpty()) {
                cachedFile.writeBytes(extractedBytes)
                return@withContext Pair(cachedFile, cues)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bounded stream reader (max 8 MB) to prevent Zip-Bomb / OutOfMemoryError attacks.
     */
    fun readBytesBounded(input: java.io.InputStream, maxBytes: Int = 8 * 1024 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            if (total + read > maxBytes) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    fun extractSubtitleBytesFromArchiveIfNeeded(rawBytes: ByteArray): ByteArray {
        if (rawBytes.size < 4) return rawBytes
        if (rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()) {
            try {
                ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase(Locale.ROOT)
                        if (!entry.isDirectory && !name.contains("..") &&
                            (name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".ass") || name.endsWith(".ssa") || name.endsWith(".sub"))
                        ) {
                            return readBytesBounded(zis, 8 * 1024 * 1024)
                        }
                        entry = zis.nextEntry
                    }
                }
            } catch (_: Exception) {}
        }
        if (rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()) {
            try {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gis ->
                    return readBytesBounded(gis, 8 * 1024 * 1024)
                }
            } catch (_: Exception) {}
        }
        return rawBytes
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
