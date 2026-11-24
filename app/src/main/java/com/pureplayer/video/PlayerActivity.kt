package com.pureplayer.video

import android.util.Log

import android.app.PendingIntent
import android.app.PictureInPictureParams
import androidx.core.content.ContextCompat
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.provider.Settings
import android.widget.Toast
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.BidiFormatter
import android.util.Rational
import android.util.TypedValue
import android.widget.FrameLayout
import android.view.Gravity
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.WindowInfoTracker
import com.pureplayer.video.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PlayerActivity : AppCompatActivity(), IVLCVout.OnNewVideoLayoutListener {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var audioManager: AudioManager
    private lateinit var gestureStateMachine: PlayerGestureStateMachine
    private lateinit var subtitleScaleDetector: ScaleGestureDetector

    private var activePfd: ParcelFileDescriptor? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isLocked = false
    private var isControlsVisible = true
    private var isUserSeeking = false
    private var currentSpeed = 1.0f
    private var longPressRunnable: Runnable? = null
    private var singleTapRunnable: Runnable? = null
    private var seekGestureStartTimeMs: Long = 0L
    private var seekGestureActive: Boolean = false
    private var lastVolumeEdgeHaptic: Int = -1
    private var lastBrightnessEdgeHaptic: Int = -1
    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastHeadsetHookTapMs = 0L

    private var currentVideoUri: Uri? = null
    private var currentVideoTitle = "Video"
    private var pendingStartPositionMs = 0L
    private var hasAppliedInitialSeek = false
    private var hasAppliedInitialEqualizer = false
    private var currentDecoderIndex = 0
    private var isEnteringPipTransition = false
    private var playlistUris: MutableList<String> = mutableListOf()
    private var playlistTitles: MutableList<String> = mutableListOf()
    private var currentPlaylistIndex: Int = -1
    private var isStreamOrIptv: Boolean = false
    private var lastVideoSwitchTimeMs: Long = 0L
    private val videoSwitchGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var activeVideoLoadJob: kotlinx.coroutines.Job? = null
    private var isCurrentVideoFromVault: Boolean = false

    companion object {
        const val ACTION_PIP_AUDIO_ONLY = "com.pureplayer.video.ACTION_PIP_AUDIO_ONLY"
        const val ACTION_PIP_PLAY_PAUSE = "com.pureplayer.video.ACTION_PIP_PLAY_PAUSE"
        const val ACTION_PIP_PREV = "com.pureplayer.video.ACTION_PIP_PREV"
        const val ACTION_PIP_NEXT = "com.pureplayer.video.ACTION_PIP_NEXT"
        const val EXTRA_PLAYLIST_URIS = "EXTRA_PLAYLIST_URIS"
        const val EXTRA_PLAYLIST_TITLES = "EXTRA_PLAYLIST_TITLES"
        const val EXTRA_PLAYLIST_INDEX = "EXTRA_PLAYLIST_INDEX"
        const val EXTRA_IS_STREAM = "EXTRA_IS_STREAM"
        const val EXTRA_IS_VAULT_VIDEO = "EXTRA_IS_VAULT_VIDEO"
        const val EXTRA_VIDEO_WIDTH = "EXTRA_VIDEO_WIDTH"
        const val EXTRA_VIDEO_HEIGHT = "EXTRA_VIDEO_HEIGHT"
        @Volatile
        var isVaultVideoActivelyPlaying: Boolean = false
    }

    private var batteryReceiverRegistered = false
    private var lowBatteryNotified15 = false
    private var lowBatteryNotified5 = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) ((level * 100) / scale).coerceIn(0, 100) else return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)

            if (BatteryOptimizationManager.isPlayerBatteryIndicatorEnabled(this@PlayerActivity)) {
                val icon = if (isCharging) "⚡" else "🔋"
                binding.tvPlayerBattery.text = "$icon $pct%"
                binding.tvPlayerBattery.visibility = View.VISIBLE
            } else {
                binding.tvPlayerBattery.visibility = View.GONE
            }

            if (BatteryOptimizationManager.isLowBatteryAlertsEnabled(this@PlayerActivity)) {
                val alertLvl = BatteryOptimizationManager.evaluateLowBatteryAlert(
                    percent = pct,
                    isCharging = isCharging,
                    alreadyNotified15 = lowBatteryNotified15,
                    alreadyNotified5 = lowBatteryNotified5
                )
                if (alertLvl == 15) {
                    lowBatteryNotified15 = true
                    Toast.makeText(this@PlayerActivity, getString(R.string.low_battery_notice_15), Toast.LENGTH_LONG).show()
                } else if (alertLvl == 5) {
                    lowBatteryNotified5 = true
                    Toast.makeText(this@PlayerActivity, getString(R.string.low_battery_notice_5), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var wasInPipMode = false
    private var isSwitchingToAudioOnly = false
    private var pipReceiverRegistered = false

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_AUDIO_ONLY -> switchToBackgroundAudioOnlyMode()
                ACTION_PIP_PREV -> switchToAdjacentVideo(-1)
                ACTION_PIP_NEXT -> switchToAdjacentVideo(+1)
                ACTION_PIP_PLAY_PAUSE -> {
                    if (::mediaPlayer.isInitialized) {
                        if (mediaPlayer.isPlaying) {
                            mediaPlayer.pause()
                            updateAutoPipParams(isPlaying = false)
                        } else {
                            mediaPlayer.play()
                            updateAutoPipParams(isPlaying = true)
                        }
                    }
                }
            }
        }
    }
    private var viewsAttached = true
    private var externalUserAgent: String? = null
    private var externalHttpReferer: String? = null
    private var hasRetriedM3u8Fallback = false
    private val playerMenuBackStack = ArrayDeque<() -> Unit>()

    private fun popPlayerMenuBackStack(): Boolean {
        val prev = playerMenuBackStack.removeLastOrNull() ?: return false
        prev.invoke()
        return true
    }

    private var externalSubtitleCues = listOf<SubtitleCue>()
    private var subtitleSyncOffsetMs = 0L
    private var currentSubtitleSourceFile: File? = null
    private var currentSubtitleSourceLang: String = "en"
    private var currentSubtitleDisplayLabel: String = ""
    private var activeTranslationJob: kotlinx.coroutines.Job? = null
    private var originalSubtitleCues = listOf<SubtitleCue>()
    private var lastTranslatedTexts = listOf<String>()
    private val translationRequestId = java.util.concurrent.atomic.AtomicLong(0L)

    private fun cancelActiveSubtitleTranslation() {
        translationRequestId.incrementAndGet()
        activeTranslationJob?.cancel()
        activeTranslationJob = null
    }
    private var isDraggingSubtitle = false
    private var isPinchingSubtitle = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTransX = 0f
    private var dragStartTransY = 0f

    private var gestureVolumeAccumulator = 0f
    private val springOvershoot = OvershootInterpolator(1.6f)

    private var playerOrientationMode = PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO
    private var manualOrientationOverrideForCurrentVideo = false
    private var currentManualRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    private var initialHintWidth = 0
    private var initialHintHeight = 0
    private var orientationProbeJob: kotlinx.coroutines.Job? = null

    private val aspectModes = arrayOf(
        VlcAspectMode.SURFACE_BEST_FIT,
        VlcAspectMode.SURFACE_FIT_SCREEN,
        VlcAspectMode.SURFACE_FILL,
        VlcAspectMode.SURFACE_16_9,
        VlcAspectMode.SURFACE_4_3,
        VlcAspectMode.SURFACE_ORIGINAL
    )
    private var currentScaleIndex = 0

    // Video geometry reported by LibVLC IVLCVout.OnNewVideoLayoutListener
    private var mVideoWidth = 0
    private var mVideoHeight = 0
    private var mVideoVisibleWidth = 0
    private var mVideoVisibleHeight = 0
    private var mVideoSarNum = 1
    private var mVideoSarDen = 1
    private var lastInsetLeft = 0
    private var lastCutoutLeft = 0
    private var lastCutoutTop = 0
    private var lastCutoutRight = 0
    private var lastCutoutBottom = 0
    private var lastInsetTop = 0
    private var lastInsetRight = 0
    private var lastInsetBottom = 0
    private var currentAdaptiveState: AdaptiveWindowState? = null

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private val subtitlePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadExternalSubtitleUri(it) }
    }

    private val sleepTimerRunnable = Runnable {
        if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
            saveCurrentPositionNow()
            mediaPlayer.pause()
            showToastIndicator(getString(R.string.pro_sleep_30))
        }
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (::mediaPlayer.isInitialized) {
                val time = mediaPlayer.time
                if (mediaPlayer.isPlaying && !isUserSeeking) {
                    val length = mediaPlayer.length
                    if (length > 0) {
                        val progress = ((time.toDouble() / length.toDouble()) * 1000).toInt()
                        binding.seekBar.progress = progress
                        binding.tvCurrentTime.text = formatTime(time)
                        binding.tvTotalTime.text = formatTime(length)
                        currentVideoUri?.let { uri ->
                            PlaybackStore.saveProgress(this@PlayerActivity, uri, currentVideoTitle, time, length)
                        }
                    }
                }
                // Strictly resolve or clear active cue from current playback position (even when paused/seeked)
                if (!isUserSeeking) {
                    updateDraggableSubtitleText(time)
                }
            }
            handler.postDelayed(this, 100L)
        }
    }

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private val hideVerticalSliderRunnable = Runnable {
        binding.verticalSliderCard.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(180L)
            .withEndAction {
                binding.verticalSliderCard.visibility = View.GONE
            }
            .start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        try {
            val pipFilter = IntentFilter().apply {
                addAction(ACTION_PIP_AUDIO_ONLY)
                addAction(ACTION_PIP_PLAY_PAUSE)
                addAction(ACTION_PIP_PREV)
                addAction(ACTION_PIP_NEXT)
            }
            ContextCompat.registerReceiver(this, pipActionReceiver, pipFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            pipReceiverRegistered = true
        } catch (_: Exception) {}
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
        setupPlayerWindowInsets()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        playerOrientationMode = PlaybackStore.getDefaultOrientationIndex(this)
        binding.btnOrientation.text = getOrientationLabel(playerOrientationMode)
        currentDecoderIndex = PlaybackStore.getDecoderModeIndex(this)
        binding.btnDecoder.text = getDecoderShortBadge(currentDecoderIndex)
        currentScaleIndex = 0
        binding.btnAspectRatio.text = getScaleLabel(currentScaleIndex)

        extractIntentData(intent)
        if (savedInstanceState != null) {
            val savedUris = savedInstanceState.getStringArrayList(EXTRA_PLAYLIST_URIS)
            val savedTitles = savedInstanceState.getStringArrayList(EXTRA_PLAYLIST_TITLES)
            val savedIdx = savedInstanceState.getInt(EXTRA_PLAYLIST_INDEX, -1)
            if (!savedUris.isNullOrEmpty() && savedIdx in savedUris.indices) {
                playlistUris = savedUris.toMutableList()
                playlistTitles = savedTitles?.toMutableList() ?: MutableList(savedUris.size) { "Video" }
                currentPlaylistIndex = savedIdx
                currentVideoUri = Uri.parse(playlistUris[savedIdx])
                currentVideoTitle = playlistTitles.getOrElse(savedIdx) { currentVideoTitle }
                binding.tvVideoTitle.text = currentVideoTitle
            }
        }

        currentVideoUri?.let { uri ->
            subtitleSyncOffsetMs = PlaybackStore.getVideoSubtitleSyncMs(this, uri)
            val cachedSubPath = PlaybackStore.getVideoSubtitleCachePath(this, uri)
            val savedSubMeta = PlaybackStore.getVideoSubtitleUri(this, uri)
            if (!cachedSubPath.isNullOrEmpty()) {
                val initGen = videoSwitchGeneration.get()
                lifecycleScope.launch(Dispatchers.IO) {
                    val file = File(cachedSubPath)
                    if (file.exists()) {
                        val cues = SubtitleParser.parseFromFile(file)
                        if (cues.isNotEmpty() && initGen == videoSwitchGeneration.get()) {
                            withContext(Dispatchers.Main) {
                                if (initGen == videoSwitchGeneration.get()) {
                                    externalSubtitleCues = cues
                                    originalSubtitleCues = cues
                                    currentSubtitleSourceFile = file
                                    currentSubtitleDisplayLabel = savedSubMeta?.second.orEmpty()
                                    updateDraggableSubtitleText(if (::mediaPlayer.isInitialized) mediaPlayer.time else 0L)
                                }
                            }
                        }
                    }
                }
            }
        }

        applyEarlyOrientationForCurrentVideo()
        applyThemeAndSubtitleStyle()
        setupFluidDraggableAndPinchSubtitle()

        binding.playerRoot.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != (oldRight - oldLeft) || h != (oldBottom - oldTop))) {
                binding.videoSurfaceFrame.post {
                    changeSurfaceLayout()
                    restoreSubtitlePositionFromPercentage()
                }
            }
        }

        val hdrMode = PlaybackStore.getHdrModeIndex(this)
        val toneMapAlgo = when (hdrMode) {
            1 -> "reinhard"
            2 -> "mobius"
            else -> "hable"
        }
        val options = arrayListOf(
            "--avcodec-skiploopfilter", "0",
            "--avcodec-threads", "0",
            "--file-caching=600",
            "--live-caching=600",
            "--network-caching=1200",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--tone-mapping=$toneMapAlgo",
            "--subsdec-encoding", "UTF-8",
            "--freetype-rel-fontsize", "18"
        )
        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC)
        attachVlcViewsSafely()

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    binding.videoSurfaceFrame.post { changeSurfaceLayout() }
                    binding.playerLoadingBar.visibility = View.GONE
                    binding.btnPlayPause.setImageResource(R.drawable.ic_pause_modern)
                    updateMediaSessionPlaybackState(isPlaying = true)
                    updateAutoPipParams(isPlaying = true)
                    if (!hasAppliedInitialEqualizer) {
                        hasAppliedInitialEqualizer = true
                        applyAudioEqualizerFromStore()
                    }
                    if (externalSubtitleCues.isNotEmpty()) {
                        mediaPlayer.spuTrack = -1
                    }
                    if (!hasAppliedInitialSeek) {
                        hasAppliedInitialSeek = true
                        if (pendingStartPositionMs > 0L) {
                            mediaPlayer.time = pendingStartPositionMs
                            showToastIndicator(getString(R.string.resume_from_format, formatTime(pendingStartPositionMs)))
                        }
                    }
                }
                MediaPlayer.Event.Paused -> {
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play_modern)
                    updateMediaSessionPlaybackState(isPlaying = false)
                    updateAutoPipParams(isPlaying = false)
                }
                MediaPlayer.Event.EndReached -> {
                    SleepTimerManager.onMediaTrackEnded()
                    binding.playerLoadingBar.visibility = View.GONE
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play_modern)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    currentVideoUri?.let { PlaybackStore.savePosition(this, it, 0L) }
                    updateMediaSessionPlaybackState(isPlaying = false)
                    updateAutoPipParams(isPlaying = false)
                    showControls()
                }
                MediaPlayer.Event.EncounteredError -> {
                    binding.playerLoadingBar.visibility = View.GONE
                    val curStr = currentVideoUri?.toString() ?: ""
                    if (!hasRetriedM3u8Fallback && curStr.endsWith(".ts", ignoreCase = true) && curStr.startsWith("http", ignoreCase = true)) {
                        hasRetriedM3u8Fallback = true
                        val altUri = Uri.parse(curStr.dropLast(3) + ".m3u8")
                        currentVideoUri = altUri
                        startVideoPlaybackSafely(altUri)
                    }
                }
            }
        }

        val targetUri = currentVideoUri
        if (targetUri != null && isSafeExternalOrMediaUri(targetUri)) {
            if (targetUri.scheme == "file") {
                targetUri.path?.let { p ->
                    File(p).parentFile?.name?.let { fName ->
                        PlaybackStore.setLastActiveFolderName(this, fName)
                    }
                }
            }
            startVideoPlaybackSafely(targetUri)
            binding.videoSurfaceFrame.post {
                restoreSubtitlePositionFromPercentage()
            }
        } else {
            finish()
        }

        binding.tvVideoTitle.isSelected = true
        setupControls()
        applyResponsivePlayerControlsLayout()
        observeFoldablePostures()
        observeSleepTimerState()
        setupTouchGestures()
        setupMediaSessionAndAudioFocus()
    }

    private fun setupPlayerWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.playerRoot) { _, windowInsets ->
            val cutoutAndBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val cutoutOnly = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            lastInsetLeft = cutoutAndBars.left
            lastInsetTop = cutoutAndBars.top
            lastInsetRight = cutoutAndBars.right
            lastInsetBottom = cutoutAndBars.bottom
            lastCutoutLeft = cutoutOnly.left
            lastCutoutTop = cutoutOnly.top
            lastCutoutRight = cutoutOnly.right
            lastCutoutBottom = cutoutOnly.bottom
            applyPlayerBarInsets()
            binding.videoSurfaceFrame.post { changeSurfaceLayout() }
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.playerRoot)
    }

    private fun applyPlayerBarInsets() {
        val baseTopPadH = resources.getDimensionPixelSize(R.dimen.player_top_bar_padding_horizontal)
        val baseBotPadH = resources.getDimensionPixelSize(R.dimen.player_bottom_bar_padding_horizontal)
        binding.playerTopBar.setPadding(
            baseTopPadH + lastInsetLeft,
            dp(12) + lastInsetTop,
            baseTopPadH + lastInsetRight,
            dp(12)
        )
        binding.playerBottomBar.setPadding(
            baseBotPadH + lastInsetLeft,
            dp(10),
            baseBotPadH + lastInsetRight,
            dp(14) + lastInsetBottom
        )
        val unlockLp = binding.btnUnlock.layoutParams as? FrameLayout.LayoutParams
        if (unlockLp != null) {
            unlockLp.marginStart = dp(24) + lastInsetLeft
            unlockLp.topMargin = dp(24) + lastInsetTop
            binding.btnUnlock.layoutParams = unlockLp
        }
    }

    private fun attachVlcViewsSafely() {
        if (!::mediaPlayer.isInitialized) return
        val vlcVout = mediaPlayer.vlcVout
        if (!vlcVout.areViewsAttached()) {
            binding.subtitlesSurface.setZOrderMediaOverlay(true)
            binding.subtitlesSurface.holder.setFormat(PixelFormat.TRANSLUCENT)
            vlcVout.setVideoView(binding.videoSurface)
            vlcVout.setSubtitlesView(binding.subtitlesSurface)
            vlcVout.attachViews(this)
        }
        viewsAttached = true
        binding.videoSurfaceFrame.post { changeSurfaceLayout() }
    }

    private fun detachVlcViewsSafely() {
        cancelActivePlayerGesturesSafely()
        if (!::mediaPlayer.isInitialized) return
        val vlcVout = mediaPlayer.vlcVout
        if (vlcVout.areViewsAttached()) {
            vlcVout.detachViews()
        }
        viewsAttached = false
    }

    override fun onNewVideoLayout(
        vlcVout: IVLCVout?,
        width: Int,
        height: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int
    ) {
        mVideoWidth = width
        mVideoHeight = height
        mVideoVisibleWidth = visibleWidth
        mVideoVisibleHeight = visibleHeight
        mVideoSarNum = sarNum
        mVideoSarDen = sarDen
        val effW = if (visibleWidth > 0) visibleWidth else width
        val effH = if (visibleHeight > 0) visibleHeight else height
        val par = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen.toFloat() else 1f
        if (effW > 0 && effH > 0) {
            currentVideoUri?.let { uri ->
                PlaybackStore.saveVideoDimensionsMeta(this, uri, effW, effH, 0)
            }
            applySmartOrientationForDimensions(effW, effH, rotationDeg = 0, pixelAspectRatio = par)
        }
        changeSurfaceLayout()
    }

    /**
     * Applies the selected VLC aspect/scale mode cleanly:
     * 1) Resets mediaPlayer.scale = 0f and mediaPlayer.aspectRatio = null FIRST so modes never accumulate.
     * 2) Updates IVLCVout.setWindowSize(sw, sh) with real container dimensions.
     * 3) Applies SAR-aware surface dimensions via VlcSurfaceLayoutMath.
     */
    private fun changeSurfaceLayout() {
        if (!::mediaPlayer.isInitialized) return
        val rootW = binding.playerRoot.width
        val rootH = binding.playerRoot.height
        if (rootW <= 0 || rootH <= 0) return
        val postureState = currentAdaptiveState
        val hinge = postureState?.hingeBounds
        val sw: Int
        val sh: Int
        when {
            postureState?.posture == FoldPosture.TABLETOP && hinge != null && hinge.top > 0 -> {
                val (upperH, _) = FoldableAdaptiveMath.computeTabletopSplit(rootH, hinge.top, hinge.bottom)
                sw = rootW
                sh = upperH
            }
            postureState?.posture == FoldPosture.BOOK && hinge != null && hinge.left > 0 -> {
                val (startW, _) = FoldableAdaptiveMath.computeBookSplit(rootW, hinge.left, hinge.right)
                sw = startW
                sh = rootH
            }
            else -> {
                val activeMode = aspectModes[currentScaleIndex.coerceIn(0, aspectModes.lastIndex)]
                if (activeMode == VlcAspectMode.SURFACE_BEST_FIT) {
                    // Keep video inside display-cutout safe area so notch/punch-hole never crops any edge or subtitle
                    val safePadH = maxOf(lastCutoutLeft, lastCutoutRight)
                    val safePadV = maxOf(lastCutoutTop, lastCutoutBottom)
                    binding.videoSurfaceFrame.setPadding(lastCutoutLeft, lastCutoutTop, lastCutoutRight, lastCutoutBottom)
                    sw = (rootW - lastCutoutLeft - lastCutoutRight).coerceAtLeast(1)
                    sh = (rootH - lastCutoutTop - lastCutoutBottom).coerceAtLeast(1)
                    // Symmetric check unused var avoided
                    if (safePadH < 0 && safePadV < 0) { /* no-op */ }
                } else {
                    binding.videoSurfaceFrame.setPadding(0, 0, 0, 0)
                    sw = rootW
                    sh = rootH
                }
            }
        }

        val vlcVout = mediaPlayer.vlcVout
        // Step 1: Always reset previous state before applying any new mode
        mediaPlayer.scale = 0f
        mediaPlayer.aspectRatio = null

        // Step 2: Always notify LibVLC of the real window size
        vlcVout.setWindowSize(sw, sh)

        val mode = aspectModes[currentScaleIndex.coerceIn(0, aspectModes.lastIndex)]
        val result = VlcSurfaceLayoutMath.computeLayout(
            sw = sw,
            sh = sh,
            videoWidth = mVideoWidth,
            videoHeight = mVideoHeight,
            visibleWidth = mVideoVisibleWidth,
            visibleHeight = mVideoVisibleHeight,
            sarNum = mVideoSarNum,
            sarDen = mVideoSarDen,
            mode = mode
        )

        // Step 3: Apply computed surface layout params to both video and subtitle surfaces
        val videoLp = binding.videoSurface.layoutParams
        if (videoLp.width != result.surfaceWidth || videoLp.height != result.surfaceHeight) {
            videoLp.width = result.surfaceWidth
            videoLp.height = result.surfaceHeight
            binding.videoSurface.layoutParams = videoLp
        }
        val subLp = binding.subtitlesSurface.layoutParams
        if (subLp.width != result.surfaceWidth || subLp.height != result.surfaceHeight) {
            subLp.width = result.surfaceWidth
            subLp.height = result.surfaceHeight
            binding.subtitlesSurface.layoutParams = subLp
        }

        val frameLp = binding.videoSurfaceFrame.layoutParams
        if (frameLp.width != sw || frameLp.height != sh) {
            frameLp.width = sw
            frameLp.height = sh
            binding.videoSurfaceFrame.layoutParams = frameLp
        }

        // Step 4: Apply mode-specific aspectRatio / scale after clean reset
        mediaPlayer.aspectRatio = result.aspectRatio
        mediaPlayer.scale = result.scale

        binding.videoSurface.requestLayout()
        binding.videoSurface.invalidate()
        binding.subtitlesSurface.requestLayout()
    }

    private fun observeFoldablePostures() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@PlayerActivity)
                    .windowLayoutInfo(this@PlayerActivity)
                    .collect { layoutInfo ->
                        val state = FoldableWindowMetricsHelper.fromWindowLayoutInfo(this@PlayerActivity, layoutInfo)
                        applyFoldablePosture(state)
                    }
            }
        }
    }

    private fun applyFoldablePosture(state: AdaptiveWindowState) {
        currentAdaptiveState = state
        val rootW = binding.playerRoot.width
        val rootH = binding.playerRoot.height
        val frameLp = binding.videoSurfaceFrame.layoutParams as? FrameLayout.LayoutParams
        val subLp = binding.tvDraggableSubtitle.layoutParams as? FrameLayout.LayoutParams

        if (frameLp != null && rootW > 0 && rootH > 0) {
            val hinge = state.hingeBounds
            when {
                state.posture == FoldPosture.TABLETOP && hinge != null && hinge.top > 0 -> {
                    // Tabletop posture: video cleanly on the upper half above the hinge; controls on lower half
                    val (upperH, lowerH) = FoldableAdaptiveMath.computeTabletopSplit(rootH, hinge.top, hinge.bottom)
                    frameLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    frameLp.width = rootW
                    frameLp.height = upperH
                    frameLp.marginStart = 0
                    frameLp.topMargin = 0
                    binding.videoSurfaceFrame.layoutParams = frameLp
                    if (subLp != null) {
                        // Keep subtitles safely above the horizontal hinge
                        subLp.bottomMargin = lowerH + hinge.height + dp(18)
                        binding.tvDraggableSubtitle.layoutParams = subLp
                    }
                }
                state.posture == FoldPosture.BOOK && hinge != null && hinge.left > 0 -> {
                    // Book posture: video on the start side avoiding vertical hinge occlusion
                    val (startW, _) = FoldableAdaptiveMath.computeBookSplit(rootW, hinge.left, hinge.right)
                    frameLp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    frameLp.width = startW
                    frameLp.height = rootH
                    frameLp.marginStart = 0
                    frameLp.topMargin = 0
                    binding.videoSurfaceFrame.layoutParams = frameLp
                    if (subLp != null) {
                        subLp.bottomMargin = dp(46)
                        binding.tvDraggableSubtitle.layoutParams = subLp
                    }
                }
                else -> {
                    // Flat (folded cover screen or unfolded inner screen / tablet)
                    frameLp.gravity = Gravity.CENTER
                    frameLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    frameLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    frameLp.marginStart = 0
                    frameLp.topMargin = 0
                    binding.videoSurfaceFrame.layoutParams = frameLp
                    if (subLp != null) {
                        subLp.bottomMargin = dp(46)
                        binding.tvDraggableSubtitle.layoutParams = subLp
                    }
                }
            }
        }
        applyResponsivePlayerControlsLayout()
        binding.videoSurfaceFrame.post {
            changeSurfaceLayout()
            restoreSubtitlePositionFromPercentage()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelActivePlayerGesturesSafely()
        val wasVisible = isControlsVisible
        hideSystemBars()
        applyResponsivePlayerControlsLayout()
        ViewCompat.requestApplyInsets(binding.playerRoot)
        // Preserve exact controls visibility state across orientation changes
        if (isLocked) {
            binding.controlsContainer.visibility = View.GONE
            binding.btnUnlock.visibility = View.VISIBLE
        } else if (wasVisible) {
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.alpha = 1f
            binding.playerTopBar.translationY = 0f
            binding.playerBottomBar.translationY = 0f
            scheduleHideControls()
        } else {
            binding.controlsContainer.visibility = View.GONE
        }
        binding.videoSurfaceFrame.post {
            changeSurfaceLayout()
            restoreSubtitlePositionFromPercentage()
        }
    }

    private fun applyResponsivePlayerControlsLayout() {
        val config = resources.configuration
        val (winW, _) = FoldableWindowMetricsHelper.computeCurrentWindowDp(this)
        val screenWidthDp = if (winW > 0) winW else config.screenWidthDp
        val widthClass = FoldableAdaptiveMath.classifyWidth(screenWidthDp)
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE || widthClass != WindowWidthSizeClass.COMPACT

        val compactTopBar = ResponsiveUiMetrics.shouldUseCompactPlayerTopBar(screenWidthDp)
        val quickSubVisibility = if (compactTopBar) View.GONE else View.VISIBLE
        binding.subtitleToolsPill.visibility = quickSubVisibility

        // Dynamically re-constrain playerBottomBar using ConstraintSet so buttons NEVER scatter on rotation
        val cs = ConstraintSet()
        cs.clone(binding.playerBottomBar)

        cs.clear(R.id.transportGroup)
        cs.clear(R.id.leftToolsGroup)
        cs.clear(R.id.rightToolsGroup)

        cs.constrainWidth(R.id.transportGroup, ConstraintSet.WRAP_CONTENT)
        cs.constrainHeight(R.id.transportGroup, ConstraintSet.WRAP_CONTENT)
        cs.constrainWidth(R.id.leftToolsGroup, ConstraintSet.WRAP_CONTENT)
        cs.constrainHeight(R.id.leftToolsGroup, ConstraintSet.WRAP_CONTENT)
        cs.constrainWidth(R.id.rightToolsGroup, ConstraintSet.WRAP_CONTENT)
        cs.constrainHeight(R.id.rightToolsGroup, ConstraintSet.WRAP_CONTENT)

        // Centered primary transport group in BOTH orientations
        cs.connect(R.id.transportGroup, ConstraintSet.TOP, R.id.seekBarRow, ConstraintSet.BOTTOM, dp(8))
        cs.connect(R.id.transportGroup, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        cs.connect(R.id.transportGroup, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

        if (isLandscape) {
            // Landscape: Single organized row — leftTools at START, transport CENTERED, rightTools at END
            cs.connect(R.id.leftToolsGroup, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(R.id.leftToolsGroup, ConstraintSet.TOP, R.id.transportGroup, ConstraintSet.TOP)
            cs.connect(R.id.leftToolsGroup, ConstraintSet.BOTTOM, R.id.transportGroup, ConstraintSet.BOTTOM)

            cs.connect(R.id.rightToolsGroup, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.connect(R.id.rightToolsGroup, ConstraintSet.TOP, R.id.transportGroup, ConstraintSet.TOP)
            cs.connect(R.id.rightToolsGroup, ConstraintSet.BOTTOM, R.id.transportGroup, ConstraintSet.BOTTOM)
        } else {
            // Portrait: Two tidy rows — centered transport on Row 1, evenly chained secondary tools on Row 2
            cs.connect(R.id.leftToolsGroup, ConstraintSet.TOP, R.id.transportGroup, ConstraintSet.BOTTOM, dp(8))
            cs.connect(R.id.leftToolsGroup, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(R.id.leftToolsGroup, ConstraintSet.END, R.id.rightToolsGroup, ConstraintSet.START, dp(12))

            cs.connect(R.id.rightToolsGroup, ConstraintSet.TOP, R.id.leftToolsGroup, ConstraintSet.TOP)
            cs.connect(R.id.rightToolsGroup, ConstraintSet.BOTTOM, R.id.leftToolsGroup, ConstraintSet.BOTTOM)
            cs.connect(R.id.rightToolsGroup, ConstraintSet.START, R.id.leftToolsGroup, ConstraintSet.END)
            cs.connect(R.id.rightToolsGroup, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

            cs.setHorizontalChainStyle(R.id.leftToolsGroup, ConstraintSet.CHAIN_PACKED)
        }

        cs.applyTo(binding.playerBottomBar)
        applyPlayerBarInsets()
    }

    /**
     * Premium Cinema Popup Menu Engine for PlayerActivity
     */
    private fun showPlayerPremiumMenu(
        headerIcon: String,
        title: String,
        subtitle: String?,
        items: List<PremiumMenuItem>
    ) {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)
        val isLight = PlaybackStore.isLightThemeActive(this)
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)

        val outerScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }
        outerScroll.addView(cardContainer)

        val topPill = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(accent)
            }
        }
        cardContainer.addView(topPill)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }

        val tvHeaderIcon = TextView(this).apply {
            text = headerIcon
            textSize = 21f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                marginEnd = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(15).toFloat()
                setColor(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)))
                setStroke(dp(1), Color.argb(85, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(primaryText)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        headerTextCol.addView(tvTitle)

        if (!subtitle.isNullOrBlank()) {
            val tvSubtitle = TextView(this).apply {
                text = subtitle
                setTextColor(accent)
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            }
            headerTextCol.addView(tvSubtitle)
        }

        headerRow.addView(tvHeaderIcon)
        headerRow.addView(headerTextCol)
        cardContainer.addView(headerRow)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(10)
            }
            setBackgroundColor(if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#22FFFFFF"))
        }
        cardContainer.addView(divider)

        val dialog = AlertDialog.Builder(this).setView(outerScroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        for (item in items) {
            if (!item.sectionHeader.isNullOrBlank()) {
                val secRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(12), dp(4), dp(6))
                }
                val secBar = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(4), dp(15)).apply {
                        marginEnd = dp(8)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(accent)
                    }
                }
                val tvSec = TextView(this).apply {
                    text = item.sectionHeader
                    setTextColor(accent)
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                }
                secRow.addView(secBar)
                secRow.addView(tvSec)
                cardContainer.addView(secRow)
            }

            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                val strokeC = when {
                    item.isDanger -> Color.parseColor("#55F43F5E")
                    item.isAccentHighlighted -> Color.argb(145, Color.red(accent), Color.green(accent), Color.blue(accent))
                    else -> if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#1FFFFFFF")
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(15).toFloat()
                    setColor(bg)
                    setStroke(dp(1), strokeC)
                }
            }

            val iconBadge = TextView(this).apply {
                text = item.iconEmoji
                textSize = 17f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    marginEnd = dp(12)
                }
                val badgeBg = if (item.isDanger) {
                    Color.parseColor("#28F43F5E")
                } else {
                    Color.argb(32, Color.red(accent), Color.green(accent), Color.blue(accent))
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(11).toFloat()
                    setColor(badgeBg)
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvItemTitle = TextView(this).apply {
                text = item.title
                setTextColor(if (item.isDanger) Color.parseColor("#E11D48") else primaryText)
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
            }
            textCol.addView(tvItemTitle)

            if (!item.subtitle.isNullOrBlank()) {
                val tvItemSub = TextView(this).apply {
                    text = item.subtitle
                    setTextColor(if (item.isAccentHighlighted) accent else secondaryText)
                    textSize = 11.5f
                    setPadding(0, dp(2), 0, 0)
                }
                textCol.addView(tvItemSub)
            }

            val tvArrow = TextView(this).apply {
                text = "›"
                setTextColor(if (item.isDanger) Color.parseColor("#F43F5E") else accent)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, dp(2), 0)
                visibility = if (item.isInteractive) View.VISIBLE else View.GONE
            }

            rowCard.addView(iconBadge)
            rowCard.addView(textCol)
            if (item.deleteAction != null) {
                val btnDel = TextView(this).apply {
                    text = "✕"
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#F43F5E"))
                    minWidth = dp(48)
                    minHeight = dp(48)
                    contentDescription = item.deleteContentDescription ?: getString(R.string.delete_action)
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        marginStart = dp(4)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.argb(if (isLight) 24 else 32, 244, 63, 94))
                        setStroke(dp(1), Color.argb(75, 244, 63, 94))
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        item.deleteAction.invoke()
                    }
                }
                rowCard.addView(btnDel)
            } else {
                rowCard.addView(tvArrow)
            }

            if (item.isInteractive) {
                rowCard.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(85L).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                    }
                    false
                }

                rowCard.setOnClickListener {
                    dialog.dismiss()
                    item.onClick()
                }
            }

            cardContainer.addView(rowCard)
        }

        val btnClose = TextView(this).apply {
            text = getString(R.string.close)
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#18FFFFFF"))
            }
            setOnClickListener {
                dialog.dismiss()
                popPlayerMenuBackStack()
            }
        }
        cardContainer.addView(btnClose)

        dialog.setOnCancelListener {
            popPlayerMenuBackStack()
        }

        dialog.window?.setDimAmount(0.58f)
        dialog.show()
        cardContainer.alpha = 0f
        cardContainer.translationY = dp(24).toFloat()
        cardContainer.scaleX = 0.94f
        cardContainer.scaleY = 0.94f
        cardContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /**
     * Tall Vertical Pill Slider for Volume & Brightness (Flagship Cinema Style)
     */
    private fun showVerticalSliderIndicator(isVolume: Boolean, percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val accent = PlaybackStore.getAccentColor(this)

        // Place Volume slider on the Start side and Brightness slider on the End side so finger never blocks it
        val lp = binding.verticalSliderCard.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (isVolume) {
            Gravity.CENTER_VERTICAL or Gravity.START
        } else {
            Gravity.CENTER_VERTICAL or Gravity.END
        }
        binding.verticalSliderCard.layoutParams = lp

        binding.verticalSliderCard.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(31).toFloat()
            setColor(Color.parseColor("#EB090D16"))
            setStroke(dp(1), Color.argb(125, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }

        binding.verticalBarTrack.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#2EFFFFFF"))
        }

        val fillColor = if (isVolume) accent else Color.parseColor("#FACC15")
        binding.verticalBarFill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(fillColor)
        }

        val icon = if (isVolume) {
            when {
                clamped == 0 -> "🔇"
                clamped < 35 -> "🔈"
                clamped < 70 -> "🔉"
                else -> "🔊"
            }
        } else {
            when {
                clamped < 30 -> "🌙"
                clamped < 70 -> "🌤️"
                else -> "☀️"
            }
        }

        binding.tvVerticalSliderIcon.text = icon
        binding.tvVerticalSliderPercent.text = "$clamped%"
        binding.tvVerticalSliderPercent.setTextColor(if (isVolume) accent else Color.parseColor("#FACC15"))

        binding.verticalBarTrack.post {
            val trackH = binding.verticalBarTrack.height.takeIf { it > 0 } ?: dp(116)
            val targetFillH = ((clamped / 100f) * trackH).toInt().coerceAtLeast(if (clamped > 0) dp(4) else 0)
            val fillLp = binding.verticalBarFill.layoutParams
            fillLp.height = targetFillH
            binding.verticalBarFill.layoutParams = fillLp
        }

        if (binding.verticalSliderCard.visibility != View.VISIBLE) {
            binding.verticalSliderCard.alpha = 0f
            binding.verticalSliderCard.scaleX = 0.9f
            binding.verticalSliderCard.scaleY = 0.9f
            binding.verticalSliderCard.visibility = View.VISIBLE
        }

        binding.verticalSliderCard.animate().cancel()
        binding.verticalSliderCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120L)
            .start()

        binding.tvDraggableSubtitle.bringToFront()
        handler.removeCallbacks(hideVerticalSliderRunnable)
        handler.postDelayed(hideVerticalSliderRunnable, PlayerGestureConstants.INDICATOR_FADE_DELAY_MS)
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (!::mediaPlayer.isInitialized) return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                cancelActivePlayerGesturesSafely()
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) return@OnAudioFocusChangeListener
                if (mediaPlayer.isPlaying) {
                    saveCurrentPositionNow()
                    mediaPlayer.pause()
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play_modern)
                    updateMediaSessionPlaybackState(isPlaying = false)
                }
            }
        }
    }

    private fun requestPlayerAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, handler)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
    }

    private fun abandonPlayerAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
    }

    private fun togglePlayPauseFromHeadset(forcePlay: Boolean? = null) {
        if (!::mediaPlayer.isInitialized) return
        val shouldPlay = forcePlay ?: !mediaPlayer.isPlaying
        if (shouldPlay) {
            requestPlayerAudioFocus()
            mediaPlayer.play()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause_modern)
            updateMediaSessionPlaybackState(isPlaying = true)
            showToastIndicator("▶️")
        } else {
            saveCurrentPositionNow()
            mediaPlayer.pause()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.btnPlayPause.setImageResource(R.drawable.ic_play_modern)
            updateMediaSessionPlaybackState(isPlaying = false)
            showToastIndicator("⏸️")
        }
    }

    private fun seekByHeadsetDelta(deltaMs: Long) {
        if (!::mediaPlayer.isInitialized) return
        val length = mediaPlayer.length
        val target = if (deltaMs > 0) {
            (mediaPlayer.time + deltaMs).coerceAtMost(if (length > 0) length else Long.MAX_VALUE)
        } else {
            (mediaPlayer.time + deltaMs).coerceAtLeast(0L)
        }
        mediaPlayer.time = target
        saveCurrentPositionNow()
        updateMediaSessionPlaybackState(isPlaying = mediaPlayer.isPlaying)
        showToastIndicator(if (deltaMs > 0) getString(R.string.toast_forward_15) else getString(R.string.toast_rewind_15))
    }

    private fun handleMediaKeyEvent(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val now = System.currentTimeMillis()
                if (now - lastHeadsetHookTapMs in 1..360) {
                    lastHeadsetHookTapMs = 0L
                    seekByHeadsetDelta(15_000L)
                } else {
                    lastHeadsetHookTapMs = now
                    togglePlayPauseFromHeadset(null)
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                togglePlayPauseFromHeadset(true)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                togglePlayPauseFromHeadset(false)
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekByHeadsetDelta(15_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekByHeadsetDelta(-15_000L)
                true
            }
            else -> false
        }
    }

    private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
        val session = mediaSession ?: return
        try {
            val pos = if (::mediaPlayer.isInitialized) mediaPlayer.time.coerceAtLeast(0L) else 0L
            val stateCode = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO
            val pbState = PlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, pos, if (isPlaying) currentSpeed else 0f)
                .build()
            session.setPlaybackState(pbState)
        } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
    }

    private fun setupMediaSessionAndAudioFocus() {
        requestPlayerAudioFocus()
        try {
            val session = MediaSession(this, "IPTVAndVideoPlayerMediaSession")
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    togglePlayPauseFromHeadset(true)
                }

                override fun onPause() {
                    togglePlayPauseFromHeadset(false)
                }

                override fun onStop() {
                    togglePlayPauseFromHeadset(false)
                }

                override fun onSkipToNext() {
                    if (hasActiveVideoPlaylist()) {
                        runOnUiThread { switchToAdjacentVideo(+1) }
                    } else {
                        seekByHeadsetDelta(15_000L)
                    }
                }

                override fun onSkipToPrevious() {
                    if (hasActiveVideoPlaylist()) {
                        runOnUiThread { switchToAdjacentVideo(-1) }
                    } else {
                        seekByHeadsetDelta(-15_000L)
                    }
                }

                override fun onFastForward() {
                    seekByHeadsetDelta(15_000L)
                }

                override fun onRewind() {
                    seekByHeadsetDelta(-15_000L)
                }

                override fun onSeekTo(pos: Long) {
                    if (::mediaPlayer.isInitialized && mediaPlayer.length > 0) {
                        mediaPlayer.time = pos.coerceIn(0L, mediaPlayer.length)
                        saveCurrentPositionNow()
                        updateMediaSessionPlaybackState(isPlaying = mediaPlayer.isPlaying)
                    }
                }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        if (handleMediaKeyEvent(keyEvent.keyCode)) {
                            return true
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            session.isActive = true
            mediaSession = session
            updateMediaSessionPlaybackState(isPlaying = true)
        } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (handleMediaKeyEvent(event.keyCode)) {
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val nextVol = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + 1).coerceAtMost(maxVol)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                    val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100).toInt()
                    showVerticalSliderIndicator(isVolume = true, percent = pct)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val nextVol = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) - 1).coerceAtLeast(0)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                    val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100).toInt()
                    showVerticalSliderIndicator(isVolume = true, percent = pct)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val controlsVisible = (binding.controlsContainer.visibility == View.VISIBLE)
                    if (!controlsVisible) {
                        if (::mediaPlayer.isInitialized) {
                            if (mediaPlayer.isPlaying) {
                                mediaPlayer.pause()
                                binding.btnPlayPause.setImageResource(R.drawable.ic_play_modern)
                            } else {
                                mediaPlayer.play()
                                binding.btnPlayPause.setImageResource(R.drawable.ic_pause_modern)
                            }
                        }
                        showControls()
                        scheduleHideControls()
                        return true
                    } else {
                        scheduleHideControls()
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (binding.controlsContainer.visibility != View.VISIBLE) {
                        if (::mediaPlayer.isInitialized) {
                            val target = (mediaPlayer.time - 10_000L).coerceAtLeast(0L)
                            mediaPlayer.time = target
                            showToastIndicator("⏪ -10s")
                        }
                        return true
                    } else {
                        scheduleHideControls()
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (binding.controlsContainer.visibility != View.VISIBLE) {
                        if (::mediaPlayer.isInitialized) {
                            val len = mediaPlayer.length.coerceAtLeast(0L)
                            val target = if (len > 0L) (mediaPlayer.time + 10_000L).coerceAtMost(len) else (mediaPlayer.time + 10_000L)
                            mediaPlayer.time = target
                            showToastIndicator("⏩ +10s")
                        }
                        return true
                    } else {
                        scheduleHideControls()
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (binding.controlsContainer.visibility != View.VISIBLE) {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val nextVol = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + 1).coerceAtMost(maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                        val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100).toInt()
                        showVerticalSliderIndicator(isVolume = true, percent = pct)
                        return true
                    } else {
                        scheduleHideControls()
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (binding.controlsContainer.visibility != View.VISIBLE) {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val nextVol = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) - 1).coerceAtLeast(0)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                        val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100).toInt()
                        showVerticalSliderIndicator(isVolume = true, percent = pct)
                        return true
                    } else {
                        scheduleHideControls()
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun extractIntentData(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        var resolvedUri: Uri? = incomingIntent.data

        if (resolvedUri == null && incomingIntent.clipData != null && incomingIntent.clipData!!.itemCount > 0) {
            resolvedUri = incomingIntent.clipData!!.getItemAt(0).uri
        }

        if (resolvedUri == null && incomingIntent.action == Intent.ACTION_SEND) {
            val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!sharedText.isNullOrEmpty()) {
                val urlMatch = Regex("https?://\\S+").find(sharedText)
                resolvedUri = Uri.parse(urlMatch?.value ?: sharedText)
            }
        }

        if (resolvedUri == null) {
            val extraUrl = incomingIntent.getStringExtra("url") ?: incomingIntent.getStringExtra("video_url")
            if (!extraUrl.isNullOrBlank()) {
                resolvedUri = Uri.parse(extraUrl.trim())
            }
        }

        val incomingUris = incomingIntent.getStringArrayListExtra(EXTRA_PLAYLIST_URIS)
        val incomingTitles = incomingIntent.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES)
        val incomingIdx = incomingIntent.getIntExtra(EXTRA_PLAYLIST_INDEX, -1)
        val uriScheme = resolvedUri?.scheme?.lowercase(Locale.ROOT) ?: ""
        isCurrentVideoFromVault = incomingIntent.getBooleanExtra(EXTRA_IS_VAULT_VIDEO, false)
        initialHintWidth = incomingIntent.getIntExtra(EXTRA_VIDEO_WIDTH, 0)
        initialHintHeight = incomingIntent.getIntExtra(EXTRA_VIDEO_HEIGHT, 0)
        if (isCurrentVideoFromVault) isVaultVideoActivelyPlaying = true
        isStreamOrIptv = incomingIntent.getBooleanExtra(EXTRA_IS_STREAM, false) ||
            uriScheme == "http" || uriScheme == "https" || uriScheme == "rtsp" || uriScheme == "rtmp" || uriScheme == "mms"
        if (!isStreamOrIptv && !incomingUris.isNullOrEmpty() && incomingIdx in incomingUris.indices) {
            playlistUris = incomingUris.toMutableList()
            playlistTitles = incomingTitles?.toMutableList() ?: MutableList(incomingUris.size) { "Video" }
            currentPlaylistIndex = incomingIdx
        } else {
            playlistUris = mutableListOf()
            playlistTitles = mutableListOf()
            currentPlaylistIndex = -1
        }

        val rawStr = resolvedUri?.toString() ?: ""
        if (rawStr.startsWith("vlc://", ignoreCase = true)) {
            val inner = rawStr.substring(6)
            resolvedUri = if (inner.startsWith("http://", ignoreCase = true) || inner.startsWith("https://", ignoreCase = true)) {
                Uri.parse(inner)
            } else {
                Uri.parse("https://$inner")
            }
        }

        currentVideoUri = resolvedUri
        currentVideoTitle = incomingIntent.getStringExtra("VIDEO_TITLE")
            ?: incomingIntent.getStringExtra("title")
            ?: incomingIntent.getStringExtra(Intent.EXTRA_TITLE)
            ?: resolvedUri?.lastPathSegment
            ?: "Video"
        binding.tvVideoTitle.text = currentVideoTitle

        val mxPos = incomingIntent.getIntExtra("position", -1)
        pendingStartPositionMs = if (mxPos > 0) {
            mxPos.toLong()
        } else {
            incomingIntent.getLongExtra("START_POSITION_MS", 0L)
        }

        externalUserAgent = incomingIntent.getStringExtra("user-agent")?.replace("\r", "")?.replace("\n", "")?.trim()
        externalHttpReferer = incomingIntent.getStringExtra("referer")?.replace("\r", "")?.replace("\n", "")?.trim()
    }

    private fun isSafeExternalOrMediaUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val allowedSchemes = setOf("content", "file", "http", "https", "rtsp", "rtmp", "mms", "vlc")
        if (scheme !in allowedSchemes) return false

        if (scheme == "file") {
            val rawPath = uri.path ?: return false
            if (rawPath.contains("..")) return false
            try {
                val canonicalTarget = File(rawPath).canonicalPath
                val internalDataDir = applicationInfo.dataDir
                if (canonicalTarget.startsWith(internalDataDir) ||
                    canonicalTarget.startsWith("/data/data/") ||
                    canonicalTarget.startsWith("/data/user/")
                ) {
                    return false
                }
            } catch (_: Exception) {
                return false
            }
        }
        return true
    }

    private fun getOrientationLabel(mode: Int): String {
        return when (mode) {
            PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO -> {
                if (manualOrientationOverrideForCurrentVideo) {
                    if (currentManualRequestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
                        getString(R.string.orientation_portrait)
                    } else {
                        getString(R.string.orientation_landscape)
                    }
                } else {
                    getString(R.string.orientation_auto_video_short)
                }
            }
            PlaybackStore.ORIENTATION_MODE_FOLLOW_DEVICE -> getString(R.string.orientation_follow_device_short)
            PlaybackStore.ORIENTATION_MODE_LANDSCAPE -> getString(R.string.orientation_landscape)
            else -> getString(R.string.orientation_portrait)
        }
    }

    private fun applyRequestedOrientationIfDifferent(targetOrientation: Int) {
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        val inMultiWin = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInMultiWindowMode else false
        if (inPip || inMultiWin) return
        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
        }
    }

    private fun applySmartOrientationForDimensions(
        rawW: Int,
        rawH: Int,
        rotationDeg: Int,
        pixelAspectRatio: Float
    ) {
        if (playerOrientationMode != PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO) return
        if (manualOrientationOverrideForCurrentVideo) return

        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        val inMultiWin = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInMultiWindowMode else false
        val swDp = resources.configuration.smallestScreenWidthDp

        val decision = HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(
            rawWidth = rawW,
            rawHeight = rawH,
            rotationDegrees = rotationDeg,
            pixelWidthHeightRatio = pixelAspectRatio,
            isInPip = inPip,
            isInMultiWindow = inMultiWin,
            smallestScreenWidthDp = swDp
        )
        when (decision) {
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.LANDSCAPE ->
                applyRequestedOrientationIfDifferent(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.PORTRAIT ->
                applyRequestedOrientationIfDifferent(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.KEEP_CURRENT -> {
                // Keep current orientation (square video, large screen portrait, PiP, multi-window, or unknown size)
            }
        }
    }

    private fun applyEarlyOrientationForCurrentVideo() {
        orientationProbeJob?.cancel()
        binding.btnOrientation.text = getOrientationLabel(playerOrientationMode)

        when (playerOrientationMode) {
            PlaybackStore.ORIENTATION_MODE_FOLLOW_DEVICE -> {
                // SCREEN_ORIENTATION_USER honors system auto-rotate lock
                applyRequestedOrientationIfDifferent(ActivityInfo.SCREEN_ORIENTATION_USER)
                return
            }
            PlaybackStore.ORIENTATION_MODE_LANDSCAPE -> {
                applyRequestedOrientationIfDifferent(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                return
            }
            PlaybackStore.ORIENTATION_MODE_PORTRAIT -> {
                applyRequestedOrientationIfDifferent(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
                return
            }
        }

        if (manualOrientationOverrideForCurrentVideo) {
            applyRequestedOrientationIfDifferent(currentManualRequestedOrientation)
            return
        }

        val uri = currentVideoUri ?: return
        val cached = PlaybackStore.getVideoDimensionsMeta(this, uri)
        if (cached != null) {
            applySmartOrientationForDimensions(cached.first, cached.second, cached.third, 1f)
            return
        }
        if (initialHintWidth > 0 && initialHintHeight > 0) {
            applySmartOrientationForDimensions(initialHintWidth, initialHintHeight, 0, 1f)
        }

        // Background MediaMetadataRetriever probe for exact rotation metadata (e.g., 1920x1080 rotated 90°)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: ""
        if (scheme == "content" || scheme == "file") {
            val probeGen = videoSwitchGeneration.get()
            orientationProbeJob = lifecycleScope.launch(Dispatchers.IO) {
                val probed = runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(this@PlayerActivity, uri)
                        val w = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val h = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rot = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (w > 0 && h > 0) Triple(w, h, rot) else null
                    } finally {
                        runCatching { mmr.release() }
                    }
                }.getOrNull()
                if (probed != null && probeGen == videoSwitchGeneration.get()) {
                    PlaybackStore.saveVideoDimensionsMeta(this@PlayerActivity, uri, probed.first, probed.second, probed.third)
                    withContext(Dispatchers.Main) {
                        if (probeGen == videoSwitchGeneration.get() && !isFinishing && !isDestroyed) {
                            applySmartOrientationForDimensions(probed.first, probed.second, probed.third, 1f)
                        }
                    }
                }
            }
        }
    }

    private fun showPlayerOrientationModeDialog() {
        val items = listOf(
            PremiumMenuItem(
                iconEmoji = if (playerOrientationMode == PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO) "✅" else "🎬",
                title = getString(R.string.orientation_auto_video),
                subtitle = getString(R.string.orientation_auto_video_sub),
                isAccentHighlighted = (playerOrientationMode == PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO),
                onClick = {
                    playerOrientationMode = PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO
                    manualOrientationOverrideForCurrentVideo = false
                    PlaybackStore.setDefaultOrientationIndex(this, playerOrientationMode)
                    applyEarlyOrientationForCurrentVideo()
                }
            ),
            PremiumMenuItem(
                iconEmoji = if (playerOrientationMode == PlaybackStore.ORIENTATION_MODE_FOLLOW_DEVICE) "✅" else "🔄",
                title = getString(R.string.orientation_follow_device),
                subtitle = getString(R.string.orientation_follow_device_sub),
                isAccentHighlighted = (playerOrientationMode == PlaybackStore.ORIENTATION_MODE_FOLLOW_DEVICE),
                onClick = {
                    playerOrientationMode = PlaybackStore.ORIENTATION_MODE_FOLLOW_DEVICE
                    manualOrientationOverrideForCurrentVideo = false
                    PlaybackStore.setDefaultOrientationIndex(this, playerOrientationMode)
                    applyEarlyOrientationForCurrentVideo()
                }
            ),
            PremiumMenuItem(
                iconEmoji = if (playerOrientationMode == PlaybackStore.ORIENTATION_MODE_LANDSCAPE) "✅" else "↔️",
                title = getString(R.string.orientation_landscape),
                subtitle = null,
                isAccentHighlighted = (playerOrientationMode == PlaybackStore.ORIENTATION_MODE_LANDSCAPE),
                onClick = {
                    playerOrientationMode = PlaybackStore.ORIENTATION_MODE_LANDSCAPE
                    manualOrientationOverrideForCurrentVideo = false
                    PlaybackStore.setDefaultOrientationIndex(this, playerOrientationMode)
                    applyEarlyOrientationForCurrentVideo()
                }
            ),
            PremiumMenuItem(
                iconEmoji = if (playerOrientationMode == PlaybackStore.ORIENTATION_MODE_PORTRAIT) "✅" else "↕️",
                title = getString(R.string.orientation_portrait),
                subtitle = null,
                isAccentHighlighted = (playerOrientationMode == PlaybackStore.ORIENTATION_MODE_PORTRAIT),
                onClick = {
                    playerOrientationMode = PlaybackStore.ORIENTATION_MODE_PORTRAIT
                    manualOrientationOverrideForCurrentVideo = false
                    PlaybackStore.setDefaultOrientationIndex(this, playerOrientationMode)
                    applyEarlyOrientationForCurrentVideo()
                }
            )
        )
        showPlayerPremiumMenu("🔄", getString(R.string.setting_default_orientation), getOrientationLabel(playerOrientationMode), items)
    }

    private fun getScaleLabel(index: Int): String {
        return when (aspectModes[index.coerceIn(0, aspectModes.lastIndex)]) {
            VlcAspectMode.SURFACE_BEST_FIT -> getString(R.string.scale_best_fit)
            VlcAspectMode.SURFACE_FIT_SCREEN -> getString(R.string.scale_fit_screen)
            VlcAspectMode.SURFACE_FILL -> getString(R.string.scale_fill)
            VlcAspectMode.SURFACE_16_9 -> getString(R.string.scale_16_9)
            VlcAspectMode.SURFACE_4_3 -> getString(R.string.scale_4_3)
            VlcAspectMode.SURFACE_ORIGINAL -> getString(R.string.scale_original)
        }
    }

    private fun startVideoPlaybackSafely(uri: Uri) {
        try {
            activePfd?.close()
            activePfd = null

            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: ""
            val media = if (scheme == "content") {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                activePfd = pfd
                if (pfd != null) {
                    Media(libVLC, pfd.fileDescriptor)
                } else {
                    Media(libVLC, uri)
                }
            } else {
                Media(libVLC, uri)
            }

            if (scheme.startsWith("http") || scheme == "rtsp" || scheme == "rtmp" || scheme == "mms") {
                binding.playerLoadingBar.visibility = View.VISIBLE
                media.addOption(":network-caching=900")
                media.addOption(":http-reconnect")
                val ua = externalUserAgent ?: "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                media.addOption(":http-user-agent=$ua")
                if (!externalHttpReferer.isNullOrEmpty()) {
                    media.addOption(":http-referrer=$externalHttpReferer")
                }
            } else {
                media.addOption(":file-caching=600")
            }

            // Pre-seek at demuxer level BEFORE decoding frame 0 -> eliminates the 2-second black screen on resume!
            if (!hasAppliedInitialSeek && pendingStartPositionMs > 0L) {
                val startSec = String.format(Locale.US, "%.2f", pendingStartPositionMs / 1000.0)
                media.addOption(":start-time=$startSec")
                hasAppliedInitialSeek = true
                showToastIndicator(getString(R.string.resume_from_format, formatTime(pendingStartPositionMs)))
            }

            when (currentDecoderIndex) {
                0 -> {
                    // HW+ (Hardware Accelerated Plus)
                    media.setHWDecoderEnabled(true, true)
                    media.addOption(":codec=mediacodec_ndk,mediacodec_jni,all")
                }
                1 -> {
                    // HW (Standard Hardware Decoder)
                    media.setHWDecoderEnabled(true, false)
                }
                else -> {
                    // SW (Software CPU Decoder)
                    media.setHWDecoderEnabled(false, false)
                    media.addOption(":codec=avcodec,all")
                }
            }
            hasAppliedInitialEqualizer = false
            mediaPlayer.media = media
            media.release()
        } catch (_: Exception) {
            try {
                val fallbackMedia = Media(libVLC, uri)
                fallbackMedia.setHWDecoderEnabled(true, false)
                mediaPlayer.media = fallbackMedia
                fallbackMedia.release()
            } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }

        if (!viewsAttached) {
            attachVlcViewsSafely()
            viewsAttached = true
        }
        mediaPlayer.play()
        changeSurfaceLayout()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)
        scheduleHideControls()
    }

    private fun loadExternalSubtitleUri(uri: Uri) {
        if (!isSafeExternalOrMediaUri(uri)) return
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use {
                    (SubtitleSearchEngine.readBytesBounded(it, 8 * 1024 * 1024) ?: ByteArray(0))
                }
            }
            if (bytes != null && bytes.isNotEmpty()) {
                val cues = withContext(Dispatchers.Default) {
                    SubtitleParser.parseFromBytes(bytes)
                }
                if (cues.isNotEmpty()) {
                    currentVideoUri?.let { vUri ->
                        withContext(Dispatchers.IO) {
                            val cacheDir = File(filesDir, "subtitles_cache").apply { mkdirs() }
                            val localCopy = File(cacheDir, "local_${vUri.hashCode()}.srt")
                            localCopy.writeBytes(bytes)
                            val label = uri.lastPathSegment?.substringAfterLast('/') ?: "Local Subtitle"
                            PlaybackStore.saveVideoSubtitleCachePath(this@PlayerActivity, vUri, localCopy.absolutePath)
                            PlaybackStore.saveVideoSubtitleUri(this@PlayerActivity, vUri, localCopy.absolutePath, label)
                            currentSubtitleSourceFile = localCopy
                            currentSubtitleSourceLang = "auto"
                            currentSubtitleDisplayLabel = label
                        }
                    }
                    applyLoadedSubtitleCues(cues)
                    showToastIndicator(getString(R.string.sub_loaded_count_format, cues.size))
                } else {
                    showToastIndicator(getString(R.string.sub_err_corrupted))
                }
            } else {
                mediaPlayer.addSlave(0, uri, true)
            }
        }
    }

    private fun applyLoadedSubtitleCues(cues: List<SubtitleCue>, isTranslated: Boolean = false) {
        externalSubtitleCues = cues
        if (!isTranslated) {
            originalSubtitleCues = cues
            lastTranslatedTexts = emptyList()
        }
        mediaPlayer.spuTrack = -1
        restoreSubtitlePositionFromPercentage()
        updateDraggableSubtitleText(if (::mediaPlayer.isInitialized) mediaPlayer.time else 0L)
    }

    private fun updateDraggableSubtitleText(currentTimeMs: Long) {
        if (externalSubtitleCues.isEmpty()) {
            if (binding.tvDraggableSubtitle.visibility != View.GONE || binding.tvDraggableSubtitle.text.isNotEmpty()) {
                binding.tvDraggableSubtitle.text = ""
                binding.tvDraggableSubtitle.visibility = View.GONE
            }
            return
        }
        if (isDraggingSubtitle || isPinchingSubtitle) return

        val activeCue = SubtitleAutoTranslator.resolveActiveCueAt(
            cues = externalSubtitleCues,
            playbackTimeMs = currentTimeMs,
            syncOffsetMs = subtitleSyncOffsetMs
        )
        if (activeCue != null) {
            val rawText = activeCue.text
            val containsRtl = rawText.any { it in '\u0600'..'\u06FF' || it in '\u0590'..'\u05FF' }
            binding.tvDraggableSubtitle.textDirection = if (containsRtl) {
                View.TEXT_DIRECTION_RTL
            } else {
                View.TEXT_DIRECTION_LTR
            }
            val formatted = if (containsRtl) {
                BidiFormatter.getInstance(true).unicodeWrap(rawText)
            } else {
                rawText
            }
            if (binding.tvDraggableSubtitle.text.toString() != formatted) {
                binding.tvDraggableSubtitle.text = formatted
            }
            if (binding.tvDraggableSubtitle.visibility != View.VISIBLE) {
                binding.tvDraggableSubtitle.visibility = View.VISIBLE
            }
        } else {
            if (binding.tvDraggableSubtitle.visibility != View.GONE || binding.tvDraggableSubtitle.text.isNotEmpty()) {
                binding.tvDraggableSubtitle.text = ""
                binding.tvDraggableSubtitle.visibility = View.GONE
            }
        }
    }

    private fun setupFluidDraggableAndPinchSubtitle() {
        subtitleScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isPinchingSubtitle = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val currentSp = PlaybackStore.getSubSizeSp(this@PlayerActivity)
                    val newSp = (currentSp * detector.scaleFactor).coerceIn(12f, 46f)
                    PlaybackStore.setSubSizeSp(this@PlayerActivity, newSp)
                    binding.tvDraggableSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, newSp)
                    showToastIndicator("${newSp.toInt()}sp")
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isPinchingSubtitle = false
                }
            }
        )

        val touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceAtLeast(12f)
        val hitPadPx = 12f * resources.displayMetrics.density
        var subDownTimeMs = 0L
        var subTouchTracking = false
        var subDidScale = false

        fun isPointOnRenderedSubtitleText(tv: TextView, x: Float, y: Float): Boolean {
            if (tv.visibility != View.VISIBLE || tv.text.isNullOrBlank()) return false
            val layout = tv.layout ?: return false
            if (layout.lineCount <= 0) return false
            var minLeft = Float.MAX_VALUE
            var maxRight = 0f
            for (line in 0 until layout.lineCount) {
                minLeft = minOf(minLeft, layout.getLineLeft(line))
                maxRight = maxOf(maxRight, layout.getLineRight(line))
            }
            val top = tv.totalPaddingTop + layout.getLineTop(0).toFloat()
            val bottom = tv.totalPaddingTop + layout.getLineBottom(layout.lineCount - 1).toFloat()
            val left = tv.totalPaddingLeft + minLeft
            val right = tv.totalPaddingLeft + maxRight
            return x in (left - hitPadPx)..(right + hitPadPx) && y in (top - hitPadPx)..(bottom + hitPadPx)
        }

        binding.tvDraggableSubtitle.setOnTouchListener { view, event ->
            val tv = view as? TextView ?: return@setOnTouchListener false
            if (isLocked || PlaybackStore.isSubtitlePositionLocked(this)) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Only intercept touches that actually land ON the rendered subtitle text bounds (+12dp pad)
                    if (!isPointOnRenderedSubtitleText(tv, event.x, event.y)) {
                        subTouchTracking = false
                        isDraggingSubtitle = false
                        return@setOnTouchListener false
                    }
                    subTouchTracking = true
                    isDraggingSubtitle = false
                    subDidScale = false
                    subDownTimeMs = event.eventTime
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartTransX = view.translationX
                    dragStartTransY = view.translationY
                    subtitleScaleDetector.onTouchEvent(event)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!subTouchTracking) return@setOnTouchListener false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    subDidScale = true
                    subtitleScaleDetector.onTouchEvent(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!subTouchTracking) return@setOnTouchListener false
                    if (event.pointerCount >= 2) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        subDidScale = true
                        subtitleScaleDetector.onTouchEvent(event)
                        return@setOnTouchListener true
                    }
                    val deltaX = event.rawX - dragStartRawX
                    val deltaY = event.rawY - dragStartRawY
                    val movedDist = kotlin.math.hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()
                    val heldMs = event.eventTime - subDownTimeMs

                    if (!isDraggingSubtitle && (movedDist >= touchSlopPx || heldMs >= 300L)) {
                        isDraggingSubtitle = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        view.background = GradientDrawable().apply {
                            cornerRadius = 18f
                            setColor(Color.parseColor("#CC0284C7"))
                        }
                    }

                    if (!subtitleScaleDetector.isInProgress && isDraggingSubtitle && event.pointerCount == 1) {
                        val parentW = binding.playerRoot.width.toFloat().coerceAtLeast(1f)
                        val parentH = binding.playerRoot.height.toFloat().coerceAtLeast(1f)

                        val halfRemainingW = ((parentW - view.width) / 2f).coerceAtLeast(0f)
                        val minTransX = -halfRemainingW + 8f
                        val maxTransX = halfRemainingW - 8f

                        val topBound = -(view.top.toFloat() - 16f).coerceAtLeast(0f)
                        val bottomBound = (parentH - view.bottom.toFloat() - 16f).coerceAtLeast(0f)

                        val clampedX = (dragStartTransX + deltaX).coerceIn(
                            minOf(minTransX, maxTransX),
                            maxOf(minTransX, maxTransX)
                        )
                        val clampedY = (dragStartTransY + deltaY).coerceIn(
                            minOf(topBound, bottomBound),
                            maxOf(topBound, bottomBound)
                        )

                        view.translationX = clampedX
                        view.translationY = clampedY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!subTouchTracking) return@setOnTouchListener false
                    subTouchTracking = false
                    subtitleScaleDetector.onTouchEvent(event)
                    val elapsedMs = event.eventTime - subDownTimeMs
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    val movedDist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    if (isDraggingSubtitle) {
                        isDraggingSubtitle = false
                        val parentW = binding.playerRoot.width.toFloat().coerceAtLeast(1f)
                        val parentH = binding.playerRoot.height.toFloat().coerceAtLeast(1f)
                        val pctX = (view.translationX / parentW).coerceIn(-0.48f, 0.48f)
                        val pctY = (view.translationY / parentH).coerceIn(-0.85f, 0.08f)
                        PlaybackStore.saveSubPositionPercent(this, pctX, pctY)
                        applyThemeAndSubtitleStyle()
                        showToastIndicator(
                            getString(
                                R.string.sub_drag_saved_toast,
                                ((pctX + 0.5f) * 100).toInt(),
                                ((1f + pctY) * 100).toInt()
                            )
                        )
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && !subDidScale && elapsedMs < 220L && movedDist < touchSlopPx) {
                        // Quick tap on subtitle text -> toggle Play/Pause & show controls!
                        if (::mediaPlayer.isInitialized) {
                            if (mediaPlayer.isPlaying) {
                                mediaPlayer.pause()
                                binding.btnPlayPause.setImageResource(R.drawable.ic_play_modern)
                                showToastIndicator("⏸")
                            } else {
                                mediaPlayer.play()
                                binding.btnPlayPause.setImageResource(R.drawable.ic_pause_modern)
                                showToastIndicator("▶")
                            }
                        }
                        showControls()
                        scheduleHideControls()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun restoreSubtitlePositionFromPercentage() {
        val parentW = binding.playerRoot.width.toFloat()
        val parentH = binding.playerRoot.height.toFloat()
        if (parentW <= 0f || parentH <= 0f) return

        val pctX = PlaybackStore.getSubPositionPercentX(this)
        val pctY = PlaybackStore.getSubPositionPercentY(this)

        val view = binding.tvDraggableSubtitle
        val halfRemainingW = ((parentW - view.width) / 2f).coerceAtLeast(0f)
        val targetX = (pctX * parentW).coerceIn(-halfRemainingW, halfRemainingW)
        val targetY = (pctY * parentH).coerceIn(-(parentH * 0.85f), parentH * 0.08f)

        view.translationX = targetX
        view.translationY = targetY
    }

    private fun resetSubtitlePositionNow() {
        PlaybackStore.resetSubPosition(this)
        binding.tvDraggableSubtitle.translationX = 0f
        binding.tvDraggableSubtitle.translationY = 0f
        showToastIndicator(getString(R.string.sub_position_reset_toast))
    }

    private fun attachSpringPressAnimation(vararg views: View) {
        for (v in views) {
            v.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(180L)
                            .setInterpolator(springOvershoot).start()
                    }
                }
                false
            }
        }
    }

    private fun applyThemeAndSubtitleStyle() {
        val accent = PlaybackStore.getAccentColor(this)
        binding.btnPlayPause.imageTintList = ColorStateList.valueOf(accent)
        binding.btnUnlock.imageTintList = ColorStateList.valueOf(accent)
        binding.btnDecoder.setTextColor(accent)
        binding.btnOrientation.setTextColor(accent)
        binding.btnSpeed.setTextColor(accent)
        binding.btnSubFontUp.setTextColor(accent)
        binding.btnResetSubPos.setTextColor(accent)

        binding.seekBar.progressTintList = ColorStateList.valueOf(accent)
        binding.seekBar.thumbTintList = ColorStateList.valueOf(accent)

        // Style Top & Bottom Quick Pills as sleek glass capsules
        val pillViews = listOf(
            binding.btnDecoder,
            binding.btnOrientation,
            binding.btnSpeed
        )
        for (pill in pillViews) {
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#24FFFFFF"))
                setStroke(dp(1), Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        // Style Play/Pause as a glowing circular flagship button
        binding.btnPlayPause.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(42, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(dp(2), Color.argb(145, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }

        // Style Unlock button and Center Gesture Indicator as rounded glass pills
        binding.btnUnlock.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D9090D16"))
            setStroke(dp(1), accent)
        }

        binding.tvGestureIndicator.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(Color.parseColor("#E6090D16"))
            setStroke(dp(1), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        binding.tvGestureIndicator.setTextColor(accent)

        val subSizeSp = PlaybackStore.getSubSizeSp(this)
        val subColor = PlaybackStore.getSubColorValue(this)
        val subBgColor = PlaybackStore.getSubBackgroundColor(this)
        val subOutlinePx = PlaybackStore.getSubOutlinePx(this)
        val subTypeface = PlaybackStore.getSubTypeface(this)
        val textAlpha = ((PlaybackStore.getSubTextOpacityPct(this) / 100.0) * 255).toInt().coerceIn(50, 255)

        binding.tvDraggableSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, subSizeSp)
        binding.tvDraggableSubtitle.setTextColor(subColor)
        binding.tvDraggableSubtitle.typeface = subTypeface

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f
            setColor(subBgColor)
        }
        binding.tvDraggableSubtitle.background = bgDrawable

        val shadowAlpha = if (subOutlinePx <= 0.5f) 0 else textAlpha
        binding.tvDraggableSubtitle.setShadowLayer(
            subOutlinePx,
            if (subOutlinePx > 0f) 2f else 0f,
            if (subOutlinePx > 0f) 2f else 0f,
            Color.argb(shadowAlpha, 0, 0, 0)
        )
        restoreSubtitlePositionFromPercentage()
    }

    private fun saveCurrentPositionNow() {
        if (!::mediaPlayer.isInitialized) return
        val uri = currentVideoUri ?: return
        val time = mediaPlayer.time
        val length = mediaPlayer.length
        if (time > 0L) {
            PlaybackStore.saveProgress(this, uri, currentVideoTitle, time, length)
        }
    }

    private fun adjustSubtitleSyncBy(deltaMs: Long) {
        subtitleSyncOffsetMs += deltaMs
        currentVideoUri?.let { uri ->
            PlaybackStore.saveVideoSubtitleSyncMs(this, uri, subtitleSyncOffsetMs)
        }
        updateDraggableSubtitleText(mediaPlayer.time)
        showToastIndicator(getString(R.string.sub_sync_offset_format, subtitleSyncOffsetMs))
    }

    private fun hasActiveVideoPlaylist(): Boolean {
        return !isStreamOrIptv && playlistUris.size > 1 && currentPlaylistIndex in playlistUris.indices
    }

    private fun updatePrevNextButtonsState() {
        val showArrows = hasActiveVideoPlaylist()
        binding.btnPrevVideo.visibility = if (showArrows) View.VISIBLE else View.GONE
        binding.btnNextVideo.visibility = if (showArrows) View.VISIBLE else View.GONE
        if (!showArrows) return

        val canPrev = HiddenMediaAndPlaylistHelper.canGoPrevious(currentPlaylistIndex, playlistUris.size)
        val canNext = HiddenMediaAndPlaylistHelper.canGoNext(currentPlaylistIndex, playlistUris.size)
        binding.btnPrevVideo.isEnabled = canPrev
        binding.btnPrevVideo.alpha = if (canPrev) 1.0f else 0.32f
        binding.btnNextVideo.isEnabled = canNext
        binding.btnNextVideo.alpha = if (canNext) 1.0f else 0.32f
    }

    private fun switchToAdjacentVideo(delta: Int) {
        if (!hasActiveVideoPlaylist()) return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (!HiddenMediaAndPlaylistHelper.shouldAcceptSwitch(nowMs, lastVideoSwitchTimeMs)) return

        val targetIdx = if (delta < 0) {
            HiddenMediaAndPlaylistHelper.computePreviousIndex(currentPlaylistIndex, playlistUris.size)
        } else {
            HiddenMediaAndPlaylistHelper.computeNextIndex(currentPlaylistIndex, playlistUris.size)
        } ?: return

        lastVideoSwitchTimeMs = nowMs

        // Cancel pending gestures & async subtitle translation for previous item
        cancelActivePlayerGesturesSafely()
        cancelActiveSubtitleTranslation()
        saveCurrentPositionNow()

        currentPlaylistIndex = targetIdx
        val nextUri = Uri.parse(playlistUris[targetIdx])
        val nextTitle = playlistTitles.getOrElse(targetIdx) { "Video" }
        currentVideoUri = nextUri
        currentVideoTitle = nextTitle
        PlaybackStore.markVideoOpened(this, nextUri)

        // Restore saved resume position for the new video (if any)
        val savedPos = PlaybackStore.getSavedPosition(this, nextUri)
        pendingStartPositionMs = if (savedPos > 3_000L) savedPos else 0L
        hasAppliedInitialSeek = false

        // Clear old subtitles immediately on UI thread & cancel any previous in-flight video switch
        val switchGen = videoSwitchGeneration.incrementAndGet()
        activeVideoLoadJob?.cancel()
        subtitleSyncOffsetMs = PlaybackStore.getVideoSubtitleSyncMs(this, nextUri)
        val cachedSubPath = PlaybackStore.getVideoSubtitleCachePath(this, nextUri)
        val savedSubMeta = PlaybackStore.getVideoSubtitleUri(this, nextUri)
        externalSubtitleCues = emptyList()
        originalSubtitleCues = emptyList()
        lastTranslatedTexts = emptyList()
        currentSubtitleSourceFile = null
        currentSubtitleDisplayLabel = ""
        binding.tvDraggableSubtitle.text = ""
        binding.tvDraggableSubtitle.visibility = View.GONE

        // Reset per-video manual orientation override so next/prev video returns to Auto mode
        manualOrientationOverrideForCurrentVideo = false
        initialHintWidth = 0
        initialHintHeight = 0
        applyEarlyOrientationForCurrentVideo()

        binding.tvVideoTitle.text = nextTitle
        binding.seekBar.progress = 0
        binding.tvCurrentTime.text = formatTime(pendingStartPositionMs)
        updatePrevNextButtonsState()
        showToastIndicator((if (delta < 0) "⏮ " else "⏭ ") + nextTitle)

        // Move blocking LibVLC stop + subtitle disk I/O off the Main Thread
        activeVideoLoadJob = lifecycleScope.launch {
            val loadedSubPair = withContext(Dispatchers.IO) {
                if (::mediaPlayer.isInitialized) {
                    runCatching { mediaPlayer.stop() }
                }
                if (!cachedSubPath.isNullOrEmpty()) {
                    val f = File(cachedSubPath)
                    if (f.exists()) Pair(f, SubtitleParser.parseFromFile(f)) else null
                } else null
            }
            if (switchGen != videoSwitchGeneration.get() || isFinishing || isDestroyed) return@launch

            if (loadedSubPair != null && loadedSubPair.second.isNotEmpty()) {
                currentSubtitleSourceFile = loadedSubPair.first
                currentSubtitleDisplayLabel = savedSubMeta?.second.orEmpty()
                externalSubtitleCues = loadedSubPair.second
                originalSubtitleCues = loadedSubPair.second
            }

            startVideoPlaybackSafely(nextUri)
            if (::mediaPlayer.isInitialized && currentSpeed != 1.0f) {
                runCatching { mediaPlayer.rate = currentSpeed }
            }
            updateMediaSessionPlaybackState(isPlaying = true)
            updateAutoPipParams(isPlaying = true)
        }
    }

    private fun setupControls() {
        attachSpringPressAnimation(
            binding.btnClosePlayer,
            binding.btnSubSyncMinus,
            binding.btnSubSyncPlus,
            binding.btnSubFontDown,
            binding.btnSubFontUp,
            binding.btnResetSubPos,
            binding.btnDecoder,
            binding.btnOrientation,
            binding.btnCastScreen,
            binding.btnPip,
            binding.btnMorePro,
            binding.btnLock,
            binding.btnUnlock,
            binding.btnSubtitles,
            binding.btnAudioTrack,
            binding.btnPrevVideo,
            binding.btnRewind,
            binding.btnPlayPause,
            binding.btnForward,
            binding.btnNextVideo,
            binding.btnSpeed,
            binding.btnAspectRatio
        )

        binding.btnClosePlayer.setOnClickListener {
            saveCurrentPositionNow()
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            if (mediaPlayer.isPlaying) {
                saveCurrentPositionNow()
                mediaPlayer.pause()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.btnPlayPause.setImageResource(R.drawable.ic_play_modern)
            } else {
                mediaPlayer.play()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause_modern)
            }
            scheduleHideControls()
        }

        binding.btnSubFontUp.setOnClickListener {
            val newSize = (PlaybackStore.getSubSizeSp(this) + 2f).coerceAtMost(46f)
            PlaybackStore.setSubSizeSp(this, newSize)
            applyThemeAndSubtitleStyle()
            binding.tvDraggableSubtitle.visibility = View.VISIBLE
            if (binding.tvDraggableSubtitle.text.isNullOrEmpty()) {
                binding.tvDraggableSubtitle.text = getString(R.string.style_preview_sample)
            }
            showToastIndicator("${newSize.toInt()}sp")
            scheduleHideControls()
        }

        binding.btnSubFontDown.setOnClickListener {
            val newSize = (PlaybackStore.getSubSizeSp(this) - 2f).coerceAtLeast(12f)
            PlaybackStore.setSubSizeSp(this, newSize)
            applyThemeAndSubtitleStyle()
            binding.tvDraggableSubtitle.visibility = View.VISIBLE
            if (binding.tvDraggableSubtitle.text.isNullOrEmpty()) {
                binding.tvDraggableSubtitle.text = getString(R.string.style_preview_sample)
            }
            showToastIndicator("${newSize.toInt()}sp")
            scheduleHideControls()
        }

        binding.btnSubSyncMinus.setOnClickListener {
            adjustSubtitleSyncBy(-100L)
            scheduleHideControls()
        }

        binding.btnSubSyncPlus.setOnClickListener {
            adjustSubtitleSyncBy(100L)
            scheduleHideControls()
        }

        binding.btnResetSubPos.setOnClickListener {
            resetSubtitlePositionNow()
            scheduleHideControls()
        }

        binding.btnPrevVideo.setOnClickListener {
            switchToAdjacentVideo(-1)
            scheduleHideControls()
        }

        binding.btnNextVideo.setOnClickListener {
            switchToAdjacentVideo(+1)
            scheduleHideControls()
        }

        updatePrevNextButtonsState()

        binding.btnRewind.setOnClickListener {
            val newTime = (mediaPlayer.time - 15_000L).coerceAtLeast(0L)
            mediaPlayer.time = newTime
            saveCurrentPositionNow()
            showToastIndicator(getString(R.string.toast_rewind_15))
            scheduleHideControls()
        }

        binding.btnForward.setOnClickListener {
            val length = mediaPlayer.length
            val newTime = (mediaPlayer.time + 15_000L).coerceAtMost(if (length > 0) length else Long.MAX_VALUE)
            mediaPlayer.time = newTime
            saveCurrentPositionNow()
            showToastIndicator(getString(R.string.toast_forward_15))
            scheduleHideControls()
        }

        binding.btnLock.setOnClickListener {
            isLocked = true
            binding.controlsContainer.visibility = View.GONE
            binding.btnUnlock.visibility = View.VISIBLE
            showToastIndicator(getString(R.string.toast_locked))
        }

        binding.btnUnlock.setOnClickListener {
            isLocked = false
            binding.btnUnlock.visibility = View.GONE
            showControls()
            showToastIndicator(getString(R.string.toast_unlocked))
        }

        binding.btnDecoder.setOnClickListener {
            playerMenuBackStack.clear()
            showDecoderModeSelectorDialog()
        }

        binding.btnOrientation.setOnClickListener {
            val isCurrentlyLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val nextTarget = if (isCurrentlyLand) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            if (playerOrientationMode == PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO) {
                // Respect manual override for THIS video only; next video returns to Auto!
                manualOrientationOverrideForCurrentVideo = true
                currentManualRequestedOrientation = nextTarget
            }
            applyRequestedOrientationIfDifferent(nextTarget)
            val label = if (nextTarget == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
                getString(R.string.orientation_portrait)
            } else {
                getString(R.string.orientation_landscape)
            }
            binding.btnOrientation.text = getOrientationLabel(playerOrientationMode)
            showToastIndicator(label)
            scheduleHideControls()
        }

        binding.btnOrientation.setOnLongClickListener {
            showPlayerOrientationModeDialog()
            true
        }

        binding.btnSleepTimer.setOnClickListener {
            showEnhancedSleepTimerDialog()
        }

        binding.tvSleepTimerBadge.setOnClickListener {
            showEnhancedSleepTimerDialog()
        }

        binding.btnAudioOnly.setOnClickListener {
            switchToBackgroundAudioOnlyMode()
        }

        binding.btnCastScreen.setOnClickListener {
            playerMenuBackStack.clear()
            showCastScreenDialog()
        }

        binding.btnPip.setOnClickListener {
            enterPipModeNow()
        }

        binding.btnAspectRatio.setOnClickListener {
            currentScaleIndex = (currentScaleIndex + 1) % aspectModes.size
            changeSurfaceLayout()
            showToastIndicator(getScaleLabel(currentScaleIndex))
            scheduleHideControls()
        }

        binding.btnSpeed.setOnClickListener {
            playerMenuBackStack.clear()
            showSpeedDialog()
        }

        binding.btnSubtitles.setOnClickListener {
            playerMenuBackStack.clear()
            showSubtitlesDialog()
        }

        binding.btnAudioTrack.setOnClickListener {
            playerMenuBackStack.clear()
            showAudioTracksDialog()
        }

        binding.btnMorePro.setOnClickListener {
            playerMenuBackStack.clear()
            showMoreProOptionsDialog()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer.length > 0) {
                    val targetTime = (progress / 1000.0 * mediaPlayer.length).toLong()
                    binding.tvCurrentTime.text = formatTime(targetTime)
                    updateDraggableSubtitleText(targetTime)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                if (mediaPlayer.length > 0) {
                    mediaPlayer.time = (progress / 1000.0 * mediaPlayer.length).toLong()
                    saveCurrentPositionNow()
                }
                isUserSeeking = false
                scheduleHideControls()
            }
        })
    }

    private fun cancelActivePlayerGesturesSafely() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        singleTapRunnable?.let { handler.removeCallbacks(it) }
        singleTapRunnable = null
        seekGestureActive = false
        if (::gestureStateMachine.isInitialized) {
            gestureStateMachine.cancelAll(notifyMultiTouch = false)
        }
    }

    private fun isPointInsideViewInRoot(view: View, rootX: Float, rootY: Float): Boolean {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
        val rootLoc = IntArray(2)
        val viewLoc = IntArray(2)
        binding.playerRoot.getLocationOnScreen(rootLoc)
        view.getLocationOnScreen(viewLoc)
        val left = (viewLoc[0] - rootLoc[0]).toFloat()
        val top = (viewLoc[1] - rootLoc[1]).toFloat()
        return rootX >= left && rootX <= left + view.width && rootY >= top && rootY <= top + view.height
    }

    private fun isTouchOnVisibleControls(x: Float, y: Float): Boolean {
        if (binding.btnUnlock.visibility == View.VISIBLE && isPointInsideViewInRoot(binding.btnUnlock, x, y)) {
            return true
        }
        if (binding.controlsContainer.visibility == View.VISIBLE) {
            if (isPointInsideViewInRoot(binding.playerTopBar, x, y)) return true
            if (isPointInsideViewInRoot(binding.playerBottomBar, x, y)) return true
        }
        return false
    }

    private fun setupTouchGestures() {
        val density = resources.displayMetrics.density
        val touchSlopPx = android.view.ViewConfiguration.get(this).scaledTouchSlop
            .toFloat()
            .coerceAtLeast(PlayerGestureConstants.DEFAULT_TOUCH_SLOP_DP * density)
        val edgeExclusionPx = PlayerGestureConstants.EDGE_EXCLUSION_DP * density

        gestureStateMachine = PlayerGestureStateMachine(
            touchSlopPx = touchSlopPx,
            edgeExclusionPx = edgeExclusionPx,
            callbacks = object : PlayerGestureStateMachine.Callbacks {
                override fun scheduleLongPress(delayMs: Long, token: Long) {
                    cancelLongPress()
                    val r = Runnable { gestureStateMachine.onLongPressTimeout(token) }
                    longPressRunnable = r
                    handler.postDelayed(r, delayMs)
                }

                override fun cancelLongPress() {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                }

                override fun scheduleSingleTap(delayMs: Long, token: Long) {
                    cancelSingleTap()
                    val r = Runnable { gestureStateMachine.onSingleTapTimeout(token) }
                    singleTapRunnable = r
                    handler.postDelayed(r, delayMs)
                }

                override fun cancelSingleTap() {
                    singleTapRunnable?.let { handler.removeCallbacks(it) }
                    singleTapRunnable = null
                }

                override fun getCurrentPlaybackSpeed(): Float {
                    return if (::mediaPlayer.isInitialized && mediaPlayer.rate > 0f) {
                        currentSpeed
                    } else {
                        currentSpeed
                    }
                }

                override fun onLongPressSpeedStart(targetSpeed: Float, savedOriginalSpeed: Float) {
                    if (isLocked || isDraggingSubtitle || isPinchingSubtitle || !::mediaPlayer.isInitialized) {
                        gestureStateMachine.cancelAll()
                        return
                    }
                    if (!mediaPlayer.isPlaying) {
                        gestureStateMachine.cancelAll()
                        return
                    }
                    mediaPlayer.rate = targetSpeed
                    showToastIndicator(getString(R.string.toast_speed_hold))
                }

                override fun onLongPressSpeedEnd(restoredSpeed: Float) {
                    currentSpeed = restoredSpeed
                    if (::mediaPlayer.isInitialized) {
                        runCatching { mediaPlayer.rate = restoredSpeed }
                    }
                    showToastIndicator(getString(R.string.toast_speed_normal, restoredSpeed.toString()))
                }

                override fun onBrightnessDelta(deltaFraction: Float) {
                    if (isLocked || isDraggingSubtitle || isPinchingSubtitle) return
                    adjustBrightnessSmooth(deltaFraction)
                }

                override fun onVolumeDelta(deltaFraction: Float) {
                    if (isLocked || isDraggingSubtitle || isPinchingSubtitle) return
                    adjustVolumeSmooth(deltaFraction)
                }

                override fun onSeekPreview(deltaMs: Long) {
                    if (isLocked || isDraggingSubtitle || isPinchingSubtitle || !::mediaPlayer.isInitialized) return
                    val length = mediaPlayer.length
                    if (length <= 0L) return
                    if (!seekGestureActive) {
                        seekGestureActive = true
                        seekGestureStartTimeMs = mediaPlayer.time.coerceIn(0L, length)
                    }
                    val targetMs = (seekGestureStartTimeMs + deltaMs).coerceIn(0L, length)
                    val actualDeltaMs = targetMs - seekGestureStartTimeMs
                    val sign = if (actualDeltaMs >= 0L) "+" else "-"
                    val arrow = if (actualDeltaMs >= 0L) "⏩" else "⏪"
                    showToastIndicator("$arrow ${formatTime(targetMs)} ($sign${formatTime(abs(actualDeltaMs))})")
                }

                override fun onSeekCommit(deltaMs: Long) {
                    if (!seekGestureActive || !::mediaPlayer.isInitialized) {
                        seekGestureActive = false
                        return
                    }
                    seekGestureActive = false
                    val length = mediaPlayer.length
                    if (length > 0L) {
                        val targetMs = (seekGestureStartTimeMs + deltaMs).coerceIn(0L, length)
                        mediaPlayer.time = targetMs
                        saveCurrentPositionNow()
                        updateDraggableSubtitleText(targetMs)
                        showToastIndicator("⏱️ ${formatTime(targetMs)} / ${formatTime(length)}")
                    }
                }

                override fun onSeekCancel() {
                    seekGestureActive = false
                }

                override fun onDoubleTap(zone: TapZone) {
                    if (isLocked || !::mediaPlayer.isInitialized) return
                    val length = mediaPlayer.length
                    when (zone) {
                        TapZone.RIGHT -> {
                            val newTime = (mediaPlayer.time + PlayerGestureConstants.DOUBLE_TAP_SEEK_MS)
                                .coerceAtMost(if (length > 0L) length else Long.MAX_VALUE)
                            mediaPlayer.time = newTime
                            saveCurrentPositionNow()
                            updateDraggableSubtitleText(newTime)
                            showToastIndicator("⏩ +10s • ${formatTime(newTime)}")
                        }
                        TapZone.LEFT -> {
                            val newTime = (mediaPlayer.time - PlayerGestureConstants.DOUBLE_TAP_SEEK_MS)
                                .coerceAtLeast(0L)
                            mediaPlayer.time = newTime
                            saveCurrentPositionNow()
                            updateDraggableSubtitleText(newTime)
                            showToastIndicator("⏪ -10s • ${formatTime(newTime)}")
                        }
                        TapZone.CENTER -> {
                            binding.btnPlayPause.performClick()
                        }
                    }
                }

                override fun onSingleTapConfirmed() {
                    if (isLocked) {
                        binding.btnUnlock.visibility =
                            if (binding.btnUnlock.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        return
                    }
                    if (isControlsVisible) {
                        hideControls()
                    } else {
                        showControls()
                    }
                }

                override fun onMultiTouchCancel() {
                    seekGestureActive = false
                }
            }
        )

        binding.playerRoot.setOnTouchListener { _, event ->
            if (isDraggingSubtitle || isPinchingSubtitle) {
                cancelActivePlayerGesturesSafely()
                return@setOnTouchListener false
            }

            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    gestureVolumeAccumulator = if (maxVol > 0) curVol.toFloat() / maxVol.toFloat() else 0.5f
                    lastVolumeEdgeHaptic = -1
                    lastBrightnessEdgeHaptic = -1
                    PointerAction.DOWN
                }
                MotionEvent.ACTION_POINTER_DOWN -> PointerAction.POINTER_DOWN
                MotionEvent.ACTION_MOVE -> PointerAction.MOVE
                MotionEvent.ACTION_UP -> PointerAction.UP
                MotionEvent.ACTION_CANCEL -> PointerAction.CANCEL
                else -> return@setOnTouchListener false
            }

            val onControls = (action == PointerAction.DOWN) && isTouchOnVisibleControls(event.x, event.y)
            val input = PointerInput(
                action = action,
                x = event.x,
                y = event.y,
                timeMs = event.eventTime,
                pointerCount = event.pointerCount,
                viewWidth = binding.playerRoot.width.toFloat().coerceAtLeast(1f),
                viewHeight = binding.playerRoot.height.toFloat().coerceAtLeast(1f),
                isOnControls = onControls
            )
            gestureStateMachine.onPointerEvent(input)
        }
    }

    private fun adjustVolumeSmooth(deltaFraction: Float) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return
        gestureVolumeAccumulator = (gestureVolumeAccumulator + deltaFraction * 1.15f).coerceIn(0f, 1f)
        val targetVol = (gestureVolumeAccumulator * maxVol).toInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        val percent = (gestureVolumeAccumulator * 100f).toInt().coerceIn(0, 100)
        if ((percent == 0 || percent == 100) && lastVolumeEdgeHaptic != percent) {
            lastVolumeEdgeHaptic = percent
            binding.playerRoot.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else if (percent in 1..99) {
            lastVolumeEdgeHaptic = -1
        }
        showVerticalSliderIndicator(isVolume = true, percent = percent)
    }

    private fun adjustBrightnessSmooth(deltaFraction: Float) {
        val layoutParams = window.attributes
        var brightness = layoutParams.screenBrightness
        if (brightness < 0f) brightness = 0.5f
        brightness = (brightness + deltaFraction * 1.15f).coerceIn(0.02f, 1.0f)
        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams
        val percent = (((brightness - 0.02f) / 0.98f) * 100f).toInt().coerceIn(0, 100)
        if ((percent == 0 || percent == 100) && lastBrightnessEdgeHaptic != percent) {
            lastBrightnessEdgeHaptic = percent
            binding.playerRoot.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else if (percent in 1..99) {
            lastBrightnessEdgeHaptic = -1
        }
        showVerticalSliderIndicator(isVolume = false, percent = percent)
    }

    private fun executeDownloadedSubtitleDelete(
        entry: SubtitleAutoTranslator.DownloadedSubtitleEntry,
        offerUndo: Boolean
    ) {
        val deletedPath = entry.file.absolutePath
        val wasActive = (currentSubtitleSourceFile?.absolutePath == deletedPath) ||
            (currentVideoUri?.let { PlaybackStore.getVideoSubtitleCachePath(this, it) == deletedPath } == true)
        val backupBytes = if (offerUndo) runCatching { entry.file.readBytes() }.getOrNull() else null
        val prevLabel = currentSubtitleDisplayLabel
        val prevCues = externalSubtitleCues
        val prevOrigCues = originalSubtitleCues
        val prevTranslatedTexts = lastTranslatedTexts

        SubtitleAutoTranslator.deleteDownloadedSubtitleAndRelatedCaches(filesDir, entry.file)
        currentVideoUri?.let { vUri ->
            PlaybackStore.clearVideoSubtitleIfMatches(this, vUri, deletedPath)
        }

        if (wasActive) {
            cancelActiveSubtitleTranslation()
            externalSubtitleCues = emptyList()
            originalSubtitleCues = emptyList()
            lastTranslatedTexts = emptyList()
            currentSubtitleSourceFile = null
            currentSubtitleDisplayLabel = ""
            binding.tvDraggableSubtitle.text = ""
            binding.tvDraggableSubtitle.visibility = View.GONE
            if (::mediaPlayer.isInitialized) {
                mediaPlayer.spuTrack = -1
            }
        }

        if (offerUndo && backupBytes != null && backupBytes.isNotEmpty()) {
            com.google.android.material.snackbar.Snackbar
                .make(binding.playerRoot, getString(R.string.sub_removed_snackbar), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.action_undo)) {
                    runCatching {
                        entry.file.parentFile?.mkdirs()
                        entry.file.writeBytes(backupBytes)
                        if (wasActive && prevCues.isNotEmpty()) {
                            currentSubtitleSourceFile = entry.file
                            currentSubtitleDisplayLabel = prevLabel
                            externalSubtitleCues = prevCues
                            originalSubtitleCues = prevOrigCues
                            lastTranslatedTexts = prevTranslatedTexts
                            currentVideoUri?.let { vUri ->
                                PlaybackStore.saveVideoSubtitleCachePath(this, vUri, deletedPath)
                                PlaybackStore.saveVideoSubtitleUri(this, vUri, deletedPath, prevLabel)
                            }
                            updateDraggableSubtitleText(if (::mediaPlayer.isInitialized) mediaPlayer.time else 0L)
                        }
                        showToastIndicator(getString(R.string.sub_restored_toast))
                    }
                }
                .show()
        } else {
            showToastIndicator(getString(R.string.sub_deleted_toast))
        }
        showSubtitlesDialog()
    }

    private fun confirmAndDeleteDownloadedSubtitle(entry: SubtitleAutoTranslator.DownloadedSubtitleEntry) {
        if (!PlaybackStore.isConfirmBeforeDeletingSubtitles(this)) {
            executeDownloadedSubtitleDelete(entry, offerUndo = true)
            return
        }

        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }
        val tvTitle = TextView(this).apply {
            text = "🗑️  ${getString(R.string.sub_delete_confirm_title)}"
            setTextColor(primaryText)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        val tvMsg = TextView(this).apply {
            text = getString(R.string.sub_delete_confirm_msg, entry.displayName)
            setTextColor(secondaryText)
            textSize = 14f
            setPadding(0, 0, 0, dp(10))
        }
        val cbDontAsk = android.widget.CheckBox(this).apply {
            text = getString(R.string.sub_delete_dont_ask_again)
            setTextColor(primaryText)
            textSize = 13.5f
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(dp(4), dp(6), dp(4), dp(12))
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val dlg = AlertDialog.Builder(this).setView(container).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(secondaryText)
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                dlg.dismiss()
                showSubtitlesDialog()
            }
        }
        val btnDelete = TextView(this).apply {
            text = getString(R.string.delete_action)
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E11D48"))
            }
            setOnClickListener {
                if (cbDontAsk.isChecked) {
                    PlaybackStore.setConfirmBeforeDeletingSubtitles(this@PlayerActivity, false)
                }
                dlg.dismiss()
                executeDownloadedSubtitleDelete(entry, offerUndo = cbDontAsk.isChecked)
            }
        }
        btnRow.addView(btnCancel)
        btnRow.addView(btnDelete)
        container.addView(tvTitle)
        container.addView(tvMsg)
        container.addView(cbDontAsk)
        container.addView(btnRow)
        dlg.setOnCancelListener { showSubtitlesDialog() }
        dlg.show()
    }

    private fun showSubtitlesDialog() {
        val spuTracks = mediaPlayer.spuTracks ?: emptyArray()
        val preferredLangsStr = PlaybackStore.getPreferredSubtitleLanguages(this).joinToString(" → ") { it.uppercase() }
        val subSize = PlaybackStore.getSubSizeSp(this).toInt()
        val subBgOpacity = PlaybackStore.getSubBgOpacityPct(this)

        val targetLang = PlaybackStore.getAutoTranslateTargetLang(this)
        val isArUi = Locale.getDefault().language.equals("ar", ignoreCase = true) ||
            PlaybackStore.getAppLanguageTag(this).startsWith("ar", ignoreCase = true)
        val targetLangName = SubtitleAutoTranslator.getLanguageDisplayName(targetLang, isArUi)
        val bilingualOn = PlaybackStore.isBilingualSubtitleEnabled(this)

        val items = mutableListOf(
            // 1. Auto search (with Search by name inline fallback if not found)
            PremiumMenuItem(
                iconEmoji = "⚡",
                title = getString(R.string.sub_menu_auto_search),
                subtitle = currentVideoTitle,
                isAccentHighlighted = true,
                onClick = { runAutomaticMultiSourceSubtitleSearch(customQuery = null) }
            ),
            // 2. Translate subtitle to my language (right after Auto search)
            PremiumMenuItem(
                iconEmoji = "✨",
                title = getString(R.string.sub_menu_translate_to_my_lang),
                subtitle = if (externalSubtitleCues.isNotEmpty()) {
                    getString(R.string.sub_menu_translate_to_my_lang_sub, targetLangName)
                } else {
                    getString(R.string.sub_menu_translate_need_sub)
                },
                isAccentHighlighted = true,
                onClick = {
                    val srcCues = originalSubtitleCues.ifEmpty { externalSubtitleCues }
                    if (srcCues.isNotEmpty()) {
                        startSubtitleAutoTranslation(
                            sourceFile = currentSubtitleSourceFile,
                            sourceCues = srcCues,
                            sourceLang = currentSubtitleSourceLang.ifBlank { "auto" },
                            targetLang = targetLang,
                            forceRetranslate = lastTranslatedTexts.isNotEmpty()
                        )
                    } else {
                        runAutomaticMultiSourceSubtitleSearch(customQuery = null)
                    }
                }
            ),
            // 3. Remaining items in original relative order
            PremiumMenuItem(
                iconEmoji = "📂",
                title = getString(R.string.sub_menu_load_local),
                subtitle = ".SRT • .ASS • .VTT",
                onClick = { subtitlePickerLauncher.launch(arrayOf("*/*")) }
            ),
            PremiumMenuItem(
                iconEmoji = "⏱️",
                title = getString(R.string.sub_menu_quick_sync),
                subtitle = getString(R.string.sub_sync_offset_format, subtitleSyncOffsetMs),
                onClick = { playerMenuBackStack.addLast { showSubtitlesDialog() }; showQuickSubtitleSyncDialog() }
            ),
            PremiumMenuItem(
                iconEmoji = "🎨",
                title = getString(R.string.sub_menu_style),
                subtitle = "${subSize}sp • ${getString(R.string.style_bg_opacity_label, subBgOpacity)}",
                onClick = { playerMenuBackStack.addLast { showSubtitlesDialog() }; showSubtitleLiveStyleDialog() }
            ),
            PremiumMenuItem(
                iconEmoji = "🌐",
                title = getString(R.string.sub_menu_languages, preferredLangsStr),
                subtitle = getString(R.string.sub_lang_dialog_title),
                onClick = { playerMenuBackStack.addLast { showSubtitlesDialog() }; showPlayerSubtitleLanguagePicker() }
            ),
            PremiumMenuItem(
                iconEmoji = if (bilingualOn) "🗣️" else "💬",
                title = getString(R.string.sub_menu_bilingual),
                subtitle = if (bilingualOn) getString(R.string.sub_bilingual_on_sub) else getString(R.string.sub_bilingual_off_sub),
                isAccentHighlighted = bilingualOn,
                onClick = {
                    val nextBi = !PlaybackStore.isBilingualSubtitleEnabled(this)
                    PlaybackStore.setBilingualSubtitleEnabled(this, nextBi)
                    if (originalSubtitleCues.isNotEmpty() && lastTranslatedTexts.isNotEmpty()) {
                        val updated = SubtitleAutoTranslator.mergeTranslatedTextsPreservingTiming(
                            originalSubtitleCues, lastTranslatedTexts, bilingual = nextBi
                        )
                        applyLoadedSubtitleCues(updated, isTranslated = true)
                    }
                    showToastIndicator(if (nextBi) getString(R.string.sub_bilingual_on_sub) else getString(R.string.sub_bilingual_off_sub))
                }
            ),
            PremiumMenuItem(
                iconEmoji = if (PlaybackStore.isSubtitlePositionLocked(this)) "🔒" else "🔓",
                title = getString(R.string.setting_lock_sub_position),
                subtitle = if (PlaybackStore.isSubtitlePositionLocked(this)) getString(R.string.sub_pos_locked_on) else getString(R.string.sub_pos_locked_off),
                isAccentHighlighted = PlaybackStore.isSubtitlePositionLocked(this),
                onClick = {
                    val nextLocked = !PlaybackStore.isSubtitlePositionLocked(this)
                    PlaybackStore.setSubtitlePositionLocked(this, nextLocked)
                    showToastIndicator(if (nextLocked) getString(R.string.sub_pos_locked_on) else getString(R.string.sub_pos_locked_off))
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🎯",
                title = getString(R.string.sub_menu_reset_pos),
                subtitle = getString(R.string.sub_position_reset_toast),
                onClick = { resetSubtitlePositionNow() }
            )
        )

        if (externalSubtitleCues.isNotEmpty() || mediaPlayer.spuTrack != -1) {
            items.add(
                PremiumMenuItem(
                    iconEmoji = "🚫",
                    title = getString(R.string.sub_menu_disable),
                    subtitle = null,
                    isDanger = true,
                    onClick = {
                        cancelActiveSubtitleTranslation()
                        externalSubtitleCues = emptyList()
                        originalSubtitleCues = emptyList()
                        lastTranslatedTexts = emptyList()
                        binding.tvDraggableSubtitle.text = ""
                        binding.tvDraggableSubtitle.visibility = View.GONE
                        mediaPlayer.spuTrack = -1
                    }
                )
            )
        }

        val downloadedEntries = SubtitleAutoTranslator.listAppDownloadedSubtitles(filesDir)
        downloadedEntries.forEachIndexed { dIdx, entry ->
            val isActiveFile = (currentSubtitleSourceFile?.absolutePath == entry.file.absolutePath) ||
                (currentVideoUri?.let { PlaybackStore.getVideoSubtitleCachePath(this, it) == entry.file.absolutePath } == true)
            val sizeKb = (entry.sizeBytes / 1024L).coerceAtLeast(1L)
            val subBadge = if (entry.isTranslated) {
                getString(R.string.sub_downloaded_translated_badge, sizeKb)
            } else {
                getString(R.string.sub_downloaded_badge, sizeKb)
            }
            items.add(
                PremiumMenuItem(
                    iconEmoji = if (entry.isTranslated) "✨" else "📄",
                    title = entry.displayName,
                    subtitle = subBadge,
                    isAccentHighlighted = isActiveFile,
                    sectionHeader = if (dIdx == 0) getString(R.string.sub_section_downloaded) else null,
                    deleteAction = { confirmAndDeleteDownloadedSubtitle(entry) },
                    deleteContentDescription = getString(R.string.sub_delete_downloaded_cd, entry.displayName),
                    onClick = {
                        val cues = SubtitleParser.parseFromFile(entry.file)
                        if (cues.isNotEmpty()) {
                            currentSubtitleSourceFile = entry.file
                            currentSubtitleDisplayLabel = entry.displayName
                            currentVideoUri?.let { vUri ->
                                PlaybackStore.saveVideoSubtitleCachePath(this, vUri, entry.file.absolutePath)
                                PlaybackStore.saveVideoSubtitleUri(this, vUri, entry.file.absolutePath, entry.displayName)
                            }
                            applyLoadedSubtitleCues(cues, isTranslated = entry.isTranslated)
                            showToastIndicator(getString(R.string.sub_loaded_count_format, cues.size))
                        } else {
                            showToastIndicator(getString(R.string.sub_err_corrupted))
                        }
                    }
                )
            )
        }

        for (track in spuTracks) {
            val tName = track.name ?: "Track ${track.id}"
            items.add(
                PremiumMenuItem(
                    iconEmoji = "🎬",
                    title = getString(R.string.sub_embedded_prefix, tName),
                    subtitle = "Embedded Track #${track.id}",
                    isAccentHighlighted = (mediaPlayer.spuTrack == track.id),
                    onClick = {
                        externalSubtitleCues = emptyList()
                        binding.tvDraggableSubtitle.visibility = View.GONE
                        mediaPlayer.spuTrack = track.id
                    }
                )
            )
        }

        val headerSub = if (currentSubtitleDisplayLabel.isNotBlank() && externalSubtitleCues.isNotEmpty()) {
            "$currentSubtitleDisplayLabel • $preferredLangsStr"
        } else {
            preferredLangsStr
        }
        showPlayerPremiumMenu(
            headerIcon = "💬",
            title = getString(R.string.subtitles),
            subtitle = headerSub,
            items = items
        )
    }

    private fun showCustomTitleAutoSearchDialog() {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)

        val defaultClean = currentVideoTitle
            .substringBeforeLast(".")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("(2160p|1080p|720p|480p|4K|BluRay|WEBRip|WEB-DL|x264|x265|HEVC|10bit)", RegexOption.IGNORE_CASE), "")
            .trim()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)
        val tvTitle = TextView(this).apply {
            text = "🔍  ${getString(R.string.sub_menu_web_search)}"
            setTextColor(primaryText)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }

        val input = EditText(this).apply {
            setText(defaultClean)
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setSingleLine(true)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(bg)
                setStroke(dp(1), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        container.addView(tvTitle)
        container.addView(input)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                dialog.dismiss()
                popPlayerMenuBackStack()
            }
        }
        dialog.setOnCancelListener { popPlayerMenuBackStack() }

        val btnSearch = TextView(this).apply {
            text = getString(R.string.subtitles)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                val q = input.text.toString().trim()
                dialog.dismiss()
                if (q.isNotEmpty()) {
                    runAutomaticMultiSourceSubtitleSearch(customQuery = q)
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSearch)
        container.addView(btnRow)
        dialog.show()
    }

    private fun runAutomaticMultiSourceSubtitleSearch(customQuery: String? = null) {
        val uri = currentVideoUri ?: return
        binding.playerLoadingBar.visibility = View.VISIBLE
        showToastIndicator(getString(R.string.searching_subtitles_progress))

        lifecycleScope.launch {
            val outcome = SubtitleSearchEngine.searchAndAutoDownload(
                context = this@PlayerActivity,
                videoUri = uri,
                rawTitle = currentVideoTitle,
                customSearchQuery = customQuery
            )
            binding.playerLoadingBar.visibility = View.GONE

            val targetLang = PlaybackStore.getAutoTranslateTargetLang(this@PlayerActivity)
            val autoTranslateOn = PlaybackStore.isAutoTranslateEnabled(this@PlayerActivity)

            when (outcome) {
                is SubtitleSearchOutcome.Success -> {
                    val bestLang = outcome.bestItem.languageCode.lowercase(Locale.ROOT)
                    val cachedPath = PlaybackStore.getVideoSubtitleCachePath(this@PlayerActivity, uri)
                    val cachedFile = cachedPath?.let { File(it) }?.takeIf { it.exists() }
                    currentSubtitleSourceFile = cachedFile
                    currentSubtitleSourceLang = bestLang

                    if (autoTranslateOn && bestLang != targetLang && targetLang.isNotBlank()) {
                        // Downloaded subtitle is in another language -> offer or run auto-translation!
                        offerOrRunAutoTranslation(
                            sourceFile = cachedFile,
                            sourceCues = outcome.autoAppliedCues,
                            sourceLang = bestLang,
                            targetLang = targetLang,
                            fallbackResults = outcome.results
                        )
                    } else {
                        val isAr = Locale.getDefault().language.equals("ar", ignoreCase = true) ||
                            PlaybackStore.getAppLanguageTag(this@PlayerActivity).startsWith("ar", ignoreCase = true)
                        val lbl = "${SubtitleAutoTranslator.getLanguageDisplayName(bestLang, isAr)} (${outcome.bestItem.providerName})"
                        currentSubtitleDisplayLabel = lbl
                        cachedFile?.let { f ->
                            PlaybackStore.saveVideoSubtitleUri(this@PlayerActivity, uri, f.absolutePath, lbl)
                        }
                        applyLoadedSubtitleCues(outcome.autoAppliedCues)
                        showToastIndicator(
                            getString(
                                R.string.sub_auto_applied_format,
                                outcome.bestItem.providerName,
                                outcome.bestItem.languageCode.uppercase()
                            )
                        )
                        showManualSubtitleResultsDialog(outcome.results)
                    }
                }
                is SubtitleSearchOutcome.Error -> {
                    // If target language not found and auto-translate is enabled, search fallback languages (en, fr, es, de)
                    if (outcome.errorType == SubtitleSearchOutcome.ErrorType.NOT_FOUND && autoTranslateOn) {
                        binding.playerLoadingBar.visibility = View.VISIBLE
                        showToastIndicator(getString(R.string.sub_auto_translate_searching_fallback))
                        val fallbackLangs = listOf("en", "fr", "es", "de", "tr").filter { it != targetLang }
                        val fallback = SubtitleSearchEngine.searchAndDownloadWithLanguages(
                            context = this@PlayerActivity,
                            videoUri = uri,
                            rawTitle = currentVideoTitle,
                            languages = fallbackLangs,
                            customSearchQuery = customQuery
                        )
                        binding.playerLoadingBar.visibility = View.GONE
                        if (fallback != null) {
                            val (fbItem, fbFile, fbCues) = fallback
                            currentSubtitleSourceFile = fbFile
                            currentSubtitleSourceLang = fbItem.languageCode.lowercase(Locale.ROOT)
                            offerOrRunAutoTranslation(
                                sourceFile = fbFile,
                                sourceCues = fbCues,
                                sourceLang = fbItem.languageCode.lowercase(Locale.ROOT),
                                targetLang = targetLang,
                                fallbackResults = emptyList()
                            )
                            return@launch
                        }
                    }

                    val msgRes = when (outcome.errorType) {
                        SubtitleSearchOutcome.ErrorType.NO_INTERNET -> R.string.sub_err_no_internet
                        SubtitleSearchOutcome.ErrorType.TIMEOUT -> R.string.sub_err_timeout
                        SubtitleSearchOutcome.ErrorType.RATE_LIMIT -> R.string.sub_err_rate_limit
                        SubtitleSearchOutcome.ErrorType.CORRUPTED_FILE -> R.string.sub_err_corrupted
                        SubtitleSearchOutcome.ErrorType.NOT_FOUND -> R.string.sub_err_not_found
                    }
                    showToastIndicator(getString(msgRes))
                    showPlayerPremiumMenu(
                        headerIcon = "⚠️",
                        title = getString(R.string.subtitles),
                        subtitle = getString(msgRes),
                        items = listOf(
                            PremiumMenuItem("🔍", getString(R.string.sub_menu_web_search), currentVideoTitle, true) {
                                showCustomTitleAutoSearchDialog()
                            },
                            PremiumMenuItem("📂", getString(R.string.sub_menu_load_local), ".SRT • .ASS • .VTT") {
                                subtitlePickerLauncher.launch(arrayOf("*/*"))
                            }
                        )
                    )
                }
            }
        }
    }

    private fun offerOrRunAutoTranslation(
        sourceFile: File?,
        sourceCues: List<SubtitleCue>,
        sourceLang: String,
        targetLang: String,
        fallbackResults: List<SubtitleResultItem>
    ) {
        if (!PlaybackStore.isAskBeforeTranslating(this)) {
            startSubtitleAutoTranslation(
                sourceFile = sourceFile,
                sourceCues = sourceCues,
                sourceLang = sourceLang,
                targetLang = targetLang,
                forceRetranslate = false
            )
            return
        }

        val isAr = Locale.getDefault().language.equals("ar", ignoreCase = true) ||
            PlaybackStore.getAppLanguageTag(this).startsWith("ar", ignoreCase = true)
        val srcName = SubtitleAutoTranslator.getLanguageDisplayName(sourceLang, isAr)
        val tgtName = SubtitleAutoTranslator.getLanguageDisplayName(targetLang, isAr)

        showPlayerPremiumMenu(
            headerIcon = "🌐",
            title = getString(R.string.sub_auto_translate_ask_title),
            subtitle = getString(R.string.sub_auto_translate_ask_msg, tgtName, srcName),
            items = listOf(
                PremiumMenuItem(
                    iconEmoji = "✨",
                    title = getString(R.string.sub_auto_translate_action_now, tgtName),
                    subtitle = getString(R.string.sub_auto_translate_from_sub, srcName),
                    isAccentHighlighted = true,
                    onClick = {
                        startSubtitleAutoTranslation(
                            sourceFile = sourceFile,
                            sourceCues = sourceCues,
                            sourceLang = sourceLang,
                            targetLang = targetLang,
                            forceRetranslate = false
                        )
                    }
                ),
                PremiumMenuItem(
                    iconEmoji = "💬",
                    title = getString(R.string.sub_auto_translate_use_original, srcName),
                    subtitle = getString(R.string.sub_loaded_count_format, sourceCues.size),
                    onClick = {
                        currentSubtitleDisplayLabel = srcName
                        applyLoadedSubtitleCues(sourceCues)
                        showToastIndicator(getString(R.string.sub_loaded_count_format, sourceCues.size))
                        if (fallbackResults.size > 1) showManualSubtitleResultsDialog(fallbackResults)
                    }
                )
            )
        )
    }

    private fun startSubtitleAutoTranslation(
        sourceFile: File?,
        sourceCues: List<SubtitleCue>,
        sourceLang: String,
        targetLang: String,
        forceRetranslate: Boolean
    ) {
        activeTranslationJob?.cancel()

        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val tvTitle = TextView(this).apply {
            text = "✨  ${getString(R.string.sub_auto_translate_progress_title)}"
            setTextColor(primaryText)
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }

        val tvStatus = TextView(this).apply {
            text = getString(R.string.sub_auto_translate_progress_detail, 0, sourceCues.size)
            setTextColor(secondaryText)
            textSize = 13.5f
            setPadding(0, 0, 0, dp(12))
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = sourceCues.size.coerceAtLeast(1)
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply { bottomMargin = dp(14) }
        }

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(accent)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        container.addView(tvTitle)
        container.addView(tvStatus)
        container.addView(progressBar)
        container.addView(btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel.setOnClickListener {
            activeTranslationJob?.cancel()
            dialog.dismiss()
            showToastIndicator(getString(R.string.sub_auto_translate_cancelled))
        }

        dialog.show()

        val reqId = translationRequestId.incrementAndGet()
        val currentPlayMs = if (::mediaPlayer.isInitialized) mediaPlayer.time else 0L
        val bilingual = PlaybackStore.isBilingualSubtitleEnabled(this)

        activeTranslationJob = lifecycleScope.launch {
            val sourceBytes = withContext(Dispatchers.IO) {
                try {
                    sourceFile?.takeIf { it.exists() }?.readBytes()
                        ?: SubtitleAutoTranslator.serializeToSrt(sourceCues).toByteArray(Charsets.UTF_8)
                } catch (_: Exception) {
                    SubtitleAutoTranslator.serializeToSrt(sourceCues).toByteArray(Charsets.UTF_8)
                }
            }

            val result = try {
                SubtitleAutoTranslator.translateSubtitleCues(
                    context = this@PlayerActivity,
                    sourceBytes = sourceBytes,
                    sourceCues = sourceCues,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    forceRetranslate = forceRetranslate,
                    bilingual = bilingual,
                    priorityPlaybackMs = currentPlayMs,
                    onBatchReady = { partialCues ->
                        if (reqId == translationRequestId.get()) {
                            runOnUiThread {
                                if (reqId == translationRequestId.get()) {
                                    externalSubtitleCues = partialCues
                                    updateDraggableSubtitleText(if (::mediaPlayer.isInitialized) mediaPlayer.time else 0L)
                                }
                            }
                        }
                    },
                    onProgress = { completed, total, _ ->
                        runOnUiThread {
                            if (dialog.isShowing && reqId == translationRequestId.get()) {
                                progressBar.max = total.coerceAtLeast(1)
                                progressBar.progress = completed
                                tvStatus.text = getString(R.string.sub_auto_translate_progress_detail, completed, total)
                            }
                        }
                    }
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                null
            } catch (_: Exception) {
                null
            }

            if (dialog.isShowing) dialog.dismiss()
            if (reqId != translationRequestId.get()) return@launch

            if (result != null && result.cues.isNotEmpty()) {
                originalSubtitleCues = result.originalCues
                lastTranslatedTexts = result.translatedTexts
                currentSubtitleDisplayLabel = result.displayLabel
                currentVideoUri?.let { vUri ->
                    PlaybackStore.saveVideoSubtitleCachePath(this@PlayerActivity, vUri, result.translatedFile.absolutePath)
                    PlaybackStore.saveVideoSubtitleUri(this@PlayerActivity, vUri, result.translatedFile.absolutePath, result.displayLabel)
                }
                applyLoadedSubtitleCues(result.cues, isTranslated = true)
                showToastIndicator(result.displayLabel)
            } else if (isActive) {
                applyLoadedSubtitleCues(sourceCues, isTranslated = false)
                showToastIndicator(getString(R.string.sub_auto_translate_failed_fallback))
            }
        }
    }

    private fun showQuickSubtitleSyncDialog() {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val tvTitle = TextView(this).apply {
            text = "⏱️  ${getString(R.string.sub_menu_quick_sync)}"
            setTextColor(primaryText)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }

        val tvCurrent = TextView(this).apply {
            text = getString(R.string.sub_sync_offset_format, subtitleSyncOffsetMs)
            setTextColor(accent)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val steps = listOf(
            "-0.5s" to -500L,
            "-0.1s" to -100L,
            "0.0s" to 0L,
            "+0.1s" to 100L,
            "+0.5s" to 500L
        )

        for ((label, delta) in steps) {
            val isReset = (delta == 0L)
            val btn = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isReset) Color.parseColor("#090D16") else primaryText)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (isReset) accent else bg)
                    if (!isReset) setStroke(dp(1), Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
                setOnClickListener {
                    if (isReset) {
                        subtitleSyncOffsetMs = 0L
                        currentVideoUri?.let { PlaybackStore.saveVideoSubtitleSyncMs(this@PlayerActivity, it, 0L) }
                        updateDraggableSubtitleText(mediaPlayer.time)
                    } else {
                        adjustSubtitleSyncBy(delta)
                    }
                    tvCurrent.text = getString(R.string.sub_sync_offset_format, subtitleSyncOffsetMs)
                }
            }
            btnRow.addView(btn)
        }

        val btnDone = TextView(this).apply {
            text = getString(R.string.ok)
            setTextColor(secondaryText)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
            setPadding(dp(14), dp(14), dp(8), dp(4))
        }

        container.addView(tvTitle)
        container.addView(tvCurrent)
        container.addView(btnRow)
        container.addView(btnDone)

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnDone.setOnClickListener {
            dialog.dismiss()
            popPlayerMenuBackStack()
        }
        dialog.setOnCancelListener { popPlayerMenuBackStack() }
        dialog.show()
    }

    private fun showManualSubtitleResultsDialog(results: List<SubtitleResultItem>) {
        if (results.size <= 1) return
        val items = results.take(12).mapIndexed { idx, item ->
            val badge = when (item.matchQuality) {
                MatchQuality.HASH_MATCH -> "★ HASH"
                MatchQuality.EXACT_TITLE -> "✓ EXACT"
                MatchQuality.FUZZY_TITLE -> "~ MATCH"
            }
            PremiumMenuItem(
                iconEmoji = "💬",
                title = "${idx + 1}. ${item.title}",
                subtitle = "${item.languageCode.uppercase()} • ${item.providerName} • $badge",
                isAccentHighlighted = (idx == 0),
                onClick = {
                    binding.playerLoadingBar.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        val pair = SubtitleSearchEngine.downloadAndCacheSubtitle(this@PlayerActivity, item)
                        binding.playerLoadingBar.visibility = View.GONE
                        if (pair != null && pair.second.isNotEmpty()) {
                            currentVideoUri?.let { vUri ->
                                PlaybackStore.saveVideoSubtitleCachePath(this@PlayerActivity, vUri, pair.first.absolutePath)
                            }
                            applyLoadedSubtitleCues(pair.second)
                            showToastIndicator(getString(R.string.sub_loaded_count_format, pair.second.size))
                        } else {
                            showToastIndicator(getString(R.string.sub_err_corrupted))
                        }
                    }
                }
            )
        }

        showPlayerPremiumMenu(
            headerIcon = "✅",
            title = getString(R.string.sub_results_dialog_title),
            subtitle = "${results.size} Subtitles Found",
            items = items
        )
    }

    private fun showPlayerSubtitleLanguagePicker() {
        val langCodes = arrayOf("ar", "en", "fr", "es", "de", "tr", "ru", "pt", "it", "hi", "ur", "id", "zh", "ja", "ko")
        val langLabels = arrayOf(
            getString(R.string.sub_lang_ar),
            getString(R.string.sub_lang_en),
            getString(R.string.sub_lang_fr),
            getString(R.string.sub_lang_es),
            getString(R.string.lang_german),
            getString(R.string.sub_lang_tr),
            getString(R.string.lang_russian),
            getString(R.string.lang_portuguese),
            getString(R.string.lang_italian),
            getString(R.string.sub_lang_hi),
            getString(R.string.sub_lang_ur),
            getString(R.string.lang_indonesian),
            getString(R.string.lang_chinese),
            getString(R.string.lang_japanese),
            getString(R.string.lang_korean)
        )
        val currentPreferred = PlaybackStore.getPreferredSubtitleLanguages(this).toMutableList()
        val checked = BooleanArray(langCodes.size) { currentPreferred.contains(langCodes[it]) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sub_lang_dialog_title))
            .setMultiChoiceItems(langLabels, checked) { _, which, isChecked ->
                val code = langCodes[which]
                if (isChecked) {
                    if (!currentPreferred.contains(code)) currentPreferred.add(code)
                } else {
                    currentPreferred.remove(code)
                }
            }
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val finalOrder = if (currentPreferred.isEmpty()) listOf("ar", "en") else currentPreferred
                PlaybackStore.setPreferredSubtitleLanguages(this, finalOrder)
                showToastIndicator(
                    getString(R.string.sub_lang_priority_saved, finalOrder.joinToString(" → ") { it.uppercase() })
                )
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> popPlayerMenuBackStack() }
            .setOnCancelListener { popPlayerMenuBackStack() }
            .show()
    }

    private fun showSubtitleLiveStyleDialog() {
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val accent = PlaybackStore.getAccentColor(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(surface)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 20)
            setBackgroundColor(surface)
        }
        scroll.addView(container)

        var currentSize = PlaybackStore.getSubSizeSp(this)
        var currentBgOpacity = PlaybackStore.getSubBgOpacityPct(this)
        var currentTextOpacity = PlaybackStore.getSubTextOpacityPct(this)
        var currentOutline = PlaybackStore.getSubOutlinePx(this)
        var selectedColorIdx = PlaybackStore.getSubColorIndex(this)

        val tvPreview = TextView(this).apply {
            text = getString(R.string.style_preview_sample)
            gravity = Gravity.CENTER
            setPadding(24, 14, 24, 14)
        }

        fun updateLive() {
            PlaybackStore.setSubSizeSp(this, currentSize)
            PlaybackStore.setSubBgOpacityPct(this, currentBgOpacity)
            PlaybackStore.setSubTextOpacityPct(this, currentTextOpacity)
            PlaybackStore.setSubOutlinePx(this, currentOutline)
            PlaybackStore.setSubColorIndex(this, selectedColorIdx)

            tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize)
            tvPreview.setTextColor(PlaybackStore.getSubColorValue(this))
            tvPreview.background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(PlaybackStore.getSubBackgroundColor(this@PlayerActivity))
            }
            val textAlpha = ((currentTextOpacity / 100.0) * 255).toInt().coerceIn(50, 255)
            val shadowAlpha = if (currentOutline <= 0.5f) 0 else textAlpha
            tvPreview.setShadowLayer(
                currentOutline,
                if (currentOutline > 0f) 2f else 0f,
                if (currentOutline > 0f) 2f else 0f,
                Color.argb(shadowAlpha, 0, 0, 0)
            )
            applyThemeAndSubtitleStyle()
            if (binding.tvDraggableSubtitle.visibility != View.VISIBLE) {
                binding.tvDraggableSubtitle.visibility = View.VISIBLE
                binding.tvDraggableSubtitle.text = getString(R.string.style_preview_sample)
            }
        }
        updateLive()

        val tvSizeLabel = TextView(this).apply {
            text = getString(R.string.style_size_label, currentSize.toInt())
            setTextColor(primaryText)
            setPadding(0, 12, 0, 4)
        }
        val sizeSeek = SeekBar(this).apply {
            max = 32
            progress = (currentSize - 12f).toInt().coerceIn(0, 32)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentSize = 12f + p
                    tvSizeLabel.text = getString(R.string.style_size_label, currentSize.toInt())
                    updateLive()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvBgOpacityLabel = TextView(this).apply {
            text = getString(R.string.style_bg_opacity_label, currentBgOpacity)
            setTextColor(primaryText)
            setPadding(0, 12, 0, 4)
        }
        val bgOpacitySeek = SeekBar(this).apply {
            max = 100
            progress = currentBgOpacity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentBgOpacity = p
                    tvBgOpacityLabel.text = getString(R.string.style_bg_opacity_label, currentBgOpacity)
                    updateLive()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvTextOpacityLabel = TextView(this).apply {
            text = getString(R.string.style_text_opacity_label, currentTextOpacity)
            setTextColor(primaryText)
            setPadding(0, 12, 0, 4)
        }
        val textOpacitySeek = SeekBar(this).apply {
            max = 80
            progress = (currentTextOpacity - 20).coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentTextOpacity = 20 + p
                    tvTextOpacityLabel.text = getString(R.string.style_text_opacity_label, currentTextOpacity)
                    updateLive()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvOutlineLabel = TextView(this).apply {
            text = getString(R.string.style_outline_label, currentOutline.toInt())
            setTextColor(primaryText)
            setPadding(0, 12, 0, 4)
        }
        val outlineSeek = SeekBar(this).apply {
            max = 12
            progress = currentOutline.toInt().coerceIn(0, 12)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentOutline = p.toFloat()
                    tvOutlineLabel.text = getString(R.string.style_outline_label, currentOutline.toInt())
                    updateLive()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvColorLabel = TextView(this).apply {
            text = getString(R.string.style_color_label)
            setTextColor(primaryText)
            setPadding(0, 12, 0, 6)
        }

        val colorNames = arrayOf(
            getString(R.string.style_color_white),
            getString(R.string.style_color_yellow),
            getString(R.string.style_color_cyan),
            getString(R.string.style_color_green)
        )
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        colorNames.forEachIndexed { idx, label ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                setTextColor(primaryText)
                buttonTintList = ColorStateList.valueOf(accent)
                isChecked = (idx == selectedColorIdx)
                setOnClickListener {
                    selectedColorIdx = idx
                    updateLive()
                }
            }
            radioGroup.addView(rb)
        }

        container.addView(tvPreview)
        container.addView(tvSizeLabel)
        container.addView(sizeSeek)
        container.addView(tvBgOpacityLabel)
        container.addView(bgOpacitySeek)
        container.addView(tvTextOpacityLabel)
        container.addView(textOpacitySeek)
        container.addView(tvOutlineLabel)
        container.addView(outlineSeek)
        container.addView(tvColorLabel)
        container.addView(radioGroup)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.style_dialog_title))
            .setView(scroll)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                applyThemeAndSubtitleStyle()
            }
            .setNeutralButton(getString(R.string.reset)) { _, _ ->
                resetSubtitlePositionNow()
            }
            .setNegativeButton(getString(R.string.close)) { _, _ -> popPlayerMenuBackStack() }
            .setOnCancelListener { popPlayerMenuBackStack() }
            .show()
    }

    private fun showMoreProOptionsDialog() {
        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "⚡",
                title = getString(R.string.decoder_dialog_title),
                subtitle = getDecoderShortBadge(currentDecoderIndex),
                isAccentHighlighted = true,
                sectionHeader = getString(R.string.settings_sec_media_engine),
                onClick = {
                    playerMenuBackStack.addLast { showMoreProOptionsDialog() }
                    showDecoderModeSelectorDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🎛️",
                title = getString(R.string.settings_hub_audio_title),
                subtitle = getPlayerAudioModeName(PlaybackStore.getAudioPresetMode(this)),
                isAccentHighlighted = true,
                onClick = {
                    playerMenuBackStack.addLast { showMoreProOptionsDialog() }
                    showPlayerSoundModesDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🌈",
                title = getString(R.string.pro_hdr_mode),
                subtitle = "HDR10 / HLG Tonemapping",
                onClick = {
                    playerMenuBackStack.addLast { showMoreProOptionsDialog() }
                    showHdrModeSelectorDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🖼️",
                title = getString(R.string.pro_pip),
                subtitle = getString(R.string.setting_auto_pip_title),
                isAccentHighlighted = true,
                sectionHeader = getString(R.string.settings_subsec_pip_and_resume),
                onClick = {
                    enterPipModeNow()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "📺",
                title = getString(R.string.cast_screen_title),
                subtitle = getString(R.string.cast_screen_wireless_sub),
                onClick = {
                    playerMenuBackStack.addLast { showMoreProOptionsDialog() }
                    showCastScreenDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "⏪",
                title = getString(R.string.pro_sync_earlier),
                subtitle = "${subtitleSyncOffsetMs}ms",
                sectionHeader = getString(R.string.settings_hub_subtitles_only_title),
                onClick = {
                    adjustSubtitleSyncBy(-100L)
                }
            ),
            PremiumMenuItem(
                iconEmoji = "⏩",
                title = getString(R.string.pro_sync_later),
                subtitle = "${subtitleSyncOffsetMs}ms",
                onClick = {
                    adjustSubtitleSyncBy(100L)
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔄",
                title = getString(R.string.pro_sync_reset),
                subtitle = "0ms",
                onClick = {
                    subtitleSyncOffsetMs = 0L
                    currentVideoUri?.let { PlaybackStore.saveVideoSubtitleSyncMs(this, it, 0L) }
                    showToastIndicator(getString(R.string.sub_sync_offset_format, 0L))
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🎯",
                title = getString(R.string.pro_reset_sub_pos),
                subtitle = getString(R.string.sub_position_reset_toast),
                onClick = {
                    resetSubtitlePositionNow()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🌙",
                title = getString(R.string.pro_sleep_30),
                subtitle = "30 Minutes",
                sectionHeader = "🌙 Sleep Timer",
                onClick = {
                    handler.removeCallbacks(sleepTimerRunnable)
                    handler.postDelayed(sleepTimerRunnable, 30 * 60 * 1000L)
                    showToastIndicator(getString(R.string.pro_sleep_30))
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🌙",
                title = getString(R.string.pro_sleep_60),
                subtitle = "60 Minutes",
                onClick = {
                    handler.removeCallbacks(sleepTimerRunnable)
                    handler.postDelayed(sleepTimerRunnable, 60 * 60 * 1000L)
                    showToastIndicator(getString(R.string.pro_sleep_60))
                }
            )
        )

        showPlayerPremiumMenu(
            headerIcon = "⚙️",
            title = getString(R.string.pro_tools_title),
            subtitle = currentVideoTitle,
            items = items
        )
    }

    private fun showHdrModeSelectorDialog() {
        val current = PlaybackStore.getHdrModeIndex(this)
        val hdrModes = listOf(
            Triple("🌟", "HDR10 / HLG Cinema Natural", "Hable Tonemapping"),
            Triple("🔥", "HDR Vivid Boost", "Reinhard Tonemapping"),
            Triple("⚖️", "HDR-to-SDR Balanced", "Mobius Tonemapping")
        )
        val items = hdrModes.mapIndexed { idx, (emoji, title, sub) ->
            PremiumMenuItem(
                iconEmoji = emoji,
                title = title,
                subtitle = sub,
                isAccentHighlighted = (idx == current),
                onClick = {
                    playerMenuBackStack.clear()
                    PlaybackStore.setHdrModeIndex(this, idx)
                    showToastIndicator("$emoji $title")
                }
            )
        }
        showPlayerPremiumMenu("🌈", getString(R.string.pro_hdr_mode), null, items)
    }


    private fun getConnectedExternalDisplayName(): String? {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
            val presentationDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            if (!presentationDisplays.isNullOrEmpty()) {
                presentationDisplays.first().name
            } else if (dm.displays.size > 1) {
                dm.displays.firstOrNull { it.displayId != android.view.Display.DEFAULT_DISPLAY }?.name
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun launchWirelessDisplaySettings(enterPipFirst: Boolean) {
        if (enterPipFirst && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipModeNow()
        }
        Toast.makeText(this, getString(R.string.cast_opening_settings_toast), Toast.LENGTH_LONG).show()
        val candidateActions = listOf(
            Settings.ACTION_CAST_SETTINGS,
            "android.settings.WIFI_DISPLAY_SETTINGS",
            "android.settings.CAST_SETTINGS",
            "com.samsung.wfd.LAUNCH_WFD_PICKER_DLG",
            Settings.ACTION_WIFI_SETTINGS
        )
        for (action in candidateActions) {
            try {
                val intent = Intent(action).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
    }

    private fun beamVideoToExternalCastApp() {
        val rawUri = currentVideoUri ?: return
        try {
            val safeShareUri = if (rawUri.scheme == "file" && !rawUri.path.isNullOrEmpty()) {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    File(rawUri.path!!)
                )
            } else {
                rawUri
            }
            val sendIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(safeShareUri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("title", currentVideoTitle)
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.cast_screen_title)))
        } catch (_: Exception) {
            launchWirelessDisplaySettings(enterPipFirst = false)
        }
    }

    private fun showCastScreenDialog() {
        val connectedName = getConnectedExternalDisplayName()
        val accent = PlaybackStore.getAccentColor(this)
        binding.btnCastScreen.imageTintList = ColorStateList.valueOf(
            if (connectedName != null) accent else Color.WHITE
        )

        val statusSub = if (connectedName != null) {
            "🟢 " + getString(R.string.cast_status_connected, connectedName)
        } else {
            getString(R.string.cast_status_ready)
        }

        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "📺",
                title = getString(R.string.cast_screen_wireless_title),
                subtitle = getString(R.string.cast_screen_wireless_sub),
                isAccentHighlighted = true,
                onClick = {
                    playerMenuBackStack.clear()
                    launchWirelessDisplaySettings(enterPipFirst = false)
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🖼️",
                title = getString(R.string.cast_screen_pip_mirror_title),
                subtitle = getString(R.string.cast_screen_pip_mirror_sub),
                onClick = {
                    playerMenuBackStack.clear()
                    launchWirelessDisplaySettings(enterPipFirst = true)
                }
            ),
            PremiumMenuItem(
                iconEmoji = "📡",
                title = getString(R.string.cast_screen_dlna_title),
                subtitle = getString(R.string.cast_screen_dlna_sub),
                onClick = {
                    playerMenuBackStack.clear()
                    beamVideoToExternalCastApp()
                }
            )
        )

        showPlayerPremiumMenu(
            headerIcon = "📺",
            title = getString(R.string.cast_screen_title),
            subtitle = statusSub,
            items = items
        )
    }

    private fun getDecoderShortBadge(index: Int): String {
        return when (index) {
            1 -> "HW"
            2 -> "SW"
            else -> "HW+"
        }
    }

    private fun showDecoderModeSelectorDialog() {
        val options = listOf(
            Triple(0, "⚡", Pair(getString(R.string.decoder_hw_plus_title), getString(R.string.decoder_hw_plus_sub))),
            Triple(1, "🔧", Pair(getString(R.string.decoder_hw_title), getString(R.string.decoder_hw_sub))),
            Triple(2, "💻", Pair(getString(R.string.decoder_sw_title), getString(R.string.decoder_sw_sub)))
        )
        val items = options.map { (idx, emoji, texts) ->
            val isSel = (idx == currentDecoderIndex)
            PremiumMenuItem(
                iconEmoji = emoji,
                title = texts.first,
                subtitle = texts.second,
                isAccentHighlighted = isSel,
                onClick = {
                    playerMenuBackStack.clear()
                    if (currentDecoderIndex != idx) {
                        currentDecoderIndex = idx
                        PlaybackStore.setDecoderModeIndex(this, idx)
                        binding.btnDecoder.text = getDecoderShortBadge(idx)
                        val resumeMs = if (::mediaPlayer.isInitialized) mediaPlayer.time.coerceAtLeast(0L) else 0L
                        pendingStartPositionMs = resumeMs
                        hasAppliedInitialSeek = false
                        currentVideoUri?.let { uri ->
                            startVideoPlaybackSafely(uri)
                        }
                    }
                    showToastIndicator(getString(R.string.decoder_switched_toast, getDecoderShortBadge(idx)))
                }
            )
        }
        showPlayerPremiumMenu("⚡", getString(R.string.decoder_dialog_title), getDecoderShortBadge(currentDecoderIndex), items)
    }

    private fun buildPipParams(isPlaying: Boolean = true): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            val audioOnlyPending = PendingIntent.getBroadcast(
                this,
                201,
                Intent(ACTION_PIP_AUDIO_ONLY).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val audioOnlyAction = RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_headphones),
                getString(R.string.audio_only_mode_label),
                getString(R.string.audio_only_mode_label),
                audioOnlyPending
            )
            val playPausePending = PendingIntent.getBroadcast(
                this,
                202,
                Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val playPauseAction = RemoteAction(
                Icon.createWithResource(this, playPauseIcon),
                if (isPlaying) "Pause" else "Play",
                if (isPlaying) "Pause" else "Play",
                playPausePending
            )
            val pipActions = mutableListOf<RemoteAction>()
            if (hasActiveVideoPlaylist() && HiddenMediaAndPlaylistHelper.canGoPrevious(currentPlaylistIndex, playlistUris.size)) {
                val prevPending = PendingIntent.getBroadcast(
                    this, 203, Intent(ACTION_PIP_PREV).setPackage(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pipActions.add(
                    RemoteAction(
                        Icon.createWithResource(this, R.drawable.ic_prev_video_modern),
                        getString(R.string.player_prev_video),
                        getString(R.string.player_prev_video),
                        prevPending
                    )
                )
            } else {
                pipActions.add(audioOnlyAction)
            }
            pipActions.add(playPauseAction)
            if (hasActiveVideoPlaylist() && HiddenMediaAndPlaylistHelper.canGoNext(currentPlaylistIndex, playlistUris.size)) {
                val nextPending = PendingIntent.getBroadcast(
                    this, 204, Intent(ACTION_PIP_NEXT).setPackage(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pipActions.add(
                    RemoteAction(
                        Icon.createWithResource(this, R.drawable.ic_next_video_modern),
                        getString(R.string.player_next_video),
                        getString(R.string.player_next_video),
                        nextPending
                    )
                )
            }
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(pipActions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(PlaybackStore.isAutoPipEnabled(this) && isPlaying)
                builder.setSeamlessResizeEnabled(true)
            }
            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    private fun updateAutoPipParams(isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                buildPipParams(isPlaying)?.let { setPictureInPictureParams(it) }
            } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
    }

    private fun enterPipModeNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                isEnteringPipTransition = true
                binding.controlsContainer.visibility = View.GONE
                val params = buildPipParams(isPlaying = true)
                if (params != null) {
                    enterPictureInPictureMode(params)
                }
            } catch (_: Exception) {
                isEnteringPipTransition = false
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PlaybackStore.isAutoPipEnabled(this) && ::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
            enterPipModeNow()
        }
    }

    private fun stopPlaybackImmediatelyAndClosePip(finishActivity: Boolean) {
        saveCurrentPositionNow()
        isEnteringPipTransition = false
        wasInPipMode = false
        if (::mediaPlayer.isInitialized) {
            try {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                }
                mediaPlayer.stop()
            } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
        updateAutoPipParams(isPlaying = false)
        updateMediaSessionPlaybackState(isPlaying = false)
        abandonPlayerAudioFocus()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (finishActivity && !isFinishing) {
            finish()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        cancelActivePlayerGesturesSafely()
        binding.videoSurfaceFrame.post { changeSurfaceLayout() }
        if (isInPictureInPictureMode) {
            wasInPipMode = true
            isEnteringPipTransition = false
            binding.controlsContainer.visibility = View.GONE
        } else {
            isEnteringPipTransition = false
            if (isSwitchingToAudioOnly) {
                wasInPipMode = false
                return
            }
            val closedViaXButton = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || isFinishing
            if (closedViaXButton) {
                stopPlaybackImmediatelyAndClosePip(finishActivity = true)
            } else {
                wasInPipMode = false
                showControls()
            }
        }
    }

    private fun getPlayerAudioModeName(mode: Int): String {
        return when (mode) {
            1 -> "🎬 " + getString(R.string.audio_mode_cinematic)
            2 -> "🏛️ " + getString(R.string.audio_mode_hall)
            3 -> "🗣️ " + getString(R.string.audio_mode_voice)
            4 -> "🎸 " + getString(R.string.audio_mode_bass)
            5 -> "🏟️ " + getString(R.string.audio_mode_stadium)
            6 -> "🌙 " + getString(R.string.audio_mode_night)
            7 -> "🎛️ " + getString(R.string.audio_mode_custom)
            else -> "🎵 " + getString(R.string.audio_mode_standard)
        }
    }

    private fun applyAudioEqualizerFromStore() {
        if (!::mediaPlayer.isInitialized) return
        try {
            val mode = PlaybackStore.getAudioPresetMode(this)
            val preAmp = PlaybackStore.getAudioPreAmpDb(this)
            val bands5 = PlaybackStore.getAudioEqBandsDb(this)
            val isAllFlat = (mode == 0 && preAmp == 0f && bands5.all { it == 0f })
            if (isAllFlat) {
                mediaPlayer.setEqualizer(null)
                return
            }
            val eq = MediaPlayer.Equalizer.create()
            eq.setPreAmp(preAmp)
            // Map 5 user bands (60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz) across LibVLC's 10 internal bands (0..9)
            for (b in 0 until 5) {
                val gain = bands5[b].coerceIn(-20f, 20f)
                eq.setAmp(b * 2, gain)
                eq.setAmp(b * 2 + 1, gain)
            }
            mediaPlayer.setEqualizer(eq)
        } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
    }

    private fun showPlayerSoundModesDialog() {
        val activeMode = PlaybackStore.getAudioPresetMode(this)
        val modes = listOf(
            Triple(0, "🎵", Pair(getString(R.string.audio_mode_standard), getString(R.string.audio_mode_standard_sub))),
            Triple(1, "🎬", Pair(getString(R.string.audio_mode_cinematic), getString(R.string.audio_mode_cinematic_sub))),
            Triple(2, "🏛️", Pair(getString(R.string.audio_mode_hall), getString(R.string.audio_mode_hall_sub))),
            Triple(3, "🗣️", Pair(getString(R.string.audio_mode_voice), getString(R.string.audio_mode_voice_sub))),
            Triple(4, "🎸", Pair(getString(R.string.audio_mode_bass), getString(R.string.audio_mode_bass_sub))),
            Triple(5, "🏟️", Pair(getString(R.string.audio_mode_stadium), getString(R.string.audio_mode_stadium_sub))),
            Triple(6, "🌙", Pair(getString(R.string.audio_mode_night), getString(R.string.audio_mode_night_sub)))
        )
        val items = modes.map { (modeIdx, emoji, texts) ->
            val isSel = (modeIdx == activeMode)
            PremiumMenuItem(
                iconEmoji = emoji,
                title = texts.first,
                subtitle = texts.second,
                isAccentHighlighted = isSel,
                onClick = {
                    playerMenuBackStack.clear()
                    PlaybackStore.setAudioPresetMode(this, modeIdx)
                    applyAudioEqualizerFromStore()
                    showToastIndicator("$emoji ${texts.first}")
                }
            )
        }
        showPlayerPremiumMenu("🎛️", getString(R.string.settings_hub_audio_title), getPlayerAudioModeName(activeMode), items)
    }

    private fun showAudioTracksDialog() {
        val audioTracks = mediaPlayer.audioTracks ?: emptyArray()
        val activeTrack = mediaPlayer.audioTrack
        val activeModeName = getPlayerAudioModeName(PlaybackStore.getAudioPresetMode(this))
        val items = mutableListOf(
            PremiumMenuItem(
                iconEmoji = "🎛️",
                title = getString(R.string.audio_preset_section_title),
                subtitle = activeModeName,
                isAccentHighlighted = true,
                onClick = {
                    playerMenuBackStack.addLast { showAudioTracksDialog() }
                    showPlayerSoundModesDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🎚️",
                title = getString(R.string.audio_fx_studio_title),
                subtitle = getString(R.string.audio_fx_studio_sub),
                isAccentHighlighted = false,
                onClick = {
                    showVexoAudioEffectsStudioDialog()
                }
            )
        )
        for (track in audioTracks) {
            val tName = track.name ?: "Track ${track.id}"
            items.add(
                PremiumMenuItem(
                    iconEmoji = "🔊",
                    title = tName,
                    subtitle = if (track.id == activeTrack) "✅ Active Audio Track" else "Audio Track #${track.id}",
                    isAccentHighlighted = (track.id == activeTrack),
                    onClick = {
                        mediaPlayer.audioTrack = track.id
                        showToastIndicator("🔊 $tName")
                    }
                )
            )
        }
        showPlayerPremiumMenu("🔊", getString(R.string.audio_tracks), activeModeName, items)
    }

    private fun showSpeedDialog() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val items = speeds.map { spd ->
            PremiumMenuItem(
                iconEmoji = "⚡",
                title = "${spd}x",
                subtitle = if (spd == 1.0f) "Normal Speed" else "Playback Rate ${spd}x",
                isAccentHighlighted = (spd == currentSpeed),
                onClick = {
                    currentSpeed = spd
                    mediaPlayer.rate = currentSpeed
                    binding.btnSpeed.text = "${currentSpeed}x"
                    showToastIndicator("⚡ ${currentSpeed}x")
                }
            )
        }
        showPlayerPremiumMenu("⚡", getString(R.string.playback_speed), "${currentSpeed}x", items)
    }

    private fun showToastIndicator(text: String) {
        binding.tvGestureIndicator.animate().cancel()
        binding.tvGestureIndicator.text = text
        if (binding.tvGestureIndicator.visibility != View.VISIBLE) {
            binding.tvGestureIndicator.alpha = 0f
            binding.tvGestureIndicator.scaleX = 0.88f
            binding.tvGestureIndicator.scaleY = 0.88f
            binding.tvGestureIndicator.visibility = View.VISIBLE
        }
        binding.tvGestureIndicator.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140L)
            .start()
        binding.tvGestureIndicator.removeCallbacks(hideIndicatorRunnable)
        binding.tvDraggableSubtitle.bringToFront()
        binding.tvGestureIndicator.postDelayed(hideIndicatorRunnable, 1100)
    }

    private val hideIndicatorRunnable = Runnable {
        binding.tvGestureIndicator.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(160L)
            .withEndAction {
                binding.tvGestureIndicator.visibility = View.GONE
            }
            .start()
    }

    private fun showControls() {
        if (isLocked) return
        isControlsVisible = true
        binding.controlsContainer.animate().cancel()
        binding.playerTopBar.animate().cancel()
        binding.playerBottomBar.animate().cancel()

        binding.controlsContainer.visibility = View.VISIBLE
        binding.controlsContainer.alpha = 0f
        binding.playerTopBar.translationY = -32f
        binding.playerBottomBar.translationY = 32f

        binding.controlsContainer.animate().alpha(1f).setDuration(200L).start()
        binding.playerTopBar.animate().translationY(0f).setDuration(220L).start()
        binding.playerBottomBar.animate().translationY(0f).setDuration(220L).start()
        scheduleHideControls()
    }

    private fun hideControls() {
        isControlsVisible = false
        binding.playerTopBar.animate().translationY(-32f).setDuration(180L).start()
        binding.playerBottomBar.animate().translationY(32f).setDuration(180L).start()
        binding.controlsContainer.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                if (!isControlsVisible) {
                    binding.controlsContainer.visibility = View.GONE
                }
            }
            .start()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun formatTime(timeMs: Long): String {
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (playlistUris.isNotEmpty() && currentPlaylistIndex in playlistUris.indices) {
            outState.putStringArrayList(EXTRA_PLAYLIST_URIS, ArrayList(playlistUris))
            outState.putStringArrayList(EXTRA_PLAYLIST_TITLES, ArrayList(playlistTitles))
            outState.putInt(EXTRA_PLAYLIST_INDEX, currentPlaylistIndex)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!viewsAttached) {
            attachVlcViewsSafely()
            viewsAttached = true
            changeSurfaceLayout()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!batteryReceiverRegistered) {
            try {
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                batteryReceiverRegistered = true
            } catch (_: Exception) {}
        }
        hideSystemBars()
        applyThemeAndSubtitleStyle()
        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)
        binding.videoSurfaceFrame.post {
            if (!viewsAttached) {
                attachVlcViewsSafely()
                viewsAttached = true
            }
            mediaPlayer.updateViewpoint(0f, 0f, 0f, 0f, false)
            changeSurfaceLayout()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            cancelActivePlayerGesturesSafely()
        } else {
            hideSystemBars()
        }
    }

    override fun onPause() {
        super.onPause()
        cancelActivePlayerGesturesSafely()
        saveCurrentPositionNow()
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!inPip && !isEnteringPipTransition && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onStop() {
        super.onStop()
        cancelActivePlayerGesturesSafely()
        cancelActiveSubtitleTranslation()
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            batteryReceiverRegistered = false
        }
        saveCurrentPositionNow()
        isEnteringPipTransition = false
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!inPip) {
            if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
                mediaPlayer.pause()
            }
            updateAutoPipParams(isPlaying = false)
            updateMediaSessionPlaybackState(isPlaying = false)
            abandonPlayerAudioFocus()
            handler.removeCallbacksAndMessages(null)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (viewsAttached && ::mediaPlayer.isInitialized) {
                detachVlcViewsSafely()
                viewsAttached = false
            }
            if (wasInPipMode) {
                stopPlaybackImmediatelyAndClosePip(finishActivity = true)
            }
        }
    }


    private fun observeSleepTimerState() {
        SleepTimerManager.attachCallbacks(
            volumeScaleCallback = { scale ->
                if (::mediaPlayer.isInitialized) {
                    runCatching { mediaPlayer.volume = (scale * 100f).toInt().coerceIn(5, 100) }
                }
            },
            expireCallback = {
                if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
                    saveCurrentPositionNow()
                    mediaPlayer.pause()
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play_modern)
                    Toast.makeText(this@PlayerActivity, getString(R.string.sleep_timer_triggered_toast), Toast.LENGTH_LONG).show()
                }
            }
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SleepTimerManager.state.collect { st ->
                    if (!st.isActive) {
                        binding.tvSleepTimerBadge.visibility = View.GONE
                    } else {
                        binding.tvSleepTimerBadge.visibility = View.VISIBLE
                        binding.tvSleepTimerBadge.text = if (st.mode == SleepTimerMode.END_OF_MEDIA) {
                            getString(R.string.sleep_timer_end_short)
                        } else {
                            SleepTimerManager.formatRemaining(st.remainingMs)
                        }
                    }
                }
            }
        }
    }

    private fun showEnhancedSleepTimerDialog() {
        val current = SleepTimerManager.state.value
        val items = mutableListOf<PremiumMenuItem>()
        if (current.isActive) {
            val rem = if (current.mode == SleepTimerMode.END_OF_MEDIA) {
                getString(R.string.sleep_timer_end_of_media)
            } else {
                SleepTimerManager.formatRemaining(current.remainingMs)
            }
            items.add(
                PremiumMenuItem("➕", getString(R.string.sleep_timer_extend_5m), rem, isAccentHighlighted = true) {
                    SleepTimerManager.extendByMinutes(5)
                    showToastIndicator(getString(R.string.sleep_timer_extend_5m))
                }
            )
            items.add(
                PremiumMenuItem("✕", getString(R.string.sleep_timer_cancel), null, isDanger = true) {
                    SleepTimerManager.cancel()
                    showToastIndicator(getString(R.string.sleep_timer_cancelled_toast))
                }
            )
        }
        val presets = listOf(15, 30, 45, 60, 90)
        for (min in presets) {
            items.add(
                PremiumMenuItem("🌙", "$min ${getString(R.string.sleep_timer_minutes)}", null) {
                    SleepTimerManager.startMinutes(min)
                    showToastIndicator("$min ${getString(R.string.sleep_timer_minutes)}")
                }
            )
        }
        items.add(
            PremiumMenuItem("🎬", getString(R.string.sleep_timer_end_of_media), null) {
                SleepTimerManager.startEndOfCurrentMedia()
                showToastIndicator(getString(R.string.sleep_timer_end_of_media))
            }
        )
        items.add(
            PremiumMenuItem("⏱️", getString(R.string.sleep_timer_custom), null) {
                val input = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    hint = "25"
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.sleep_timer_custom))
                    .setView(input)
                    .setPositiveButton(getString(R.string.save)) { _, _ ->
                        val mins = input.text.toString().toIntOrNull() ?: 0
                        if (mins > 0) {
                            SleepTimerManager.startMinutes(mins)
                            showToastIndicator("$mins ${getString(R.string.sleep_timer_minutes)}")
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        )
        showPlayerPremiumMenu("🌙", getString(R.string.sleep_timer_title), null, items)
    }

    private fun switchToBackgroundAudioOnlyMode() {
        val uri = currentVideoUri ?: return
        isSwitchingToAudioOnly = true
        runCatching { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        val pos = if (::mediaPlayer.isInitialized) mediaPlayer.time.coerceAtLeast(0L) else 0L
        saveCurrentPositionNow()
        if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
        detachVlcViewsSafely()
        VexoPlaybackService.startAudioOnlyFromVideo(
            context = this,
            uri = uri,
            title = currentVideoTitle,
            positionMs = pos,
            speed = currentSpeed
        )
        Toast.makeText(this, getString(R.string.audio_only_started_toast), Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun finish() {
        orientationProbeJob?.cancel()
        runCatching { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        try {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {}
        super.finish()
    }

    override fun onDestroy() {
        cancelActivePlayerGesturesSafely()
        cancelActiveSubtitleTranslation()
        if (pipReceiverRegistered) {
            try { unregisterReceiver(pipActionReceiver) } catch (_: Exception) {}
            pipReceiverRegistered = false
        }
        saveCurrentPositionNow()
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) { Log.w("PlayerActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        abandonPlayerAudioFocus()
        if (viewsAttached) {
            detachVlcViewsSafely()
            viewsAttached = false
        }
        activeVideoLoadJob?.cancel()
        if (isCurrentVideoFromVault) {
            isVaultVideoActivelyPlaying = false
        }
        val mpToRelease = if (::mediaPlayer.isInitialized) mediaPlayer else null
        val vlcToRelease = if (::libVLC.isInitialized) libVLC else null
        val pfdToClose = activePfd
        activePfd = null
        // Release LibVLC native resources on a background thread so returning from player never freezes UI
        Thread({
            runCatching { mpToRelease?.stop() }
            runCatching { mpToRelease?.release() }
            runCatching { vlcToRelease?.release() }
            runCatching { pfdToClose?.close() }
        }, "VPlayer-VlcRelease").apply { isDaemon = true }.start()
    }
}
