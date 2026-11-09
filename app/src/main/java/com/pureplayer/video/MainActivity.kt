package com.pureplayer.video

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.pureplayer.video.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaAdapter
    private var videoFoldersOnly = listOf<VideoFolder>()
    private var isShowingFolderVideos = false
    private var currentOpenedFolder: VideoFolder? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadVideoFoldersOnly()
        } else {
            showEmptyState(getString(R.string.permission_needed), showButton = true)
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            handleVideoSelection(it, it.lastPathSegment ?: "فيديو خارجي")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        applyUserTheme()
        checkPermissionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        applyUserTheme()
        updateResumeLastFab()
        adapter.notifyDataSetChanged()
    }

    private fun applyUserTheme() {
        val accent = PlaybackStore.getAccentColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)

        binding.mainRoot.setBackgroundColor(bg)
        binding.topBar.setBackgroundColor(surface)
        window.statusBarColor = surface
        window.navigationBarColor = bg
        binding.btnSettings.imageTintList = ColorStateList.valueOf(accent)
        binding.fabResumeLast.backgroundTintList = ColorStateList.valueOf(accent)
        adapter.notifyDataSetChanged()
    }

    private fun updateResumeLastFab() {
        val lastVideo = PlaybackStore.getLastPlayedVideo(this)
        if (lastVideo != null) {
            val (uri, title, positionMs) = lastVideo
            val timeFormatted = PlaybackStore.formatTime(positionMs)
            binding.fabResumeLast.text = "▶ استئناف ($timeFormatted)"
            binding.fabResumeLast.visibility = View.VISIBLE
            binding.fabResumeLast.setOnClickListener {
                launchPlayer(uri, title, startPositionMs = positionMs)
            }
        } else {
            binding.fabResumeLast.visibility = View.GONE
        }
    }

    private fun setupRecyclerView() {
        adapter = MediaAdapter(
            onFolderClick = { folder ->
                isShowingFolderVideos = true
                currentOpenedFolder = folder
                binding.tvToolbarTitle.text = "${folder.name} (${folder.videos.size})"
                binding.btnBackFolder.visibility = View.VISIBLE
                adapter.submitVideos(folder.videos)
            },
            onVideoClick = { video ->
                handleVideoSelection(video.uri, video.title)
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun handleVideoSelection(uri: Uri, title: String) {
        val savedPositionMs = PlaybackStore.getSavedPosition(this, uri)
        if (savedPositionMs > 3_000L) {
            val formattedTime = PlaybackStore.formatTime(savedPositionMs)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.resume_dialog_title))
                .setMessage("توقفت في «$title» عند الدقيقة ($formattedTime). هل تريد استئناف المشاهدة أم البدء من البداية؟")
                .setPositiveButton("استئناف من ($formattedTime)") { _, _ ->
                    launchPlayer(uri, title, startPositionMs = savedPositionMs)
                }
                .setNegativeButton(getString(R.string.start_from_beginning)) { _, _ ->
                    launchPlayer(uri, title, startPositionMs = 0L)
                }
                .setNeutralButton("إلغاء", null)
                .show()
        } else {
            launchPlayer(uri, title, startPositionMs = 0L)
        }
    }

    private fun setupListeners() {
        binding.btnBackFolder.setOnClickListener {
            showFoldersList()
        }

        binding.btnOpenFile.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("video/*", "application/x-matroska"))
        }

        binding.btnSettings.setOnClickListener {
            showProSettingsDialog()
        }

        binding.btnGrantPermission.setOnClickListener {
            requestStoragePermission()
        }

        binding.swipeRefresh.setOnRefreshListener {
            checkPermissionAndLoad()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isShowingFolderVideos) {
                    showFoldersList()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showProSettingsDialog() {
        val options = arrayOf(
            "🎨 تغيير ثيم وألوان التطبيق (Themes)",
            "🔠 تخصيص حجم ولون وخلفية الترجمة",
            "🔄 الوضع الافتراضي لدوران شاشة الفيديو",
            "↕️ إعادة ضبط مكان الترجمة للوضع الافتراضي"
        )
        AlertDialog.Builder(this)
            .setTitle("⚙️ إعدادات Pure Video Player الاحترافية")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showThemeSelectorDialog()
                    1 -> showSubtitleStyleDialog()
                    2 -> showDefaultOrientationDialog()
                    3 -> {
                        PlaybackStore.setSubVerticalOffsetPx(this, 0f)
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showThemeSelectorDialog() {
        val themes = arrayOf(
            "💎 الأزرق السماوي (Cyber Blue)",
            "🌙 أسود ملكي وذهبي (AMOLED Pure Black & Gold)",
            "🌿 الأخضر الزمردي (Emerald Neon)",
            "🌹 الأحمر الياقوتي (Crimson Rose)",
            "🔮 البنفسجي العصري (Material Violet)"
        )
        val current = PlaybackStore.getThemeIndex(this)
        AlertDialog.Builder(this)
            .setTitle("اختر ثيم التطبيق")
            .setSingleChoiceItems(themes, current) { dialog, which ->
                PlaybackStore.setThemeIndex(this, which)
                applyUserTheme()
                dialog.dismiss()
            }
            .show()
    }

    private fun showSubtitleStyleDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 36, 56, 24)
        }

        var currentSize = PlaybackStore.getSubSizeSp(this)
        val tvSizeLabel = TextView(this).apply {
            text = "حجم خط الترجمة: ${currentSize.toInt()}sp"
            textSize = 16f
            setPadding(0, 0, 0, 12)
        }
        val sizeSeek = SeekBar(this).apply {
            max = 28 // 14sp to 42sp
            progress = (currentSize - 14f).toInt().coerceIn(0, 28)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    currentSize = 14f + p
                    tvSizeLabel.text = "حجم خط الترجمة: ${currentSize.toInt()}sp"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val tvColorLabel = TextView(this).apply {
            text = "لون خط الترجمة:"
            textSize = 16f
            setPadding(0, 16, 0, 8)
        }

        val colorNames = arrayOf("⚪ أبيض ناصع", "🟡 أصفر سينمائي", "🔵 سماوي", "🟢 أخضر")
        var selectedColorIdx = PlaybackStore.getSubColorIndex(this)

        container.addView(tvSizeLabel)
        container.addView(sizeSeek)
        container.addView(tvColorLabel)

        AlertDialog.Builder(this)
            .setTitle("تخصيص مظهر الترجمة")
            .setView(container)
            .setSingleChoiceItems(colorNames, selectedColorIdx) { _, which ->
                selectedColorIdx = which
            }
            .setPositiveButton("حفظ") { _, _ ->
                PlaybackStore.setSubSizeSp(this, currentSize)
                PlaybackStore.setSubColorIndex(this, selectedColorIdx)
            }
            .setNeutralButton(
                if (PlaybackStore.isSubBgEnabled(this)) "إخفاء الخلفية السوداء" else "تفعيل الخلفية السوداء"
            ) { _, _ ->
                PlaybackStore.setSubBgEnabled(this, !PlaybackStore.isSubBgEnabled(this))
                PlaybackStore.setSubSizeSp(this, currentSize)
                PlaybackStore.setSubColorIndex(this, selectedColorIdx)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showDefaultOrientationDialog() {
        val modes = arrayOf(
            "🔄 دوران تلقائي حر (Auto Sensor)",
            "🔒 عرضي دائماً (Landscape)",
            "📱 طولي (Portrait)"
        )
        val current = PlaybackStore.getDefaultOrientationIndex(this)
        AlertDialog.Builder(this)
            .setTitle("الوضع الافتراضي عند فتح أي فيديو")
            .setSingleChoiceItems(modes, current) { dialog, which ->
                PlaybackStore.setDefaultOrientationIndex(this, which)
                dialog.dismiss()
            }
            .show()
    }

    private fun showFoldersList() {
        isShowingFolderVideos = false
        currentOpenedFolder = null
        binding.tvToolbarTitle.text = getString(R.string.folders_title)
        binding.btnBackFolder.visibility = View.GONE
        adapter.submitFolders(videoFoldersOnly)
    }

    private fun checkPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadVideoFoldersOnly()
        } else {
            binding.swipeRefresh.isRefreshing = false
            requestStoragePermission()
        }
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(permission)
    }

    private fun loadVideoFoldersOnly() {
        binding.swipeRefresh.isRefreshing = true
        val videoList = mutableListOf<VideoItem>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val selection = "${MediaStore.Video.Media.SIZE} > 0"
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(nameCol) ?: continue
                val duration = cursor.getLong(durCol)
                val size = cursor.getLong(sizeCol)
                val path = cursor.getString(dataCol) ?: ""

                if (path.isNotEmpty() && !File(path).exists()) continue

                val bucketName = if (bucketCol != -1) cursor.getString(bucketCol) else null
                val folderName = when {
                    !bucketName.isNullOrBlank() -> bucketName
                    path.isNotEmpty() -> File(path).parentFile?.name ?: "مجلد الفيديوهات"
                    else -> "مجلد الفيديوهات"
                }

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                videoList.add(VideoItem(id, title, contentUri, duration, size, folderName))
            }
        }

        binding.swipeRefresh.isRefreshing = false

        videoFoldersOnly = videoList
            .groupBy { it.folderName }
            .filter { (_, videos) -> videos.isNotEmpty() }
            .map { (folderName, videos) -> VideoFolder(folderName, videos) }
            .sortedByDescending { it.videos.size }

        if (videoFoldersOnly.isEmpty()) {
            showEmptyState(getString(R.string.no_videos_found), showButton = false)
        } else {
            binding.emptyLayout.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            if (isShowingFolderVideos && currentOpenedFolder != null) {
                val updated = videoFoldersOnly.find { it.name == currentOpenedFolder?.name }
                if (updated != null) {
                    adapter.submitVideos(updated.videos)
                } else {
                    showFoldersList()
                }
            } else {
                showFoldersList()
            }
        }
    }

    private fun showEmptyState(message: String, showButton: Boolean) {
        binding.recyclerView.visibility = View.GONE
        binding.emptyLayout.visibility = View.VISIBLE
        binding.tvEmptyMessage.text = message
        binding.btnGrantPermission.visibility = if (showButton) View.VISIBLE else View.GONE
    }

    private fun launchPlayer(uri: Uri, title: String, startPositionMs: Long) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = uri
            putExtra("VIDEO_TITLE", title)
            putExtra("START_POSITION_MS", startPositionMs)
        }
        startActivity(intent)
    }
}
