package com.pureplayer.video

import java.io.File
import java.util.Locale

/**
 * Pure-Kotlin helpers & constants for:
 * 1. Hidden folder / hidden video classification & filtering (dot-folders, .nomedia, .trashed, Android/data)
 * 2. Player Previous / Next playlist index navigation & tap debouncing
 */
object HiddenMediaAndPlaylistHelper {

    const val SWITCH_DEBOUNCE_MS = 450L
    const val DEFAULT_SHOW_HIDDEN_FOLDERS = true
    const val MAX_SHARE_ITEMS = 100
    const val LARGE_SHARE_BYTES_THRESHOLD = 50L * 1024L * 1024L
    const val LARGE_SHARE_COUNT_THRESHOLD = 5

    data class ShareBatchSummary(
        val totalCount: Int,
        val totalBytes: Long,
        val hasFolderSelection: Boolean,
        val requiresConfirmation: Boolean,
        val exceedsMaxLimit: Boolean
    )

    fun collectUniqueVideosForShare(
        selectedVideos: List<VideoItem>,
        selectedFolders: List<VideoFolder>,
        showHidden: Boolean
    ): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        val seen = hashSetOf<String>()
        fun addIfEligible(v: VideoItem) {
            if (!showHidden && v.isHidden) return
            val key = v.path.takeIf { it.isNotBlank() } ?: v.uri.toString()
            if (seen.add(key)) out.add(v)
        }
        selectedVideos.forEach { addIfEligible(it) }
        selectedFolders.forEach { folder ->
            if (!showHidden && folder.isHidden) return@forEach
            folder.videos.forEach { addIfEligible(it) }
        }
        return out
    }

    fun summarizeShareBatch(
        videoSizes: List<Long>,
        hasFolderSelection: Boolean
    ): ShareBatchSummary {
        val count = videoSizes.size
        val totalBytes = videoSizes.sumOf { it.coerceAtLeast(0L) }
        val exceedsMax = count > MAX_SHARE_ITEMS
        val needsConfirm = hasFolderSelection ||
            count >= LARGE_SHARE_COUNT_THRESHOLD ||
            totalBytes >= LARGE_SHARE_BYTES_THRESHOLD
        return ShareBatchSummary(
            totalCount = count,
            totalBytes = totalBytes,
            hasFolderSelection = hasFolderSelection,
            requiresConfirmation = needsConfirm,
            exceedsMaxLimit = exceedsMax
        )
    }

    fun formatBytesReadable(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
            b >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
            b >= 1024L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
            else -> "$b B"
        }
    }

    /**
     * Pure-Kotlin path check (no filesystem I/O) to classify whether a video path/folder is hidden:
     * - Any folder or file segment starting with "." (e.g. ".Statuses", ".hidden", ".trashed-123-clip.mp4")
     * - Located inside Android/data or Android/obb hidden app directories
     * - Or inside a folder (or ancestor folder) containing a ".nomedia" file (passed via [hasNomediaAncestor])
     */
    fun isHiddenMedia(
        path: String,
        folderName: String,
        title: String = "",
        hasNomediaAncestor: Boolean = false
    ): Boolean {
        if (hasNomediaAncestor) return true
        val trimmedFolder = folderName.trim()
        if (trimmedFolder.startsWith(".")) return true
        val trimmedTitle = title.trim()
        if (trimmedTitle.startsWith(".")) return true

        if (path.isNotBlank()) {
            val normalized = path.replace('\\', '/')
            val lower = normalized.lowercase(Locale.ROOT)
            if (lower.contains("/android/data/") || lower.contains("/android/obb/")) {
                return true
            }
            val segments = normalized.split('/')
            for (seg in segments) {
                if (seg.startsWith(".") && seg != "." && seg != "..") {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Checks whether [file]'s directory or any ancestor up to external storage root contains a `.nomedia` file.
     * Uses an optional memoization cache [nomediaCache] so scanning hundreds of videos is O(folders).
     */
    fun hasNomediaInParentChain(
        filePath: String,
        nomediaCache: MutableMap<String, Boolean>
    ): Boolean {
        if (filePath.isBlank()) return false
        var dir = runCatching { File(filePath).parentFile }.getOrNull() ?: return false
        var depth = 0
        while (depth < 8) {
            val key = dir.absolutePath
            val cached = nomediaCache[key]
            if (cached != null) {
                if (cached) return true
            } else {
                val exists = runCatching { File(dir, ".nomedia").exists() }.getOrDefault(false)
                nomediaCache[key] = exists
                if (exists) return true
            }
            val parent = dir.parentFile ?: break
            if (parent.absolutePath == key || key == "/storage/emulated/0" || key == "/storage") break
            dir = parent
            depth++
        }
        return false
    }

    inline fun <T> filterHiddenItems(
        items: List<T>,
        showHidden: Boolean,
        isHiddenSelector: (T) -> Boolean
    ): List<T> {
        return if (showHidden) items else items.filter { !isHiddenSelector(it) }
    }

    fun filterVideosByHiddenPreference(
        videos: List<VideoItem>,
        showHidden: Boolean
    ): List<VideoItem> {
        if (showHidden) return videos
        return videos.filter { !it.isHidden }
    }

    fun filterFoldersByHiddenPreference(
        folders: List<VideoFolder>,
        showHidden: Boolean
    ): List<VideoFolder> {
        if (showHidden) return folders
        return folders.mapNotNull { folder ->
            if (folder.isHidden) return@mapNotNull null
            val visibleVideos = folder.videos.filter { !it.isHidden }
            if (visibleVideos.isEmpty()) null else folder.copy(videos = visibleVideos)
        }
    }

    // =========================================================================
    // Player Previous / Next Playlist Navigation (Pure Kotlin)
    // =========================================================================

    fun canGoPrevious(currentIndex: Int, playlistSize: Int, repeatAll: Boolean = false): Boolean {
        if (playlistSize <= 1 || currentIndex !in 0 until playlistSize) return false
        return repeatAll || currentIndex > 0
    }

    fun canGoNext(currentIndex: Int, playlistSize: Int, repeatAll: Boolean = false): Boolean {
        if (playlistSize <= 1 || currentIndex !in 0 until playlistSize) return false
        return repeatAll || currentIndex < playlistSize - 1
    }

    fun computePreviousIndex(currentIndex: Int, playlistSize: Int, repeatAll: Boolean = false): Int? {
        if (!canGoPrevious(currentIndex, playlistSize, repeatAll)) return null
        return if (currentIndex > 0) currentIndex - 1 else playlistSize - 1
    }

    fun computeNextIndex(currentIndex: Int, playlistSize: Int, repeatAll: Boolean = false): Int? {
        if (!canGoNext(currentIndex, playlistSize, repeatAll)) return null
        return if (currentIndex < playlistSize - 1) currentIndex + 1 else 0
    }

    fun shouldAcceptSwitch(
        nowMs: Long,
        lastSwitchMs: Long,
        debounceMs: Long = SWITCH_DEBOUNCE_MS
    ): Boolean {
        return lastSwitchMs <= 0L || (nowMs - lastSwitchMs) >= debounceMs
    }
}
