package com.pureplayer.video

import android.app.Application
import android.content.Context
import android.net.Uri
import com.google.android.material.color.DynamicColors
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

object PlaybackStore {
    private const val PREF_NAME = "pure_video_playback_store"
    private const val KEY_LAST_URI = "last_video_uri"
    private const val KEY_LAST_TITLE = "last_video_title"
    private const val KEY_LAST_POSITION = "last_video_position"
    private const val PREFIX_POS = "pos_"

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
