package com.pureplayer.video

import androidx.recyclerview.widget.RecyclerView

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricPrompt
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.CancellationSignal
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.ProgressBar
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.pureplayer.video.databinding.ActivityMainBinding
import android.widget.PopupMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Hardware-backed AndroidKeyStore guard that binds Biometric authentication to the current
 * enrolled fingerprints ONLY after the owner enters the app Password.
 * If ANY new fingerprint is registered in Android Settings, AndroidKeyStore permanently invalidates
 * the key (setInvalidatedByBiometricEnrollment(true)), blocking fingerprint access until the
 * owner enters the Password again!
 */

class MainActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityMainBinding
    internal lateinit var adapter: MediaAdapter

    internal var allDiscoveredVideos = listOf<VideoItem>()
    internal var allDiscoveredFolders = listOf<VideoFolder>()

    private var publicFoldersOnly = listOf<VideoFolder>()
    internal var publicVideosFlat = listOf<VideoItem>()
    private var vaultFoldersOnly = listOf<VideoFolder>()
    internal var vaultVideosFlat = listOf<VideoItem>()

    // 0 = Video Folders, 1 = Private Vault, 2 = IPTV
    internal var currentMainTab = 0
    internal var isInVaultTab = false
    internal var isInIptvTab = false
    internal var isVaultSessionUnlocked = false
    internal var currentIptvCategoryOffset = 0
    internal var isLoadingNextIptvPage = false
    internal var hasMoreIptvCategoryPages = false
    internal val currentIptvPagedList = ArrayList<Any>()
    internal var isShowingFolderVideos = false
    internal var currentOpenedFolder: VideoFolder? = null
    internal var currentOpenedFolderName: String? = null
    internal var isSearchActive = false
    private var pendingDeleteVideo: VideoItem? = null
    private var loadMediaJob: Job? = null
    internal var iptvSyncJob: Job? = null

    // Settings & Sub-Settings Back-Stack + Persistent Full-Screen Host (eliminates window close/open flicker)
    internal val settingsBackStack = ArrayDeque<() -> Unit>()
    internal var isNavigatingToSubDialog = false
    internal var activeFullScreenDialog: AlertDialog? = null
    internal var activeFullScreenHost: FrameLayout? = null
    internal var activeFullScreenCardView: View? = null
    internal var fullScreenMenuRenderToken: Int = 0
    internal var isFullScreenDialogClosing: Boolean = false

    // Instant Debounced Search StateFlow (280ms debounce + distinctUntilChanged + flatMapLatest)
    private val searchQueryFlow = MutableStateFlow("")

    // IPTV Navigation Hierarchy State (preserved when switching tabs)
    // 0 = Sources List, 1 = Source Dashboard (Live/Movies/Series/Radio), 2 = Categories, 3 = Streams/Series List, 4 = Series Seasons, 5 = Season Episodes
    internal var iptvNavLevel = 0
    internal var activeIptvSource: IptvSource? = null
    internal var activeIptvContentType: IptvContentType? = null
    internal var activeIptvCategoryId: String? = null
    internal var activeIptvCategoryName: String = ""
    internal var activeIptvSeriesInfo: IptvSeriesInfoResult? = null
    internal var activeIptvSeasonGroup: IptvSeasonGroup? = null
    internal var currentOpenedSeriesStreamItem: IptvStreamItem? = null
    // 0 = All / Categories, 1 = Favorites ❤️, 2 = Continue Watching / History ⏱️
    internal var iptvSectionSubTab = 0
    private var lastBackExitPressMs = 0L
    internal var currentAnimatedBgColor = Color.parseColor("#090D16")
    internal var currentAnimatedSurfaceColor = Color.parseColor("#131C2E")
    internal var currentAnimatedAccentColor = Color.parseColor("#38BDF8")

    // Supports Android 14+ "Allow Limited Access" (READ_MEDIA_VISUAL_USER_SELECTED) as well as Full Access!
    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasVideoAccessPermission()) {
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
            } catch (e: Exception) { Log.w("MainActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
            handleVideoSelection(it, it.lastPathSegment ?: getString(R.string.open_external_file))
        }
    }

    internal var pendingM3uFileSourceName: String = ""
    internal val m3uFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { Log.w("MainActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
            val defaultName = pendingM3uFileSourceName.ifBlank {
                it.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "M3U Playlist"
            }
            syncIptvSourceWithProgress(
                existingSourceId = null,
                name = defaultName,
                type = IptvSourceType.M3U_FILE,
                serverOrM3uUrl = it.toString()
            )
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, getString(R.string.video_deleted_toast), Toast.LENGTH_SHORT).show()
            pendingDeleteVideo = null
            loadVideoFoldersOnly()
        } else {
            pendingDeleteVideo = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            val savedTab = savedInstanceState.getInt("STATE_MAIN_TAB", 0)
            // Security: Never restore Vault unlocked state across process recreation; require re-authentication
            isVaultSessionUnlocked = false
            currentMainTab = if (savedTab == 1) 0 else savedTab
            isInVaultTab = false
            isInIptvTab = (currentMainTab == 2)
            isShowingFolderVideos = savedInstanceState.getBoolean("STATE_SHOWING_FOLDER", false)
            currentOpenedFolderName = savedInstanceState.getString("STATE_OPENED_FOLDER_NAME", null)
            iptvNavLevel = savedInstanceState.getInt("STATE_IPTV_LEVEL", 0)
            iptvSectionSubTab = savedInstanceState.getInt("STATE_IPTV_SUBTAB", 0)
            activeIptvCategoryId = savedInstanceState.getString("STATE_IPTV_CAT_ID", null)
            activeIptvCategoryName = savedInstanceState.getString("STATE_IPTV_CAT_NAME", "") ?: ""
            savedInstanceState.getString("STATE_IPTV_CONTENT_TYPE", null)?.let { cName ->
                activeIptvContentType = runCatching { IptvContentType.valueOf(cName) }.getOrNull()
            }
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        setupInstantDebouncedSearchFlow()
        applyUserTheme()
        checkPermissionAndLoad()
        if (savedInstanceState?.getBoolean("STATE_SETTINGS_OPEN", false) == true) {
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    showProSettingsDialog(clearStack = true)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("STATE_MAIN_TAB", currentMainTab)
        outState.putBoolean("STATE_SHOWING_FOLDER", isShowingFolderVideos)
        outState.putString("STATE_OPENED_FOLDER_NAME", currentOpenedFolder?.name ?: currentOpenedFolderName)
        outState.putInt("STATE_IPTV_LEVEL", iptvNavLevel)
        outState.putInt("STATE_IPTV_SUBTAB", iptvSectionSubTab)
        outState.putString("STATE_IPTV_CAT_ID", activeIptvCategoryId)
        outState.putString("STATE_IPTV_CAT_NAME", activeIptvCategoryName)
        outState.putString("STATE_IPTV_CONTENT_TYPE", activeIptvContentType?.name)
        outState.putBoolean("STATE_SETTINGS_OPEN", activeFullScreenDialog?.isShowing == true)
    }

    override fun onDestroy() {
        activeFullScreenDialog?.setOnCancelListener(null)
        if (activeFullScreenDialog?.isShowing == true) {
            runCatching { activeFullScreenDialog?.dismiss() }
        }
        activeFullScreenDialog = null
        activeFullScreenHost = null
        activeFullScreenCardView = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyUserTheme(animated = false)
        updateRecyclerLayoutManager()
    }

    override fun onResume() {
        super.onResume()
        applyUserTheme()
        if (currentMainTab == 2) {
            // In IPTV tab: refresh visible items without full list reset
            if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        } else if (hasVideoAccessPermission()) {
            if (allDiscoveredVideos.isEmpty()) {
                loadVideoFoldersOnly()
            } else {
                // Refresh only the active folder or last-played video row when returning from PlayerActivity
                val lastUri = PlaybackStore.getLastPlayedUri(this)
                val lastFolder = PlaybackStore.getLastActiveFolderName(this)
                val currentItems = adapter.currentList
                var updatedAny = false
                for (i in currentItems.indices) {
                    val item = currentItems[i]
                    if ((item is VideoItem && item.uri == lastUri) || (item is VideoFolder && item.name == lastFolder)) {
                        adapter.notifyItemChanged(i)
                        updatedAny = true
                    }
                }
                if (!updatedAny && adapter.itemCount > 0) {
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
        }
        updateResumeLastFab()
    }

    /**
     * Returns true if the user granted EITHER Full Video Access OR Android 14+ "Allow Limited Access"!
     */
    private fun hasVideoAccessPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns true if the user specifically chose "Allow limited access" on Android 14+ (API 34+).
     */
    internal fun isUsingLimitedAccessMode(): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val fullGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            val partialGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            return !fullGranted && partialGranted
        }
        return false
    }


    internal fun applyUserTheme(animated: Boolean = false) {
        val accent = PlaybackStore.getAccentColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)

        if (animated) {
            val fromBg = currentAnimatedBgColor
            val fromSurface = currentAnimatedSurfaceColor
            val fromAccent = currentAnimatedAccentColor
            val eval = ArgbEvaluator()
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 360L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedFraction
                    val curBg = eval.evaluate(f, fromBg, bg) as Int
                    val curSurf = eval.evaluate(f, fromSurface, surface) as Int
                    val curAcc = eval.evaluate(f, fromAccent, accent) as Int
                    binding.mainRoot.setBackgroundColor(curBg)
                    binding.topBar.setBackgroundColor(curSurf)
                    binding.bottomNavBar.setBackgroundColor(curSurf)
                    binding.searchBarContainer.setBackgroundColor(curSurf)
                    window.statusBarColor = curSurf
                    window.navigationBarColor = curSurf
                    binding.btnTopMenu.imageTintList = ColorStateList.valueOf(curAcc)
                    binding.btnToggleSearch.imageTintList = ColorStateList.valueOf(curAcc)
                    binding.fabResumeLast.backgroundTintList = ColorStateList.valueOf(curAcc)
                }
                start()
            }
        } else {
            binding.mainRoot.setBackgroundColor(bg)
            binding.topBar.setBackgroundColor(surface)
            binding.bottomNavBar.setBackgroundColor(surface)
            binding.searchBarContainer.setBackgroundColor(surface)
            window.statusBarColor = surface
            window.navigationBarColor = surface
            binding.btnTopMenu.imageTintList = ColorStateList.valueOf(accent)
            binding.btnToggleSearch.imageTintList = ColorStateList.valueOf(accent)
            binding.fabResumeLast.backgroundTintList = ColorStateList.valueOf(accent)
        }

        currentAnimatedBgColor = bg
        currentAnimatedSurfaceColor = surface
        currentAnimatedAccentColor = accent

        val isLight = PlaybackStore.isLightThemeActive(this)
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)
        binding.tvToolbarTitle.setTextColor(primaryText)
        binding.tvEmptyMessage.setTextColor(secondaryText)
        binding.etSearchVideos.setTextColor(primaryText)
        binding.etSearchVideos.setHintTextColor(secondaryText)
        binding.tvIptvStatusSubtitle.setTextColor(secondaryText)
        binding.tvAddIptvBarLabel.setTextColor(primaryText)
        binding.btnClearSearch.imageTintList = ColorStateList.valueOf(primaryText)
        binding.iptvHeaderBar.setBackgroundColor(surface)

        runCatching {
            WindowInsetsControllerCompat(window, binding.root).let { controller ->
                controller.isAppearanceLightStatusBars = isLight
                controller.isAppearanceLightNavigationBars = isLight
            }
        }

        binding.btnBackFolder.imageTintList = ColorStateList.valueOf(accent)
        binding.ivAddIptvPlus.imageTintList = ColorStateList.valueOf(accent)
        binding.iptvProgressBar.indeterminateTintList = ColorStateList.valueOf(accent)
        binding.fabResumeLast.imageTintList = ColorStateList.valueOf(Color.parseColor("#090D16"))

        val topActionButtons = listOf(
            binding.btnBackFolder,
            binding.btnToggleSearch,
            binding.btnTopMenu
        )
        for (btn in topActionButtons) {
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#18FFFFFF"))
                setStroke(dp(1), Color.argb(48, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        binding.btnAddIptvSourceBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(dp(1), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        binding.iptvLoadingOverlay.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(surface)
            setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }

        val searchBoxBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(bg)
            setStroke(2, accent)
        }
        binding.etSearchVideos.background = searchBoxBg
        updateBottomTabsVisualState()
        if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    internal fun updateResumeLastFab() {
        val lastVideo = PlaybackStore.getLastPlayedVideo(this)
        if (lastVideo != null) {
            val (uri, title, positionMs) = lastVideo
            val matchingItem = allDiscoveredVideos.find { it.uri == uri }
            if (matchingItem != null && PlaybackStore.isFolderLocked(this, matchingItem.folderName)) {
                binding.fabResumeLast.visibility = View.GONE
                return
            }

            val timeFormatted = PlaybackStore.formatTime(positionMs)
            val resumeHint = "$title • ${getString(R.string.resume_fab_format, timeFormatted)}"
            binding.fabResumeLast.contentDescription = resumeHint
            if (binding.fabResumeLast.visibility != View.VISIBLE) {
                binding.fabResumeLast.scaleX = 0.5f
                binding.fabResumeLast.scaleY = 0.5f
                binding.fabResumeLast.alpha = 0f
                binding.fabResumeLast.visibility = View.VISIBLE
                binding.fabResumeLast.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(280L)
                    .start()
            }
            binding.fabResumeLast.setOnClickListener {
                launchPlayer(uri, title, startPositionMs = positionMs)
            }
            binding.fabResumeLast.setOnLongClickListener {
                Toast.makeText(this, resumeHint, Toast.LENGTH_SHORT).show()
                true
            }
        } else {
            binding.fabResumeLast.visibility = View.GONE
        }
    }

    internal fun updateBottomTabsVisualState() {
        val accent = PlaybackStore.getAccentColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val lockedCount = PlaybackStore.getLockedFolders(this).size
        val inactiveColor = PlaybackStore.getSecondaryTextColor(this)

        fun createTabBg(active: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            if (active) {
                setColor(Color.argb(42, Color.red(accent), Color.green(accent), Color.blue(accent)))
                setStroke(dp(1), Color.argb(145, Color.red(accent), Color.green(accent), Color.blue(accent)))
            } else {
                setColor(bg)
                setStroke(dp(1), Color.parseColor("#18FFFFFF"))
            }
        }

        binding.tvTabVideos.text = getString(R.string.tab_video_folders_compact)
        binding.tvTabVault.text = if (lockedCount > 0 && currentMainTab == 1 && isVaultSessionUnlocked) {
            "${getString(R.string.tab_vault_compact)} ($lockedCount)"
        } else {
            getString(R.string.tab_vault_compact)
        }
        binding.tvTabIptv.text = getString(R.string.tab_iptv_compact)

        val isVideos = (currentMainTab == 0)
        val isVault = (currentMainTab == 1)
        val isIptv = (currentMainTab == 2)

        binding.tabVideos.background = createTabBg(isVideos)
        binding.tvTabVideos.setTextColor(if (isVideos) primaryText else inactiveColor)
        binding.ivTabVideos.imageTintList = ColorStateList.valueOf(if (isVideos) accent else inactiveColor)

        binding.tabVault.background = createTabBg(isVault)
        binding.tvTabVault.setTextColor(if (isVault) primaryText else inactiveColor)
        binding.ivTabVault.imageTintList = ColorStateList.valueOf(if (isVault) accent else inactiveColor)

        binding.tabIptv.background = createTabBg(isIptv)
        binding.tvTabIptv.setTextColor(if (isIptv) primaryText else inactiveColor)
        binding.ivTabIptv.imageTintList = ColorStateList.valueOf(if (isIptv) accent else inactiveColor)
    }

    internal fun calculateAdaptiveGridSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return when {
            widthDp >= 1200 -> 5
            widthDp >= 840 -> 4
            widthDp >= 600 -> 3
            else -> 2
        }
    }

    internal fun updateRecyclerLayoutManager() {
        val gridEnabled = PlaybackStore.isGridLayoutEnabled(this)
        adapter.isGridMode = gridEnabled
        val currentLm = binding.recyclerView.layoutManager
        if (gridEnabled) {
            val targetSpan = calculateAdaptiveGridSpanCount()
            if (currentLm is GridLayoutManager) {
                if (currentLm.spanCount != targetSpan) {
                    currentLm.spanCount = targetSpan
                }
            } else {
                binding.recyclerView.layoutManager = GridLayoutManager(this, targetSpan)
            }
        } else {
            if (currentLm is GridLayoutManager || currentLm == null) {
                binding.recyclerView.layoutManager = LinearLayoutManager(this)
            }
        }
        if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    internal fun updateIptvSubTabsVisualState() {
        val accent = PlaybackStore.getAccentColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)
        val inactiveText = Color.parseColor("#94A3B8")

        fun tabDrawable(active: Boolean, isFavTab: Boolean = false) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(11).toFloat()
            if (active) {
                val c = if (isFavTab) Color.parseColor("#F43F5E") else accent
                setColor(Color.argb(45, Color.red(c), Color.green(c), Color.blue(c)))
                setStroke(dp(1), Color.argb(165, Color.red(c), Color.green(c), Color.blue(c)))
            } else {
                setColor(bg)
                setStroke(dp(1), Color.parseColor("#1FFFFFFF"))
            }
        }

        val src = activeIptvSource
        val cType = activeIptvContentType
        val favCount = if (src != null) IptvRepository.countFavoritesSql(this, src.id, cType) else 0
        val recentCount = if (src != null) IptvRepository.countRecentsSql(this, src.id, cType) else 0

        binding.btnIptvSubTabAll.text = getString(R.string.iptv_subtab_all)
        binding.btnIptvSubTabFav.text = if (favCount > 0) "${getString(R.string.iptv_subtab_favorites)} ($favCount)" else getString(R.string.iptv_subtab_favorites)
        binding.btnIptvSubTabRecent.text = if (recentCount > 0) "${getString(R.string.iptv_subtab_recent)} ($recentCount)" else getString(R.string.iptv_subtab_recent)

        binding.btnIptvSubTabAll.background = tabDrawable(iptvSectionSubTab == 0)
        binding.btnIptvSubTabAll.setTextColor(if (iptvSectionSubTab == 0) Color.WHITE else inactiveText)

        binding.btnIptvSubTabFav.background = tabDrawable(iptvSectionSubTab == 1, isFavTab = true)
        binding.btnIptvSubTabFav.setTextColor(if (iptvSectionSubTab == 1) Color.WHITE else inactiveText)

        binding.btnIptvSubTabRecent.background = tabDrawable(iptvSectionSubTab == 2)
        binding.btnIptvSubTabRecent.setTextColor(if (iptvSectionSubTab == 2) Color.WHITE else inactiveText)
    }

    private fun setupRecyclerView() {
        adapter = MediaAdapter(
            onFolderClick = { folder ->
                openFolderNow(folder)
            },
            onVideoClick = { video ->
                handleVideoSelection(video.uri, video.title)
            },
            onVideoLongClick = { video ->
                showVideoLongPressMenu(video)
            },
            onFolderLongClick = { folder ->
                showFolderLockMenu(folder)
            },
            onIptvItemClick = { item ->
                handleIptvItemClick(item)
            },
            onIptvItemLongClick = { item ->
                handleIptvItemLongClick(item)
            },
            onIptvFavoriteToggle = { streamItem, nowFav ->
                val msgRes = if (nowFav) R.string.iptv_fav_added_toast else R.string.iptv_fav_removed_toast
                Toast.makeText(this, getString(msgRes, streamItem.title), Toast.LENGTH_SHORT).show()
                if (currentMainTab == 2 && iptvSectionSubTab == 1) {
                    renderIptvCurrentLevel()
                } else {
                    updateIptvSubTabsVisualState()
                }
            },
            onIptvDownloadClick = { item ->
                handleIptvDownloadRequest(item)
            }
        )
        updateRecyclerLayoutManager()
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return
                if (currentMainTab == 2 && iptvNavLevel == 3 && iptvSectionSubTab == 0 && !isSearchActive && hasMoreIptvCategoryPages && !isLoadingNextIptvPage) {
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val total = lm.itemCount
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (total > 0 && lastVisible >= total - 24) {
                        loadNextIptvStreamsPage()
                    }
                }
            }
        })
    }

    private fun openFolderNow(folder: VideoFolder) {
        isShowingFolderVideos = true
        currentOpenedFolder = folder
        currentOpenedFolderName = folder.name
        binding.tvToolbarTitle.text = "${folder.name} (${folder.videos.size})"
        binding.btnBackFolder.visibility = View.GONE
        adapter.submitVideos(folder.videos)
    }

    /**
     * Premium Folder Long-Press Popup Menu:
     * Dedicated strictly to locking/unlocking the folder with Password & Fingerprint + Changing Password.
     */

    private fun showVideoLongPressMenu(video: VideoItem) {
        settingsBackStack.clear()
        val sizeMb = String.format(Locale.US, "%.1f MB", video.size / (1024.0 * 1024.0))
        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "▶️",
                title = getString(R.string.video_menu_play),
                subtitle = "${video.folderName} • $sizeMb",
                isAccentHighlighted = true,
                onClick = { handleVideoSelection(video.uri, video.title) }
            ),
            PremiumMenuItem(
                iconEmoji = "ℹ️",
                title = getString(R.string.video_menu_details),
                subtitle = video.getResolutionLabel(),
                onClick = {
                    pushSettingsBackStack { showVideoLongPressMenu(video) }
                    showVideoDetailsDialog(video)
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🗑️",
                title = getString(R.string.video_menu_delete),
                subtitle = getString(R.string.delete_action),
                isDanger = true,
                onClick = { confirmAndDeleteVideo(video) }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "🎬",
            title = video.title,
            subtitle = "$sizeMb • ${PlaybackStore.formatTime(video.duration)}",
            items = items
        )
    }

    private fun showVideoDetailsDialog(video: VideoItem) {
        lifecycleScope.launch {
            val enrichedVideo = withContext(Dispatchers.IO) {
                var w = video.width
                var h = video.height
                if (w <= 0 || h <= 0) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@MainActivity, video.uri)
                        w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    } catch (e: Exception) { Log.w("MainActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") } finally {
                        try {
                            retriever.release()
                        } catch (e: Exception) { Log.w("MainActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
                    }
                }
                video.copy(width = w, height = h)
            }

            if (isFinishing || isDestroyed) return@launch

            val sizeFormatted = if (enrichedVideo.size >= 1024L * 1024L * 1024L) {
                String.format(Locale.US, "%.2f GB (%d MB)", enrichedVideo.size / (1024.0 * 1024.0 * 1024.0), enrichedVideo.size / (1024L * 1024L))
            } else {
                String.format(Locale.US, "%.2f MB", enrichedVideo.size / (1024.0 * 1024.0))
            }
            val durationFormatted = PlaybackStore.formatTime(enrichedVideo.duration)
            val displayPath = enrichedVideo.path.ifEmpty { enrichedVideo.uri.toString() }

            val items = listOf(
                PremiumMenuItem("🎬", getString(R.string.video_detail_name, enrichedVideo.title), enrichedVideo.folderName, true) {
                    settingsBackStack.clear()
                    handleVideoSelection(enrichedVideo.uri, enrichedVideo.title)
                },
                PremiumMenuItem("📐", getString(R.string.video_detail_resolution, enrichedVideo.getResolutionLabel()), "$sizeFormatted • $durationFormatted") {
                    settingsBackStack.clear()
                    handleVideoSelection(enrichedVideo.uri, enrichedVideo.title)
                },
                PremiumMenuItem("📍", getString(R.string.video_detail_path, displayPath), null, isInteractive = false),
                PremiumMenuItem("🗑️", getString(R.string.video_menu_delete), getString(R.string.delete_action), isDanger = true) {
                    settingsBackStack.clear()
                    confirmAndDeleteVideo(enrichedVideo)
                }
            )

            showPremiumMenuDialog(
                headerIcon = "ℹ️",
                title = getString(R.string.video_details_title),
                subtitle = "${enrichedVideo.getResolutionLabel()} • $sizeFormatted",
                items = items
            )
        }
    }

    private fun confirmAndDeleteVideo(video: VideoItem) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                pendingDeleteVideo = video
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(video.uri))
                val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                deleteRequestLauncher.launch(request)
                return
            } catch (e: Exception) { Log.w("MainActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_confirm_message, video.title))
            .setPositiveButton(getString(R.string.delete_action)) { _, _ ->
                performDirectDelete(video)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performDirectDelete(video: VideoItem) {
        lifecycleScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    val rows = contentResolver.delete(video.uri, null, null)
                    rows > 0 || (video.path.isNotEmpty() && File(video.path).delete())
                }
                if (deleted) {
                    Toast.makeText(this@MainActivity, getString(R.string.video_deleted_toast), Toast.LENGTH_SHORT).show()
                    loadVideoFoldersOnly()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.video_delete_failed_toast), Toast.LENGTH_SHORT).show()
                }
            } catch (secEx: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && secEx is RecoverableSecurityException) {
                    pendingDeleteVideo = video
                    val request = IntentSenderRequest.Builder(secEx.userAction.actionIntent.intentSender).build()
                    deleteRequestLauncher.launch(request)
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.video_delete_failed_toast), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.video_delete_failed_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun handleVideoSelection(uri: Uri, title: String) {
        val savedPositionMs = PlaybackStore.getSavedPosition(this, uri)
        if (savedPositionMs > 3_000L) {
            when (PlaybackStore.getResumeAskMode(this)) {
                1 -> {
                    // Always Resume automatically
                    launchPlayer(uri, title, startPositionMs = savedPositionMs)
                }
                2 -> {
                    // Always Start from Beginning automatically
                    launchPlayer(uri, title, startPositionMs = 0L)
                }
                else -> {
                    showResumeWithDontAskAgainDialog(uri, title, savedPositionMs)
                }
            }
        } else {
            launchPlayer(uri, title, startPositionMs = 0L)
        }
    }

    /**
     * Premium Resume / Start-From-Beginning Dialog with a "Don't show again (Remember my choice)" CheckBox!
     */
    private fun showResumeWithDontAskAgainDialog(uri: Uri, title: String, savedPositionMs: Long) {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)
        val formattedTime = PlaybackStore.formatTime(savedPositionMs)

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(95, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val topPill = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(accent)
            }
        }
        cardContainer.addView(topPill)

        val tvHeader = TextView(this).apply {
            text = "⏱️  " + getString(R.string.resume_dialog_title)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 17.5f
            setTypeface(typeface, Typeface.BOLD)
        }
        val tvSub = TextView(this).apply {
            text = title
            setTextColor(accent)
            textSize = 12.5f
            maxLines = 2
            setPadding(0, dp(4), 0, dp(12))
        }
        cardContainer.addView(tvHeader)
        cardContainer.addView(tvSub)

        val cbDontAskAgain = CheckBox(this).apply {
            text = getString(R.string.resume_dont_ask_again_checkbox)
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13.5f
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val dialog = AlertDialog.Builder(this).setView(cardContainer).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        fun buildChoiceCard(emoji: String, mainText: String, subText: String, highlight: Boolean, onPick: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(bg)
                    setStroke(
                        dp(1),
                        if (highlight) Color.argb(140, Color.red(accent), Color.green(accent), Color.blue(accent))
                        else Color.parseColor("#22FFFFFF")
                    )
                }

                val badge = TextView(this@MainActivity).apply {
                    text = emoji
                    textSize = 18f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.argb(34, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    }
                }
                val col = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val t1 = TextView(this@MainActivity).apply {
                    text = mainText
                    setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                }
                val t2 = TextView(this@MainActivity).apply {
                    text = subText
                    setTextColor(if (highlight) accent else Color.parseColor("#94A3B8"))
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                }
                col.addView(t1)
                col.addView(t2)
                addView(badge)
                addView(col)
                setOnClickListener {
                    dialog.dismiss()
                    onPick()
                }
            }
        }

        // Option 1: Resume from saved position
        cardContainer.addView(
            buildChoiceCard(
                emoji = "▶️",
                mainText = getString(R.string.resume_from_format, formattedTime),
                subText = getString(R.string.resume_mode_always_resume),
                highlight = true
            ) {
                if (cbDontAskAgain.isChecked) {
                    PlaybackStore.setResumeAskMode(this, 1)
                    Toast.makeText(this, getString(R.string.resume_pref_saved_toast), Toast.LENGTH_SHORT).show()
                }
                launchPlayer(uri, title, startPositionMs = savedPositionMs)
            }
        )

        // Option 2: Start from Beginning (00:00)
        cardContainer.addView(
            buildChoiceCard(
                emoji = "🔄",
                mainText = getString(R.string.start_from_beginning),
                subText = "00:00",
                highlight = false
            ) {
                if (cbDontAskAgain.isChecked) {
                    PlaybackStore.setResumeAskMode(this, 2)
                    Toast.makeText(this, getString(R.string.resume_pref_saved_toast), Toast.LENGTH_SHORT).show()
                }
                launchPlayer(uri, title, startPositionMs = 0L)
            }
        )

        // Checkbox row: "Don't show again (Remember my choice)"
        val cbContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(8))
            addView(cbDontAskAgain)
        }
        cardContainer.addView(cbContainer)

        val btnClose = TextView(this).apply {
            text = getString(R.string.cancel)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(11), 0, dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#18FFFFFF"))
            }
            setOnClickListener { dialog.dismiss() }
        }
        cardContainer.addView(btnClose)
        dialog.show()
    }


    internal fun attachFluidTouchAnimation(vararg views: View) {
        for (v in views) {
            v.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.90f).scaleY(0.90f).setDuration(95L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(165L).start()
                    }
                }
                false
            }
        }
    }

    private fun showTopHamburgerPopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "⚙️  " + getString(R.string.top_menu_settings)).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                contentDescription = getString(R.string.top_menu_settings)
            }
        }
        popup.menu.add(0, 2, 1, "🔃  " + getString(R.string.top_menu_sort)).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                contentDescription = getString(R.string.top_menu_sort)
            }
        }
        popup.menu.add(0, 3, 2, "📂  " + getString(R.string.top_menu_open_files)).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                contentDescription = getString(R.string.top_menu_open_files)
            }
        }
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    showProSettingsDialog(clearStack = true)
                    true
                }
                2 -> {
                    settingsBackStack.clear()
                    showSortAndLayoutDialog()
                    true
                }
                3 -> {
                    openDocumentLauncher.launch(arrayOf("video/*", "application/x-matroska"))
                    true
                }
                else -> false
            }
        }
        anchor.animate().rotationBy(90f).setDuration(180L).withEndAction {
            anchor.rotation = 0f
        }.start()
        popup.show()
    }

    private fun setupListeners() {
        attachFluidTouchAnimation(
            binding.btnBackFolder,
            binding.btnToggleSearch,
            binding.btnTopMenu,
            binding.btnAddIptvSourceBar,
            binding.tabVideos,
            binding.tabVault,
            binding.tabIptv,
            binding.fabResumeLast
        )

        // Long-Pressing on "Video Folders / مجلدات الفيديو" title in the top bar opens the Premium Folder Visibility Selector!
        binding.tvToolbarTitle.isLongClickable = true
        binding.tvToolbarTitle.setOnLongClickListener {
            if (currentMainTab == 2) {
                showAddIptvSourceDialog()
            } else {
                settingsBackStack.clear()
                showFolderVisibilityModeDialog()
            }
            true
        }

        binding.btnBackFolder.setOnClickListener {
            when {
                isSearchActive -> closeSearchBar()
                currentMainTab == 2 -> navigateBackInIptvHierarchy()
                else -> showFoldersList()
            }
        }

        binding.btnToggleSearch.setOnClickListener {
            if (isSearchActive) {
                closeSearchBar()
            } else {
                openSearchBar()
            }
        }

        binding.btnTopMenu.setOnClickListener { view ->
            showTopHamburgerPopupMenu(view)
        }

        binding.btnAddIptvSourceBar.setOnClickListener {
            showAddIptvSourceDialog()
        }

        binding.btnClearSearch.setOnClickListener {
            if (binding.etSearchVideos.text.isNotEmpty()) {
                binding.etSearchVideos.setText("")
            } else {
                closeSearchBar()
            }
        }

        binding.etSearchVideos.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                searchQueryFlow.value = q
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnOpenFile.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("video/*", "application/x-matroska"))
        }

        binding.btnSortAndLayout.setOnClickListener {
            settingsBackStack.clear()
            showSortAndLayoutDialog()
        }

        binding.btnSettings.setOnClickListener {
            showProSettingsDialog(clearStack = true)
        }

        binding.tabVideos.setOnClickListener {
            if (currentMainTab != 0) {
                currentMainTab = 0
                isInVaultTab = false
                isInIptvTab = false
                if (isSearchActive) closeSearchBar()
                updateBottomTabsVisualState()
                applyFolderVisibilityAndRefreshUI()
                updateResumeLastFab()
            } else if (isShowingFolderVideos) {
                showFoldersList()
            }
        }

        binding.tabVault.setOnClickListener {
            if (currentMainTab == 1 && isVaultSessionUnlocked) {
                if (isShowingFolderVideos) {
                    showFoldersList()
                }
                return@setOnClickListener
            }
            if (!PlaybackStore.hasPrivacyPin(this)) {
                settingsBackStack.clear()
                showFirstTimeSetupPinDialog {
                    isVaultSessionUnlocked = true
                    currentMainTab = 1
                    isInVaultTab = true
                    isInIptvTab = false
                    isShowingFolderVideos = false
                    currentOpenedFolder = null
                    if (isSearchActive) closeSearchBar()
                    updateBottomTabsVisualState()
                    applyFolderVisibilityAndRefreshUI()
                    updateResumeLastFab()
                }
            } else if (isVaultSessionUnlocked) {
                currentMainTab = 1
                isInVaultTab = true
                isInIptvTab = false
                if (isSearchActive) closeSearchBar()
                updateBottomTabsVisualState()
                applyFolderVisibilityAndRefreshUI()
                updateResumeLastFab()
            } else {
                settingsBackStack.clear()
                authenticatePrivacyAccess(getString(R.string.vault_auth_subtitle)) {
                    isVaultSessionUnlocked = true
                    currentMainTab = 1
                    isInVaultTab = true
                    isInIptvTab = false
                    isShowingFolderVideos = false
                    currentOpenedFolder = null
                    if (isSearchActive) closeSearchBar()
                    updateBottomTabsVisualState()
                    applyFolderVisibilityAndRefreshUI()
                    updateResumeLastFab()
                }
            }
        }

        binding.tabIptv.setOnClickListener {
            if (currentMainTab != 2) {
                currentMainTab = 2
                isInVaultTab = false
                isInIptvTab = true
                if (isSearchActive) closeSearchBar()
                updateBottomTabsVisualState()
                renderIptvCurrentLevel()
                updateResumeLastFab()
            } else if (iptvNavLevel > 0) {
                iptvNavLevel = 0
                renderIptvCurrentLevel()
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            when {
                currentMainTab == 2 -> showAddIptvSourceDialog()
                currentMainTab == 1 && isVaultSessionUnlocked -> {
                    settingsBackStack.clear()
                    showLockedFoldersMultiPickerDialog()
                }
                else -> requestStoragePermission()
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            if (currentMainTab == 2) {
                binding.swipeRefresh.isRefreshing = false
                val src = activeIptvSource ?: IptvRepository.getSources(this).firstOrNull()
                if (src != null) {
                    refreshExistingIptvSource(src)
                } else {
                    renderIptvCurrentLevel()
                }
            } else {
                checkPermissionAndLoad()
            }
        }

        binding.btnIptvSubTabAll.setOnClickListener {
            if (iptvSectionSubTab != 0) {
                iptvSectionSubTab = 0
                if (iptvNavLevel < 2) iptvNavLevel = 2
                renderIptvCurrentLevel()
            }
        }

        binding.btnIptvSubTabFav.setOnClickListener {
            if (iptvSectionSubTab != 1) {
                iptvSectionSubTab = 1
                renderIptvCurrentLevel()
            }
        }

        binding.btnIptvSubTabRecent.setOnClickListener {
            if (iptvSectionSubTab != 2) {
                iptvSectionSubTab = 2
                renderIptvCurrentLevel()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleUnifiedBackSwipeNavigation()
            }
        })
    }


    /**
     * Unified Phone Gesture Back-Swipe Handler:
     * Steps back cleanly level-by-level through Search -> IPTV Episodes -> Seasons -> Streams -> Categories -> Dashboard -> Sources -> Folders.
     * Never exits the app abruptly on a single accidental swipe!
     */
    private fun handleUnifiedBackSwipeNavigation(): Boolean {
        when {
            isSearchActive -> {
                closeSearchBar()
                return true
            }
            currentMainTab == 2 && iptvNavLevel > 0 -> {
                navigateBackInIptvHierarchy()
                return true
            }
            currentMainTab == 2 -> {
                currentMainTab = 0
                isInVaultTab = false
                isInIptvTab = false
                updateBottomTabsVisualState()
                applyFolderVisibilityAndRefreshUI()
                updateResumeLastFab()
                return true
            }
            isShowingFolderVideos || currentOpenedFolder != null || !currentOpenedFolderName.isNullOrEmpty() -> {
                showFoldersList()
                return true
            }
            currentMainTab == 1 -> {
                currentMainTab = 0
                isInVaultTab = false
                isInIptvTab = false
                isShowingFolderVideos = false
                currentOpenedFolder = null
                currentOpenedFolderName = null
                updateBottomTabsVisualState()
                applyFolderVisibilityAndRefreshUI()
                updateResumeLastFab()
                return true
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackExitPressMs < 2000L) {
                    finish()
                } else {
                    lastBackExitPressMs = now
                    Toast.makeText(this, getString(R.string.press_back_again_to_exit), Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return handleUnifiedBackSwipeNavigation()
        }
        return super.onKeyDown(keyCode, event)
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun setupInstantDebouncedSearchFlow() {
        lifecycleScope.launch {
            searchQueryFlow
                .debounce(280L)
                .distinctUntilChanged()
                .flatMapLatest { rawQuery ->
                    flow {
                        if (!isSearchActive) {
                            emit(Pair(rawQuery, emptyList<Any>()))
                            return@flow
                        }
                        val normQuery = IptvSearchNormalizer.normalize(rawQuery)
                        if (currentMainTab == 2) {
                            val src = activeIptvSource ?: IptvRepository.getSources(this@MainActivity).firstOrNull()
                            if (src == null) {
                                emit(Pair(rawQuery, emptyList<Any>()))
                            } else {
                                val matched = IptvRepository.searchStreamsSql(
                                    this@MainActivity,
                                    src.id,
                                    normQuery,
                                    limit = if (normQuery.isEmpty()) 300 else 400
                                ).sortedBy { it.contentType.ordinal }
                                emit(Pair(rawQuery, matched))
                            }
                        } else {
                            val sourcePool = when {
                                isShowingFolderVideos && currentOpenedFolder != null -> currentOpenedFolder!!.videos
                                currentMainTab == 1 && isVaultSessionUnlocked -> vaultVideosFlat
                                else -> publicVideosFlat
                            }
                            val matched = if (normQuery.isEmpty()) {
                                sourcePool
                            } else {
                                sourcePool.filter { v ->
                                    IptvSearchNormalizer.matches(v.title, v.folderName, normQuery)
                                }
                            }
                            emit(Pair(rawQuery, matched))
                        }
                    }.flowOn(Dispatchers.Default)
                }
                .collectLatest { (rawQuery, results) ->
                    if (!isSearchActive) return@collectLatest
                    if (results.isEmpty() && rawQuery.isNotBlank()) {
                        binding.recyclerView.visibility = View.GONE
                        binding.emptyLayout.visibility = View.VISIBLE
                        binding.tvEmptyMessage.text = getString(R.string.search_no_results)
                        binding.btnGrantPermission.visibility = View.GONE
                    } else {
                        binding.emptyLayout.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                        adapter.submitIptvList(results)
                    }
                }
        }
    }

    internal fun openSearchBar() {
        isSearchActive = true
        binding.etSearchVideos.hint = if (currentMainTab == 2) {
            getString(R.string.iptv_search_hint)
        } else {
            getString(R.string.search_videos_hint)
        }
        binding.searchBarContainer.alpha = 0f
        binding.searchBarContainer.visibility = View.VISIBLE
        binding.searchBarContainer.animate().alpha(1f).setDuration(180L).start()
        binding.etSearchVideos.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etSearchVideos, InputMethodManager.SHOW_IMPLICIT)
        filterVideosByQuery(binding.etSearchVideos.text.toString())
    }

    internal fun closeSearchBar() {
        isSearchActive = false
        binding.etSearchVideos.setText("")
        searchQueryFlow.value = ""
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchVideos.windowToken, 0)
        binding.searchBarContainer.visibility = View.GONE
        if (currentMainTab == 2) {
            renderIptvCurrentLevel()
        } else if (isShowingFolderVideos && currentOpenedFolder != null) {
            binding.tvToolbarTitle.text = "${currentOpenedFolder!!.name} (${currentOpenedFolder!!.videos.size})"
            binding.btnBackFolder.visibility = View.GONE
            adapter.submitVideos(currentOpenedFolder!!.videos)
        } else {
            showFoldersList()
        }
    }

    internal fun filterVideosByQuery(rawQuery: String) {
        if (!isSearchActive) return
        searchQueryFlow.value = rawQuery
    }

    /**
     * Premium Media Layout (List / Grid) & Comprehensive Sort Dialog!
     */

    internal fun showFoldersList() {
        isShowingFolderVideos = false
        currentOpenedFolder = null
        currentOpenedFolderName = null
        adapter.isVaultMode = isInVaultTab

        if (isInVaultTab) {
            binding.tvToolbarTitle.text = "${getString(R.string.vault_toolbar_title)} (${vaultFoldersOnly.size})"
            binding.btnBackFolder.visibility = View.GONE
            adapter.submitFolders(vaultFoldersOnly)
            return
        }

        val filterMode = PlaybackStore.getFolderFilterMode(this)
        if (filterMode == 2) {
            binding.tvToolbarTitle.text = "${getString(R.string.folders_title)} (${publicVideosFlat.size})"
            binding.btnBackFolder.visibility = View.GONE
            adapter.submitVideos(publicVideosFlat)
        } else {
            val suffix = if (isUsingLimitedAccessMode()) " • Limited" else ""
            binding.tvToolbarTitle.text = "${getString(R.string.folders_title)}$suffix"
            binding.btnBackFolder.visibility = View.GONE
            adapter.submitFolders(publicFoldersOnly)
        }
    }

    internal fun applyFolderVisibilityAndRefreshUI() {
        if (currentMainTab == 2) {
            updateRecyclerLayoutManager()
            updateBottomTabsVisualState()
            renderIptvCurrentLevel()
            return
        }
        binding.iptvHeaderBar.visibility = View.GONE
        val mode = PlaybackStore.getFolderFilterMode(this)
        val allowedSet = PlaybackStore.getAllowedFolders(this)
        val lockedSet = PlaybackStore.getLockedFolders(this)
        val sortMode = PlaybackStore.getSortOrderMode(this)

        updateRecyclerLayoutManager()
        updateBottomTabsVisualState()
        adapter.isVaultMode = isInVaultTab

        // 1. Vault folders: any folder locked by user moves automatically into Vault and is 100% hidden from normal tab
        val rawVaultFolders = allDiscoveredFolders.filter { it.name in lockedSet }
        vaultFoldersOnly = sortFoldersList(rawVaultFolders, sortMode)
        vaultVideosFlat = sortVideosList(allDiscoveredVideos.filter { it.folderName in lockedSet }, sortMode)

        // 2. Public (unhidden) folders for Tab 1 (Video Folders)
        val unlockedDiscovered = allDiscoveredFolders.filter { it.name !in lockedSet }
        val filteredBySelection = if (mode == 1 && allowedSet.isNotEmpty()) {
            unlockedDiscovered.filter { it.name in allowedSet }
        } else {
            unlockedDiscovered
        }

        val basePublicFolders = if (filteredBySelection.isEmpty() && unlockedDiscovered.isNotEmpty()) {
            unlockedDiscovered
        } else {
            filteredBySelection
        }
        publicFoldersOnly = sortFoldersList(basePublicFolders, sortMode)
        val visiblePublicNames = publicFoldersOnly.map { it.name }.toSet()
        publicVideosFlat = sortVideosList(allDiscoveredVideos.filter { it.folderName in visiblePublicNames }, sortMode)

        val activeFoldersList = if (isInVaultTab) vaultFoldersOnly else publicFoldersOnly

        if (activeFoldersList.isEmpty()) {
            if (isInVaultTab) {
                binding.tvToolbarTitle.text = getString(R.string.vault_toolbar_title)
                binding.btnBackFolder.visibility = View.GONE
                binding.btnGrantPermission.text = getString(R.string.vault_add_folders_btn)
                showEmptyState(getString(R.string.vault_empty_msg), showButton = allDiscoveredFolders.isNotEmpty())
            } else {
                binding.btnGrantPermission.text = getString(R.string.grant_permission)
                val msg = if (isUsingLimitedAccessMode()) {
                    getString(R.string.limited_access_empty_msg)
                } else {
                    getString(R.string.no_videos_found)
                }
                showEmptyState(msg, showButton = isUsingLimitedAccessMode())
            }
        } else {
            binding.emptyLayout.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            if (isSearchActive) {
                filterVideosByQuery(binding.etSearchVideos.text.toString())
            } else if (isShowingFolderVideos && (currentOpenedFolder != null || !currentOpenedFolderName.isNullOrEmpty())) {
                val targetName = currentOpenedFolder?.name ?: currentOpenedFolderName
                val updated = activeFoldersList.find { it.name == targetName }
                if (updated != null) {
                    currentOpenedFolder = updated
                    currentOpenedFolderName = updated.name
                    binding.tvToolbarTitle.text = "${updated.name} (${updated.videos.size})"
                    binding.btnBackFolder.visibility = View.GONE
                    adapter.submitVideos(updated.videos)
                } else {
                    showFoldersList()
                }
            } else {
                showFoldersList()
            }
        }
    }

    internal fun checkPermissionAndLoad() {
        if (hasVideoAccessPermission()) {
            // Step 1: 0ms Instant Render from Local Cache if available!
            if (allDiscoveredVideos.isEmpty()) {
                val cached = VideoIndexCache.getInstantCachedVideos(this)
                if (cached.isNotEmpty()) {
                    allDiscoveredVideos = cached
                    allDiscoveredFolders = cached
                        .groupBy { it.folderName }
                        .filter { (_, videos) -> videos.isNotEmpty() }
                        .map { (folderName, videos) -> VideoFolder(folderName, videos) }
                        .sortedByDescending { it.videos.size }
                    binding.swipeRefresh.isRefreshing = false
                    applyFolderVisibilityAndRefreshUI()
                    updateResumeLastFab()
                }
            }
            // Step 2: Silent background scan updates any new/changed files without blocking UI!
            loadVideoFoldersOnly(showSpinner = allDiscoveredVideos.isEmpty())
        } else {
            binding.swipeRefresh.isRefreshing = false
            requestStoragePermission()
        }
    }

    internal fun requestStoragePermission() {
        val permissions = if (Build.VERSION.SDK_INT >= 34) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        multiPermissionLauncher.launch(permissions)
    }

    private val supportedVideoExtensions = setOf(
        "mkv", "mp4", "avi", "mov", "webm", "ts", "m2ts", "mts",
        "m4v", "flv", "wmv", "3gp", "3g2", "ogv", "divx", "vob", "f4v", "rmvb", "asf", "mpg", "mpeg"
    )

    private val releaseVideoNameRegex = Regex(
        "(?i)(s\\d{1,2}[._\\s-]*e\\d{1,3}|\\b(?:2160p|1080p|720p|480p|h264|x264|h265|x265|hevc|webrip|web-dl|bluray|bdrip|hdrip)\\b)"
    )

    private fun isRecognizedVideoFile(fileName: String, mimeType: String? = null, sizeBytes: Long = 0L): Boolean {
        val cleanName = fileName.trim()
        if (cleanName.isEmpty() || cleanName.startsWith(".")) return false
        val ext = cleanName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in supportedVideoExtensions) return true
        val mime = mimeType?.lowercase(Locale.ROOT) ?: ""
        if (mime.startsWith("video/") || mime.contains("matroska") || mime.contains("mpegurl")) return true
        // Catch downloaded episode/movie files whose extension was omitted or non-standard (e.g. dexter.resurrection.s02e03.1080p.web.h264-cakes)
        if (sizeBytes >= 5L * 1024L * 1024L && ext !in setOf("zip", "rar", "7z", "apk", "pdf", "iso", "srt", "ass", "sub", "txt", "jpg", "png", "mp3", "flac", "wav", "part", "crdownload")) {
            if (releaseVideoNameRegex.containsMatchIn(cleanName)) return true
        }
        return false
    }

    internal fun loadVideoFoldersOnly(showSpinner: Boolean = false) {
        loadMediaJob?.cancel()
        if (showSpinner && allDiscoveredVideos.isEmpty()) {
            binding.swipeRefresh.isRefreshing = true
        }

        loadMediaJob = lifecycleScope.launch {
            val (videoList, groupedFolders) = withContext(Dispatchers.IO) {
                val list = mutableListOf<VideoItem>()
                val seenIds = HashSet<Long>()
                val seenPaths = HashSet<String>()

                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DATE_ADDED
                )
                val selection = "${MediaStore.Video.Media.SIZE} > 0"
                val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

                // Stage 1: Standard MediaStore.Video table
                try {
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
                        val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                        val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                        val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                        val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                        val dateAddedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val title = cursor.getString(nameCol) ?: continue
                            val duration = cursor.getLong(durCol)
                            val size = cursor.getLong(sizeCol)
                            val path = if (dataCol != -1) (cursor.getString(dataCol) ?: "") else ""
                            val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                            val height = if (heightCol != -1) cursor.getInt(heightCol) else 0
                            val dateAddedSec = if (dateAddedCol != -1) cursor.getLong(dateAddedCol) else 0L

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
                            seenIds.add(id)
                            if (path.isNotEmpty()) seenPaths.add(path.lowercase(Locale.ROOT))
                            list.add(
                                VideoItem(
                                    id = id,
                                    title = title,
                                    uri = contentUri,
                                    duration = duration,
                                    size = size,
                                    folderName = folderName,
                                    width = width,
                                    height = height,
                                    path = path,
                                    dateAddedSec = dateAddedSec
                                )
                            )
                        }
                    }
                } catch (e: Exception) { Log.w("MainActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }

                // Stage 2: Deep MediaStore.Files query for MKV & unindexed container files (e.g. application/x-matroska, octet-stream, or release episodes)
                try {
                    val filesUri = MediaStore.Files.getContentUri("external")
                    val filesProj = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.MIME_TYPE,
                        MediaStore.Files.FileColumns.DATE_ADDED,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.WIDTH,
                        MediaStore.Video.Media.HEIGHT
                    )
                    val filesSelection = "${MediaStore.Files.FileColumns.SIZE} > 0 AND (" +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.mkv' OR " +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.mp4' OR " +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.avi' OR " +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.mov' OR " +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.webm' OR " +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.ts' OR " +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.m2ts' OR " +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.m4v' OR " +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.flv' OR " +
                        "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'video/%' OR " +
                        "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE '%matroska%' OR " +
                        "${MediaStore.Files.FileColumns.SIZE} > 5242880)"

                    contentResolver.query(
                        filesUri,
                        filesProj,
                        filesSelection,
                        null,
                        "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                        val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                        val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                        val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                        val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                        val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                        val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                        val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                        val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            if (id in seenIds) continue
                            val path = if (dataCol != -1) (cursor.getString(dataCol) ?: "") else ""
                            if (path.isNotEmpty() && path.lowercase(Locale.ROOT) in seenPaths) continue

                            val rawTitle = (if (nameCol != -1) cursor.getString(nameCol) else null)
                                ?: (if (path.isNotEmpty()) File(path).name else null)
                                ?: continue
                            val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                            val mime = if (mimeCol != -1) cursor.getString(mimeCol) else null

                            if (!isRecognizedVideoFile(rawTitle, mime, size)) continue

                            val duration = if (durCol != -1) cursor.getLong(durCol) else 0L
                            val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                            val height = if (heightCol != -1) cursor.getInt(heightCol) else 0
                            val dateAddedSec = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                            val bucketName = if (bucketCol != -1) cursor.getString(bucketCol) else null
                            val folderName = when {
                                !bucketName.isNullOrBlank() -> bucketName
                                path.isNotEmpty() -> File(path).parentFile?.name ?: "Downloads"
                                else -> "Downloads"
                            }

                            val contentUri = ContentUris.withAppendedId(filesUri, id)
                            seenIds.add(id)
                            if (path.isNotEmpty()) seenPaths.add(path.lowercase(Locale.ROOT))
                            list.add(
                                VideoItem(
                                    id = id,
                                    title = rawTitle,
                                    uri = contentUri,
                                    duration = duration,
                                    size = size,
                                    folderName = folderName,
                                    width = width,
                                    height = height,
                                    path = path,
                                    dateAddedSec = dateAddedSec
                                )
                            )
                        }
                    }
                } catch (e: Exception) { Log.w("MainActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }

                // Stage 3: Direct Filesystem Walk for unindexed MKV & release files in Download, Movies, Video, Telegram
                val unindexedPathsToScan = mutableListOf<String>()
                try {
                    val candidateRoots = linkedSetOf<File>()
                    runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { candidateRoots.add(it) } }
                    runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)?.let { candidateRoots.add(it) } }
                    runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.let { candidateRoots.add(it) } }
                    runCatching {
                        val extRoot = Environment.getExternalStorageDirectory()
                        if (extRoot != null) {
                            candidateRoots.add(File(extRoot, "Download"))
                            candidateRoots.add(File(extRoot, "Movies"))
                            candidateRoots.add(File(extRoot, "Video"))
                            candidateRoots.add(File(extRoot, "Videos"))
                            candidateRoots.add(File(extRoot, "Telegram"))
                            candidateRoots.add(File(extRoot, "Android/media"))
                        }
                    }

                    fun scanFolderRecursive(dir: File, depth: Int) {
                        if (depth > 4 || !dir.exists() || !dir.isDirectory || !dir.canRead()) return
                        val children = dir.listFiles() ?: return
                        for (child in children) {
                            if (child.name.startsWith(".")) continue
                            if (child.isDirectory) {
                                scanFolderRecursive(child, depth + 1)
                            } else if (child.isFile && child.length() > 0L) {
                                val absPath = child.absolutePath
                                val normPath = absPath.lowercase(Locale.ROOT)
                                if (normPath in seenPaths) continue
                                if (isRecognizedVideoFile(child.name, null, child.length())) {
                                    seenPaths.add(normPath)
                                    unindexedPathsToScan.add(absPath)
                                    val syntheticId = (absPath.hashCode().toLong() and 0x7FFFFFFFL) + 900_000_000L
                                    val folderName = child.parentFile?.name ?: "Download"
                                    list.add(
                                        VideoItem(
                                            id = syntheticId,
                                            title = child.name,
                                            uri = Uri.fromFile(child),
                                            duration = 0L,
                                            size = child.length(),
                                            folderName = folderName,
                                            width = 0,
                                            height = 0,
                                            path = absPath,
                                            dateAddedSec = child.lastModified() / 1000L
                                        )
                                    )
                                }
                            }
                        }
                    }

                    for (rootDir in candidateRoots) {
                        scanFolderRecursive(rootDir, 0)
                    }
                    if (unindexedPathsToScan.isNotEmpty()) {
                        runCatching {
                            MediaScannerConnection.scanFile(
                                applicationContext,
                                unindexedPathsToScan.toTypedArray(),
                                null,
                                null
                            )
                        }
                    }
                } catch (e: Exception) { Log.w("MainActivity", "Handled non-fatal exception: ${e.javaClass.simpleName}") }

                val folders = list
                    .groupBy { it.folderName }
                    .filter { (_, videos) -> videos.isNotEmpty() }
                    .map { (folderName, videos) -> VideoFolder(folderName, videos) }
                    .sortedByDescending { it.videos.size }

                Pair(list, folders)
            }

            if (isFinishing || isDestroyed) return@launch
            binding.swipeRefresh.isRefreshing = false
            PlaybackStore.syncDiscoveredVideosForNewBadge(
                this@MainActivity,
                videoList.map { it.id }.toSet()
            )
            val prevSignature = allDiscoveredVideos.map { Triple(it.id, it.size, it.folderName) }
            val newSignature = videoList.map { Triple(it.id, it.size, it.folderName) }
            val hasChanged = (prevSignature != newSignature) || allDiscoveredFolders.isEmpty()

            allDiscoveredVideos = videoList
            allDiscoveredFolders = groupedFolders
            withContext(Dispatchers.IO) {
                VideoIndexCache.saveCachedVideos(this@MainActivity, videoList)
            }

            if (hasChanged) {
                applyFolderVisibilityAndRefreshUI()
            }
            updateResumeLastFab()
        }
    }

    internal fun showEmptyState(message: String, showButton: Boolean) {
        binding.recyclerView.visibility = View.GONE
        binding.emptyLayout.visibility = View.VISIBLE
        binding.tvEmptyMessage.text = message
        binding.btnGrantPermission.visibility = if (showButton) View.VISIBLE else View.GONE
    }


    internal fun launchPlayer(
        uri: Uri,
        title: String,
        startPositionMs: Long,
        httpUserAgent: String = "",
        httpReferrer: String = ""
    ) {
        PlaybackStore.markVideoOpened(this, uri)
        val matchedFolder = allDiscoveredVideos.find { it.uri == uri }?.folderName
            ?: currentOpenedFolder?.name
            ?: currentOpenedFolderName
        if (!matchedFolder.isNullOrBlank()) {
            PlaybackStore.setLastPlayedVideoInFolder(this, matchedFolder, uri)
            PlaybackStore.setLastActiveFolderName(this, matchedFolder)
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = uri
            putExtra("VIDEO_TITLE", title)
            putExtra("START_POSITION_MS", startPositionMs)
            if (httpUserAgent.isNotBlank()) putExtra("user-agent", httpUserAgent)
            if (httpReferrer.isNotBlank()) putExtra("referer", httpReferrer)
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // =========================================================================
    // FULL IPTV SMARTERS PRO-STYLE UI & SOURCE MANAGEMENT
    // =========================================================================

}
