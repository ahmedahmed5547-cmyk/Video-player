package com.pureplayer.video

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed class VideoLibraryUiState {
    object Loading : VideoLibraryUiState()
    data class Success(
        val videos: List<VideoItem>,
        val folders: List<VideoFolder>,
        val isRefreshing: Boolean = false
    ) : VideoLibraryUiState()
    object Empty : VideoLibraryUiState()
    object PermissionDenied : VideoLibraryUiState()
}

class VideoLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val _uiState = MutableStateFlow<VideoLibraryUiState>(VideoLibraryUiState.Loading)
    val uiState: StateFlow<VideoLibraryUiState> = _uiState.asStateFlow()

    private val isScanRunning = AtomicBoolean(false)
    private var scanJob: Job? = null
    private var observerDebounceJob: Job? = null
    private var hasRunFreshInstallScannerFallback = false
    private var hasMediaChangedSinceLastLoad = false
    private var lastKnownPermissionState = false
    private var isObserverRegistered = false

    private val supportedVideoExtensions = setOf(
        "mkv", "mp4", "avi", "mov", "webm", "ts", "m2ts", "mts",
        "m4v", "flv", "wmv", "3gp", "3g2", "ogv", "divx", "vob", "f4v", "rmvb", "asf", "mpg", "mpeg"
    )

    private val releaseVideoNameRegex = Regex(
        "(?i)(s\\d{1,2}[._\\s-]*e\\d{1,3}|\\b(?:2160p|1080p|720p|480p|h264|x264|h265|x265|hevc|webrip|web-dl|bluray|bdrip|hdrip)\\b)"
    )

    private val mediaContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            hasMediaChangedSinceLastLoad = true
            observerDebounceJob?.cancel()
            observerDebounceJob = viewModelScope.launch {
                delay(500L)
                if (hasVideoAccessPermission()) {
                    refreshLibrary(force = true, showLoadingIfEmpty = false)
                }
            }
        }
    }

    fun hasVideoAccessPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureObserverRegistered() {
        if (isObserverRegistered) return
        runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaContentObserver
            )
            isObserverRegistered = true
        }
    }

    fun onPermissionGranted() {
        lastKnownPermissionState = true
        ensureObserverRegistered()
        refreshLibrary(force = true, showLoadingIfEmpty = true)
    }

    fun onPermissionDenied() {
        lastKnownPermissionState = false
        _uiState.value = VideoLibraryUiState.PermissionDenied
    }

    fun onActivityResumed() {
        val granted = hasVideoAccessPermission()
        val permissionJustChanged = granted != lastKnownPermissionState
        lastKnownPermissionState = granted

        if (!granted) {
            _uiState.value = VideoLibraryUiState.PermissionDenied
            return
        }

        ensureObserverRegistered()
        val currentState = _uiState.value
        val firstScanDone = PlaybackStore.isFirstLibraryScanDone(appContext)
        val needsScan = permissionJustChanged ||
            hasMediaChangedSinceLastLoad ||
            currentState is VideoLibraryUiState.PermissionDenied ||
            (!firstScanDone && currentState !is VideoLibraryUiState.Success && !isScanRunning.get())

        if (needsScan) {
            refreshLibrary(
                force = false,
                showLoadingIfEmpty = !firstScanDone && currentState !is VideoLibraryUiState.Success,
                isUserPullRefresh = false
            )
        }
    }

    fun refreshLibrary(
        force: Boolean = true,
        showLoadingIfEmpty: Boolean = true,
        isUserPullRefresh: Boolean = false
    ) {
        if (!hasVideoAccessPermission()) {
            _uiState.value = VideoLibraryUiState.PermissionDenied
            return
        }
        ensureObserverRegistered()

        // Avoid cancelling an active first-install scan unless the user explicitly requested a forced refresh
        if (!force && isScanRunning.get()) return

        if (force) {
            scanJob?.cancel()
        }

        val firstScanDone = PlaybackStore.isFirstLibraryScanDone(appContext)
        val currentState = _uiState.value
        if (currentState is VideoLibraryUiState.Success) {
            if (isUserPullRefresh) {
                _uiState.value = currentState.copy(isRefreshing = true)
            }
        } else {
            // Instant cache hydration before MediaStore query — ZERO spinner on normal launches!
            val cached = runCatching { VideoIndexCache.getInstantCachedVideos(appContext) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                val cachedFolders = groupVideosIntoFolders(cached)
                _uiState.value = VideoLibraryUiState.Success(cached, cachedFolders, isRefreshing = isUserPullRefresh)
            } else if (showLoadingIfEmpty && !firstScanDone) {
                _uiState.value = VideoLibraryUiState.Loading
            }
        }

        scanJob = viewModelScope.launch {
            isScanRunning.set(true)
            hasMediaChangedSinceLastLoad = false
            try {
                val scanResult = withContext(Dispatchers.IO) {
                    queryAllDeviceVideos()
                }
                val videoList = scanResult.first
                val unindexedPaths = scanResult.second
                val folders = groupVideosIntoFolders(videoList)

                withContext(Dispatchers.IO) {
                    runCatching { VideoIndexCache.saveCachedVideos(appContext, videoList) }
                }
                PlaybackStore.setFirstLibraryScanDone(appContext, true)

                if (videoList.isEmpty()) {
                    _uiState.value = VideoLibraryUiState.Empty
                    if (!hasRunFreshInstallScannerFallback) {
                        hasRunFreshInstallScannerFallback = true
                        triggerOneTimeMediaScannerFallback(unindexedPaths)
                    }
                } else {
                    _uiState.value = VideoLibraryUiState.Success(
                        videos = videoList,
                        folders = folders,
                        isRefreshing = false
                    )
                    if (unindexedPaths.isNotEmpty() && !hasRunFreshInstallScannerFallback) {
                        hasRunFreshInstallScannerFallback = true
                        triggerOneTimeMediaScannerFallback(unindexedPaths)
                    }
                }
            } catch (e: Exception) {
                Log.w("VideoLibraryViewModel", "Library scan failed: ${e.javaClass.simpleName}")
                val fallbackState = _uiState.value
                if (fallbackState is VideoLibraryUiState.Success) {
                    _uiState.value = fallbackState.copy(isRefreshing = false)
                } else if (fallbackState is VideoLibraryUiState.Loading) {
                    _uiState.value = VideoLibraryUiState.Empty
                }
            } finally {
                isScanRunning.set(false)
            }
        }
    }

    private fun triggerOneTimeMediaScannerFallback(explicitPaths: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val pathsToScan = if (explicitPaths.isNotEmpty()) {
                explicitPaths
            } else {
                collectCandidateMediaPaths()
            }
            if (pathsToScan.isEmpty()) return@launch

            val remaining = AtomicInteger(pathsToScan.size)
            runCatching {
                MediaScannerConnection.scanFile(
                    appContext,
                    pathsToScan.toTypedArray(),
                    null
                ) { _, _ ->
                    if (remaining.decrementAndGet() <= 0) {
                        viewModelScope.launch {
                            delay(250L)
                            if (hasVideoAccessPermission()) {
                                refreshLibrary(force = false, showLoadingIfEmpty = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun collectCandidateMediaPaths(): List<String> {
        val paths = mutableListOf<String>()
        val roots = linkedSetOf<File>()
        runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { roots.add(it) } }
        runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)?.let { roots.add(it) } }
        runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.let { roots.add(it) } }
        runCatching {
            Environment.getExternalStorageDirectory()?.let { ext ->
                roots.add(File(ext, "Video"))
                roots.add(File(ext, "Videos"))
                roots.add(File(ext, "Telegram"))
            }
        }
        fun walk(dir: File, depth: Int) {
            if (depth > 3 || !dir.exists() || !dir.isDirectory || !dir.canRead() || paths.size >= 150) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (child.isFile && child.length() > 0L && isRecognizedVideoFile(child.name, null, child.length())) {
                    paths.add(child.absolutePath)
                    if (paths.size >= 150) return
                }
            }
        }
        for (root in roots) walk(root, 0)
        return paths
    }

    private fun isRecognizedVideoFile(fileName: String, mimeType: String? = null, sizeBytes: Long = 0L): Boolean {
        val cleanName = fileName.trim()
        if (cleanName.isEmpty() || cleanName.startsWith(".")) return false
        val ext = cleanName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in supportedVideoExtensions) return true
        val mime = mimeType?.lowercase(Locale.ROOT) ?: ""
        if (mime.startsWith("video/") || mime.contains("matroska") || mime.contains("mpegurl")) return true
        if (sizeBytes >= 5L * 1024L * 1024L && ext !in setOf(
                "zip", "rar", "7z", "apk", "pdf", "iso", "srt", "ass", "sub",
                "txt", "jpg", "png", "mp3", "flac", "wav", "part", "crdownload"
            )
        ) {
            if (releaseVideoNameRegex.containsMatchIn(cleanName)) return true
        }
        return false
    }

    private fun queryAllDeviceVideos(): Pair<List<VideoItem>, List<String>> {
        val list = mutableListOf<VideoItem>()
        val seenIds = HashSet<Long>()
        val seenPaths = HashSet<String>()
        val unindexedPaths = mutableListOf<String>()
        val cr = appContext.contentResolver

        // Stage 1: Standard MediaStore.Video.Media table
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED
        )
        runCatching {
            cr.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Video.Media.SIZE} > 0",
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(nameCol) ?: continue
                    val duration = cursor.getLong(durCol)
                    val size = cursor.getLong(sizeCol)
                    val path = if (dataCol != -1) (cursor.getString(dataCol) ?: "") else ""
                    val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                    val height = if (heightCol != -1) cursor.getInt(heightCol) else 0
                    val dateAddedSec = if (dateAddedCol != -1) cursor.getLong(dateAddedCol) else 0L
                    val bucketName = if (bucketCol != -1) cursor.getString(bucketCol) else null
                    val folderName = when {
                        !bucketName.isNullOrBlank() -> bucketName
                        path.isNotEmpty() -> File(path).parentFile?.name ?: "Videos"
                        else -> "Videos"
                    }
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    seenIds.add(id)
                    if (path.isNotEmpty()) seenPaths.add(path.lowercase(Locale.ROOT))
                    list.add(
                        VideoItem(
                            id = id,
                            title = title,
                            uri = contentUri,
                            duration = duration,
                            size = size,
                            folderName = folderName,
                            width = width,
                            height = height,
                            path = path,
                            dateAddedSec = dateAddedSec
                        )
                    )
                }
            }
        }

        // Stage 2: Deep MediaStore.Files query for MKV & unindexed video containers
        runCatching {
            val filesUri = MediaStore.Files.getContentUri("external")
            val filesProj = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
            )
            val filesSelection = "${MediaStore.Files.FileColumns.SIZE} > 0 AND (" +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.mkv' OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.mp4' OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.avi' OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.mov' OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.webm' OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.ts' OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.m2ts' OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.m4v' OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.flv' OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'video/%' OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE '%matroska%' OR " +
                "${MediaStore.Files.FileColumns.SIZE} > 5242880)"

            cr.query(
                filesUri,
                filesProj,
                filesSelection,
                null,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    if (id in seenIds) continue
                    val path = if (dataCol != -1) (cursor.getString(dataCol) ?: "") else ""
                    if (path.isNotEmpty() && path.lowercase(Locale.ROOT) in seenPaths) continue
                    val rawTitle = (if (nameCol != -1) cursor.getString(nameCol) else null)
                        ?: (if (path.isNotEmpty()) File(path).name else null)
                        ?: continue
                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val mime = if (mimeCol != -1) cursor.getString(mimeCol) else null
                    if (!isRecognizedVideoFile(rawTitle, mime, size)) continue

                    val duration = if (durCol != -1) cursor.getLong(durCol) else 0L
                    val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                    val height = if (heightCol != -1) cursor.getInt(heightCol) else 0
                    val dateAddedSec = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                    val bucketName = if (bucketCol != -1) cursor.getString(bucketCol) else null
                    val folderName = when {
                        !bucketName.isNullOrBlank() -> bucketName
                        path.isNotEmpty() -> File(path).parentFile?.name ?: "Downloads"
                        else -> "Downloads"
                    }
                    val contentUri = ContentUris.withAppendedId(filesUri, id)
                    seenIds.add(id)
                    if (path.isNotEmpty()) seenPaths.add(path.lowercase(Locale.ROOT))
                    list.add(
                        VideoItem(
                            id = id,
                            title = rawTitle,
                            uri = contentUri,
                            duration = duration,
                            size = size,
                            folderName = folderName,
                            width = width,
                            height = height,
                            path = path,
                            dateAddedSec = dateAddedSec
                        )
                    )
                }
            }
        }

        // Stage 3: Filesystem fallback for unindexed videos in Download, Movies, DCIM, Video, Telegram
        runCatching {
            val candidatePaths = collectCandidateMediaPaths()
            for (absPath in candidatePaths) {
                val normPath = absPath.lowercase(Locale.ROOT)
                if (normPath in seenPaths) continue
                val child = File(absPath)
                if (!child.exists() || !child.isFile || child.length() <= 0L) continue
                seenPaths.add(normPath)
                unindexedPaths.add(absPath)
                val syntheticId = (absPath.hashCode().toLong() and 0x7FFFFFFFL) + 900_000_000L
                val folderName = child.parentFile?.name ?: "Download"
                list.add(
                    VideoItem(
                        id = syntheticId,
                        title = child.name,
                        uri = Uri.fromFile(child),
                        duration = 0L,
                        size = child.length(),
                        folderName = folderName,
                        width = 0,
                        height = 0,
                        path = absPath,
                        dateAddedSec = child.lastModified() / 1000L
                    )
                )
            }
        }

        return Pair(list, unindexedPaths)
    }

    private fun groupVideosIntoFolders(videos: List<VideoItem>): List<VideoFolder> {
        return videos
            .groupBy { it.folderName }
            .filter { (_, vids) -> vids.isNotEmpty() }
            .map { (folderName, vids) -> VideoFolder(folderName, vids) }
            .sortedByDescending { it.videos.size }
    }

    override fun onCleared() {
        observerDebounceJob?.cancel()
        scanJob?.cancel()
        if (isObserverRegistered) {
            runCatching { appContext.contentResolver.unregisterContentObserver(mediaContentObserver) }
            isObserverRegistered = false
        }
        super.onCleared()
    }
}
