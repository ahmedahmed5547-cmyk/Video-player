package com.pureplayer.video

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.google.android.material.color.DynamicColors
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

class PureVideoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
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

    private const val KEY_THEME_INDEX = "app_theme_index"
    private const val KEY_SUB_SIZE_SP = "sub_font_size_sp"
    private const val KEY_SUB_COLOR_INDEX = "sub_color_index"
    private const val KEY_SUB_BG_ENABLED = "sub_bg_enabled"
    private const val KEY_SUB_Y_BIAS = "sub_vertical_offset_px"
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
        val title = prefs.getString(KEY_LAST_TITLE, "آخر فيديو") ?: "آخر فيديو"
        val pos = prefs.getLong(PREFIX_POS + uriStr, prefs.getLong(KEY_LAST_POSITION, 0L))
        return Triple(Uri.parse(uriStr), title, pos)
    }

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
            .putFloat(KEY_SUB_SIZE_SP, sizeSp.coerceIn(14f, 42f)).apply()
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

    fun isSubBgEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SUB_BG_ENABLED, true)

    fun setSubBgEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SUB_BG_ENABLED, enabled).apply()
    }

    fun getSubVerticalOffsetPx(context: Context): Float =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getFloat(KEY_SUB_Y_BIAS, 0f)

    fun setSubVerticalOffsetPx(context: Context, offsetPx: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_SUB_Y_BIAS, offsetPx).apply()
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

object SubtitleParser {
    fun parseFromUri(context: Context, uri: Uri): List<SubtitleCue> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        return parseFromBytes(bytes)
    }

    fun parseFromBytes(bytes: ByteArray): List<SubtitleCue> {
        if (bytes.isEmpty()) return emptyList()

        var content = String(bytes, Charsets.UTF_8)
        if (content.contains("\uFFFD")) {
            try {
                content = String(bytes, Charset.forName("windows-1256"))
            } catch (_: Exception) {}
        }

        val cues = mutableListOf<SubtitleCue>()
        val lines = content.lines()
        var i = 0
        val timeRegex = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{2,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{2,3})")

        while (i < lines.size) {
            val line = lines[i].trim()
            val srtMatch = timeRegex.find(line)
            if (srtMatch != null) {
                val startMs = toMs(srtMatch.groupValues[1], srtMatch.groupValues[2], srtMatch.groupValues[3], srtMatch.groupValues[4])
                val endMs = toMs(srtMatch.groupValues[5], srtMatch.groupValues[6], srtMatch.groupValues[7], srtMatch.groupValues[8])
                i++
                val textLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    val cleaned = lines[i].trim()
                        .replace(Regex("<[^>]*>"), "")
                        .replace(Regex("\\{[^}]*\\}"), "")
                    if (cleaned.isNotEmpty()) textLines.add(cleaned)
                    i++
                }
                if (textLines.isNotEmpty()) {
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
