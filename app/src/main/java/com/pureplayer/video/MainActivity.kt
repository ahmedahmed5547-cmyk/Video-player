package com.pureplayer.video

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
            handleVideoSelection(it, it.lastPathSegment ?: getString(R.string.open_external_file))
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
            binding.fabResumeLast.text = getString(R.string.resume_fab_format, timeFormatted)
            if (binding.fabResumeLast.visibility != View.VISIBLE) {
                binding.fabResumeLast.scaleX = 0.7f
                binding.fabResumeLast.scaleY = 0.7f
                binding.fabResumeLast.alpha = 0f
                binding.fabResumeLast.visibility = View.VISIBLE
                binding.fabResumeLast.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(260L)
                    .start()
            }
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
                .setMessage(getString(R.string.resume_dialog_message, title, formattedTime))
                .setPositiveButton(getString(R.string.resume_from_format, formattedTime)) { _, _ ->
                    launchPlayer(uri, title, startPositionMs = savedPositionMs)
                }
                .setNegativeButton(getString(R.string.start_from_beginning)) { _, _ ->
                    launchPlayer(uri, title, startPositionMs = 0L)
                }
                .setNeutralButton(getString(R.string.cancel), null)
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
            getString(R.string.setting_app_language),
            getString(R.string.setting_subtitle_languages),
            getString(R.string.setting_app_theme),
            getString(R.string.setting_subtitle_style),
            getString(R.string.setting_default_orientation),
            getString(R.string.setting_reset_sub_position)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_header))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAppLanguageDialog()
                    1 -> showSubtitleLanguagePriorityDialog()
                    2 -> showThemeSelectorDialog()
                    3 -> showSubtitleStyleDialog()
                    4 -> showDefaultOrientationDialog()
                    5 -> {
                        PlaybackStore.resetSubPosition(this)
                        Toast.makeText(this, getString(R.string.sub_position_reset_toast), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    /**
     * Requirement 4: App Language Switcher (Arabic / English / System Default)
     * Uses AndroidX AppCompatDelegate.setApplicationLocales() to switch UI & RTL/LTR direction immediately without app restart.
     */
    private fun showAppLanguageDialog() {
        val labels = arrayOf(
            getString(R.string.lang_system_default),
            getString(R.string.lang_arabic),
            getString(R.string.lang_english)
        )
        val tags = arrayOf("", "ar", "en")
        val currentTag = PlaybackStore.getAppLanguageTag(this)
        val currentIndex = tags.indexOf(currentTag).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_app_language))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val chosenTag = tags[which]
                dialog.dismiss()
                PlaybackStore.setAppLanguageTag(this, chosenTag)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Requirement 3: Subtitle Language Picker with multiple preferred languages in priority order.
     */
    private fun showSubtitleLanguagePriorityDialog() {
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
        val checkedItems = BooleanArray(langCodes.size) { idx ->
            currentPreferred.contains(langCodes[idx])
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sub_lang_dialog_title))
            .setMultiChoiceItems(langLabels, checkedItems) { _, which, isChecked ->
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
                Toast.makeText(
                    this,
                    getString(R.string.sub_lang_priority_saved, finalOrder.joinToString(" → ") { it.uppercase() }),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showThemeSelectorDialog() {
        val themes = arrayOf(
            getString(R.string.theme_cyber_blue),
            getString(R.string.theme_amoled_gold),
            getString(R.string.theme_emerald),
            getString(R.string.theme_crimson),
            getString(R.string.theme_violet)
        )
        val current = PlaybackStore.getThemeIndex(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.theme_dialog_title))
            .setSingleChoiceItems(themes, current) { dialog, which ->
                PlaybackStore.setThemeIndex(this, which)
                applyUserTheme()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Requirement 5: Subtitle Styling Dialog (Size, Color, Background Opacity, Outline/Shadow, Font) with Live Preview!
     */
    private fun showSubtitleStyleDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 24)
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
            setPadding(28, 16, 28, 16)
        }

        fun updateLivePreview() {
            tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize)
            val baseC = when (selectedColorIdx) {
                1 -> Color.parseColor("#FACC15")
                2 -> Color.parseColor("#38BDF8")
                3 -> Color.parseColor("#4ADE80")
                else -> Color.WHITE
            }
            val textAlpha = ((currentTextOpacity / 100.0) * 255).toInt().coerceIn(50, 255)
            tvPreview.setTextColor(Color.argb(textAlpha, Color.red(baseC), Color.green(baseC), Color.blue(baseC)))

            val bgAlpha = ((currentBgOpacity / 100.0) * 255).toInt().coerceIn(0, 255)
            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f
                setColor(Color.argb(bgAlpha, 0, 0, 0))
            }
            tvPreview.background = bgDrawable
            val shadowAlpha = if (currentOutline <= 0.5f) 0 else textAlpha
            tvPreview.setShadowLayer(
                currentOutline,
                if (currentOutline > 0f) 2f else 0f,
                if (currentOutline > 0f) 2f else 0f,
                Color.argb(shadowAlpha, 0, 0, 0)
            )
        }
        updateLivePreview()

        val tvSizeLabel = TextView(this).apply {
            text = getString(R.string.style_size_label, currentSize.toInt())
            setPadding(0, 14, 0, 4)
        }
        val sizeSeek = SeekBar(this).apply {
            max = 32
            progress = (currentSize - 12f).toInt().coerceIn(0, 32)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentSize = 12f + p
                    tvSizeLabel.text = getString(R.string.style_size_label, currentSize.toInt())
                    updateLivePreview()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvBgOpacityLabel = TextView(this).apply {
            text = getString(R.string.style_bg_opacity_label, currentBgOpacity)
            setPadding(0, 14, 0, 4)
        }
        val bgOpacitySeek = SeekBar(this).apply {
            max = 100
            progress = currentBgOpacity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentBgOpacity = p
                    tvBgOpacityLabel.text = getString(R.string.style_bg_opacity_label, currentBgOpacity)
                    updateLivePreview()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvTextOpacityLabel = TextView(this).apply {
            text = "شفافية نص الترجمة (Text Opacity): $currentTextOpacity%"
            setPadding(0, 14, 0, 4)
        }
        val textOpacitySeek = SeekBar(this).apply {
            max = 80
            progress = (currentTextOpacity - 20).coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentTextOpacity = 20 + p
                    tvTextOpacityLabel.text = "شفافية نص الترجمة (Text Opacity): $currentTextOpacity%"
                    updateLivePreview()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvOutlineLabel = TextView(this).apply {
            text = getString(R.string.style_outline_label, currentOutline.toInt())
            setPadding(0, 14, 0, 4)
        }
        val outlineSeek = SeekBar(this).apply {
            max = 12
            progress = currentOutline.toInt().coerceIn(0, 12)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentOutline = p.toFloat()
                    tvOutlineLabel.text = getString(R.string.style_outline_label, currentOutline.toInt())
                    updateLivePreview()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvColorLabel = TextView(this).apply {
            text = getString(R.string.style_color_label)
            setPadding(0, 14, 0, 6)
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
                    updateLivePreview()
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
                PlaybackStore.setSubSizeSp(this, currentSize)
                PlaybackStore.setSubBgOpacityPct(this, currentBgOpacity)
                PlaybackStore.setSubTextOpacityPct(this, currentTextOpacity)
                PlaybackStore.setSubOutlinePx(this, currentOutline)
                PlaybackStore.setSubColorIndex(this, selectedColorIdx)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDefaultOrientationDialog() {
        val modes = arrayOf(
            getString(R.string.orientation_auto),
            getString(R.string.orientation_landscape),
            getString(R.string.orientation_portrait)
        )
        val current = PlaybackStore.getDefaultOrientationIndex(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_default_orientation))
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
                    path.isNotEmpty() -> File(path).parentFile?.name ?: "Videos"
                    else -> "Videos"
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
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
