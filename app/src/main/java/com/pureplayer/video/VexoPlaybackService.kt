package com.pureplayer.video

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
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
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * 1) Android Auto browse tree (Favorites, Channels/Categories, Recently Played) & voice search off the main thread.
 * 2) Car UX Restrictions: Audio-Only car track selection, minimal distraction metadata, safe disconnect handling, and optional Auto-Resume.
 * 3) Background Audio Library playback with notification & lock-screen controls.
 * 4) Video -> Audio-Only background mode with seamless position continuity, sleep timer, speed & AudioFX.
 */
class VexoPlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_PLAY_AUDIO_ONLY_VIDEO = "com.pureplayer.video.ACTION_PLAY_AUDIO_ONLY_VIDEO"
        const val ACTION_PLAY_AUDIO_QUEUE = "com.pureplayer.video.ACTION_PLAY_AUDIO_QUEUE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.pureplayer.video.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_NEXT_TRACK = "com.pureplayer.video.ACTION_NEXT_TRACK"
        const val ACTION_STOP_AND_CLOSE = "com.pureplayer.video.ACTION_STOP_AND_CLOSE"
        const val ACTION_REFRESH_ANDROID_AUTO = "com.pureplayer.video.ACTION_REFRESH_ANDROID_AUTO"

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

        /**
         * Applies Android Auto setting changes or hidden-files toggle immediately to a running
         * MediaLibraryService without requiring an app restart.
         */
        fun notifyAndroidAutoConfigChanged(context: Context) {
            if (!AndroidAutoManager.connectionState.value.isServiceRunning) return
            sendAction(context, ACTION_REFRESH_ANDROID_AUTO)
        }

        fun notifyLibraryChanged(context: Context) {
            notifyAndroidAutoConfigChanged(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AndroidAutoManager.onServiceStarted()

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
                runCatching {
                    VexoAudioEffectsManager.attachToAudioSession(applicationContext, audioSessionId)
                }
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

            override fun onPlayerError(error: PlaybackException) {
                // Gracefully publish idle/error state without crashing Android Auto or system UI
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
            .setDisplayName(getString(R.string.action_close))
            .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .setSessionCommand(SessionCommand(CUSTOM_CMD_CLOSE, Bundle.EMPTY))
            .build()

        mediaLibrarySession = MediaLibrarySession.Builder(this, exo, libraryCallback)
            .setSessionActivity(sessionActivityIntent)
            .setCustomLayout(ImmutableList.of(closeBtn))
            .build()
    }

    private fun notifyCarBrowseTreeChanged() {
        val session = mediaLibrarySession ?: return
        val enabled = PlaybackStore.isAndroidAutoEnabled(applicationContext)
        val count = if (enabled) 3 else 0
        runCatching {
            session.notifyChildrenChanged(AndroidAutoManager.NODE_ROOT, count, null)
            session.notifyChildrenChanged(AndroidAutoManager.NODE_FAVORITES, 0, null)
            session.notifyChildrenChanged(AndroidAutoManager.NODE_CATEGORIES, 0, null)
            session.notifyChildrenChanged(AndroidAutoManager.NODE_RECENT, 0, null)
            session.notifyChildrenChanged(AndroidAutoManager.NODE_LOCAL_AUDIO, 0, null)
        }
    }

    private fun applyCarAudioOnlyTrackPolicy() {
        val exo = player ?: return
        val config = PlaybackStore.getAndroidAutoConfig(applicationContext)
        val disableVideoTracks = AndroidAutoManager.isAnyCarConnected() && config.enabled && config.audioOnlyOnCar
        runCatching {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideoTracks)
                .build()
        }
    }

    private fun triggerAutoResumeIfEligible() {
        val config = PlaybackStore.getAndroidAutoConfig(applicationContext)
        if (!config.enabled || !config.autoResumeOnConnect) return
        val exo = player ?: return
        if (exo.isPlaying) return

        if (exo.currentMediaItem != null) {
            mainHandler.post {
                val p = player ?: return@post
                if (!p.isPlaying && p.currentMediaItem != null) {
                    applyCarAudioOnlyTrackPolicy()
                    p.prepare()
                    p.play()
                }
            }
            return
        }

        serviceScope.launch {
            val resumeItem = runCatching {
                AndroidAutoManager.resolveAutoResumeItem(applicationContext)
            }.getOrNull() ?: return@launch

            mainHandler.post {
                val p = player ?: return@post
                if (!p.isPlaying && p.currentMediaItem == null && AndroidAutoManager.isAnyCarConnected()) {
                    applyCarAudioOnlyTrackPolicy()
                    p.setMediaItem(resumeItem)
                    p.prepare()
                    p.play()
                    publishState(p)
                }
            }
        }
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
                                    .setSubtitle(sub)
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .build()
                            )
                            .build()
                        applyCarAudioOnlyTrackPolicy()
                        exo.setMediaItem(item)
                        exo.prepare()
                        if (pos > 0L) exo.seekTo(pos)
                        exo.setPlaybackSpeed(speed)
                        exo.play()
                        runCatching { VexoAudioEffectsManager.attachToAudioSession(applicationContext, exo.audioSessionId) }
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
                            val trackTitle = titles.getOrElse(idx) { "Audio Track" }
                            val trackArtist = artists.getOrElse(idx) { "V Player" }
                            MediaItem.Builder()
                                .setMediaId(u)
                                .setUri(Uri.parse(u))
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(trackTitle)
                                        .setArtist(trackArtist)
                                        .setSubtitle(trackArtist)
                                        .setIsBrowsable(false)
                                        .setIsPlayable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                )
                                .build()
                        }
                        applyCarAudioOnlyTrackPolicy()
                        exo.setMediaItems(mediaItems, startIdx, 0L)
                        exo.prepare()
                        exo.play()
                        runCatching { VexoAudioEffectsManager.attachToAudioSession(applicationContext, exo.audioSessionId) }
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
                ACTION_REFRESH_ANDROID_AUTO -> {
                    applyCarAudioOnlyTrackPolicy()
                    notifyCarBrowseTreeChanged()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exo = player
        if (exo == null || !exo.playWhenReady || exo.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun publishState(exo: ExoPlayer) {
        val current = exo.currentMediaItem
        val meta = current?.mediaMetadata
        _playbackState.value = BackgroundPlaybackState(
            isActive = current != null && exo.playbackState != Player.STATE_IDLE,
            isPlaying = exo.isPlaying,
            isFromVideoAudioOnly = isCurrentSessionFromVideo,
            title = meta?.title?.toString() ?: "V Player",
            subtitle = meta?.artist?.toString() ?: meta?.subtitle?.toString() ?: "",
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
            val isCar = AndroidAutoManager.onControllerConnected(controller.packageName)
            val config = PlaybackStore.getAndroidAutoConfig(applicationContext)

            if (isCar && !config.enabled) {
                // Reject Android Auto connection cleanly when user disabled Android Auto support
                return MediaSession.ConnectionResult.reject()
            }

            if (isCar) {
                mainHandler.post {
                    applyCarAudioOnlyTrackPolicy()
                    triggerAutoResumeIfEligible()
                }
            }

            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CUSTOM_CMD_CLOSE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            AndroidAutoManager.onControllerDisconnected(controller.packageName)
            mainHandler.post {
                applyCarAudioOnlyTrackPolicy()
            }
            super.onDisconnected(session, controller)
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
            val isCar = AndroidAutoUxPolicy.isAndroidAutoControllerPackage(browser.packageName)
            val config = PlaybackStore.getAndroidAutoConfig(applicationContext)
            if (isCar && !config.enabled) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            val rootItem = AndroidAutoManager.buildRootNode(applicationContext)
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                val result = runCatching {
                    val item = AndroidAutoManager.resolveMediaItem(applicationContext, mediaId)
                    if (item != null) {
                        LibraryResult.ofItem(item, null)
                    } else {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    }
                }.getOrElse {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                }
                future.set(result)
            }
            return future
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val isCar = AndroidAutoUxPolicy.isAndroidAutoControllerPackage(browser.packageName)
            serviceScope.launch {
                val result = runCatching {
                    val items = AndroidAutoManager.loadChildren(
                        context = applicationContext,
                        parentId = parentId,
                        page = page,
                        pageSize = pageSize,
                        isCarController = isCar
                    )
                    LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                }.getOrElse {
                    LibraryResult.ofItemList(ImmutableList.of(), params)
                }
                future.set(result)
            }
            return future
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val future = SettableFuture.create<LibraryResult<Void>>()
            val isCar = AndroidAutoUxPolicy.isAndroidAutoControllerPackage(browser.packageName)
            serviceScope.launch {
                runCatching {
                    val matches = AndroidAutoManager.searchMedia(applicationContext, query, isCar)
                    mainHandler.post {
                        runCatching {
                            session.notifySearchResultChanged(browser, query, matches.size, params)
                        }
                    }
                }
                future.set(LibraryResult.ofVoid(params))
            }
            return future
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val isCar = AndroidAutoUxPolicy.isAndroidAutoControllerPackage(browser.packageName)
            serviceScope.launch {
                val result = runCatching {
                    val matches = AndroidAutoManager.searchMedia(applicationContext, query, isCar)
                    val paged = AndroidAutoUxPolicy.paginate(matches, page, pageSize, AndroidAutoUxPolicy.MAX_SEARCH_ITEMS)
                    LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
                }.getOrElse {
                    LibraryResult.ofItemList(ImmutableList.of(), params)
                }
                future.set(result)
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                val resolved = runCatching {
                    mediaItems.map { item ->
                        if (item.localConfiguration != null) {
                            item
                        } else {
                            AndroidAutoManager.resolveMediaItem(applicationContext, item.mediaId)
                                ?: runCatching {
                                    item.buildUpon().setUri(Uri.parse(item.mediaId)).build()
                                }.getOrDefault(item)
                        }
                    }.toMutableList()
                }.getOrDefault(mediaItems)
                future.set(resolved)
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val item = runCatching {
                    AndroidAutoManager.resolveAutoResumeItem(applicationContext)
                }.getOrNull()
                if (item != null) {
                    future.set(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L))
                } else {
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET))
                }
            }
            return future
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        AndroidAutoManager.onServiceDestroyed()
        runCatching { VexoAudioEffectsManager.releaseAll() }
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
