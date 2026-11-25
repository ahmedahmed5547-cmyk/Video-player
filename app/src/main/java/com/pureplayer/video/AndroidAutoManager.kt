package com.pureplayer.video

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * User-configurable Android Auto preferences persisted under `vexo_*` keys in PlaybackStore.
 */
data class AndroidAutoConfig(
    val enabled: Boolean = true,
    val autoResumeOnConnect: Boolean = false,
    val audioOnlyOnCar: Boolean = true,
    val minimalCarDisplay: Boolean = true
)

/**
 * Live connection & readiness state reported by VexoPlaybackService to Settings diagnostics UI.
 */
data class AndroidAutoConnectionSnapshot(
    val isServiceRunning: Boolean = false,
    val isCarConnected: Boolean = false,
    val connectedPackage: String = "",
    val connectedControllerCount: Int = 0,
    val lastConnectedAtMs: Long = 0L
)

/**
 * Snapshot produced by the "Test / Check Android Auto Status" diagnostic action.
 */
data class AndroidAutoDiagnosticReport(
    val config: AndroidAutoConfig,
    val connection: AndroidAutoConnectionSnapshot,
    val serviceRegisteredInManifest: Boolean,
    val automotiveDescDeclared: Boolean,
    val favoritesCount: Int,
    val categoriesCount: Int,
    val recentCount: Int,
    val activeTitle: String,
    val isPlaying: Boolean
)

/**
 * Pure-Kotlin Car UX Restriction policy and text/artwork sanitizer.
 * Intentionally free of Android Uri.EMPTY or Context dependencies so it is 100% JVM unit-testable.
 */
object AndroidAutoUxPolicy {
    const val MAX_ROOT_ITEMS = 6
    const val MAX_CATEGORY_ITEMS = 30
    const val MAX_LEAF_ITEMS = 40
    const val MAX_RECENT_ITEMS = 25
    const val MAX_SEARCH_ITEMS = 20
    const val MAX_TITLE_CHARS = 48
    const val MAX_SUBTITLE_CHARS = 40

    private val CAR_CONTROLLER_PACKAGES = setOf(
        "com.google.android.projection.gearhead",
        "com.google.android.autosimulator",
        "com.google.android.car",
        "com.android.car",
        "com.google.android.googlequicksearchbox"
    )

    private val EXTRA_NOISE_REGEX = Regex("\\s*\\[(?:HD|FHD|UHD|4K|SD|HEVC|H\\.?265|VIP|RAW)[^\\]]*]\\s*", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    fun isAndroidAutoControllerPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val normalized = packageName.trim().lowercase()
        return CAR_CONTROLLER_PACKAGES.any { normalized == it || normalized.startsWith("$it.") } ||
            normalized.contains("gearhead") ||
            normalized.contains("androidauto")
    }

    fun sanitizeLabel(raw: String?, maxChars: Int, fallback: String): String {
        val cleaned = raw
            ?.replace("\n", " ")
            ?.replace("\r", " ")
            ?.replace(MULTI_SPACE_REGEX, " ")
            ?.trim()
            .orEmpty()
        if (cleaned.isEmpty()) return fallback
        if (maxChars <= 1 || cleaned.length <= maxChars) return cleaned
        return cleaned.take(maxChars - 1).trimEnd() + "…"
    }

    /**
     * Formats title & subtitle according to Car UX restrictions and the user's minimal-display setting.
     * When [AndroidAutoConfig.minimalCarDisplay] is true, strips noisy tags/details and shows only
     * the concise channel/group name to minimize driver distraction.
     */
    fun formatDisplayMetadata(
        rawTitle: String?,
        rawGroupOrSource: String?,
        rawExtraDetail: String? = null,
        config: AndroidAutoConfig = AndroidAutoConfig()
    ): Pair<String, String> {
        val titleBase = if (config.minimalCarDisplay) {
            rawTitle.orEmpty().replace(EXTRA_NOISE_REGEX, " ")
        } else {
            rawTitle.orEmpty()
        }
        val title = sanitizeLabel(titleBase, MAX_TITLE_CHARS, "V Player")

        val groupClean = sanitizeLabel(rawGroupOrSource, MAX_SUBTITLE_CHARS, "")
        val subtitle = if (config.minimalCarDisplay) {
            groupClean.ifEmpty { "V Player" }
        } else {
            val extraClean = sanitizeLabel(rawExtraDetail, MAX_SUBTITLE_CHARS, "")
            when {
                groupClean.isNotEmpty() && extraClean.isNotEmpty() && !groupClean.equals(extraClean, ignoreCase = true) ->
                    sanitizeLabel("$groupClean • $extraClean", MAX_SUBTITLE_CHARS, groupClean)
                groupClean.isNotEmpty() -> groupClean
                extraClean.isNotEmpty() -> extraClean
                else -> "V Player"
            }
        }
        return title to subtitle
    }

    /**
     * In Audio-Only Mode on Car, suppress remote HTTP/HTTPS artwork fetches to prioritize audio
     * bandwidth and avoid heavy image downloads while driving.
     */
    fun shouldIncludeArtwork(
        config: AndroidAutoConfig,
        isCarController: Boolean,
        artworkUrl: String?
    ): Boolean {
        val url = artworkUrl?.trim().orEmpty()
        if (url.isEmpty()) return false
        if (isCarController && config.audioOnlyOnCar) {
            val lower = url.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return false
            }
        }
        return true
    }

    /**
     * Applies Car UX hard cap and safe 0-based pagination without throwing on out-of-range pages.
     */
    fun <T> paginate(items: List<T>, page: Int, pageSize: Int, maxCap: Int): List<T> {
        if (items.isEmpty() || maxCap <= 0) return emptyList()
        val capped = if (items.size > maxCap) items.subList(0, maxCap) else items
        if (pageSize <= 0 || page < 0) return capped
        val fromIndex = page * pageSize
        if (fromIndex >= capped.size) return emptyList()
        val toIndex = (fromIndex + pageSize).coerceAtMost(capped.size)
        return capped.subList(fromIndex, toIndex)
    }
}

/**
 * Coordinates Android Auto connection state, browse tree construction (off-main-thread),
 * voice search, auto-resume resolution, and diagnostics.
 */
object AndroidAutoManager {

    const val NODE_ROOT = "vexo_root"
    const val NODE_FAVORITES = "vexo_aa_favorites"
    const val NODE_CATEGORIES = "vexo_aa_categories"
    const val NODE_RECENT = "vexo_aa_recent"
    const val NODE_LOCAL_AUDIO = "vexo_aa_local_audio"
    const val NODE_AUDIO_PLAYLISTS = "vexo_aa_playlists"

    const val PREFIX_IPTV_CAT = "vexo_aa_cat|"
    const val PREFIX_AUDIO_PLAYLIST = "vexo_aa_pl|"
    const val PREFIX_IPTV_STREAM = "vexo_aa_stream|"

    private val ALBUM_ART_BASE_URI: Uri = Uri.parse("content://media/external/audio/albumart")

    private val activeCarControllers = ConcurrentHashMap.newKeySet<String>()
    private val resolvedItemCache = ConcurrentHashMap<String, MediaItem>()

    private val _connectionState = MutableStateFlow(AndroidAutoConnectionSnapshot())
    val connectionState: StateFlow<AndroidAutoConnectionSnapshot> = _connectionState.asStateFlow()

    fun onServiceStarted() {
        val cur = _connectionState.value
        _connectionState.value = cur.copy(isServiceRunning = true)
    }

    fun onServiceDestroyed() {
        activeCarControllers.clear()
        resolvedItemCache.clear()
        _connectionState.value = AndroidAutoConnectionSnapshot(isServiceRunning = false)
    }

    fun onControllerConnected(packageName: String?): Boolean {
        val isCar = AndroidAutoUxPolicy.isAndroidAutoControllerPackage(packageName)
        if (isCar && !packageName.isNullOrBlank()) {
            activeCarControllers.add(packageName)
            _connectionState.value = _connectionState.value.copy(
                isServiceRunning = true,
                isCarConnected = true,
                connectedPackage = packageName,
                connectedControllerCount = activeCarControllers.size,
                lastConnectedAtMs = System.currentTimeMillis()
            )
        }
        return isCar
    }

    fun onControllerDisconnected(packageName: String?) {
        if (!packageName.isNullOrBlank()) {
            activeCarControllers.remove(packageName)
        }
        val stillConnected = activeCarControllers.isNotEmpty()
        _connectionState.value = _connectionState.value.copy(
            isCarConnected = stillConnected,
            connectedPackage = if (stillConnected) activeCarControllers.firstOrNull().orEmpty() else "",
            connectedControllerCount = activeCarControllers.size
        )
    }

    fun isAnyCarConnected(): Boolean = activeCarControllers.isNotEmpty()

    /**
     * Builds the root browsable node for MediaLibraryService.
     */
    fun buildRootNode(context: Context): MediaItem {
        return buildBrowsableNode(
            id = NODE_ROOT,
            title = context.getString(R.string.app_name),
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        )
    }

    /**
     * Builds children for any browse tree node. MUST be invoked on Dispatchers.IO.
     */
    fun loadChildren(
        context: Context,
        parentId: String,
        page: Int,
        pageSize: Int,
        isCarController: Boolean
    ): List<MediaItem> {
        val config = PlaybackStore.getAndroidAutoConfig(context)
        if (isCarController && !config.enabled) {
            return emptyList()
        }

        val allItems: List<MediaItem> = when {
            parentId == NODE_ROOT -> buildRootCategories(context)
            parentId == NODE_FAVORITES -> loadFavorites(context, config, isCarController)
            parentId == NODE_CATEGORIES -> loadCategories(context, config, isCarController)
            parentId == NODE_RECENT -> loadRecentlyPlayed(context, config, isCarController)
            parentId == NODE_LOCAL_AUDIO -> queryLocalAudioItems(context, config, isCarController, limit = AndroidAutoUxPolicy.MAX_LEAF_ITEMS)
            parentId == NODE_AUDIO_PLAYLISTS -> queryLocalAudioPlaylists(context)
            parentId.startsWith(PREFIX_IPTV_CAT) -> {
                val payload = parentId.removePrefix(PREFIX_IPTV_CAT)
                val sepIdx = payload.indexOf('|')
                if (sepIdx > 0) {
                    val sourceId = payload.substring(0, sepIdx)
                    val categoryName = payload.substring(sepIdx + 1)
                    loadIptvCategoryStreams(context, sourceId, categoryName, config, isCarController)
                } else {
                    emptyList()
                }
            }
            parentId.startsWith(PREFIX_AUDIO_PLAYLIST) -> {
                val plId = parentId.removePrefix(PREFIX_AUDIO_PLAYLIST).toLongOrNull() ?: -1L
                queryLocalPlaylistTracks(context, plId, config, isCarController)
            }
            else -> emptyList()
        }

        val cap = when (parentId) {
            NODE_ROOT -> AndroidAutoUxPolicy.MAX_ROOT_ITEMS
            NODE_CATEGORIES -> AndroidAutoUxPolicy.MAX_CATEGORY_ITEMS
            NODE_RECENT -> AndroidAutoUxPolicy.MAX_RECENT_ITEMS
            else -> AndroidAutoUxPolicy.MAX_LEAF_ITEMS
        }
        return AndroidAutoUxPolicy.paginate(allItems, page, pageSize, cap)
    }

    /**
     * Resolves a single MediaItem by ID (used by onGetItem and onAddMediaItems).
     */
    fun resolveMediaItem(context: Context, mediaId: String): MediaItem? {
        if (mediaId.isBlank()) return null
        resolvedItemCache[mediaId]?.let { return it }
        if (mediaId == NODE_ROOT) return buildRootNode(context)

        // Direct URI fallback for local content:// or http(s):// media IDs
        val parsedUri = runCatching { Uri.parse(mediaId) }.getOrNull() ?: return null
        val scheme = parsedUri.scheme?.lowercase().orEmpty()
        if (scheme == "content" || scheme == "file" || scheme == "http" || scheme == "https" || scheme == "rtsp" || scheme == "rtmp") {
            return MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(parsedUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(parsedUri.lastPathSegment ?: "V Player")
                        .setArtist("V Player")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
        }
        return null
    }

    /**
     * Performs voice/text search across IPTV channels and local audio on Dispatchers.IO.
     */
    fun searchMedia(
        context: Context,
        query: String,
        isCarController: Boolean
    ): List<MediaItem> {
        val config = PlaybackStore.getAndroidAutoConfig(context)
        if (isCarController && !config.enabled) return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val results = mutableListOf<MediaItem>()
        val seenIds = hashSetOf<String>()

        // 1. Search IPTV Room database across configured sources
        runCatching {
            val dao = IptvRoomDatabase.getInstance(context.applicationContext).iptvDao()
            val sources = dao.getAllSourcesSync()
            val pattern = "%${trimmed.lowercase()}%"
            for (src in sources) {
                if (results.size >= AndroidAutoUxPolicy.MAX_SEARCH_ITEMS) break
                val remaining = AndroidAutoUxPolicy.MAX_SEARCH_ITEMS - results.size
                val matches = dao.searchStreamsBoundedSync(src.id, pattern, remaining)
                for (entity in matches) {
                    val item = buildIptvPlayableItem(entity, config, isCarController) ?: continue
                    if (seenIds.add(item.mediaId)) {
                        results.add(item)
                    }
                }
            }
        }

        // 2. Search local audio library
        if (results.size < AndroidAutoUxPolicy.MAX_SEARCH_ITEMS) {
            val remaining = AndroidAutoUxPolicy.MAX_SEARCH_ITEMS - results.size
            val audioMatches = queryLocalAudioItems(
                context = context,
                config = config,
                isCarController = isCarController,
                filterQuery = trimmed,
                limit = remaining
            )
            for (item in audioMatches) {
                if (seenIds.add(item.mediaId)) {
                    results.add(item)
                }
            }
        }

        return results.take(AndroidAutoUxPolicy.MAX_SEARCH_ITEMS)
    }

    /**
     * Resolves the best last-played item for Auto-Resume when Android Auto connects.
     * MUST be called on Dispatchers.IO.
     */
    fun resolveAutoResumeItem(context: Context): MediaItem? {
        val config = PlaybackStore.getAndroidAutoConfig(context)
        val recents = loadRecentlyPlayed(context, config, isCarController = true)
        return recents.firstOrNull()
    }

    /**
     * Runs a comprehensive status check for the "Test / Check Android Auto Status" button.
     * MUST be called on Dispatchers.IO.
     */
    fun runDiagnostics(context: Context): AndroidAutoDiagnosticReport {
        val appCtx = context.applicationContext
        val config = PlaybackStore.getAndroidAutoConfig(appCtx)
        val connection = _connectionState.value

        val serviceRegistered = runCatching {
            val pm = appCtx.packageManager
            val svcComponent = ComponentName(appCtx, VexoPlaybackService::class.java)
            pm.getServiceInfo(svcComponent, PackageManager.GET_META_DATA)
            val browseIntent = Intent("android.media.browse.MediaBrowserService").setPackage(appCtx.packageName)
            pm.queryIntentServices(browseIntent, 0).isNotEmpty()
        }.getOrDefault(false)

        val automotiveDescDeclared = runCatching {
            val appInfo = appCtx.packageManager.getApplicationInfo(appCtx.packageName, PackageManager.GET_META_DATA)
            val resId = appInfo.metaData?.getInt("com.google.android.gms.car.application", 0) ?: 0
            resId != 0
        }.getOrDefault(false)

        var favCount = 0
        var catCount = 0
        var recentCount = 0

        runCatching {
            val dao = IptvRoomDatabase.getInstance(appCtx).iptvDao()
            val sources = dao.getAllSourcesSync()
            for (src in sources) {
                favCount += dao.countFavoritesByTypeSync(src.id, null)
                catCount += dao.getCategoriesForTypeSync(src.id, "LIVE_TV").size
                recentCount += dao.countRecentsByTypeSync(src.id, null)
            }
        }

        // Also count local audio fallback items if IPTV is empty
        val localAudioCount = runCatching {
            queryLocalAudioItems(appCtx, config, isCarController = true, limit = 10).size
        }.getOrDefault(0)
        if (localAudioCount > 0) {
            catCount += 1
            if (recentCount == 0) recentCount = localAudioCount
        }
        if (PlaybackStore.getLastPlayedVideo(appCtx) != null && recentCount == 0) {
            recentCount = 1
        }

        val pbState = VexoPlaybackService.playbackState.value
        return AndroidAutoDiagnosticReport(
            config = config,
            connection = connection,
            serviceRegisteredInManifest = serviceRegistered,
            automotiveDescDeclared = automotiveDescDeclared,
            favoritesCount = favCount,
            categoriesCount = catCount,
            recentCount = recentCount,
            activeTitle = pbState.title,
            isPlaying = pbState.isPlaying
        )
    }

    // --- Private Browse Tree Builders ---

    private fun buildRootCategories(context: Context): List<MediaItem> {
        return listOf(
            buildBrowsableNode(
                id = NODE_FAVORITES,
                title = context.getString(R.string.aa_tree_favorites),
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            ),
            buildBrowsableNode(
                id = NODE_CATEGORIES,
                title = context.getString(R.string.aa_tree_categories),
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            ),
            buildBrowsableNode(
                id = NODE_RECENT,
                title = context.getString(R.string.aa_tree_recent),
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
        )
    }

    private fun loadFavorites(
        context: Context,
        config: AndroidAutoConfig,
        isCarController: Boolean
    ): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val seenIds = hashSetOf<String>()

        // 1. IPTV Favorite Channels / Streams
        runCatching {
            val dao = IptvRoomDatabase.getInstance(context.applicationContext).iptvDao()
            val sources = dao.getAllSourcesSync()
            for (src in sources) {
                if (out.size >= AndroidAutoUxPolicy.MAX_LEAF_ITEMS) break
                val favs = dao.getFavoriteStreamsSqlSync(src.id, null, AndroidAutoUxPolicy.MAX_LEAF_ITEMS - out.size)
                for (entity in favs) {
                    val item = buildIptvPlayableItem(entity, config, isCarController) ?: continue
                    if (seenIds.add(item.mediaId)) {
                        out.add(item)
                    }
                }
            }
        }

        // 2. Saved Audio Playlists (if room remains)
        if (out.size < AndroidAutoUxPolicy.MAX_LEAF_ITEMS) {
            val playlists = queryLocalAudioPlaylists(context)
            for (pl in playlists) {
                if (out.size >= AndroidAutoUxPolicy.MAX_LEAF_ITEMS) break
                if (seenIds.add(pl.mediaId)) {
                    out.add(pl)
                }
            }
        }

        return out
    }

    private fun loadCategories(
        context: Context,
        config: AndroidAutoConfig,
        isCarController: Boolean
    ): List<MediaItem> {
        val out = mutableListOf<MediaItem>()

        // 1. IPTV Live Channels / Categories (concise & capped for Car UX)
        runCatching {
            val dao = IptvRoomDatabase.getInstance(context.applicationContext).iptvDao()
            val sources = dao.getAllSourcesSync()
            for (src in sources) {
                if (out.size >= AndroidAutoUxPolicy.MAX_CATEGORY_ITEMS - 2) break
                var cats = dao.getCategoriesForTypeSync(src.id, "LIVE_TV")
                if (cats.isEmpty()) {
                    cats = dao.getCategoriesForTypeSync(src.id, "MOVIE")
                }
                for (cat in cats) {
                    if (out.size >= AndroidAutoUxPolicy.MAX_CATEGORY_ITEMS - 2) break
                    val cleanName = AndroidAutoUxPolicy.sanitizeLabel(
                        cat.categoryName,
                        AndroidAutoUxPolicy.MAX_TITLE_CHARS,
                        context.getString(R.string.aa_tree_categories)
                    )
                    val subtitle = if (config.minimalCarDisplay) {
                        null
                    } else {
                        context.getString(R.string.aa_category_count_fmt, cat.cnt)
                    }
                    out.add(
                        buildBrowsableNode(
                            id = "$PREFIX_IPTV_CAT${src.id}|${cat.categoryName}",
                            title = cleanName,
                            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            subtitle = subtitle
                        )
                    )
                }
            }
        }

        // 2. Local Audio Library & Playlists nodes for seamless offline car playback
        out.add(
            buildBrowsableNode(
                id = NODE_LOCAL_AUDIO,
                title = context.getString(R.string.aa_tree_local_audio),
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
        )
        val hasPlaylists = runCatching {
            IptvRoomDatabase.getInstance(context.applicationContext).audioPlaylistDao().getAllPlaylists().isNotEmpty()
        }.getOrDefault(false)
        if (hasPlaylists) {
            out.add(
                buildBrowsableNode(
                    id = NODE_AUDIO_PLAYLISTS,
                    title = context.getString(R.string.aa_tree_playlists),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                )
            )
        }

        return out.take(AndroidAutoUxPolicy.MAX_CATEGORY_ITEMS)
    }

    private fun loadIptvCategoryStreams(
        context: Context,
        sourceId: String,
        categoryName: String,
        config: AndroidAutoConfig,
        isCarController: Boolean
    ): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        runCatching {
            val dao = IptvRoomDatabase.getInstance(context.applicationContext).iptvDao()
            var streams = dao.getStreamsPageSync(
                sourceId = sourceId,
                contentType = "LIVE_TV",
                isAll = 0,
                categoryName = categoryName,
                categoryId = "",
                limit = AndroidAutoUxPolicy.MAX_LEAF_ITEMS,
                offset = 0
            )
            if (streams.isEmpty()) {
                streams = dao.getStreamsPageSync(
                    sourceId = sourceId,
                    contentType = "MOVIE",
                    isAll = 0,
                    categoryName = categoryName,
                    categoryId = "",
                    limit = AndroidAutoUxPolicy.MAX_LEAF_ITEMS,
                    offset = 0
                )
            }
            for (entity in streams) {
                val item = buildIptvPlayableItem(entity, config, isCarController) ?: continue
                out.add(item)
            }
        }
        return out
    }

    private fun loadRecentlyPlayed(
        context: Context,
        config: AndroidAutoConfig,
        isCarController: Boolean
    ): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val seenIds = hashSetOf<String>()

        // 1. IPTV Recently Played
        runCatching {
            val dao = IptvRoomDatabase.getInstance(context.applicationContext).iptvDao()
            val sources = dao.getAllSourcesSync()
            for (src in sources) {
                if (out.size >= AndroidAutoUxPolicy.MAX_RECENT_ITEMS) break
                val recents = dao.getRecentStreamsSqlSync(src.id, null, AndroidAutoUxPolicy.MAX_RECENT_ITEMS - out.size)
                for (entity in recents) {
                    val item = buildIptvPlayableItem(entity, config, isCarController) ?: continue
                    if (seenIds.add(item.mediaId)) {
                        out.add(item)
                    }
                }
            }
        }

        // 2. Last played local media from PlaybackStore (respects Hidden folders OFF)
        val showHidden = PlaybackStore.isShowHiddenFolders(context)
        runCatching {
            val last = PlaybackStore.getLastPlayedVideo(context)
            if (last != null && out.size < AndroidAutoUxPolicy.MAX_RECENT_ITEMS) {
                val (uri, rawTitle, _) = last
                val uriStr = uri.toString()
                val uriPath = uri.path.orEmpty()
                val isHiddenLast = !showHidden && HiddenMediaAndPlaylistHelper.isHiddenMedia(
                    path = uriPath,
                    folderName = "",
                    title = rawTitle
                )
                if (!isHiddenLast && uriStr.isNotBlank() && seenIds.add(uriStr)) {
                    val (title, subtitle) = AndroidAutoUxPolicy.formatDisplayMetadata(
                        rawTitle = rawTitle,
                        rawGroupOrSource = context.getString(R.string.aa_tree_recent),
                        rawExtraDetail = null,
                        config = config
                    )
                    val item = MediaItem.Builder()
                        .setMediaId(uriStr)
                        .setUri(uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(title)
                                .setArtist(subtitle)
                                .setSubtitle(subtitle)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        )
                        .build()
                    cachePlayableItem(item)
                    out.add(item)
                }
            }
        }

        // 3. Recent local audio tracks
        if (out.size < AndroidAutoUxPolicy.MAX_RECENT_ITEMS) {
            val remaining = AndroidAutoUxPolicy.MAX_RECENT_ITEMS - out.size
            val audioItems = queryLocalAudioItems(context, config, isCarController, limit = remaining)
            for (item in audioItems) {
                if (seenIds.add(item.mediaId)) {
                    out.add(item)
                }
            }
        }

        return out.take(AndroidAutoUxPolicy.MAX_RECENT_ITEMS)
    }

    private fun buildIptvPlayableItem(
        entity: IptvStreamEntity,
        config: AndroidAutoConfig,
        isCarController: Boolean
    ): MediaItem? {
        val url = entity.streamUrl.trim()
        if (url.isEmpty()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null

        val (title, subtitle) = AndroidAutoUxPolicy.formatDisplayMetadata(
            rawTitle = entity.title,
            rawGroupOrSource = entity.categoryName,
            rawExtraDetail = entity.durationLabel.ifBlank { entity.contentType },
            config = config
        )

        val metaBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setSubtitle(subtitle)
            .setAlbumTitle(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)

        if (AndroidAutoUxPolicy.shouldIncludeArtwork(config, isCarController, entity.logoUrl)) {
            runCatching { Uri.parse(entity.logoUrl.trim()) }.getOrNull()?.let { artUri ->
                metaBuilder.setArtworkUri(artUri)
            }
        }

        val mediaId = "$PREFIX_IPTV_STREAM${entity.sourceId}|${entity.streamId}"
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(metaBuilder.build())
            .build()
        cachePlayableItem(item)
        return item
    }

    private fun queryLocalAudioItems(
        context: Context,
        config: AndroidAutoConfig,
        isCarController: Boolean,
        filterQuery: String = "",
        limit: Int = AndroidAutoUxPolicy.MAX_LEAF_ITEMS
    ): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val q = filterQuery.trim().lowercase()
        val showHidden = PlaybackStore.isShowHiddenFolders(context)
        val nomediaCache = HashMap<String, Boolean>()
        runCatching {
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.RELATIVE_PATH
                )
            } else {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DATA
                )
            }
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 5000",
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(idCol)
                    val rawTitle = c.getString(titleCol) ?: continue
                    if (!showHidden) {
                        val filePath = if (dataCol != -1) c.getString(dataCol).orEmpty() else ""
                        val relPath = if (relPathCol != -1) c.getString(relPathCol).orEmpty() else ""
                        val combinedPath = filePath.ifBlank { relPath }
                        val isHiddenAudio = HiddenMediaAndPlaylistHelper.isHiddenMedia(
                            path = combinedPath,
                            folderName = "",
                            title = rawTitle,
                            hasNomediaAncestor = filePath.isNotBlank() && HiddenMediaAndPlaylistHelper.hasNomediaInParentChain(filePath, nomediaCache)
                        )
                        if (isHiddenAudio) continue
                    }
                    val rawArtist = if (artistCol != -1) (c.getString(artistCol) ?: "") else ""
                    val rawAlbum = if (albumCol != -1) (c.getString(albumCol) ?: "") else ""
                    val albumId = if (albumIdCol != -1) c.getLong(albumIdCol) else -1L
                    if (q.isNotEmpty() &&
                        !rawTitle.lowercase().contains(q) &&
                        !rawArtist.lowercase().contains(q) &&
                        !rawAlbum.lowercase().contains(q)
                    ) {
                        continue
                    }

                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val (title, subtitle) = AndroidAutoUxPolicy.formatDisplayMetadata(
                        rawTitle = rawTitle,
                        rawGroupOrSource = rawArtist.ifBlank { rawAlbum },
                        rawExtraDetail = rawAlbum,
                        config = config
                    )
                    val metaBuilder = MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(subtitle)
                        .setSubtitle(subtitle)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    if (!config.minimalCarDisplay && rawAlbum.isNotBlank()) {
                        metaBuilder.setAlbumTitle(AndroidAutoUxPolicy.sanitizeLabel(rawAlbum, AndroidAutoUxPolicy.MAX_SUBTITLE_CHARS, ""))
                    }
                    if (albumId > 0L) {
                        val artUri = ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId)
                        if (AndroidAutoUxPolicy.shouldIncludeArtwork(config, isCarController, artUri.toString())) {
                            metaBuilder.setArtworkUri(artUri)
                        }
                    }

                    val item = MediaItem.Builder()
                        .setMediaId(uri.toString())
                        .setUri(uri)
                        .setMediaMetadata(metaBuilder.build())
                        .build()
                    cachePlayableItem(item)
                    out.add(item)
                }
            }
        }
        return out
    }

    private fun queryLocalAudioPlaylists(context: Context): List<MediaItem> {
        return runCatching {
            val dao = IptvRoomDatabase.getInstance(context.applicationContext).audioPlaylistDao()
            dao.getAllPlaylists().take(AndroidAutoUxPolicy.MAX_CATEGORY_ITEMS).map { pl ->
                buildBrowsableNode(
                    id = "$PREFIX_AUDIO_PLAYLIST${pl.id}",
                    title = AndroidAutoUxPolicy.sanitizeLabel(pl.name, AndroidAutoUxPolicy.MAX_TITLE_CHARS, "Playlist"),
                    mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun queryLocalPlaylistTracks(
        context: Context,
        playlistId: Long,
        config: AndroidAutoConfig,
        isCarController: Boolean
    ): List<MediaItem> {
        if (playlistId <= 0L) return emptyList()
        return runCatching {
            val dao = IptvRoomDatabase.getInstance(context.applicationContext).audioPlaylistDao()
            val showHidden = PlaybackStore.isShowHiddenFolders(context)
            dao.getTracksForPlaylist(playlistId).take(AndroidAutoUxPolicy.MAX_LEAF_ITEMS).mapNotNull { tr ->
                val uri = runCatching { Uri.parse(tr.contentUri) }.getOrNull() ?: return@mapNotNull null
                if (!showHidden && HiddenMediaAndPlaylistHelper.isHiddenMedia(path = uri.path.orEmpty(), folderName = "", title = tr.title)) {
                    return@mapNotNull null
                }
                val (title, subtitle) = AndroidAutoUxPolicy.formatDisplayMetadata(
                    rawTitle = tr.title,
                    rawGroupOrSource = tr.artist,
                    rawExtraDetail = tr.album,
                    config = config
                )
                val metaBuilder = MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                if (AndroidAutoUxPolicy.shouldIncludeArtwork(config, isCarController, tr.albumArtUri)) {
                    runCatching { Uri.parse(tr.albumArtUri) }.getOrNull()?.let { metaBuilder.setArtworkUri(it) }
                }
                val item = MediaItem.Builder()
                    .setMediaId(tr.contentUri)
                    .setUri(uri)
                    .setMediaMetadata(metaBuilder.build())
                    .build()
                cachePlayableItem(item)
                item
            }
        }.getOrDefault(emptyList())
    }

    private fun buildBrowsableNode(
        id: String,
        title: String,
        mediaType: Int,
        subtitle: String? = null,
        artworkUri: Uri? = null
    ): MediaItem {
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(AndroidAutoUxPolicy.sanitizeLabel(title, AndroidAutoUxPolicy.MAX_TITLE_CHARS, "V Player"))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
        if (!subtitle.isNullOrBlank()) {
            metaBuilder.setSubtitle(AndroidAutoUxPolicy.sanitizeLabel(subtitle, AndroidAutoUxPolicy.MAX_SUBTITLE_CHARS, ""))
        }
        if (artworkUri != null) metaBuilder.setArtworkUri(artworkUri)
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    private fun cachePlayableItem(item: MediaItem) {
        if (resolvedItemCache.size > 400) {
            resolvedItemCache.clear()
        }
        resolvedItemCache[item.mediaId] = item
    }
}
