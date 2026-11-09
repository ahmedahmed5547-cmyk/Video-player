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
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pureplayer.video.databinding.ActivityPlayerBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector

    private var activePfd: ParcelFileDescriptor? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val handler = Handler(Looper.getMainLooper())
    private var isLocked = false
    private var isControlsVisible = true
    private var isUserSeeking = false
    private var currentSpeed = 1.0f
    private var isHoldSpeedActive = false
    private var speedBeforeHold = 1.0f

    private var currentVideoUri: Uri? = null
    private var currentVideoTitle = "فيديو"
    private var pendingStartPositionMs = 0L
    private var hasAppliedInitialSeek = false

    // External Custom Draggable Subtitles & Sync Offset
    private var externalSubtitleCues = listOf<SubtitleCue>()
    private var subtitleSyncOffsetMs = 0L
    private var isDraggingSubtitle = false
    private var dragInitialY = 0f
    private var dragInitialTranslationY = 0f

    // Gesture accumulators for smooth Volume & Brightness control
    private var gestureVolumeAccumulator = 0f

    private val orientationModes = intArrayOf(
        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    )
    private val orientationLabels = arrayOf(
        "🔄 تلقائي (Auto)",
        "🔒 عرضي (Landscape)",
        "📱 طولي (Portrait)"
    )
    private var currentOrientationIndex = 1

    private val scaleTypes = arrayOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT,
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
        MediaPlayer.ScaleType.SURFACE_FILL,
        MediaPlayer.ScaleType.SURFACE_16_9,
        MediaPlayer.ScaleType.SURFACE_4_3
    )
    private val scaleNames = arrayOf("تلقائي", "احتواء الشاشة", "ملء الشاشة", "16:9", "4:3")
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
            showToastIndicator("⏰ توقف الفيديو تلقائياً (مؤقت النوم)")
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
            handler.postDelayed(this, 250)
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
        binding.btnOrientation.text = orientationLabels[currentOrientationIndex]

        applyThemeAndSubtitleStyle()
        setupDraggableSubtitleTouch()

        // High-performance 64-bit flagship decoding options
        // Flagship 64-bit HDR10 / HLG / 10-bit HEVC & AV1 Hardware Rendering + Dynamic Tonemapping
        val hdrMode = PlaybackStore.getHdrModeIndex(this)
        val toneMapAlgo = when (hdrMode) {
            1 -> "reinhard" // Vivid HDR Boost
            2 -> "mobius"   // Balanced SDR Tonemapping
            else -> "hable" // Cinema Natural HDR10 / HLG (Filmic Hable)
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
                    if (!hasAppliedInitialSeek) {
                        hasAppliedInitialSeek = true
                        if (pendingStartPositionMs > 0L) {
                            mediaPlayer.time = pendingStartPositionMs
                            showToastIndicator("تم الاستئناف عند ${formatTime(pendingStartPositionMs)}")
                        }
                    }
                }
                MediaPlayer.Event.Paused -> {
                    binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                }
            }
        }

        currentVideoUri = intent.data
        currentVideoTitle = intent.getStringExtra("VIDEO_TITLE") ?: currentVideoUri?.lastPathSegment ?: "فيديو"
        pendingStartPositionMs = intent.getLongExtra("START_POSITION_MS", 0L)
        binding.tvVideoTitle.text = currentVideoTitle

        val targetUri = currentVideoUri
        if (targetUri != null) {
            binding.vlcVideoLayout.post {
                startVideoPlaybackSafely(targetUri)
            }
        } else {
            finish()
        }

        setupControls()
        setupTouchGestures()
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

            // Enable Hardware Acceleration with automatic Software fallback (true, false) so every codec plays!
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
        val parsed = SubtitleParser.parseFromUri(this, uri)
        if (parsed.isNotEmpty()) {
            externalSubtitleCues = parsed
            mediaPlayer.spuTrack = -1
            binding.tvDraggableSubtitle.visibility = View.VISIBLE
            binding.tvDraggableSubtitle.text = "تم تفعيل الترجمة (اضغط مطولاً لتحريكها لأعلى أو أسفل)"
            showToastIndicator("تم تحميل الترجمة (${parsed.size} جملة) — اضغط مطولاً عليها لتحريك مكانها")
        } else {
            mediaPlayer.addSlave(0, uri, true)
            showToastIndicator("تم تحميل ملف الترجمة")
        }
    }

    private fun updateDraggableSubtitleText(currentTimeMs: Long) {
        if (externalSubtitleCues.isEmpty()) return
        if (isDraggingSubtitle) return

        val adjustedTime = currentTimeMs - subtitleSyncOffsetMs
        val activeCue = externalSubtitleCues.firstOrNull {
            adjustedTime >= it.startMs && adjustedTime <= it.endMs
        }
        if (activeCue != null) {
            binding.tvDraggableSubtitle.text = activeCue.text
            binding.tvDraggableSubtitle.visibility = View.VISIBLE
        } else {
            binding.tvDraggableSubtitle.visibility = View.GONE
        }
    }

    private fun setupDraggableSubtitleTouch() {
        binding.tvDraggableSubtitle.translationY = PlaybackStore.getSubVerticalOffsetPx(this)

        binding.tvDraggableSubtitle.setOnLongClickListener {
            if (isLocked) return@setOnLongClickListener false
            isDraggingSubtitle = true
            binding.tvDraggableSubtitle.setBackgroundColor(Color.parseColor("#CC0284C7"))
            showToastIndicator("↕️ اسحب الترجمة لأعلى أو لأسفل لتغيير مكانها")
            true
        }

        binding.tvDraggableSubtitle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitialY = event.rawY
                    dragInitialTranslationY = view.translationY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingSubtitle) {
                        val deltaY = event.rawY - dragInitialY
                        val maxUp = -(binding.playerRoot.height * 0.78f)
                        val newTransY = (dragInitialTranslationY + deltaY).coerceIn(maxUp, 120f)
                        view.translationY = newTransY
                        PlaybackStore.setSubVerticalOffsetPx(this, newTransY)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingSubtitle) {
                        isDraggingSubtitle = false
                        applyThemeAndSubtitleStyle()
                        showToastIndicator("✅ تم تثبيت مكان الترجمة")
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun applyThemeAndSubtitleStyle() {
        val accent = PlaybackStore.getAccentColor(this)
        binding.btnPlayPause.imageTintList = ColorStateList.valueOf(accent)
        binding.btnUnlock.imageTintList = ColorStateList.valueOf(accent)
        binding.btnOrientation.setTextColor(accent)
        binding.btnSpeed.setTextColor(accent)
        binding.btnSubFontUp.setTextColor(accent)

        val subSizeSp = PlaybackStore.getSubSizeSp(this)
        val subColor = PlaybackStore.getSubColorValue(this)
        val subBg = PlaybackStore.isSubBgEnabled(this)

        binding.tvDraggableSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, subSizeSp)
        binding.tvDraggableSubtitle.setTextColor(subColor)
        binding.tvDraggableSubtitle.setBackgroundColor(
            if (subBg) Color.parseColor("#AA000000") else Color.TRANSPARENT
        )
        binding.tvDraggableSubtitle.translationY = PlaybackStore.getSubVerticalOffsetPx(this)
    }

    private fun saveCurrentPositionNow() {
        val uri = currentVideoUri ?: return
        val time = mediaPlayer.time
        val length = mediaPlayer.length
        if (time > 0L) {
            PlaybackStore.saveProgress(this, uri, currentVideoTitle, time, length)
        }
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
            val newSize = (PlaybackStore.getSubSizeSp(this) + 2f).coerceAtMost(42f)
            PlaybackStore.setSubSizeSp(this, newSize)
            applyThemeAndSubtitleStyle()
            binding.tvDraggableSubtitle.visibility = View.VISIBLE
            if (binding.tvDraggableSubtitle.text.isNullOrEmpty()) {
                binding.tvDraggableSubtitle.text = "معاينة حجم خط الترجمة (${newSize.toInt()}sp)"
            }
            showToastIndicator("حجم الترجمة: ${newSize.toInt()}sp")
            scheduleHideControls()
        }

        binding.btnSubFontDown.setOnClickListener {
            val newSize = (PlaybackStore.getSubSizeSp(this) - 2f).coerceAtLeast(14f)
            PlaybackStore.setSubSizeSp(this, newSize)
            applyThemeAndSubtitleStyle()
            binding.tvDraggableSubtitle.visibility = View.VISIBLE
            if (binding.tvDraggableSubtitle.text.isNullOrEmpty()) {
                binding.tvDraggableSubtitle.text = "معاينة حجم خط الترجمة (${newSize.toInt()}sp)"
            }
            showToastIndicator("حجم الترجمة: ${newSize.toInt()}sp")
            scheduleHideControls()
        }

        // Rewind 15 Seconds
        binding.btnRewind.setOnClickListener {
            val newTime = (mediaPlayer.time - 15_000L).coerceAtLeast(0L)
            mediaPlayer.time = newTime
            saveCurrentPositionNow()
            showToastIndicator("⏪ -15 ثانية")
            scheduleHideControls()
        }

        // Forward 15 Seconds
        binding.btnForward.setOnClickListener {
            val length = mediaPlayer.length
            val newTime = (mediaPlayer.time + 15_000L).coerceAtMost(if (length > 0) length else Long.MAX_VALUE)
            mediaPlayer.time = newTime
            saveCurrentPositionNow()
            showToastIndicator("⏩ +15 ثانية")
            scheduleHideControls()
        }

        binding.btnLock.setOnClickListener {
            isLocked = true
            binding.controlsContainer.visibility = View.GONE
            binding.btnUnlock.visibility = View.VISIBLE
            showToastIndicator("🔒 الشاشة مقفلة")
        }

        binding.btnUnlock.setOnClickListener {
            isLocked = false
            binding.btnUnlock.visibility = View.GONE
            showControls()
            showToastIndicator("🔓 تم فتح القفل")
        }

        binding.btnOrientation.setOnClickListener {
            currentOrientationIndex = (currentOrientationIndex + 1) % orientationModes.size
            requestedOrientation = orientationModes[currentOrientationIndex]
            binding.btnOrientation.text = orientationLabels[currentOrientationIndex]
            showToastIndicator("وضع الشاشة: ${orientationLabels[currentOrientationIndex]}")
            scheduleHideControls()
        }

        binding.btnPip.setOnClickListener {
            enterPipModeNow()
        }

        binding.btnAspectRatio.setOnClickListener {
            currentScaleIndex = (currentScaleIndex + 1) % scaleTypes.size
            mediaPlayer.videoScale = scaleTypes[currentScaleIndex]
            showToastIndicator("الأبعاد: ${scaleNames[currentScaleIndex]}")
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

    /**
     * Complete Gesture System:
     * - Double Tap RIGHT side of screen -> Forward +15 seconds
     * - Double Tap LEFT side of screen -> Rewind -15 seconds
     * - Double Tap CENTER -> Play / Pause
     * - Swipe on RIGHT half -> Volume Up / Down
     * - Swipe on LEFT half -> Brightness Up / Down
     * - Horizontal Swipe -> Smooth video seeking
     * - Long Press on Video -> 2.0x Turbo Speed while holding finger down!
     */
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
                    // Right side of screen -> Forward +15 seconds
                    e.x > (screenWidth * 0.60f) -> {
                        val newTime = (mediaPlayer.time + 15_000L).coerceAtMost(if (length > 0) length else Long.MAX_VALUE)
                        mediaPlayer.time = newTime
                        saveCurrentPositionNow()
                        showToastIndicator("⏩ +15 ثانية")
                    }
                    // Left side of screen -> Rewind -15 seconds
                    e.x < (screenWidth * 0.40f) -> {
                        val newTime = (mediaPlayer.time - 15_000L).coerceAtLeast(0L)
                        mediaPlayer.time = newTime
                        saveCurrentPositionNow()
                        showToastIndicator("⏪ -15 ثانية")
                    }
                    else -> {
                        binding.btnPlayPause.performClick()
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isLocked || isDraggingSubtitle) return
                if (mediaPlayer.isPlaying) {
                    isHoldSpeedActive = true
                    speedBeforeHold = currentSpeed
                    mediaPlayer.rate = 2.0f
                    showToastIndicator("⚡ سرعة مضاعفة 2.0x (استمر بالضغط)")
                }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || isDraggingSubtitle || isHoldSpeedActive || e1 == null) return false

                val screenWidth = binding.playerRoot.width.toFloat().coerceAtLeast(1f)
                val screenHeight = binding.playerRoot.height.toFloat().coerceAtLeast(1f)

                if (abs(distanceY) >= abs(distanceX)) {
                    // Vertical Swipe: Right Half = Volume, Left Half = Brightness
                    val delta = distanceY / screenHeight
                    if (e1.x > screenWidth / 2f) {
                        adjustVolumeSmooth(delta)
                    } else {
                        adjustBrightnessSmooth(delta)
                    }
                } else {
                    // Horizontal Swipe: Smooth Video Seek
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
                    showToastIndicator("السرعة العادية: ${speedBeforeHold}x")
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
        showToastIndicator("🔊 الصوت: $percent%")
    }

    private fun adjustBrightnessSmooth(delta: Float) {
        val layoutParams = window.attributes
        var brightness = layoutParams.screenBrightness
        if (brightness < 0f) brightness = 0.5f
        brightness = (brightness + delta * 1.4f).coerceIn(0.02f, 1.0f)
        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams
        val percent = (brightness * 100).toInt()
        showToastIndicator("☀️ الإضاءة: $percent%")
    }

    private fun showSubtitlesDialog() {
        val spuTracks = mediaPlayer.spuTracks ?: emptyArray()
        val items = mutableListOf<String>()
        val trackIds = mutableListOf<Int>()

        items.add(getString(R.string.in_app_sub_search))
        trackIds.add(-998)

        items.add(getString(R.string.load_external_sub))
        trackIds.add(-999)

        items.add(getString(R.string.customize_subtitles))
        trackIds.add(-996)

        if (externalSubtitleCues.isNotEmpty()) {
            items.add(getString(R.string.disable_subtitles))
            trackIds.add(-995)
        }

        for (track in spuTracks) {
            items.add(track.name ?: "Track ${track.id}")
            trackIds.add(track.id)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.subtitles))
            .setItems(items.toTypedArray()) { _, which ->
                when (val selectedId = trackIds[which]) {
                    -998 -> showInAppSubtitleDownloaderDialog()
                    -999 -> subtitlePickerLauncher.launch(arrayOf("*/*"))
                    -996 -> showQuickSubtitleColorDialog()
                    -995 -> {
                        externalSubtitleCues = emptyList()
                        binding.tvDraggableSubtitle.visibility = View.GONE
                        mediaPlayer.spuTrack = -1
                        showToastIndicator("تم إيقاف الترجمة")
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
     * Built-in In-App Subtitle Search & Direct Downloader inside the video player!
     * Opens an embedded browser window right over the player, searches Subdl / OpenSubtitles in Arabic,
     * and when the user clicks ANY download button (.srt or .zip), automatically downloads, unzips in memory,
     * and activates the Arabic subtitle on the video immediately!
     */
    private fun showInAppSubtitleDownloaderDialog() {
        val cleanQuery = currentVideoTitle
            .substringBeforeLast(".")
            .replace(Regex("[._-]"), " ")
            .replace(Regex("(1080p|720p|480p|2160p|4K|BluRay|WEB-DL|WEBRip|x264|x265|HEVC|10bit)", RegexOption.IGNORE_CASE), "")
            .trim()

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
            hint = "اسم الفيلم أو المسلسل..."
            setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSearchSubdl = Button(this).apply {
            text = "Subdl"
        }

        val btnSearchOpenSub = Button(this).apply {
            text = "OpenSub"
        }

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
            .setTitle("🔍 بحث وتنزيل مباشر للترجمة (اضغط تحميل في الموقع وسيتم تفعيلها تلقائياً)")
            .setView(rootLayout)
            .setNeutralButton("اختيار ملف من الجهاز") { _, _ ->
                subtitlePickerLauncher.launch(arrayOf("*/*"))
            }
            .setNegativeButton("إغلاق", null)
            .create()

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.endsWith(".srt", ignoreCase = true) || url.endsWith(".zip", ignoreCase = true) || url.contains("dl.subdl.com") || url.contains("/subtitleserve/")) {
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
            progressBar.visibility = View.VISIBLE
            webView.loadUrl("https://www.opensubtitles.org/ar/search2/sublanguageid-ara/moviename-$q")
        }

        dialog.show()
        val initialQ = Uri.encode(cleanQuery)
        webView.loadUrl("https://subdl.com/search?query=$initialQ")
    }

    private fun downloadAndApplySubtitleUrl(downloadUrl: String, dialog: AlertDialog) {
        showToastIndicator("⏳ جاري تنزيل وفك ضغط الترجمة تلقائياً...")
        bgExecutor.execute {
            try {
                val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 12_000
                conn.readTimeout = 12_000

                val rawBytes = conn.inputStream.use { it.readBytes() }
                var srtBytes: ByteArray? = null

                // Check if downloaded file is a ZIP archive (PK header 0x50 0x4B)
                if (rawBytes.size > 4 && rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()) {
                    ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name.lowercase(Locale.ROOT)
                            if (!entry.isDirectory && (name.endsWith(".srt") || name.endsWith(".ass") || name.endsWith(".ssa") || name.endsWith(".sub"))) {
                                val buffer = ByteArrayOutputStream()
                                zis.copyTo(buffer)
                                srtBytes = buffer.toByteArray()
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                } else {
                    srtBytes = rawBytes
                }

                val finalBytes = srtBytes
                if (finalBytes != null && finalBytes.isNotEmpty()) {
                    val cues = SubtitleParser.parseFromBytes(finalBytes)
                    handler.post {
                        if (cues.isNotEmpty()) {
                            externalSubtitleCues = cues
                            mediaPlayer.spuTrack = -1
                            binding.tvDraggableSubtitle.visibility = View.VISIBLE
                            binding.tvDraggableSubtitle.text = "✅ تم تنزيل وتفعيل الترجمة بنجاح!"
                            showToastIndicator("✅ تم تنزيل وتفعيل الترجمة (${cues.size} جملة)")
                            dialog.dismiss()
                        } else {
                            showToastIndicator("⚠️ تعذر قراءة ملف الترجمة، جرب ملفاً آخر")
                        }
                    }
                }
            } catch (_: Exception) {
                handler.post {
                    showToastIndicator("⚠️ تعذر التحميل المباشر، جرب رابطاً آخر")
                }
            }
        }
    }

    private fun showMoreProOptionsDialog() {
        val proOptions = arrayOf(
            "🌈 إعدادات عرض الألوان الفائقة (HDR10 / HLG / Dolby)",
            "⏱️ تقديم توقيت الترجمة (+0.5 ثانية)",
            "⏱️ تأخير توقيت الترجمة (-0.5 ثانية)",
            "⏰ مؤقت النوم (إيقاف بعد 30 دقيقة)",
            "⏰ مؤقت النوم (إيقاف بعد 60 دقيقة)",
            "🖼️ تشغيل في نافذة عائمة (Picture-in-Picture)",
            "↕️ إعادة مكان الترجمة للوضع الافتراضي"
        )
        AlertDialog.Builder(this)
            .setTitle("🛠️ أدوات التحكم الاحترافية (HDR & Pro)")
            .setItems(proOptions) { _, which ->
                when (which) {
                    0 -> showHdrModeSelectorDialog()
                    1 -> {
                        subtitleSyncOffsetMs += 500L
                        showToastIndicator("مزامنة الترجمة: +${subtitleSyncOffsetMs / 1000.0} ثانية")
                    }
                    2 -> {
                        subtitleSyncOffsetMs -= 500L
                        showToastIndicator("مزامنة الترجمة: ${subtitleSyncOffsetMs / 1000.0} ثانية")
                    }
                    3 -> {
                        handler.removeCallbacks(sleepTimerRunnable)
                        handler.postDelayed(sleepTimerRunnable, 30 * 60 * 1000L)
                        showToastIndicator("⏰ تم ضبط مؤقت النوم بعد 30 دقيقة")
                    }
                    4 -> {
                        handler.removeCallbacks(sleepTimerRunnable)
                        handler.postDelayed(sleepTimerRunnable, 60 * 60 * 1000L)
                        showToastIndicator("⏰ تم ضبط مؤقت النوم بعد 60 دقيقة")
                    }
                    5 -> enterPipModeNow()
                    6 -> {
                        PlaybackStore.setSubVerticalOffsetPx(this, 0f)
                        applyThemeAndSubtitleStyle()
                        showToastIndicator("تمت إعادة مكان الترجمة للأسفل")
                    }
                }
            }
            .show()
    }

    private fun showHdrModeSelectorDialog() {
        val hdrModes = arrayOf(
            "🌟 سينمائي أصلي (HDR10 / HLG Native Hable)",
            "🔥 ألوان مشبعة زاهية (HDR Vivid Boost)",
            "⚖️ موازنة سطوع تلقائية للشاشات العادية (HDR-to-SDR Tonemapping)"
        )
        val current = PlaybackStore.getHdrModeIndex(this)
        AlertDialog.Builder(this)
            .setTitle("🌈 وضع معالجة ألوان HDR (10-bit)")
            .setSingleChoiceItems(hdrModes, current) { dialog, which ->
                PlaybackStore.setHdrModeIndex(this, which)
                showToastIndicator("✅ تم تفعيل وضع: ${hdrModes[which]}")
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
            } catch (_: Exception) {
                showToastIndicator("النافذة العائمة غير مدعومة في هذا الوضع")
            }
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

    private fun showQuickSubtitleColorDialog() {
        val colors = arrayOf(
            "⚪ خط أبيض مع خلفية شفافة",
            "🟡 خط أصفر سينمائي مع خلفية",
            "🔵 خط سماوي",
            "🟢 خط أخضر",
            "↕️ إعادة مكان الترجمة للأسفل"
        )
        AlertDialog.Builder(this)
            .setTitle("لون ومكان الترجمة (أو اضغط مطولاً على الترجمة لتحريكها)")
            .setItems(colors) { _, which ->
                if (which == 4) {
                    PlaybackStore.setSubVerticalOffsetPx(this, 0f)
                } else {
                    PlaybackStore.setSubColorIndex(this, which)
                }
                applyThemeAndSubtitleStyle()
                binding.tvDraggableSubtitle.visibility = View.VISIBLE
                if (binding.tvDraggableSubtitle.text.isNullOrEmpty()) {
                    binding.tvDraggableSubtitle.text = "معاينة شكل الترجمة (اضغط مطولاً عليها لتحريكها لأعلى أو أسفل)"
                }
            }
            .show()
    }

    private fun showAudioTracksDialog() {
        val audioTracks = mediaPlayer.audioTracks ?: emptyArray()
        if (audioTracks.isEmpty()) {
            showToastIndicator("لا توجد مسارات صوتية إضافية")
            return
        }
        val names = audioTracks.map { it.name ?: "مسار ${it.id}" }.toTypedArray()
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
        bgExecutor.shutdownNow()
        mediaPlayer.release()
        libVLC.release()
        try {
            activePfd?.close()
        } catch (_: Exception) {}
    }
}
