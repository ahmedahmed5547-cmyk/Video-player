package com.pureplayer.video

import android.net.Uri

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

