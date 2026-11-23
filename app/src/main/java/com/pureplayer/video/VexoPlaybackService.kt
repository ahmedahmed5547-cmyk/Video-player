package com.pureplayer.video

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackgroundPlaybackState(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val isFromVideoAudioOnly: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val currentUri: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

/**
 * Shared foreground Media3 MediaLibraryService powering:
 * 1) Android Auto audio browse tree (Artists, Albums, Tracks, Genres, Playlists, Recently Played) & voice search.
 * 2) Background Audio Library playback with notification & lock-screen controls.
 * 3) Video -> Audio-Only background mode with seamless position continuity, sleep timer, speed & AudioFX.
 */
class VexoPlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null

    companion object {
        const val ACTION_PLAY_AUDIO_ONLY_VIDEO = "com.pureplayer.video.ACTION_PLAY_AUDIO_ONLY_VIDEO"
        const val ACTION_PLAY_AUDIO_QUEUE = "com.pureplayer.video.ACTION_PLAY_AUDIO_QUEUE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.pureplayer.video.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_NEXT_TRACK = "com.pureplayer.video.ACTION_NEXT_TRACK"
        const val ACTION_STOP_AND_CLOSE = "com.pureplayer.video.ACTION_STOP_AND_CLOSE"

        private const val CUSTOM_CMD_CLOSE = "com.pureplayer.video.CMD_CLOSE_NOTIFICATION"

        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_IS_VIDEO_AUDIO_ONLY = "extra_is_video_audio_only"
        const val EXTRA_QUEUE_URIS = "extra_queue_uris"
        const val EXTRA_QUEUE_TITLES = "extra_queue_titles"
        const val EXTRA_QUEUE_ARTISTS = "extra_queue_artists"
        const val EXTRA_START_INDEX = "extra_start_index"

        private const val NODE_ROOT = "vexo_root"
        private const val NODE_ARTISTS = "vexo_artists"
        private const val NODE_ALBUMS = "vexo_albums"
        private const val NODE_TRACKS = "vexo_tracks"
        private const val NODE_GENRES = "vexo_genres"
        private const val NODE_PLAYLISTS = "vexo_playlists"
        private const val NODE_RECENT = "vexo_recent"

        private val ALBUM_ART_BASE_URI: Uri = Uri.parse("content://media/external/audio/albumart")

        private val _playbackState = MutableStateFlow(BackgroundPlaybackState())
        val playbackState: StateFlow<BackgroundPlaybackState> = _playbackState.asStateFlow()

        @Volatile
        var isCurrentSessionFromVideo: Boolean = false
            private set

        fun startAudioOnlyFromVideo(
            context: Context,
            uri: Uri,
            title: String,
            positionMs: Long,
            speed: Float = 1.0f
        ) {
            val intent = Intent(context, VexoPlaybackService::class.java).apply {
                action = ACTION_PLAY_AUDIO_ONLY_VIDEO
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, "V Player • Audio Only")
                putExtra(EXTRA_POSITION_MS, positionMs)
                putExtra(EXTRA_SPEED, speed)
                putExtra(EXTRA_IS_VIDEO_AUDIO_ONLY, true)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun playAudioQueue(
            context: Context,
            tracks: List<AudioTrackItem>,
            startIndex: Int
        ) {
            if (tracks.isEmpty()) return
            val intent = Intent(context, VexoPlaybackService::class.java).apply {
                action = ACTION_PLAY_AUDIO_QUEUE
                putStringArrayListExtra(EXTRA_QUEUE_URIS, ArrayList(tracks.map { it.uri.toString() }))
                putStringArrayListExtra(EXTRA_QUEUE_TITLES, ArrayList(tracks.map { it.title }))
                putStringArrayListExtra(EXTRA_QUEUE_ARTISTS, ArrayList(tracks.map { it.artist }))
                putExtra(EXTRA_START_INDEX, startIndex.coerceIn(0, tracks.lastIndex))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun sendAction(context: Context, actionName: String) {
            val intent = Intent(context, VexoPlaybackService::class.java).apply {
                action = actionName
            }
            runCatching { context.startService(intent) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                VexoAudioEffectsManager.attachToAudioSession(applicationContext, audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishState(exo)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publishState(exo)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    SleepTimerManager.onMediaTrackEnded()
                }
                publishState(exo)
            }
        })

        SleepTimerManager.attachCallbacks(
            volumeScaleCallback = { vol -> exo.volume = vol.coerceIn(0f, 1f) },
            expireCallback = {
                if (exo.isPlaying) exo.pause()
                publishState(exo)
            }
        )

        player = exo

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            101,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_FROM_AUDIO_SERVICE", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeBtn = CommandButton.Builder()
            .setDisplayName("Close")
            .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .setSessionCommand(SessionCommand(CUSTOM_CMD_CLOSE, Bundle.EMPTY))
            .build()

        mediaLibrarySession = MediaLibrarySession.Builder(this, exo, libraryCallback)
            .setSessionActivity(sessionActivityIntent)
            .setCustomLayout(ImmutableList.of(closeBtn))
            .build()
    }

    private fun stopAndCloseCompletely() {
        val exo = player
        exo?.stop()
        exo?.clearMediaItems()
        isCurrentSessionFromVideo = false
        _playbackState.value = BackgroundPlaybackState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val exo = player
        if (exo != null && intent != null) {
            when (intent.action) {
                ACTION_PLAY_AUDIO_ONLY_VIDEO -> {
                    val uriStr = intent.getStringExtra(EXTRA_URI).orEmpty()
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: "V Player"
                    val sub = intent.getStringExtra(EXTRA_SUBTITLE) ?: "V Player • Audio Only"
                    val pos = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                    val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.25f, 4.0f)
                    if (uriStr.isNotEmpty()) {
                        isCurrentSessionFromVideo = true
                        val item = MediaItem.Builder()
                            .setMediaId(uriStr)
                            .setUri(Uri.parse(uriStr))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(sub)
                                    .build()
                            )
                            .build()
                        exo.setMediaItem(item)
                        exo.prepare()
                        if (pos > 0L) exo.seekTo(pos)
                        exo.setPlaybackSpeed(speed)
                        exo.play()
                        VexoAudioEffectsManager.attachToAudioSession(applicationContext, exo.audioSessionId)
                        publishState(exo)
                    }
                }
                ACTION_PLAY_AUDIO_QUEUE -> {
                    val uris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS).orEmpty()
                    val titles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES).orEmpty()
                    val artists = intent.getStringArrayListExtra(EXTRA_QUEUE_ARTISTS).orEmpty()
                    val startIdx = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, (uris.size - 1).coerceAtLeast(0))
                    if (uris.isNotEmpty()) {
                        isCurrentSessionFromVideo = false
                        val mediaItems = uris.mapIndexed { idx, u ->
                            MediaItem.Builder()
                                .setMediaId(u)
                                .setUri(Uri.parse(u))
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(titles.getOrElse(idx) { "Audio Track" })
                                        .setArtist(artists.getOrElse(idx) { "V Player" })
                                        .build()
                                )
                                .build()
                        }
                        exo.setMediaItems(mediaItems, startIdx, 0L)
                        exo.prepare()
                        exo.play()
                        VexoAudioEffectsManager.attachToAudioSession(applicationContext, exo.audioSessionId)
                        publishState(exo)
                    }
                }
                ACTION_TOGGLE_PLAY_PAUSE -> {
                    if (exo.isPlaying) exo.pause() else exo.play()
                    publishState(exo)
                }
                ACTION_NEXT_TRACK -> {
                    if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
                    publishState(exo)
                }
                ACTION_STOP_AND_CLOSE -> {
                    stopAndCloseCompletely()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun publishState(exo: ExoPlayer) {
        val current = exo.currentMediaItem
        val meta = current?.mediaMetadata
        _playbackState.value = BackgroundPlaybackState(
            isActive = current != null && exo.playbackState != Player.STATE_IDLE,
            isPlaying = exo.isPlaying,
            isFromVideoAudioOnly = isCurrentSessionFromVideo,
            title = meta?.title?.toString() ?: "V Player",
            subtitle = meta?.artist?.toString() ?: "",
            currentUri = current?.localConfiguration?.uri?.toString() ?: current?.mediaId ?: "",
            positionMs = exo.currentPosition.coerceAtLeast(0L),
            durationMs = exo.duration.coerceAtLeast(0L)
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CUSTOM_CMD_CLOSE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CUSTOM_CMD_CLOSE) {
                stopAndCloseCompletely()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = buildBrowsableNode(NODE_ROOT, "V Player", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items: List<MediaItem> = when {
                parentId == NODE_ROOT -> listOf(
                    buildBrowsableNode(NODE_TRACKS, "Tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                    buildBrowsableNode(NODE_ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                    buildBrowsableNode(NODE_ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                    buildBrowsableNode(NODE_GENRES, "Genres", MediaMetadata.MEDIA_TYPE_FOLDER_GENRES),
                    buildBrowsableNode(NODE_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                    buildBrowsableNode(NODE_RECENT, "Recently Played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                )
                parentId == NODE_TRACKS || parentId == NODE_RECENT -> queryAudioMediaItems(limit = 60)
                parentId == NODE_ARTISTS -> queryArtistNodes()
                parentId == NODE_ALBUMS -> queryAlbumNodes()
                parentId == NODE_GENRES -> queryGenreNodes()
                parentId == NODE_PLAYLISTS -> queryPlaylistMediaItems()
                parentId.startsWith("artist_") -> {
                    val artistName = parentId.removePrefix("artist_")
                    queryAudioMediaItems(artistFilter = artistName, limit = 60)
                }
                parentId.startsWith("album_") -> {
                    val albumId = parentId.removePrefix("album_").toLongOrNull() ?: -1L
                    queryAudioMediaItems(albumIdFilter = albumId, limit = 60)
                }
                parentId.startsWith("genre_") -> {
                    val genreId = parentId.removePrefix("genre_").toLongOrNull() ?: -1L
                    queryGenreTracksMediaItems(genreId)
                }
                parentId.startsWith("playlist_") -> {
                    val plId = parentId.removePrefix("playlist_").toLongOrNull() ?: -1L
                    queryPlaylistTracksMediaItems(plId)
                }
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val matches = queryAudioMediaItems(filterQuery = query, limit = 25)
            session.notifySearchResultChanged(browser, query, matches.size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val matches = queryAudioMediaItems(filterQuery = query, limit = 25)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(matches), params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    item.buildUpon().setUri(Uri.parse(item.mediaId)).build()
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    private fun buildBrowsableNode(
        id: String,
        title: String,
        mediaType: Int,
        subtitle: String? = null,
        artworkUri: Uri? = null
    ): MediaItem {
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
        if (!subtitle.isNullOrBlank()) metaBuilder.setSubtitle(subtitle)
        if (artworkUri != null) metaBuilder.setArtworkUri(artworkUri)
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    private fun queryArtistNodes(): List<MediaItem> {
        val seen = linkedMapOf<String, Int>()
        runCatching {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.ARTIST),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 5000",
                null,
                "${MediaStore.Audio.Media.ARTIST} ASC"
            )?.use { c ->
                val col = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                while (c.moveToNext() && seen.size < 60) {
                    val name = if (col != -1) (c.getString(col)?.takeIf { it.isNotBlank() } ?: "Unknown Artist") else "Unknown Artist"
                    seen[name] = (seen[name] ?: 0) + 1
                }
            }
        }
        return seen.map { (name, count) ->
            buildBrowsableNode("artist_$name", name, MediaMetadata.MEDIA_TYPE_ARTIST, "$count tracks")
        }
    }

    private fun queryAlbumNodes(): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val seen = hashSetOf<Long>()
        runCatching {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ARTIST),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 5000",
                null,
                "${MediaStore.Audio.Media.ALBUM} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                while (c.moveToNext() && out.size < 60) {
                    val albumId = if (idCol != -1) c.getLong(idCol) else -1L
                    if (albumId <= 0L || !seen.add(albumId)) continue
                    val album = if (albumCol != -1) (c.getString(albumCol) ?: "Unknown Album") else "Unknown Album"
                    val artist = if (artistCol != -1) (c.getString(artistCol) ?: "") else ""
                    val artUri = ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId)
                    out.add(buildBrowsableNode("album_$albumId", album, MediaMetadata.MEDIA_TYPE_ALBUM, artist, artUri))
                }
            }
        }
        return out
    }

    private fun queryGenreNodes(): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        runCatching {
            contentResolver.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
                null,
                null,
                "${MediaStore.Audio.Genres.NAME} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
                while (c.moveToNext() && out.size < 50) {
                    val gid = c.getLong(idCol)
                    val gname = c.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                    out.add(buildBrowsableNode("genre_$gid", gname, MediaMetadata.MEDIA_TYPE_GENRE))
                }
            }
        }
        return out
    }

    private fun queryGenreTracksMediaItems(genreId: Long): List<MediaItem> {
        if (genreId <= 0L) return emptyList()
        val out = mutableListOf<MediaItem>()
        runCatching {
            val uri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID
                ),
                null,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                while (c.moveToNext() && out.size < 60) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: continue
                    val artist = if (artistCol != -1) (c.getString(artistCol) ?: "Unknown") else "Unknown"
                    val album = if (albumCol != -1) (c.getString(albumCol) ?: "Unknown") else "Unknown"
                    val albumId = if (albumIdCol != -1) c.getLong(albumIdCol) else -1L
                    val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val artUri = if (albumId > 0L) ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId) else null
                    val metaBuilder = MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    if (artUri != null) metaBuilder.setArtworkUri(artUri)
                    out.add(
                        MediaItem.Builder()
                            .setMediaId(trackUri.toString())
                            .setUri(trackUri)
                            .setMediaMetadata(metaBuilder.build())
                            .build()
                    )
                }
            }
        }
        return out
    }

    private fun queryAudioMediaItems(
        filterQuery: String = "",
        artistFilter: String? = null,
        albumIdFilter: Long? = null,
        limit: Int = 60
    ): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val q = filterQuery.trim().lowercase()
        runCatching {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID
            )
            contentResolver.query(
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
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: continue
                    val artist = if (artistCol != -1) (c.getString(artistCol) ?: "Unknown") else "Unknown"
                    val album = if (albumCol != -1) (c.getString(albumCol) ?: "Unknown") else "Unknown"
                    val albumId = if (albumIdCol != -1) c.getLong(albumIdCol) else -1L
                    if (artistFilter != null && !artist.equals(artistFilter, ignoreCase = true)) continue
                    if (albumIdFilter != null && albumId != albumIdFilter) continue
                    if (q.isNotEmpty() && !title.lowercase().contains(q) && !artist.lowercase().contains(q) && !album.lowercase().contains(q)) {
                        continue
                    }
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val artUri = if (albumId > 0L) ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId) else null
                    val metaBuilder = MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    if (artUri != null) metaBuilder.setArtworkUri(artUri)
                    out.add(
                        MediaItem.Builder()
                            .setMediaId(uri.toString())
                            .setUri(uri)
                            .setMediaMetadata(metaBuilder.build())
                            .build()
                    )
                }
            }
        }
        return out
    }

    private fun queryPlaylistMediaItems(): List<MediaItem> {
        return runCatching {
            val dao = IptvRoomDatabase.getInstance(applicationContext).audioPlaylistDao()
            dao.getAllPlaylists().map { pl ->
                buildBrowsableNode("playlist_${pl.id}", pl.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
        }.getOrDefault(emptyList())
    }

    private fun queryPlaylistTracksMediaItems(playlistId: Long): List<MediaItem> {
        if (playlistId <= 0L) return emptyList()
        return runCatching {
            val dao = IptvRoomDatabase.getInstance(applicationContext).audioPlaylistDao()
            dao.getTracksForPlaylist(playlistId).map { tr ->
                val uri = Uri.parse(tr.contentUri)
                val metaBuilder = MediaMetadata.Builder()
                    .setTitle(tr.title)
                    .setArtist(tr.artist)
                    .setAlbumTitle(tr.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                if (tr.albumArtUri.isNotBlank()) {
                    metaBuilder.setArtworkUri(Uri.parse(tr.albumArtUri))
                }
                MediaItem.Builder()
                    .setMediaId(tr.contentUri)
                    .setUri(uri)
                    .setMediaMetadata(metaBuilder.build())
                    .build()
            }
        }.getOrDefault(emptyList())
    }

    override fun onDestroy() {
        VexoAudioEffectsManager.releaseAll()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        player = null
        _playbackState.value = BackgroundPlaybackState()
        super.onDestroy()
    }
}
