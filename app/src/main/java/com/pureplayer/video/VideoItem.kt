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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
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
    val folderName: String,
    val width: Int = 0,
    val height: Int = 0,
    val path: String = "",
    val dateAddedSec: Long = 0L
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

    private const val KEY_APP_LANG_TAG = "app_language_tag"
    private const val KEY_SUB_LANGS_CSV = "preferred_sub_langs_csv"
    private const val KEY_THEME_INDEX = "app_theme_index"
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
        val safePos = if (durationMs > 0 && positionMs >= durationMs - 4000L) 0L else positionMs
        prefs(context).edit()
            .putLong(PREFIX_POS + uri.toString(), safePos)
            .putString(KEY_LAST_URI, uri.toString())
            .putString(KEY_LAST_TITLE, title)
            .putLong(KEY_LAST_POSITION, safePos)
            .apply()
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
        prefs(context).edit().putString(PREFIX_SUB_PATH + videoUri.toString(), path).apply()
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
        val csv = prefs(context).getString(KEY_SUB_LANGS_CSV, "ar,en") ?: "ar,en"
        return csv.split(",").map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.ifEmpty { listOf("ar", "en") }
    }

    fun setPreferredSubtitleLanguages(context: Context, langs: List<String>) {
        val clean = langs.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.ifEmpty { listOf("ar", "en") }
        prefs(context).edit().putString(KEY_SUB_LANGS_CSV, clean.joinToString(",")).apply()
    }

    fun getThemeIndex(context: Context): Int = prefs(context).getInt(KEY_THEME_INDEX, 0)
    fun setThemeIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_THEME_INDEX, index).apply()
    }

    fun getAccentColor(context: Context): Int {
        return when (getThemeIndex(context)) {
            1 -> Color.parseColor("#F59E0B")
            2 -> Color.parseColor("#10B981")
            3 -> Color.parseColor("#F43F5E")
            4 -> Color.parseColor("#A855F7")
            else -> Color.parseColor("#38BDF8")
        }
    }

    fun getBackgroundColor(context: Context): Int {
        return when (getThemeIndex(context)) {
            1 -> Color.parseColor("#000000")
            else -> Color.parseColor("#090D16")
        }
    }

    fun getSurfaceColor(context: Context): Int {
        return when (getThemeIndex(context)) {
            1 -> Color.parseColor("#111111")
            else -> Color.parseColor("#131C2E")
        }
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
        !prefs(context).getString(KEY_PRIVACY_PIN, "").isNullOrEmpty()

    fun verifyPrivacyPin(context: Context, inputPin: String): Boolean {
        val saved = prefs(context).getString(KEY_PRIVACY_PIN, "") ?: ""
        return saved.isNotEmpty() && saved == inputPin.trim()
    }

    fun setPrivacyPin(context: Context, newPin: String) {
        prefs(context).edit().putString(KEY_PRIVACY_PIN, newPin.trim()).apply()
    }

    fun clearPrivacyPinAndLocks(context: Context) {
        prefs(context).edit()
            .remove(KEY_PRIVACY_PIN)
            .remove(KEY_LOCKED_FOLDERS)
            .putBoolean(KEY_BIOMETRIC_PWD_VERIFIED, false)
            .apply()
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
        prefs(context).getString(KEY_GITHUB_REPO, "") ?: ""

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

object SubtitleParser {
    fun parseFromFile(file: File): List<SubtitleCue> {
        return try {
            parseFromBytes(file.readBytes())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseFromBytes(bytes: ByteArray): List<SubtitleCue> {
        if (bytes.isEmpty()) return emptyList()
        val text = decodeBestCharset(bytes)
        return parseSubtitleText(text)
    }

    private fun decodeBestCharset(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) {
            return utf8
        }
        return try {
            String(bytes, Charset.forName("windows-1256"))
        } catch (_: Exception) {
            utf8
        }
    }

    fun parseSubtitleText(rawText: String): List<SubtitleCue> {
        val normalized = rawText.replace("\r\n", "\n").replace("\r", "\n")
        if (normalized.contains("[Events]") && normalized.contains("Dialogue:")) {
            val assCues = parseAssSubtitles(normalized)
            if (assCues.isNotEmpty()) return assCues
        }

        val cues = mutableListOf<SubtitleCue>()
        val blocks = normalized.split(Regex("\\n\\n+"))
        val timeRegex = Regex("(\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{2,3}\\s*-->\\s*(\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{2,3}")

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timeIdx = lines.indexOfFirst { timeRegex.containsMatchIn(it) }
            if (timeIdx == -1) continue

            val timeLine = lines[timeIdx]
            val parts = timeLine.split("-->")
            if (parts.size < 2) continue

            val startMs = parseTimeCode(parts[0].trim())
            val endMs = parseTimeCode(parts[1].trim().substringBefore(" "))
            if (endMs <= startMs) continue

            val textLines = lines.subList(timeIdx + 1, lines.size)
                .map {
                    it.replace(Regex("<[^>]*>"), "")
                        .replace(Regex("\\{[^}]*\\}"), "")
                        .trim()
                }
                .filter { it.isNotEmpty() }

            if (textLines.isNotEmpty()) {
                cues.add(SubtitleCue(startMs, endMs, textLines.joinToString("\n")))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseAssSubtitles(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = trimmed.substringAfter(":")
                val cols = payload.split(",", limit = 10)
                if (cols.size >= 10) {
                    val startMs = parseTimeCode(cols[1].trim())
                    val endMs = parseTimeCode(cols[2].trim())
                    val cleanText = cols[9]
                        .replace(Regex("\\{[^}]*\\}"), "")
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .trim()
                    if (endMs > startMs && cleanText.isNotEmpty()) {
                        cues.add(SubtitleCue(startMs, endMs, cleanText))
                    }
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTimeCode(raw: String): Long {
        val clean = raw.replace(',', '.').trim()
        val segments = clean.split(":")
        return try {
            when (segments.size) {
                3 -> {
                    val h = segments[0].toLong()
                    val m = segments[1].toLong()
                    val secParts = segments[2].split(".")
                    val s = secParts[0].toLong()
                    val msStr = (secParts.getOrNull(1) ?: "0").padEnd(3, '0').take(3)
                    val ms = msStr.toLong()
                    (h * 3600 + m * 60 + s) * 1000L + ms
                }
                2 -> {
                    val m = segments[0].toLong()
                    val secParts = segments[1].split(".")
                    val s = secParts[0].toLong()
                    val msStr = (secParts.getOrNull(1) ?: "0").padEnd(3, '0').take(3)
                    val ms = msStr.toLong()
                    (m * 60 + s) * 1000L + ms
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}

enum class MatchQuality(val scoreBoost: Int) {
    HASH_MATCH(300),
    EXACT_TITLE(200),
    FUZZY_TITLE(100)
}

data class SubtitleResultItem(
    val id: String,
    val title: String,
    val providerName: String,
    val languageCode: String,
    val downloadUrl: String,
    val formatExt: String = "srt",
    val matchQuality: MatchQuality = MatchQuality.EXACT_TITLE,
    val rating: Double = 0.0,
    val downloadCount: Int = 0
)

data class SubtitleSearchRequest(
    val videoUri: Uri,
    val rawTitle: String,
    val cleanQuery: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val movieHash: String?,
    val fileByteSize: Long,
    val preferredLanguages: List<String>
)

sealed class SubtitleSearchOutcome {
    data class Success(
        val bestItem: SubtitleResultItem,
        val autoAppliedCues: List<SubtitleCue>,
        val results: List<SubtitleResultItem>
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

object OpenSubtitlesHashCalculator {
    private const val CHUNK_SIZE = 65536

    fun computeHashAndSize(context: Context, uri: Uri): Pair<String, Long>? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                if (size < CHUNK_SIZE * 2) return null
                val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
                val headBuf = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                val tailBuf = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(headBuf, 0L)
                channel.read(tailBuf, size - CHUNK_SIZE)
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
        } catch (_: Exception) {
            null
        }
    }
}

object CinemetaImdbResolver {
    fun resolveImdbId(query: String, preferSeries: Boolean): Pair<String, Boolean>? {
        val order = if (preferSeries) listOf("series", "movie") else listOf("movie", "series")
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (type in order) {
            try {
                val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
                val json = httpGetString(url) ?: continue
                val metas = JSONObject(json).optJSONArray("metas") ?: continue
                if (metas.length() > 0) {
                    val id = metas.optJSONObject(0)?.optString("id")?.takeIf { it.startsWith("tt") }
                    if (id != null) {
                        return Pair(id, type == "series")
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }
}

object StremioOpenSubtitlesV3Provider : SubtitleProvider {
    override val name: String = "OpenSubtitles-v3"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> {
        val out = mutableListOf<SubtitleResultItem>()
        try {
            val resolved = CinemetaImdbResolver.resolveImdbId(request.cleanQuery, request.season != null) ?: return emptyList()
            val imdbId = resolved.first
            val isSeries = resolved.second || (request.season != null && request.episode != null)

            val urlsToTry = mutableListOf<String>()
            if (isSeries) {
                val s = request.season ?: 1
                val e = request.episode ?: 1
                urlsToTry.add("https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$s:$e.json")
            }
            val hashParam = if (!request.movieHash.isNullOrEmpty() && request.fileByteSize > 0) {
                "/videoHash=${request.movieHash}&videoSize=${request.fileByteSize}"
            } else ""
            urlsToTry.add("https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId$hashParam.json")

            for (urlStr in urlsToTry) {
                val jsonStr = httpGetString(urlStr) ?: continue
                val root = JSONObject(jsonStr)
                val subs = root.optJSONArray("subtitles") ?: continue

                for (i in 0 until subs.length()) {
                    val obj = subs.optJSONObject(i) ?: continue
                    val dlUrl = obj.optString("url")
                    if (dlUrl.isBlank()) continue
                    val rawLang = obj.optString("lang").lowercase(Locale.ROOT)
                    val lang2 = normalizeLangCode(rawLang)
                    if (lang2 !in request.preferredLanguages) continue

                    out.add(
                        SubtitleResultItem(
                            id = obj.optString("id", "stremio_${out.size}"),
                            title = "${request.cleanQuery} (${lang2.uppercase()})",
                            providerName = name,
                            languageCode = lang2,
                            downloadUrl = dlUrl,
                            formatExt = "srt",
                            matchQuality = if (!request.movieHash.isNullOrEmpty()) MatchQuality.HASH_MATCH else MatchQuality.EXACT_TITLE,
                            rating = 9.5,
                            downloadCount = 600 - i
                        )
                    )
                }
                if (out.isNotEmpty()) break
            }
        } catch (_: Exception) {}
        return out
    }
}

object OpenSubtitlesRestProvider : SubtitleProvider {
    override val name: String = "OpenSubtitles"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> {
        val out = mutableListOf<SubtitleResultItem>()
        val userAgents = listOf("TemporaryUserAgent", "VLSub 0.11.1")
        for (lang2 in request.preferredLanguages) {
            val iso3 = toIso639_2(lang2)
            for (ua in userAgents) {
                try {
                    if (!request.movieHash.isNullOrEmpty() && request.fileByteSize > 0) {
                        val hashUrl = "https://rest.opensubtitles.org/search/moviebytesize-${request.fileByteSize}/moviehash-${request.movieHash}/sublanguageid-$iso3"
                        parseOpenSubRestArray(httpGetString(hashUrl, mapOf("X-User-Agent" to ua)), lang2, MatchQuality.HASH_MATCH, out)
                    }
                    val qEnc = URLEncoder.encode(request.cleanQuery, "UTF-8")
                    val seParams = if (request.season != null && request.episode != null) {
                        "/episode-${request.episode}/season-${request.season}"
                    } else ""
                    val queryUrl = "https://rest.opensubtitles.org/search$seParams/query-$qEnc/sublanguageid-$iso3"
                    parseOpenSubRestArray(httpGetString(queryUrl, mapOf("X-User-Agent" to ua)), lang2, MatchQuality.EXACT_TITLE, out)
                    if (out.isNotEmpty()) break
                } catch (_: Exception) {}
            }
        }
        return out
    }

    private fun parseOpenSubRestArray(jsonStr: String?, lang2: String, quality: MatchQuality, out: MutableList<SubtitleResultItem>) {
        if (jsonStr.isNullOrBlank() || !jsonStr.trim().startsWith("[")) return
        val arr = JSONArray(jsonStr)
        for (i in 0 until minOf(arr.length(), 15)) {
            val obj = arr.optJSONObject(i) ?: continue
            val dl = obj.optString("SubDownloadLink").ifBlank { obj.optString("ZipDownloadLink") }
            if (dl.isBlank()) continue
            val fileName = obj.optString("SubFileName", "Subtitle")
            val downloads = obj.optString("SubDownloadsCnt", "0").toIntOrNull() ?: 0
            val rating = obj.optString("SubRating", "0.0").toDoubleOrNull() ?: 0.0
            out.add(
                SubtitleResultItem(
                    id = obj.optString("IDSubtitleFile", "os_${out.size}"),
                    title = fileName,
                    providerName = name,
                    languageCode = lang2,
                    downloadUrl = dl,
                    formatExt = obj.optString("SubFormat", "srt"),
                    matchQuality = quality,
                    rating = rating,
                    downloadCount = downloads
                )
            )
        }
    }
}

object PodnapisiProvider : SubtitleProvider {
    override val name: String = "Podnapisi"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> {
        val out = mutableListOf<SubtitleResultItem>()
        for (lang2 in request.preferredLanguages) {
            try {
                val qEnc = URLEncoder.encode(request.cleanQuery, "UTF-8")
                var url = "https://www.podnapisi.net/subtitles/search/advanced?keywords=$qEnc&language=$lang2"
                if (request.season != null && request.episode != null) {
                    url += "&seasons=${request.season}&episodes=${request.episode}"
                }
                val jsonStr = httpGetString(url, mapOf("Accept" to "application/json")) ?: continue
                if (!jsonStr.trim().startsWith("{")) continue
                val root = JSONObject(jsonStr)
                val data = root.optJSONArray("data") ?: continue
                for (i in 0 until minOf(data.length(), 12)) {
                    val item = data.optJSONObject(i) ?: continue
                    val dlPath = item.optString("download")
                    if (dlPath.isBlank()) continue
                    val fullDl = if (dlPath.startsWith("http")) dlPath else "https://www.podnapisi.net$dlPath"
                    val releases = item.optJSONArray("custom_releases")
                    val releaseTitle = if (releases != null && releases.length() > 0) {
                        releases.optString(0)
                    } else {
                        item.optJSONObject("movie")?.optString("title") ?: request.cleanQuery
                    }
                    out.add(
                        SubtitleResultItem(
                            id = "pod_${lang2}_$i",
                            title = releaseTitle,
                            providerName = name,
                            languageCode = lang2,
                            downloadUrl = fullDl,
                            formatExt = "zip",
                            matchQuality = MatchQuality.EXACT_TITLE,
                            rating = 8.8,
                            downloadCount = 250 - i
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        return out
    }
}

object YifySubtitlesProvider : SubtitleProvider {
    override val name: String = "YIFY-Subs"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> {
        val out = mutableListOf<SubtitleResultItem>()
        try {
            val resolved = CinemetaImdbResolver.resolveImdbId(request.cleanQuery, false) ?: return emptyList()
            val imdb = resolved.first
            val domains = listOf("https://yts-subs.com", "https://yifysubtitles.ch")
            for (domain in domains) {
                val html = httpGetString("$domain/movie-imdb/$imdb") ?: continue
                val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
                val hrefRegex = Regex("href=\"(/subtitles/[^\"]+)\"")
                for (m in rowRegex.findAll(html)) {
                    val rowHtml = m.groupValues[1]
                    val hrefMatch = hrefRegex.find(rowHtml) ?: continue
                    val subPagePath = hrefMatch.groupValues[1]
                    val lowerRow = rowHtml.lowercase(Locale.ROOT)
                    val matchedLang = when {
                        lowerRow.contains("arabic") -> "ar"
                        lowerRow.contains("english") -> "en"
                        lowerRow.contains("french") -> "fr"
                        lowerRow.contains("spanish") -> "es"
                        lowerRow.contains("turkish") -> "tr"
                        else -> null
                    }
                    if (matchedLang != null && matchedLang in request.preferredLanguages) {
                        val zipPath = subPagePath.replace("/subtitles/", "/subtitle/") + ".zip"
                        val fullDl = "$domain$zipPath"
                        out.add(
                            SubtitleResultItem(
                                id = "yify_${out.size}",
                                title = "${request.cleanQuery} (${matchedLang.uppercase()})",
                                providerName = name,
                                languageCode = matchedLang,
                                downloadUrl = fullDl,
                                formatExt = "zip",
                                matchQuality = MatchQuality.EXACT_TITLE,
                                rating = 8.6,
                                downloadCount = 300 - out.size
                            )
                        )
                    }
                }
                if (out.isNotEmpty()) break
            }
        } catch (_: Exception) {}
        return out
    }
}

object SubdlProvider : SubtitleProvider {
    override val name: String = "SubDL"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> {
        val out = mutableListOf<SubtitleResultItem>()
        val apiKey = BuildConfig.SUBDL_API_KEY
        if (apiKey.isNotBlank()) {
            try {
                val qEnc = URLEncoder.encode(request.cleanQuery, "UTF-8")
                val langs = request.preferredLanguages.joinToString(",") { it.uppercase(Locale.ROOT) }
                val apiUrl = "https://api.subdl.com/api/v1/subtitles?api_key=$apiKey&film_name=$qEnc&languages=$langs&subs_per_page=15"
                val jsonStr = httpGetString(apiUrl)
                if (!jsonStr.isNullOrBlank() && jsonStr.trim().startsWith("{")) {
                    val root = JSONObject(jsonStr)
                    val subs = root.optJSONArray("subtitles")
                    if (subs != null) {
                        for (i in 0 until subs.length()) {
                            val s = subs.optJSONObject(i) ?: continue
                            val link = s.optString("url")
                            if (link.isBlank()) continue
                            val fullDl = if (link.startsWith("http")) link else "https://dl.subdl.com$link"
                            val lCode = normalizeLangCode(s.optString("language", "ar"))
                            out.add(
                                SubtitleResultItem(
                                    id = "subdl_$i",
                                    title = s.optString("release_name", request.cleanQuery),
                                    providerName = name,
                                    languageCode = lCode,
                                    downloadUrl = fullDl,
                                    formatExt = "zip",
                                    matchQuality = MatchQuality.EXACT_TITLE,
                                    rating = 9.0,
                                    downloadCount = 300 - i
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return out
    }
}

private fun httpGetString(urlStr: String, headers: Map<String, String> = emptyMap()): String? {
    return try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 9000
            readTimeout = 9000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }
        if (conn.responseCode in 200..299) {
            val bytes = conn.inputStream.use { SubtitleSearchEngine.readBytesBounded(it, 4 * 1024 * 1024) }
            String(bytes, Charsets.UTF_8)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun normalizeLangCode(raw: String): String {
    val clean = raw.trim().lowercase(Locale.ROOT)
    return when {
        clean.startsWith("ara") || clean == "ar" || clean.contains("arabic") -> "ar"
        clean.startsWith("eng") || clean == "en" || clean.contains("english") -> "en"
        clean.startsWith("fre") || clean.startsWith("fra") || clean == "fr" -> "fr"
        clean.startsWith("spa") || clean == "es" -> "es"
        clean.startsWith("tur") || clean == "tr" -> "tr"
        clean.startsWith("hin") || clean == "hi" -> "hi"
        clean.startsWith("urd") || clean == "ur" -> "ur"
        else -> clean.take(2)
    }
}

private fun toIso639_2(lang2: String): String {
    return when (lang2.lowercase(Locale.ROOT)) {
        "ar" -> "ara"
        "en" -> "eng"
        "fr" -> "fre"
        "es" -> "spa"
        "tr" -> "tur"
        "hi" -> "hin"
        "ur" -> "urd"
        else -> "eng"
    }
}

object SubtitleSearchEngine {

    private val providers: List<SubtitleProvider> = listOf(
        StremioOpenSubtitlesV3Provider,
        OpenSubtitlesRestProvider,
        PodnapisiProvider,
        YifySubtitlesProvider,
        SubdlProvider
    )

    fun buildSearchRequest(
        context: Context,
        videoUri: Uri,
        rawTitle: String,
        preferredLanguages: List<String>,
        customQuery: String? = null
    ): SubtitleSearchRequest {
        val baseName = (customQuery?.takeIf { it.isNotBlank() } ?: rawTitle)
            .substringBeforeLast(".")
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')

        val seMatch = Regex("[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})").find(baseName)
            ?: Regex("(\\d{1,2})x(\\d{1,3})").find(baseName)
        val epOnlyMatch = Regex("(?:[Ee][Pp]?|Episode|الحلقة)\\s*(\\d{1,3})", RegexOption.IGNORE_CASE).find(baseName)

        val season = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: if (epOnlyMatch != null) 1 else null
        val episode = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: epOnlyMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        val yearMatch = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(baseName)
        val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        val cleanQuery = baseName
            .replace(Regex("[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}.*"), "")
            .replace(Regex("\\b\\d{1,2}x\\d{1,3}\\b.*"), "")
            .replace(Regex("(?:[Ee][Pp]?|Episode|الحلقة)\\s*\\d{1,3}.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(19\\d{2}|20\\d{2})\\b.*"), "")
            .replace(Regex("(2160p|1080p|720p|480p|360p|4K|BluRay|BRRip|WEBRip|WEB-DL|WEB|HDRip|HDTV|x264|x265|HEVC|AAC|10bit|YTS|YIFY|RARBG|PSA|EgyBest|Wecima|MyCima|FaselHD|ArabSeed|Cima4u|AnimeSlayer).*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { baseName.trim().ifEmpty { "Movie" } }

        val hashPair = if (videoUri.scheme == "content" || videoUri.scheme == "file") {
            OpenSubtitlesHashCalculator.computeHashAndSize(context, videoUri)
        } else null

        return SubtitleSearchRequest(
            videoUri = videoUri,
            rawTitle = rawTitle,
            cleanQuery = cleanQuery,
            year = year,
            season = season,
            episode = episode,
            movieHash = hashPair?.first,
            fileByteSize = hashPair?.second ?: 0L,
            preferredLanguages = preferredLanguages
        )
    }

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

        var allResults = try {
            withTimeout(15_000L) {
                coroutineScope {
                    providers.map { provider ->
                        async {
                            try {
                                provider.search(request)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
            }
        } catch (_: TimeoutCancellationException) {
            return@withContext SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.TIMEOUT)
        }

        // If full cleaned title returned 0 results and has >2 words, automatically retry with the first 2 main words!
        if (allResults.isEmpty()) {
            val words = request.cleanQuery.split(" ").filter { it.length >= 2 }
            if (words.size > 2) {
                val shorterRequest = request.copy(cleanQuery = words.take(2).joinToString(" "))
                allResults = try {
                    withTimeout(12_000L) {
                        coroutineScope {
                            providers.map { provider ->
                                async {
                                    try {
                                        provider.search(shorterRequest)
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                }
                            }.awaitAll().flatten()
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        if (allResults.isEmpty()) {
            return@withContext SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.NOT_FOUND)
        }

        val ranked = allResults.sortedWith(
            compareBy<SubtitleResultItem> {
                val idx = preferredLangs.indexOf(it.languageCode)
                if (idx == -1) 999 else idx
            }.thenByDescending { it.matchQuality.scoreBoost }
                .thenByDescending { it.rating }
                .thenByDescending { it.downloadCount }
        )

        for (candidate in ranked.take(6)) {
            val downloaded = downloadAndCacheSubtitle(context, candidate)
            if (downloaded != null && downloaded.second.isNotEmpty()) {
                PlaybackStore.saveVideoSubtitleCachePath(context, videoUri, downloaded.first.absolutePath)
                return@withContext SubtitleSearchOutcome.Success(
                    bestItem = candidate,
                    autoAppliedCues = downloaded.second,
                    results = ranked
                )
            }
        }

        SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.CORRUPTED_FILE)
    }

    suspend fun downloadAndCacheSubtitle(
        context: Context,
        item: SubtitleResultItem
    ): Pair<File, List<SubtitleCue>>? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.filesDir, "subtitles_cache").apply { mkdirs() }
            val safeId = item.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val cachedFile = File(cacheDir, "sub_${item.languageCode}_$safeId.srt")

            if (cachedFile.exists() && cachedFile.length() > 64) {
                val cachedCues = SubtitleParser.parseFromFile(cachedFile)
                if (cachedCues.isNotEmpty()) {
                    return@withContext Pair(cachedFile, cachedCues)
                }
            }

            val conn = (URL(item.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
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

    fun readBytesBounded(input: InputStream, maxBytes: Int = 8 * 1024 * 1024): ByteArray {
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
