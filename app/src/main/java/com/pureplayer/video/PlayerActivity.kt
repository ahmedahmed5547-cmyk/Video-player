package com.pureplayer.video

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
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
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector

    private val handler = Handler(Looper.getMainLooper())
    private var isLocked = false
    private var isControlsVisible = true
    private var isUserSeeking = false
    private var currentSpeed = 1.0f
    private var currentVideoUri: Uri? = null
    private var currentVideoTitle = "فيديو"
    private var pendingStartPositionMs = 0L
    private var hasAppliedInitialSeek = false

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
        uri?.let {
            mediaPlayer.addSlave(0, it, true)
            showToastIndicator("تم تحميل ملف الترجمة بنجاح")
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
                    // Save current playback position automatically every 2 seconds
                    currentVideoUri?.let { uri ->
                        PlaybackStore.saveProgress(this@PlayerActivity, uri, currentVideoTitle, time, length)
                    }
                }
            }
            handler.postDelayed(this, 500)
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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        requestedOrientation = orientationModes[currentOrientationIndex]
        binding.btnOrientation.text = orientationLabels[currentOrientationIndex]

        val options = arrayListOf(
            "--avcodec-skiploopfilter", "1",
            "--subsdec-encoding", "UTF-8",
            "--freetype-rel-fontsize", "18"
        )
        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC)
        mediaPlayer.attachViews(binding.vlcVideoLayout, null, true, false)

        // Listen for Playing event to apply exact start/resume position cleanly
        mediaPlayer.setEventListener { event ->
            if (event.type == MediaPlayer.Event.Playing && !hasAppliedInitialSeek) {
                hasAppliedInitialSeek = true
                if (pendingStartPositionMs > 0L) {
                    mediaPlayer.time = pendingStartPositionMs
                    showToastIndicator("تم الاستئناف عند ${formatTime(pendingStartPositionMs)}")
                }
            }
        }

        currentVideoUri = intent.data
        currentVideoTitle = intent.getStringExtra("VIDEO_TITLE") ?: currentVideoUri?.lastPathSegment ?: "فيديو"
        pendingStartPositionMs = intent.getLongExtra("START_POSITION_MS", 0L)
        binding.tvVideoTitle.text = currentVideoTitle

        val targetUri = currentVideoUri
        if (targetUri != null) {
            playVideo(targetUri)
        } else {
            finish()
        }

        setupControls()
        setupTouchGestures()
    }

    private fun playVideo(uri: Uri) {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val media = Media(libVLC, pfd.fileDescriptor)
            media.setHWDecoderEnabled(true, false)
            mediaPlayer.media = media
            media.release()
        } ?: run {
            val media = Media(libVLC, uri)
            media.setHWDecoderEnabled(true, false)
            mediaPlayer.media = media
            media.release()
        }

        mediaPlayer.play()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.post(updateProgressRunnable)
        scheduleHideControls()
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

        binding.btnRewind.setOnClickListener {
            val newTime = (mediaPlayer.time - 10_000L).coerceAtLeast(0L)
            mediaPlayer.time = newTime
            saveCurrentPositionNow()
            showToastIndicator("-10 ثواني")
            scheduleHideControls()
        }

        binding.btnForward.setOnClickListener {
            val length = mediaPlayer.length
            val newTime = (mediaPlayer.time + 10_000L).coerceAtMost(if (length > 0) length else Long.MAX_VALUE)
            mediaPlayer.time = newTime
            saveCurrentPositionNow()
            showToastIndicator("+10 ثواني")
            scheduleHideControls()
        }

        binding.btnLock.setOnClickListener {
            isLocked = true
            binding.controlsContainer.visibility = View.GONE
            binding.btnUnlock.visibility = View.VISIBLE
            showToastIndicator("الشاشة مقفلة")
        }

        binding.btnUnlock.setOnClickListener {
            isLocked = false
            binding.btnUnlock.visibility = View.GONE
            showControls()
            showToastIndicator("تم فتح القفل")
        }

        binding.btnOrientation.setOnClickListener {
            currentOrientationIndex = (currentOrientationIndex + 1) % orientationModes.size
            requestedOrientation = orientationModes[currentOrientationIndex]
            binding.btnOrientation.text = orientationLabels[currentOrientationIndex]
            showToastIndicator("وضع الشاشة: ${orientationLabels[currentOrientationIndex]}")
            scheduleHideControls()
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

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer.length > 0) {
                    val targetTime = (progress / 1000.0 * mediaPlayer.length).toLong()
                    binding.tvCurrentTime.text = formatTime(targetTime)
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
                if (e.x < screenWidth / 3) {
                    mediaPlayer.time = (mediaPlayer.time - 10_000L).coerceAtLeast(0L)
                    saveCurrentPositionNow()
                    showToastIndicator("⏪ -10 ثواني")
                } else if (e.x > (screenWidth * 2) / 3) {
                    mediaPlayer.time = (mediaPlayer.time + 10_000L).coerceAtMost(mediaPlayer.length)
                    saveCurrentPositionNow()
                    showToastIndicator("⏩ +10 ثواني")
                } else {
                    binding.btnPlayPause.performClick()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || e1 == null) return false

                val screenWidth = binding.playerRoot.width.toFloat()
                val screenHeight = binding.playerRoot.height.toFloat()

                if (abs(distanceY) > abs(distanceX)) {
                    val delta = distanceY / screenHeight
                    if (e1.x > screenWidth / 2) {
                        adjustVolume(delta)
                    } else {
                        adjustBrightness(delta)
                    }
                } else {
                    val seekDeltaMs = (-distanceX / screenWidth * 90_000L).toLong()
                    val newTime = (mediaPlayer.time + seekDeltaMs).coerceIn(0L, mediaPlayer.length)
                    mediaPlayer.time = newTime
                    saveCurrentPositionNow()
                    showToastIndicator(formatTime(newTime))
                }
                return true
            }
        })

        binding.playerRoot.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun adjustVolume(delta: Float) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = (delta * maxVol * 1.5f).toInt()
        val newVol = (currentVol + step).coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        val percent = ((newVol.toFloat() / maxVol) * 100).toInt()
        showToastIndicator("الصوت: $percent%")
    }

    private fun adjustBrightness(delta: Float) {
        val layoutParams = window.attributes
        var brightness = layoutParams.screenBrightness
        if (brightness < 0f) brightness = 0.5f
        brightness = (brightness + delta).coerceIn(0.05f, 1.0f)
        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams
        val percent = (brightness * 100).toInt()
        showToastIndicator("السطوع: $percent%")
    }

    private fun showSubtitlesDialog() {
        val spuTracks = mediaPlayer.spuTracks ?: emptyArray()
        val items = mutableListOf<String>()
        val trackIds = mutableListOf<Int>()

        items.add(getString(R.string.load_external_sub))
        trackIds.add(-999)

        items.add(getString(R.string.search_online_sub_subdl))
        trackIds.add(-998)

        items.add(getString(R.string.search_online_sub_opensub))
        trackIds.add(-997)

        for (track in spuTracks) {
            items.add(track.name ?: "Track ${track.id}")
            trackIds.add(track.id)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.subtitles))
            .setItems(items.toTypedArray()) { _, which ->
                when (val selectedId = trackIds[which]) {
                    -999 -> subtitlePickerLauncher.launch(arrayOf("*/*"))
                    -998 -> promptOnlineSubtitleSearch(provider = "subdl")
                    -997 -> promptOnlineSubtitleSearch(provider = "opensubtitles")
                    else -> mediaPlayer.spuTrack = selectedId
                }
            }
            .show()
    }

    private fun promptOnlineSubtitleSearch(provider: String) {
        val cleanQuery = currentVideoTitle
            .substringBeforeLast(".")
            .replace(Regex("[._-]"), " ")
            .replace(Regex("(1080p|720p|480p|2160p|4K|BluRay|WEB-DL|WEBRip|x264|x265|HEVC|10bit)", RegexOption.IGNORE_CASE), "")
            .trim()

        val input = EditText(this).apply {
            setText(cleanQuery)
            hint = "اكتب اسم الفيلم أو المسلسل..."
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("البحث عن ترجمة عربية في الإنترنت")
            .setMessage("تأكد من اسم الفيلم أو المسلسل للبحث عن ملف الترجمة وتنزيله:")
            .setView(input)
            .setPositiveButton("بحث وتنزيل") { _, _ ->
                val query = Uri.encode(input.text.toString().trim())
                val url = if (provider == "subdl") {
                    "https://subdl.com/search?query=$query"
                } else {
                    "https://www.opensubtitles.org/ar/search2/sublanguageid-ara/moviename-$query"
                }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton("إلغاء", null)
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
        binding.tvGestureIndicator.postDelayed(hideIndicatorRunnable, 900)
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
        if (mediaPlayer.isPlaying) {
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
    }
}
