package com.pureplayer.video

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.FrameLayout
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
    private var externalUserAgent: String? = null
    private var externalHttpReferer: String? = null

    private var externalSubtitleCues = listOf<SubtitleCue>()
    private var subtitleSyncOffsetMs = 0L
    private var isDraggingSubtitle = false
    private var isPinchingSubtitle = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTransX = 0f
    private var dragStartTransY = 0f

    private var gestureVolumeAccumulator = 0f
    private val springOvershoot = OvershootInterpolator(1.6f)

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
            if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying && !isUserSeeking) {
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

        extractIntentData(intent)

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
                    binding.playerLoadingBar.visibility = View.GONE
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
        if (targetUri != null && isSafeExternalOrMediaUri(targetUri)) {
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
                setStroke(dp(1), Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)))
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
            setTextColor(Color.WHITE)
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
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
        }
        cardContainer.addView(divider)

        val dialog = AlertDialog.Builder(this).setView(outerScroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        for (item in items) {
            if (!item.sectionHeader.isNullOrBlank()) {
                val tvSec = TextView(this).apply {
                    text = item.sectionHeader
                    setTextColor(accent)
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(6), dp(8), dp(6), dp(4))
                }
                cardContainer.addView(tvSec)
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
                    item.isAccentHighlighted -> Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent))
                    else -> Color.parseColor("#1FFFFFFF")
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
                setTextColor(if (item.isDanger) Color.parseColor("#FDA4AF") else Color.WHITE)
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
            }
            textCol.addView(tvItemTitle)

            if (!item.subtitle.isNullOrBlank()) {
                val tvItemSub = TextView(this).apply {
                    text = item.subtitle
                    setTextColor(if (item.isAccentHighlighted) accent else Color.parseColor("#94A3B8"))
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
            }

            rowCard.addView(iconBadge)
            rowCard.addView(textCol)
            rowCard.addView(tvArrow)

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

            cardContainer.addView(rowCard)
        }

        val btnClose = TextView(this).apply {
            text = getString(R.string.close)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CBD5E1"))
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
                setColor(Color.parseColor("#18FFFFFF"))
            }
            setOnClickListener { dialog.dismiss() }
        }
        cardContainer.addView(btnClose)

        dialog.show()
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
        handler.postDelayed(hideVerticalSliderRunnable, 1200L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
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
                media.addOption(":network-caching=2500")
                media.addOption(":http-reconnect")
                val ua = externalUserAgent ?: "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                media.addOption(":http-user-agent=$ua")
                if (!externalHttpReferer.isNullOrEmpty()) {
                    media.addOption(":http-referrer=$externalHttpReferer")
                }
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

        if (!viewsAttached) {
            mediaPlayer.attachViews(binding.vlcVideoLayout, null, true, false)
            viewsAttached = true
        }
        mediaPlayer.play()
        mediaPlayer.videoScale = scaleTypes[currentScaleIndex]
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
                    SubtitleSearchEngine.readBytesBounded(it, 8 * 1024 * 1024)
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
                    view.background = GradientDrawable().apply {
                        cornerRadius = 18f
                        setColor(Color.parseColor("#CC0284C7"))
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!subtitleScaleDetector.isInProgress && isDraggingSubtitle && event.pointerCount == 1) {
                        val parentW = binding.playerRoot.width.toFloat().coerceAtLeast(1f)
                        val parentH = binding.playerRoot.height.toFloat().coerceAtLeast(1f)

                        val deltaX = event.rawX - dragStartRawX
                        val deltaY = event.rawY - dragStartRawY

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
        binding.btnOrientation.setTextColor(accent)
        binding.btnSpeed.setTextColor(accent)
        binding.btnSubFontUp.setTextColor(accent)
        binding.btnResetSubPos.setTextColor(accent)

        binding.seekBar.progressTintList = ColorStateList.valueOf(accent)
        binding.seekBar.thumbTintList = ColorStateList.valueOf(accent)

        // Style Top & Bottom Quick Pills as sleek glass capsules
        val pillViews = listOf(
            binding.btnSubSyncMinus,
            binding.btnSubSyncPlus,
            binding.btnSubFontDown,
            binding.btnSubFontUp,
            binding.btnResetSubPos,
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

    private fun setupControls() {
        attachSpringPressAnimation(
            binding.btnClosePlayer,
            binding.btnSubSyncMinus,
            binding.btnSubSyncPlus,
            binding.btnSubFontDown,
            binding.btnSubFontUp,
            binding.btnResetSubPos,
            binding.btnOrientation,
            binding.btnPip,
            binding.btnMorePro,
            binding.btnLock,
            binding.btnUnlock,
            binding.btnSubtitles,
            binding.btnAudioTrack,
            binding.btnRewind,
            binding.btnPlayPause,
            binding.btnForward,
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
        showVerticalSliderIndicator(isVolume = true, percent = percent)
    }

    private fun adjustBrightnessSmooth(delta: Float) {
        val layoutParams = window.attributes
        var brightness = layoutParams.screenBrightness
        if (brightness < 0f) brightness = 0.5f
        brightness = (brightness + delta * 1.4f).coerceIn(0.02f, 1.0f)
        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams
        val percent = (brightness * 100).toInt()
        showVerticalSliderIndicator(isVolume = false, percent = percent)
    }

    private fun showSubtitlesDialog() {
        val spuTracks = mediaPlayer.spuTracks ?: emptyArray()
        val preferredLangsStr = PlaybackStore.getPreferredSubtitleLanguages(this).joinToString(" → ") { it.uppercase() }
        val subSize = PlaybackStore.getSubSizeSp(this).toInt()
        val subBgOpacity = PlaybackStore.getSubBgOpacityPct(this)

        val items = mutableListOf(
            PremiumMenuItem(
                iconEmoji = "⚡",
                title = getString(R.string.sub_menu_auto_search),
                subtitle = currentVideoTitle,
                isAccentHighlighted = true,
                onClick = { runAutomaticMultiSourceSubtitleSearch(customQuery = null) }
            ),
            PremiumMenuItem(
                iconEmoji = "🔍",
                title = getString(R.string.sub_menu_web_search),
                subtitle = preferredLangsStr,
                onClick = { showCustomTitleAutoSearchDialog() }
            ),
            PremiumMenuItem(
                iconEmoji = "📂",
                title = getString(R.string.sub_menu_load_local),
                subtitle = ".SRT • .ASS • .VTT",
                onClick = { subtitlePickerLauncher.launch(arrayOf("*/*")) }
            ),
            PremiumMenuItem(
                iconEmoji = "🎨",
                title = getString(R.string.sub_menu_style),
                subtitle = "${subSize}sp • ${getString(R.string.style_bg_opacity_label, subBgOpacity)}",
                onClick = { showSubtitleLiveStyleDialog() }
            ),
            PremiumMenuItem(
                iconEmoji = "🌐",
                title = getString(R.string.sub_menu_languages, preferredLangsStr),
                subtitle = getString(R.string.sub_lang_dialog_title),
                onClick = { showPlayerSubtitleLanguagePicker() }
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
                        externalSubtitleCues = emptyList()
                        binding.tvDraggableSubtitle.visibility = View.GONE
                        mediaPlayer.spuTrack = -1
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

        showPlayerPremiumMenu(
            headerIcon = "💬",
            title = getString(R.string.subtitles),
            subtitle = preferredLangsStr,
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

        val tvTitle = TextView(this).apply {
            text = "🔍  ${getString(R.string.sub_menu_web_search)}"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }

        val input = EditText(this).apply {
            setText(defaultClean)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
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
            setOnClickListener { dialog.dismiss() }
        }

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
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 20)
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
            text = "شفافية نص الترجمة (Text Opacity): $currentTextOpacity%"
            setPadding(0, 12, 0, 4)
        }
        val textOpacitySeek = SeekBar(this).apply {
            max = 80
            progress = (currentTextOpacity - 20).coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentTextOpacity = 20 + p
                    tvTextOpacityLabel.text = "شفافية نص الترجمة (Text Opacity): $currentTextOpacity%"
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

        val tvColorLabel = TextView(this).apply {
            text = getString(R.string.style_color_label)
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
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showMoreProOptionsDialog() {
        val items = listOf(
            PremiumMenuItem("⏪", getString(R.string.pro_sync_earlier), "${subtitleSyncOffsetMs}ms", true) {
                adjustSubtitleSyncBy(-100L)
            },
            PremiumMenuItem("⏩", getString(R.string.pro_sync_later), "${subtitleSyncOffsetMs}ms", true) {
                adjustSubtitleSyncBy(100L)
            },
            PremiumMenuItem("🔄", getString(R.string.pro_sync_reset), "0ms") {
                subtitleSyncOffsetMs = 0L
                currentVideoUri?.let { PlaybackStore.saveVideoSubtitleSyncMs(this, it, 0L) }
                showToastIndicator(getString(R.string.sub_sync_offset_format, 0L))
            },
            PremiumMenuItem("🎯", getString(R.string.pro_reset_sub_pos), getString(R.string.sub_position_reset_toast)) {
                resetSubtitlePositionNow()
            },
            PremiumMenuItem("🌈", getString(R.string.pro_hdr_mode), "HDR10 / HLG Tonemapping") {
                showHdrModeSelectorDialog()
            },
            PremiumMenuItem("🌙", getString(R.string.pro_sleep_30), "30 Minutes") {
                handler.removeCallbacks(sleepTimerRunnable)
                handler.postDelayed(sleepTimerRunnable, 30 * 60 * 1000L)
                showToastIndicator(getString(R.string.pro_sleep_30))
            },
            PremiumMenuItem("🌙", getString(R.string.pro_sleep_60), "60 Minutes") {
                handler.removeCallbacks(sleepTimerRunnable)
                handler.postDelayed(sleepTimerRunnable, 60 * 60 * 1000L)
                showToastIndicator(getString(R.string.pro_sleep_60))
            },
            PremiumMenuItem("🖼️", getString(R.string.pro_pip), "Floating Window") {
                enterPipModeNow()
            }
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
                    PlaybackStore.setHdrModeIndex(this, idx)
                    showToastIndicator("$emoji $title")
                }
            )
        }
        showPlayerPremiumMenu("🌈", getString(R.string.pro_hdr_mode), null, items)
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
        val activeTrack = mediaPlayer.audioTrack
        val items = audioTracks.map { track ->
            val tName = track.name ?: "Track ${track.id}"
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
        }
        showPlayerPremiumMenu("🔊", getString(R.string.audio_tracks), null, items)
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

    private var viewsAttached = true

    override fun onStart() {
        super.onStart()
        if (!viewsAttached) {
            mediaPlayer.attachViews(binding.vlcVideoLayout, null, true, false)
            viewsAttached = true
            mediaPlayer.videoScale = scaleTypes[currentScaleIndex]
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        applyThemeAndSubtitleStyle()
        binding.vlcVideoLayout.post {
            if (!viewsAttached) {
                mediaPlayer.attachViews(binding.vlcVideoLayout, null, true, false)
                viewsAttached = true
            }
            mediaPlayer.updateViewpoint(0f, 0f, 0f, 0f, false)
            mediaPlayer.videoScale = scaleTypes[currentScaleIndex]
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

    override fun onStop() {
        super.onStop()
        saveCurrentPositionNow()
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!inPip && viewsAttached) {
            mediaPlayer.detachViews()
            viewsAttached = false
        }
    }

    override fun onDestroy() {
        saveCurrentPositionNow()
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (viewsAttached) {
            mediaPlayer.detachViews()
            viewsAttached = false
        }
        mediaPlayer.release()
        libVLC.release()
        try {
            activePfd?.close()
        } catch (_: Exception) {}
    }
}
