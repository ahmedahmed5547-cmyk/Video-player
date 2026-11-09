package com.pureplayer.video

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.BidiFormatter
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.pureplayer.video.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector
    private lateinit var subtitleScaleDetector: ScaleGestureDetector

    private var activePfd: ParcelFileDescriptor? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isLocked = false
    private var isControlsVisible = true
    private var isUserSeeking = false
    private var currentSpeed = 1.0f
    private var isHoldSpeedActive = false
    private var speedBeforeHold = 1.0f

    private var currentVideoUri: Uri? = null
    private var currentVideoTitle = "Video"
    private var pendingStartPositionMs = 0L
    private var hasAppliedInitialSeek = false

    // External Custom Draggable Subtitles & Sync Offset (100ms precision)
    private var externalSubtitleCues = listOf<SubtitleCue>()
    private var subtitleSyncOffsetMs = 0L
    private var isDraggingSubtitle = false
    private var isPinchingSubtitle = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTransX = 0f
    private var dragStartTransY = 0f

    private var gestureVolumeAccumulator = 0f

    private val orientationModes = intArrayOf(
        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    )
    private var currentOrientationIndex = 1

    private val scaleTypes = arrayOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT,
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
        MediaPlayer.ScaleType.SURFACE_FILL,
        MediaPlayer.ScaleType.SURFACE_16_9,
        MediaPlayer.ScaleType.SURFACE_4_3
    )
    private var currentScaleIndex = 0

    private val subtitlePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadExternalSubtitleUri(it) }
    }

    private val sleepTimerRunnable = Runnable {
        if (mediaPlayer.isPlaying) {
            saveCurrentPositionNow()
            mediaPlayer.pause()
            showToastIndicator(getString(R.string.pro_sleep_30))
        }
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (mediaPlayer.isPlaying && !isUserSeeking) {
                val length = mediaPlayer.length
                val time = mediaPlayer.time
                if (length > 0) {
                    val progress = ((time.toDouble() / length.toDouble()) * 1000).toInt()
                    binding.seekBar.progress = progress
                    binding.tvCurrentTime.text = formatTime(time)
                    binding.tvTotalTime.text = formatTime(length)
                    currentVideoUri?.let { uri ->
                        PlaybackStore.saveProgress(this@PlayerActivity, uri, currentVideoTitle, time, length)
                    }
                }
                updateDraggableSubtitleText(time)
            }
            handler.postDelayed(this, 200)
        }
    }

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        currentOrientationIndex = PlaybackStore.getDefaultOrientationIndex(this)
        requestedOrientation = orientationModes[currentOrientationIndex]
        binding.btnOrientation.text = getOrientationLabel(currentOrientationIndex)

        currentVideoUri = intent.data
        currentVideoTitle = intent.getStringExtra("VIDEO_TITLE") ?: currentVideoUri?.lastPathSegment ?: "Video"
        pendingStartPositionMs = intent.getLongExtra("START_POSITION_MS", 0L)
        binding.tvVideoTitle.text = currentVideoTitle

        // Restore per-video subtitle sync offset and cached subtitle if available
        currentVideoUri?.let { uri ->
            subtitleSyncOffsetMs = PlaybackStore.getVideoSubtitleSyncMs(this, uri)
            val cachedSubPath = PlaybackStore.getVideoSubtitleCachePath(this, uri)
            if (!cachedSubPath.isNullOrEmpty()) {
                val file = File(cachedSubPath)
                if (file.exists()) {
                    val cues = SubtitleParser.parseFromFile(file)
                    if (cues.isNotEmpty()) {
                        externalSubtitleCues = cues
                    }
                }
            }
        }

        applyThemeAndSubtitleStyle()
        setupFluidDraggableAndPinchSubtitle()

        // Restore percentage-based subtitle position whenever container size changes (fullscreen / rotation)
        binding.playerRoot.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != (oldRight - oldLeft) || h != (oldBottom - oldTop))) {
                binding.tvDraggableSubtitle.post {
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
            "--tone-mapping=$toneMapAlgo",
            "--subsdec-encoding", "UTF-8",
            "--freetype-rel-fontsize", "18"
        )
        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC)
        mediaPlayer.attachViews(binding.vlcVideoLayout, null, true, false)

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
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
                    binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                }
            }
        }

        val targetUri = currentVideoUri
        if (targetUri != null) {
            binding.vlcVideoLayout.post {
                restoreSubtitlePositionFromPercentage()
                startVideoPlaybackSafely(targetUri)
            }
        } else {
            finish()
        }

        setupControls()
        setupTouchGestures()
    }

    private fun getOrientationLabel(index: Int): String {
        return when (index) {
            0 -> getString(R.string.orientation_auto)
            1 -> getString(R.string.orientation_landscape)
            else -> getString(R.string.orientation_portrait)
        }
    }

    private fun getScaleLabel(index: Int): String {
        return when (index) {
            0 -> getString(R.string.scale_best_fit)
            1 -> getString(R.string.scale_fit_screen)
            2 -> getString(R.string.scale_fill)
            3 -> getString(R.string.scale_16_9)
            else -> getString(R.string.scale_4_3)
        }
    }

    private fun startVideoPlaybackSafely(uri: Uri) {
        try {
            activePfd?.close()
            activePfd = null

            val media = if (uri.scheme == "content") {
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

            media.setHWDecoderEnabled(true, false)
            mediaPlayer.media = media
            media.release()
        } catch (_: Exception) {
            try {
                val fallbackMedia = Media(libVLC, uri)
                fallbackMedia.setHWDecoderEnabled(true, false)
                mediaPlayer.media = fallbackMedia
                fallbackMedia.release()
            } catch (_: Exception) {}
        }

        mediaPlayer.play()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)
        scheduleHideControls()
    }

    private fun loadExternalSubtitleUri(uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null && bytes.isNotEmpty()) {
                val cues = withContext(Dispatchers.Default) {
                    SubtitleParser.parseFromBytes(bytes)
                }
                if (cues.isNotEmpty()) {
                    // Cache loaded subtitle file per-video in internal storage
                    currentVideoUri?.let { vUri ->
                        withContext(Dispatchers.IO) {
                            val cacheDir = File(filesDir, "subtitles_cache").apply { mkdirs() }
                            val localCopy = File(cacheDir, "local_${vUri.hashCode()}.srt")
                            localCopy.writeBytes(bytes)
                            PlaybackStore.saveVideoSubtitleCachePath(this@PlayerActivity, vUri, localCopy.absolutePath)
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

    private fun applyLoadedSubtitleCues(cues: List<SubtitleCue>) {
        externalSubtitleCues = cues
        mediaPlayer.spuTrack = -1
        binding.tvDraggableSubtitle.visibility = View.VISIBLE
        restoreSubtitlePositionFromPercentage()
        updateDraggableSubtitleText(mediaPlayer.time)
    }

    /**
     * Proper RTL rendering for Arabic subtitles & LTR for English subtitles.
     */
    private fun updateDraggableSubtitleText(currentTimeMs: Long) {
        if (externalSubtitleCues.isEmpty()) return
        if (isDraggingSubtitle || isPinchingSubtitle) return

        val adjustedTime = currentTimeMs - subtitleSyncOffsetMs
        val activeCue = externalSubtitleCues.firstOrNull {
            adjustedTime >= it.startMs && adjustedTime <= it.endMs
        }
        if (activeCue != null) {
            val rawText = activeCue.text
            val containsArabic = rawText.any { it in '\u0600'..'\u06FF' }
            binding.tvDraggableSubtitle.textDirection = if (containsArabic) {
                View.TEXT_DIRECTION_RTL
            } else {
                View.TEXT_DIRECTION_LTR
            }
            val formatted = if (containsArabic) {
                BidiFormatter.getInstance(true).unicodeWrap(rawText)
            } else {
                rawText
            }
            binding.tvDraggableSubtitle.text = formatted
            binding.tvDraggableSubtitle.visibility = View.VISIBLE
        } else {
            binding.tvDraggableSubtitle.visibility = View.GONE
        }
    }

    /**
     * Requirement 1 & 5:
     * - Touching the subtitle text makes it immediately draggable (no re-layout on move; uses translationX & translationY).
     * - Stays exactly where the user releases their finger and NEVER snaps back.
     * - Clamped strictly inside video bounds so it cannot go off-screen.
     * - Saved as a percentage of the container size (works across fullscreen, rotation, and any screen size).
     * - Supports Pinch-to-Zoom directly on the subtitle to resize font smoothly!
     */
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

        binding.tvDraggableSubtitle.setOnTouchListener { view, event ->
            if (isLocked) return@setOnTouchListener false
            view.parent?.requestDisallowInterceptTouchEvent(true)
            subtitleScaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingSubtitle = true
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartTransX = view.translationX
                    dragStartTransY = view.translationY
                    view.setBackgroundColor(Color.parseColor("#CC0284C7"))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!subtitleScaleDetector.isInProgress && isDraggingSubtitle && event.pointerCount == 1) {
                        val parentW = binding.playerRoot.width.toFloat().coerceAtLeast(1f)
                        val parentH = binding.playerRoot.height.toFloat().coerceAtLeast(1f)

                        val deltaX = event.rawX - dragStartRawX
                        val deltaY = event.rawY - dragStartRawY

                        // Compute safe clamping bounds so the subtitle never goes off-screen
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
                    }
                    true
                }
                else -> true
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

    private fun applyThemeAndSubtitleStyle() {
        val accent = PlaybackStore.getAccentColor(this)
        binding.btnPlayPause.imageTintList = ColorStateList.valueOf(accent)
        binding.btnUnlock.imageTintList = ColorStateList.valueOf(accent)
        binding.btnOrientation.setTextColor(accent)
        binding.btnSpeed.setTextColor(accent)
        binding.btnSubFontUp.setTextColor(accent)
        binding.btnResetSubPos.setTextColor(accent)

        val subSizeSp = PlaybackStore.getSubSizeSp(this)
        val subColor = PlaybackStore.getSubColorValue(this)
        val subBgColor = PlaybackStore.getSubBackgroundColor(this)
        val subOutlinePx = PlaybackStore.getSubOutlinePx(this)
        val subTypeface = PlaybackStore.getSubTypeface(this)

        binding.tvDraggableSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, subSizeSp)
        binding.tvDraggableSubtitle.setTextColor(subColor)
        binding.tvDraggableSubtitle.typeface = subTypeface
        binding.tvDraggableSubtitle.setBackgroundColor(subBgColor)
        binding.tvDraggableSubtitle.setShadowLayer(subOutlinePx.coerceAtLeast(1f), 2f, 2f, Color.BLACK)
        restoreSubtitlePositionFromPercentage()
    }

    private fun saveCurrentPositionNow() {
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

    private fun setupControls() {
        binding.btnClosePlayer.setOnClickListener {
            saveCurrentPositionNow()
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            if (mediaPlayer.isPlaying) {
                saveCurrentPositionNow()
                mediaPlayer.pause()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                mediaPlayer.play()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
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

        // On-Screen 100ms Subtitle Sync Buttons (-100ms / +100ms)
        binding.btnSubSyncMinus.setOnClickListener {
            adjustSubtitleSyncBy(-100L)
            scheduleHideControls()
        }

        binding.btnSubSyncPlus.setOnClickListener {
            adjustSubtitleSyncBy(100L)
            scheduleHideControls()
        }

        // On-Screen Reset Subtitle Position Button
        binding.btnResetSubPos.setOnClickListener {
            resetSubtitlePositionNow()
            scheduleHideControls()
        }

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

        binding.btnOrientation.setOnClickListener {
            currentOrientationIndex = (currentOrientationIndex + 1) % orientationModes.size
            requestedOrientation = orientationModes[currentOrientationIndex]
            val label = getOrientationLabel(currentOrientationIndex)
            binding.btnOrientation.text = label
            showToastIndicator(label)
            scheduleHideControls()
        }

        binding.btnPip.setOnClickListener {
            enterPipModeNow()
        }

        binding.btnAspectRatio.setOnClickListener {
            currentScaleIndex = (currentScaleIndex + 1) % scaleTypes.size
            mediaPlayer.videoScale = scaleTypes[currentScaleIndex]
            showToastIndicator(getScaleLabel(currentScaleIndex))
            scheduleHideControls()
        }

        binding.btnSpeed.setOnClickListener {
            showSpeedDialog()
        }

        binding.btnSubtitles.setOnClickListener {
            showSubtitlesDialog()
        }

        binding.btnAudioTrack.setOnClickListener {
            showAudioTracksDialog()
        }

        binding.btnMorePro.setOnClickListener {
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

    private fun setupTouchGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    binding.btnUnlock.visibility =
                        if (binding.btnUnlock.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    return true
                }
                if (isControlsVisible) {
                    hideControls()
                } else {
                    showControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return true
                val screenWidth = binding.playerRoot.width
                val length = mediaPlayer.length
                when {
                    e.x > (screenWidth * 0.60f) -> {
                        val newTime = (mediaPlayer.time + 15_000L).coerceAtMost(if (length > 0) length else Long.MAX_VALUE)
                        mediaPlayer.time = newTime
                        saveCurrentPositionNow()
                        showToastIndicator(getString(R.string.toast_forward_15))
                    }
                    e.x < (screenWidth * 0.40f) -> {
                        val newTime = (mediaPlayer.time - 15_000L).coerceAtLeast(0L)
                        mediaPlayer.time = newTime
                        saveCurrentPositionNow()
                        showToastIndicator(getString(R.string.toast_rewind_15))
                    }
                    else -> {
                        binding.btnPlayPause.performClick()
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isLocked || isDraggingSubtitle || isPinchingSubtitle) return
                if (mediaPlayer.isPlaying) {
                    isHoldSpeedActive = true
                    speedBeforeHold = currentSpeed
                    mediaPlayer.rate = 2.0f
                    showToastIndicator(getString(R.string.toast_speed_hold))
                }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || isDraggingSubtitle || isPinchingSubtitle || isHoldSpeedActive || e1 == null) return false

                val screenWidth = binding.playerRoot.width.toFloat().coerceAtLeast(1f)
                val screenHeight = binding.playerRoot.height.toFloat().coerceAtLeast(1f)

                if (abs(distanceY) >= abs(distanceX)) {
                    val delta = distanceY / screenHeight
                    if (e1.x > screenWidth / 2f) {
                        adjustVolumeSmooth(delta)
                    } else {
                        adjustBrightnessSmooth(delta)
                    }
                } else {
                    val seekDeltaMs = (-distanceX / screenWidth * 90_000L).toLong()
                    val length = mediaPlayer.length
                    if (length > 0) {
                        val newTime = (mediaPlayer.time + seekDeltaMs).coerceIn(0L, length)
                        mediaPlayer.time = newTime
                        saveCurrentPositionNow()
                        showToastIndicator("⏱️ ${formatTime(newTime)} / ${formatTime(length)}")
                    }
                }
                return true
            }
        })

        binding.playerRoot.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                gestureVolumeAccumulator = if (maxVol > 0) curVol.toFloat() / maxVol.toFloat() else 0.5f
            } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (isHoldSpeedActive) {
                    isHoldSpeedActive = false
                    mediaPlayer.rate = speedBeforeHold
                    showToastIndicator(getString(R.string.toast_speed_normal, speedBeforeHold.toString()))
                }
            }
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun adjustVolumeSmooth(delta: Float) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return
        gestureVolumeAccumulator = (gestureVolumeAccumulator + delta * 1.4f).coerceIn(0f, 1f)
        val targetVol = (gestureVolumeAccumulator * maxVol).toInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        val percent = (gestureVolumeAccumulator * 100).toInt()
        showToastIndicator(getString(R.string.toast_volume_format, percent))
    }

    private fun adjustBrightnessSmooth(delta: Float) {
        val layoutParams = window.attributes
        var brightness = layoutParams.screenBrightness
        if (brightness < 0f) brightness = 0.5f
        brightness = (brightness + delta * 1.4f).coerceIn(0.02f, 1.0f)
        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams
        val percent = (brightness * 100).toInt()
        showToastIndicator(getString(R.string.toast_brightness_format, percent))
    }

    private fun showSubtitlesDialog() {
        val spuTracks = mediaPlayer.spuTracks ?: emptyArray()
        val items = mutableListOf<String>()
        val trackIds = mutableListOf<Int>()

        val preferredLangsStr = PlaybackStore.getPreferredSubtitleLanguages(this).joinToString(",") { it.uppercase() }

        // 1. Instant 1-Click Automatic Search & Auto-Download (MX Player Style — No browser!)
        items.add(getString(R.string.sub_menu_auto_search))
        trackIds.add(-998)

        // 2. Search by typing custom movie/series name (if video file is named e.g. VID_1234.mp4)
        items.add(getString(R.string.sub_menu_web_search))
        trackIds.add(-994)

        // 3. Load local file from device
        items.add(getString(R.string.sub_menu_load_local))
        trackIds.add(-999)

        // 4. Preferred Subtitle Languages
        items.add(getString(R.string.sub_menu_languages, preferredLangsStr))
        trackIds.add(-993)

        // 5. Subtitle Styling (Font, Size, Color, Opacity, Outline)
        items.add(getString(R.string.sub_menu_style))
        trackIds.add(-996)

        // 6. Reset Subtitle Position
        items.add(getString(R.string.sub_menu_reset_pos))
        trackIds.add(-992)

        if (externalSubtitleCues.isNotEmpty() || mediaPlayer.spuTrack != -1) {
            items.add(getString(R.string.sub_menu_disable))
            trackIds.add(-995)
        }

        // Detect and list embedded subtitle tracks in the video
        for (track in spuTracks) {
            val tName = track.name ?: "Track ${track.id}"
            items.add(getString(R.string.sub_embedded_prefix, tName))
            trackIds.add(track.id)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.subtitles))
            .setItems(items.toTypedArray()) { _, which ->
                when (val selectedId = trackIds[which]) {
                    -998 -> runAutomaticMultiSourceSubtitleSearch(customQuery = null)
                    -994 -> showCustomTitleAutoSearchDialog()
                    -999 -> subtitlePickerLauncher.launch(arrayOf("*/*"))
                    -993 -> showPlayerSubtitleLanguagePicker()
                    -996 -> showSubtitleLiveStyleDialog()
                    -992 -> resetSubtitlePositionNow()
                    -995 -> {
                        externalSubtitleCues = emptyList()
                        binding.tvDraggableSubtitle.visibility = View.GONE
                        mediaPlayer.spuTrack = -1
                    }
                    else -> {
                        externalSubtitleCues = emptyList()
                        binding.tvDraggableSubtitle.visibility = View.GONE
                        mediaPlayer.spuTrack = selectedId
                    }
                }
            }
            .show()
    }

    /**
     * MX Player-style custom title input that still searches and downloads 100% automatically in the background (no website!).
     */
    private fun showCustomTitleAutoSearchDialog() {
        val defaultClean = currentVideoTitle
            .substringBeforeLast(".")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("(2160p|1080p|720p|480p|4K|BluRay|WEBRip|WEB-DL|x264|x265|HEVC|10bit)", RegexOption.IGNORE_CASE), "")
            .trim()

        val input = EditText(this).apply {
            setText(defaultClean)
            setPadding(48, 32, 48, 32)
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sub_menu_auto_search))
            .setView(input)
            .setPositiveButton(getString(R.string.subtitles)) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) {
                    runAutomaticMultiSourceSubtitleSearch(customQuery = q)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Requirement 2: 100% Background Automatic Multi-Source Subtitle Search & Auto-Download (Zero Browser!).
     * Searches OpenSubtitles-v3, OpenSubtitles-REST, Podnapisi, YIFY, SubDL, and Addic7ed in parallel,
     * downloads and extracts the best match automatically in the background, and activates it on the video!
     */
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

            when (outcome) {
                is SubtitleSearchOutcome.Success -> {
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
                is SubtitleSearchOutcome.Error -> {
                    val msgRes = when (outcome.errorType) {
                        SubtitleSearchOutcome.ErrorType.NO_INTERNET -> R.string.sub_err_no_internet
                        SubtitleSearchOutcome.ErrorType.TIMEOUT -> R.string.sub_err_timeout
                        SubtitleSearchOutcome.ErrorType.RATE_LIMIT -> R.string.sub_err_rate_limit
                        SubtitleSearchOutcome.ErrorType.CORRUPTED_FILE -> R.string.sub_err_corrupted
                        SubtitleSearchOutcome.ErrorType.NOT_FOUND -> R.string.sub_err_not_found
                    }
                    showToastIndicator(getString(msgRes))
                    // If automatic filename didn't match (e.g. file is named VID_001.mp4), let user type the movie name for automatic search!
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle(getString(R.string.subtitles))
                        .setMessage(getString(msgRes))
                        .setPositiveButton(getString(R.string.sub_menu_web_search)) { _, _ ->
                            showCustomTitleAutoSearchDialog()
                        }
                        .setNeutralButton(getString(R.string.sub_menu_load_local)) { _, _ ->
                            subtitlePickerLauncher.launch(arrayOf("*/*"))
                        }
                        .setNegativeButton(getString(R.string.close), null)
                        .show()
                }
            }
        }
    }

    private fun showManualSubtitleResultsDialog(results: List<SubtitleResultItem>) {
        if (results.size <= 1) return
        val displayItems = results.take(12).mapIndexed { idx, item ->
            val badge = when (item.matchQuality) {
                MatchQuality.HASH_MATCH -> "★ HASH"
                MatchQuality.EXACT_TITLE -> "✓ EXACT"
                MatchQuality.FUZZY_TITLE -> "~ MATCH"
            }
            "${idx + 1}. [${item.languageCode.uppercase()} • ${item.providerName} • $badge] ${item.title}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sub_results_dialog_title))
            .setItems(displayItems) { _, which ->
                val chosen = results[which]
                binding.playerLoadingBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val pair = SubtitleSearchEngine.downloadAndCacheSubtitle(this@PlayerActivity, chosen)
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
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showPlayerSubtitleLanguagePicker() {
        val langCodes = arrayOf("ar", "en", "fr", "es", "tr", "hi", "ur")
        val langLabels = arrayOf(
            getString(R.string.sub_lang_ar),
            getString(R.string.sub_lang_en),
            getString(R.string.sub_lang_fr),
            getString(R.string.sub_lang_es),
            getString(R.string.sub_lang_tr),
            getString(R.string.sub_lang_hi),
            getString(R.string.sub_lang_ur)
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
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSubtitleLiveStyleDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 20)
        }

        var currentSize = PlaybackStore.getSubSizeSp(this)
        var currentOpacity = PlaybackStore.getSubBgOpacityPct(this)
        var currentOutline = PlaybackStore.getSubOutlinePx(this)
        var selectedColorIdx = PlaybackStore.getSubColorIndex(this)

        val tvPreview = TextView(this).apply {
            text = getString(R.string.style_preview_sample)
            gravity = Gravity.CENTER
            setPadding(24, 14, 24, 14)
        }

        fun updateLive() {
            tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize)
            val c = when (selectedColorIdx) {
                1 -> Color.parseColor("#FACC15")
                2 -> Color.parseColor("#38BDF8")
                3 -> Color.parseColor("#4ADE80")
                else -> Color.WHITE
            }
            tvPreview.setTextColor(c)
            val alpha = ((currentOpacity / 100.0) * 255).toInt().coerceIn(0, 255)
            tvPreview.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
            tvPreview.setShadowLayer(currentOutline.coerceAtLeast(1f), 2f, 2f, Color.BLACK)
        }
        updateLive()

        val tvSizeLabel = TextView(this).apply {
            text = getString(R.string.style_size_label, currentSize.toInt())
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

        val tvOpacityLabel = TextView(this).apply {
            text = getString(R.string.style_bg_opacity_label, currentOpacity)
            setPadding(0, 12, 0, 4)
        }
        val opacitySeek = SeekBar(this).apply {
            max = 100
            progress = currentOpacity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentOpacity = p
                    tvOpacityLabel.text = getString(R.string.style_bg_opacity_label, currentOpacity)
                    updateLive()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvOutlineLabel = TextView(this).apply {
            text = getString(R.string.style_outline_label, currentOutline.toInt())
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

        val colorNames = arrayOf(
            getString(R.string.style_color_white),
            getString(R.string.style_color_yellow),
            getString(R.string.style_color_cyan),
            getString(R.string.style_color_green)
        )

        container.addView(tvPreview)
        container.addView(tvSizeLabel)
        container.addView(sizeSeek)
        container.addView(tvOpacityLabel)
        container.addView(opacitySeek)
        container.addView(tvOutlineLabel)
        container.addView(outlineSeek)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.style_dialog_title))
            .setView(container)
            .setSingleChoiceItems(colorNames, selectedColorIdx) { _, which ->
                selectedColorIdx = which
                updateLive()
            }
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                PlaybackStore.setSubSizeSp(this, currentSize)
                PlaybackStore.setSubBgOpacityPct(this, currentOpacity)
                PlaybackStore.setSubOutlinePx(this, currentOutline)
                PlaybackStore.setSubColorIndex(this, selectedColorIdx)
                applyThemeAndSubtitleStyle()
            }
            .setNeutralButton(getString(R.string.reset)) { _, _ ->
                resetSubtitlePositionNow()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showInAppSubtitleDownloaderDialog() {
        val cleanQuery = SubtitleSearchEngine.buildSearchRequest(
            this,
            currentVideoUri ?: Uri.EMPTY,
            currentVideoTitle,
            PlaybackStore.getPreferredSubtitleLanguages(this)
        ).cleanQuery

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
        }

        val etQuery = EditText(this).apply {
            setText(cleanQuery)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSearchSubdl = Button(this).apply { text = "SubDL" }
        val btnSearchOpenSub = Button(this).apply { text = "OpenSub" }

        searchRow.addView(etQuery)
        searchRow.addView(btnSearchSubdl)
        searchRow.addView(btnSearchOpenSub)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.65).toInt()
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }

        rootLayout.addView(searchRow)
        rootLayout.addView(progressBar)
        rootLayout.addView(webView)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.sub_menu_web_search))
            .setView(rootLayout)
            .setNeutralButton(getString(R.string.sub_menu_load_local)) { _, _ ->
                subtitlePickerLauncher.launch(arrayOf("*/*"))
            }
            .setNegativeButton(getString(R.string.close), null)
            .create()

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.endsWith(".srt", ignoreCase = true) || url.endsWith(".vtt", ignoreCase = true) || url.endsWith(".ass", ignoreCase = true) || url.endsWith(".zip", ignoreCase = true) || url.contains("dl.subdl.com") || url.contains("/subtitleserve/")) {
                    downloadAndApplySubtitleUrl(url, dialog)
                    return true
                }
                return false
            }
        }

        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            if (!url.isNullOrBlank()) {
                downloadAndApplySubtitleUrl(url, dialog)
            }
        })

        btnSearchSubdl.setOnClickListener {
            val q = Uri.encode(etQuery.text.toString().trim())
            progressBar.visibility = View.VISIBLE
            webView.loadUrl("https://subdl.com/search?query=$q")
        }

        btnSearchOpenSub.setOnClickListener {
            val q = Uri.encode(etQuery.text.toString().trim())
            val firstLang = PlaybackStore.getPreferredSubtitleLanguages(this).firstOrNull() ?: "ar"
            val iso3 = if (firstLang == "ar") "ara" else "eng"
            progressBar.visibility = View.VISIBLE
            webView.loadUrl("https://www.opensubtitles.org/$firstLang/search2/sublanguageid-$iso3/moviename-$q")
        }

        dialog.show()
        val initialQ = Uri.encode(cleanQuery)
        webView.loadUrl("https://subdl.com/search?query=$initialQ")
    }

    private fun downloadAndApplySubtitleUrl(downloadUrl: String, dialog: AlertDialog) {
        binding.playerLoadingBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val dummyItem = SubtitleResultItem(
                id = "web_${downloadUrl.hashCode()}",
                title = currentVideoTitle,
                providerName = "Web",
                languageCode = PlaybackStore.getPreferredSubtitleLanguages(this@PlayerActivity).firstOrNull() ?: "ar",
                downloadUrl = downloadUrl,
                formatExt = "srt",
                matchQuality = MatchQuality.EXACT_TITLE,
                rating = 10.0,
                downloadCount = 100
            )
            val pair = SubtitleSearchEngine.downloadAndCacheSubtitle(this@PlayerActivity, dummyItem)
            binding.playerLoadingBar.visibility = View.GONE
            if (pair != null && pair.second.isNotEmpty()) {
                currentVideoUri?.let { vUri ->
                    PlaybackStore.saveVideoSubtitleCachePath(this@PlayerActivity, vUri, pair.first.absolutePath)
                }
                applyLoadedSubtitleCues(pair.second)
                showToastIndicator(getString(R.string.sub_loaded_count_format, pair.second.size))
                dialog.dismiss()
            } else {
                showToastIndicator(getString(R.string.sub_err_corrupted))
            }
        }
    }

    private fun showMoreProOptionsDialog() {
        val proOptions = arrayOf(
            getString(R.string.pro_sync_earlier),
            getString(R.string.pro_sync_later),
            getString(R.string.pro_sync_reset),
            getString(R.string.pro_reset_sub_pos),
            getString(R.string.pro_hdr_mode),
            getString(R.string.pro_sleep_30),
            getString(R.string.pro_sleep_60),
            getString(R.string.pro_pip)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pro_tools_title))
            .setItems(proOptions) { _, which ->
                when (which) {
                    0 -> adjustSubtitleSyncBy(-100L)
                    1 -> adjustSubtitleSyncBy(100L)
                    2 -> {
                        subtitleSyncOffsetMs = 0L
                        currentVideoUri?.let { PlaybackStore.saveVideoSubtitleSyncMs(this, it, 0L) }
                        showToastIndicator(getString(R.string.sub_sync_offset_format, 0L))
                    }
                    3 -> resetSubtitlePositionNow()
                    4 -> showHdrModeSelectorDialog()
                    5 -> {
                        handler.removeCallbacks(sleepTimerRunnable)
                        handler.postDelayed(sleepTimerRunnable, 30 * 60 * 1000L)
                        showToastIndicator(getString(R.string.pro_sleep_30))
                    }
                    6 -> {
                        handler.removeCallbacks(sleepTimerRunnable)
                        handler.postDelayed(sleepTimerRunnable, 60 * 60 * 1000L)
                        showToastIndicator(getString(R.string.pro_sleep_60))
                    }
                    7 -> enterPipModeNow()
                }
            }
            .show()
    }

    private fun showHdrModeSelectorDialog() {
        val hdrModes = arrayOf(
            "🌟 HDR10 / HLG Cinema Natural (Hable)",
            "🔥 HDR Vivid Boost (Reinhard)",
            "⚖️ HDR-to-SDR Balanced Tonemapping (Mobius)"
        )
        val current = PlaybackStore.getHdrModeIndex(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pro_hdr_mode))
            .setSingleChoiceItems(hdrModes, current) { dialog, which ->
                PlaybackStore.setHdrModeIndex(this, which)
                showToastIndicator(hdrModes[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun enterPipModeNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.controlsContainer.visibility = View.GONE
        } else {
            showControls()
        }
    }

    private fun showAudioTracksDialog() {
        val audioTracks = mediaPlayer.audioTracks ?: emptyArray()
        if (audioTracks.isEmpty()) {
            showToastIndicator(getString(R.string.toast_no_extra_audio))
            return
        }
        val names = audioTracks.map { it.name ?: "Track ${it.id}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.audio_tracks))
            .setItems(names) { _, which ->
                mediaPlayer.audioTrack = audioTracks[which].id
            }
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.playback_speed))
            .setItems(labels) { _, which ->
                currentSpeed = speeds[which]
                mediaPlayer.rate = currentSpeed
                binding.btnSpeed.text = "${currentSpeed}x"
            }
            .show()
    }

    private fun showToastIndicator(text: String) {
        binding.tvGestureIndicator.text = text
        binding.tvGestureIndicator.visibility = View.VISIBLE
        binding.tvGestureIndicator.removeCallbacks(hideIndicatorRunnable)
        binding.tvDraggableSubtitle.bringToFront()
        binding.tvGestureIndicator.postDelayed(hideIndicatorRunnable, 1100)
    }

    private val hideIndicatorRunnable = Runnable {
        binding.tvGestureIndicator.visibility = View.GONE
    }

    private fun showControls() {
        if (isLocked) return
        isControlsVisible = true
        binding.controlsContainer.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun hideControls() {
        isControlsVisible = false
        binding.controlsContainer.visibility = View.GONE
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

    override fun onPause() {
        super.onPause()
        saveCurrentPositionNow()
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!inPip && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        saveCurrentPositionNow()
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer.release()
        libVLC.release()
        try {
            activePfd?.close()
        } catch (_: Exception) {}
    }
}
