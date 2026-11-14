package com.pureplayer.video

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object VideoIndexCache {
    private const val CACHE_FILE_NAME = "video_media_index_cache_v1.json"

    @Volatile
    private var memoryVideos: List<VideoItem>? = null

    fun getInstantCachedVideos(context: Context): List<VideoItem> {
        memoryVideos?.let { if (it.isNotEmpty()) return it }
        return try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            if (!file.exists() || file.length() <= 2L) return emptyList()
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val result = ArrayList<VideoItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optLong("i", 0L)
                val title = obj.optString("t", "")
                val uriStr = obj.optString("u", "")
                if (title.isEmpty() || uriStr.isEmpty()) continue
                result.add(
                    VideoItem(
                        id = id,
                        title = title,
                        uri = Uri.parse(uriStr),
                        duration = obj.optLong("d", 0L),
                        size = obj.optLong("s", 0L),
                        folderName = obj.optString("f", "Videos"),
                        width = obj.optInt("w", 0),
                        height = obj.optInt("h", 0),
                        path = obj.optString("p", ""),
                        dateAddedSec = obj.optLong("a", 0L)
                    )
                )
            }
            memoryVideos = result
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCachedVideos(context: Context, videos: List<VideoItem>) {
        memoryVideos = videos
        try {
            val arr = JSONArray()
            for (v in videos) {
                val obj = JSONObject().apply {
                    put("i", v.id)
                    put("t", v.title)
                    put("u", v.uri.toString())
                    put("d", v.duration)
                    put("s", v.size)
                    put("f", v.folderName)
                    put("w", v.width)
                    put("h", v.height)
                    put("p", v.path)
                    put("a", v.dateAddedSec)
                }
                arr.put(obj)
            }
            val file = File(context.filesDir, CACHE_FILE_NAME)
            file.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) { Log.w("VideoIndexCache", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
    }
}
