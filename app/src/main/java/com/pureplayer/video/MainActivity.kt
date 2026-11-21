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
import android.os.Parcelable
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import android.widget.ProgressBar
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    private var isAudioTabHighlighted = false
    private val tabScrollStates = mutableMapOf<Int, Parcelable?>()
    private val tabSearchQueries = mutableMapOf<Int, String>()
    private var savedVideoFolder: VideoFolder? = null
    private var savedVaultFolder: VideoFolder? = null
    private var activeAudioSubTab: Int = 0
    @Volatile
    internal var cachedIptvSources: List<IptvSource>? = null
    private var lastAnimatedBottomTab = -1
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
    private var activeSearchJob: Job? = null
    internal val iptvSessionSyncGuard = IptvSessionSyncGuard()

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
    private val audioLibraryViewModel: AudioLibraryViewModel by lazy {
        ViewModelProvider(this)[AudioLibraryViewModel::class.java]
    }
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        audioLibraryViewModel.refreshAudioLibrary()
    }
    private var activeAudioSection = AudioTabSection.TRACKS

    private val videoLibraryViewModel: VideoLibraryViewModel by lazy {
        ViewModelProvider(this)[VideoLibraryViewModel::class.java]
    }

    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasVideoAccessPermission()) {
            videoLibraryViewModel.onPermissionGranted()
        } else {
            videoLibraryViewModel.onPermissionDenied()
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
        enableEdgeToEdge()
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { view, windowInsets ->
            val sysBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime()) && imeInsets.bottom > sysBars.bottom
            val bottomInset = maxOf(sysBars.bottom, imeInsets.bottom)
            view.setPadding(sysBars.left, sysBars.top, sysBars.right, bottomInset)
            val horizPad = dp(ResponsiveUiMetrics.getListHorizontalPaddingDp(resources.configuration.screenWidthDp))
            binding.recyclerView.setPadding(horizPad, dp(8), horizPad, if (imeVisible) dp(12) else dp(88))
            windowInsets
        }

        setupRecyclerView()
        setupListeners()
        setupInstantDebouncedSearchFlow()
        applyUserTheme()
        observeVideoLibraryState()
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.setItemViewCacheSize(12)
        lifecycleScope.launch(Dispatchers.IO) {
            cachedIptvSources = IptvRepository.getSources(this@MainActivity)
        }
        setupInPlaceAudioTabUi()
        observeBackgroundAudioMiniPlayer()
        handleNotificationReopenIntent(intent)
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
        } else {
            videoLibraryViewModel.onActivityResumed()
            if (hasVideoAccessPermission() && allDiscoveredVideos.isNotEmpty()) {
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
        binding.tvToolbarSubtitle.setTextColor(secondaryText)
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
        binding.iptvSubtleSyncBar.indeterminateTintList = ColorStateList.valueOf(accent)
        binding.iptvInlineSyncSpinner.indeterminateTintList = ColorStateList.valueOf(accent)
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

        fun createPillBg(active: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(17).toFloat()
            if (active) {
                setColor(Color.argb(48, Color.red(accent), Color.green(accent), Color.blue(accent)))
                setStroke(dp(1), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(0, Color.TRANSPARENT)
            }
        }

        val activeTabKey = if (isAudioTabHighlighted) 3 else currentMainTab
        val isVideos = (activeTabKey == 0)
        val isAudio = (activeTabKey == 3)
        val isVault = (activeTabKey == 1)
        val isIptv = (activeTabKey == 2)
        val animateSelection = (lastAnimatedBottomTab != activeTabKey)
        lastAnimatedBottomTab = activeTabKey

        fun applyTabState(
            tabRoot: View,
            pill: View,
            icon: ImageView,
            active: Boolean,
            filledRes: Int,
            outlinedRes: Int,
            label: String
        ) {
            tabRoot.isSelected = active
            tabRoot.contentDescription = label
            TooltipCompat.setTooltipText(tabRoot, label)
            pill.background = createPillBg(active)
            icon.setImageResource(if (active) filledRes else outlinedRes)
            icon.imageTintList = ColorStateList.valueOf(if (active) accent else inactiveColor)
            if (animateSelection && active) {
                pill.scaleX = 0.62f
                pill.alpha = 0.25f
                pill.animate().scaleX(1f).alpha(1f).setDuration(190L).setInterpolator(DecelerateInterpolator()).start()
                icon.animate().scaleX(1.08f).scaleY(1.08f).setDuration(190L).setInterpolator(DecelerateInterpolator()).start()
            } else {
                pill.scaleX = 1f
                pill.alpha = 1f
                val sc = if (active) 1.06f else 1f
                icon.scaleX = sc
                icon.scaleY = sc
            }
        }

        val vaultLabel = if (lockedCount > 0 && currentMainTab == 1 && isVaultSessionUnlocked) {
            "${getString(R.string.tab_vault)} ($lockedCount)"
        } else {
            getString(R.string.tab_vault)
        }

        applyTabState(
            binding.tabVideos, binding.pillTabVideos, binding.ivTabVideos,
            isVideos, R.drawable.ic_nav_video_filled, R.drawable.ic_nav_video_outlined,
            getString(R.string.tab_video_folders_compact)
        )
        applyTabState(
            binding.tabAudio, binding.pillTabAudio, binding.ivTabAudio,
            isAudio, R.drawable.ic_nav_audio_filled, R.drawable.ic_nav_audio_outlined,
            getString(R.string.tab_audio)
        )
        applyTabState(
            binding.tabVault, binding.pillTabVault, binding.ivTabVault,
            isVault, R.drawable.ic_nav_private_filled, R.drawable.ic_nav_private_outlined,
            vaultLabel
        )
        applyTabState(
            binding.tabIptv, binding.pillTabIptv, binding.ivTabIptv,
            isIptv, R.drawable.ic_nav_iptv_filled, R.drawable.ic_nav_iptv_outlined,
            getString(R.string.tab_iptv_compact)
        )

        if (lockedCount > 0 && currentMainTab == 1 && isVaultSessionUnlocked) {
            binding.tvVaultBadge.text = lockedCount.toString()
            binding.tvVaultBadge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(accent)
            }
            binding.tvVaultBadge.visibility = View.VISIBLE
        } else {
            binding.tvVaultBadge.visibility = View.GONE
        }
    }

    internal fun calculateAdaptiveGridSpanCount(): Int {
        val cfg = resources.configuration
        val isPosterSection = (currentMainTab == 2 && iptvNavLevel == 3 &&
            (activeIptvContentType == IptvContentType.MOVIES || activeIptvContentType == IptvContentType.SERIES))
        return ResponsiveUiMetrics.calculateAdaptiveSpanCount(
            screenWidthDp = cfg.screenWidthDp,
            smallestScreenWidthDp = cfg.smallestScreenWidthDp,
            isGridMode = true,
            isPosterSection = isPosterSection
        )
    }

    internal fun updateRecyclerLayoutManager() {
        val cfg = resources.configuration
        val gridEnabled = PlaybackStore.isGridLayoutEnabled(this)
        adapter.isGridMode = gridEnabled
        val isPosterSection = (currentMainTab == 2 && iptvNavLevel == 3 &&
            (activeIptvContentType == IptvContentType.MOVIES || activeIptvContentType == IptvContentType.SERIES))
        val targetSpan = ResponsiveUiMetrics.calculateAdaptiveSpanCount(
            screenWidthDp = cfg.screenWidthDp,
            smallestScreenWidthDp = cfg.smallestScreenWidthDp,
            isGridMode = gridEnabled,
            isPosterSection = isPosterSection
        )
        val horizPad = dp(ResponsiveUiMetrics.getListHorizontalPaddingDp(cfg.screenWidthDp))
        if (binding.recyclerView.paddingLeft != horizPad || binding.recyclerView.paddingRight != horizPad) {
            binding.recyclerView.setPadding(horizPad, binding.recyclerView.paddingTop, horizPad, binding.recyclerView.paddingBottom)
        }
        val currentLm = binding.recyclerView.layoutManager
        if (targetSpan > 1) {
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
                if (currentMainTab == 2) {
                    // Never trigger a server refresh when scrolling inside Movies, TV Series, or Live TV
                    iptvSessionSyncGuard.onScrollInsideIptv()
                }
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
        binding.btnBackFolder.visibility = View.VISIBLE
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
            binding.tabAudio,
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
                if (isSearchActive) {
                    performInstantLiveSearch(q)
                }
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
            if (currentMainTab != 0 || isAudioTabHighlighted) {
                saveCurrentTabUiState()
                if (currentMainTab == 2) {
                    iptvSessionSyncGuard.onLeaveMainIptvSection()
                    setSubtleIptvSyncIndicatorVisible(false)
                }
                isAudioTabHighlighted = false
                binding.audioTabContainer.visibility = View.GONE
                binding.swipeRefresh.visibility = View.VISIBLE
                currentMainTab = 0
                isInVaultTab = false
                isInIptvTab = false
                binding.swipeRefresh.isEnabled = true
                restoreTabSearchState(0)
                updateBottomTabsVisualState()
                if (savedVideoFolder != null) {
                    openFolder(savedVideoFolder!!)
                } else {
                    applyFolderVisibilityAndRefreshUI()
                }
                restoreTabScrollState(0)
                updateResumeLastFab()
            } else if (isShowingFolderVideos) {
                savedVideoFolder = null
                showFoldersList()
            } else {
                binding.recyclerView.smoothScrollToPosition(0)
            }
        }

        binding.tabVault.setOnClickListener {
            if (currentMainTab == 1 && isVaultSessionUnlocked && !isAudioTabHighlighted) {
                if (isShowingFolderVideos) {
                    savedVaultFolder = null
                    showFoldersList()
                } else {
                    binding.recyclerView.smoothScrollToPosition(0)
                }
                return@setOnClickListener
            }
            saveCurrentTabUiState()
            isAudioTabHighlighted = false
            binding.audioTabContainer.visibility = View.GONE
            binding.swipeRefresh.visibility = View.VISIBLE
            if (currentMainTab == 1 && isVaultSessionUnlocked) {
                if (isShowingFolderVideos) {
                    showFoldersList()
                }
                return@setOnClickListener
            }
            if (!PlaybackStore.hasPrivacyPin(this)) {
                settingsBackStack.clear()
                showFirstTimeSetupPinDialog {
                    if (currentMainTab == 2) {
                        iptvSessionSyncGuard.onLeaveMainIptvSection()
                        setSubtleIptvSyncIndicatorVisible(false)
                    }
                    isVaultSessionUnlocked = true
                    currentMainTab = 1
                    isInVaultTab = true
                    isInIptvTab = false
                    binding.swipeRefresh.isEnabled = true
                    isShowingFolderVideos = false
                    currentOpenedFolder = null
                    if (isSearchActive) closeSearchBar()
                    updateBottomTabsVisualState()
                    applyFolderVisibilityAndRefreshUI()
                    updateResumeLastFab()
                }
            } else if (isVaultSessionUnlocked) {
                if (currentMainTab == 2) {
                    iptvSessionSyncGuard.onLeaveMainIptvSection()
                    setSubtleIptvSyncIndicatorVisible(false)
                }
                currentMainTab = 1
                isInVaultTab = true
                isInIptvTab = false
                binding.swipeRefresh.isEnabled = true
                if (isSearchActive) closeSearchBar()
                updateBottomTabsVisualState()
                applyFolderVisibilityAndRefreshUI()
                updateResumeLastFab()
            } else {
                settingsBackStack.clear()
                authenticatePrivacyAccess(getString(R.string.vault_auth_subtitle)) {
                    if (currentMainTab == 2) {
                        iptvSessionSyncGuard.onLeaveMainIptvSection()
                        setSubtleIptvSyncIndicatorVisible(false)
                    }
                    isVaultSessionUnlocked = true
                    currentMainTab = 1
                    isInVaultTab = true
                    isInIptvTab = false
                    binding.swipeRefresh.isEnabled = true
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
            if (currentMainTab != 2 || isAudioTabHighlighted) {
                saveCurrentTabUiState()
                VideoThumbnailLoader.cancelAllPending()
                isAudioTabHighlighted = false
                binding.audioTabContainer.visibility = View.GONE
                binding.swipeRefresh.visibility = View.VISIBLE
                currentMainTab = 2
                isInVaultTab = false
                isInIptvTab = true
                restoreTabSearchState(2)
                updateBottomTabsVisualState()
                renderIptvCurrentLevel()
                restoreTabScrollState(2)
                updateResumeLastFab()
                binding.recyclerView.post {
                    if (currentMainTab == 2 && !isFinishing) {
                        triggerAutoRefreshOnEnteringIptvSection()
                    }
                }
            } else if (iptvNavLevel > 0) {
                iptvNavLevel = 0
                activeIptvContentType = null
                iptvSessionSyncGuard.onInternalSectionNavigation(iptvNavLevel, activeIptvContentType, activeIptvCategoryId, iptvSectionSubTab)
                renderIptvCurrentLevel()
            } else {
                binding.recyclerView.smoothScrollToPosition(0)
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

        binding.tabAudio.setOnClickListener {
            if (isAudioTabHighlighted) {
                binding.audioTabListView.smoothScrollToPosition(0)
                return@setOnClickListener
            }
            saveCurrentTabUiState()
            VideoThumbnailLoader.cancelAllPending()
            if (currentMainTab == 2) {
                iptvSessionSyncGuard.onLeaveMainIptvSection()
                setSubtleIptvSyncIndicatorVisible(false)
            }
            isAudioTabHighlighted = true
            binding.iptvHeaderBar.visibility = View.GONE
            binding.emptyLayout.visibility = View.GONE
            binding.swipeRefresh.visibility = View.GONE
            binding.fabResumeLast.visibility = View.GONE
            binding.audioTabContainer.visibility = View.VISIBLE
            binding.tvToolbarTitle.text = getString(R.string.tab_audio)
            binding.btnBackFolder.visibility = View.GONE
            updateBottomTabsVisualState()
            showInPlaceAudioLibraryTab()
        }

        binding.btnMiniPlayPause.setOnClickListener {
            VexoPlaybackService.sendAction(this, VexoPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
        }
        binding.btnMiniNext.setOnClickListener {
            VexoPlaybackService.sendAction(this, VexoPlaybackService.ACTION_NEXT_TRACK)
        }
        binding.btnMiniClose.setOnClickListener {
            VexoPlaybackService.sendAction(this, VexoPlaybackService.ACTION_STOP_AND_CLOSE)
        }
        binding.btnMiniReturnVideo.setOnClickListener {
            val st = VexoPlaybackService.playbackState.value
            if (st.currentUri.isNotBlank()) {
                val uri = Uri.parse(st.currentUri)
                val pos = st.positionMs
                val title = st.title
                VexoPlaybackService.sendAction(this, VexoPlaybackService.ACTION_STOP_AND_CLOSE)
                launchPlayer(uri, title, pos)
            }
        }

        binding.btnRefreshEmpty.setOnClickListener {
            if (hasVideoAccessPermission()) {
                videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = true)
            } else {
                requestStoragePermission()
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            if (currentMainTab == 2) {
                binding.swipeRefresh.isRefreshing = false
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
                iptvSessionSyncGuard.onLeaveMainIptvSection()
                setSubtleIptvSyncIndicatorVisible(false)
                currentMainTab = 0
                isInVaultTab = false
                isInIptvTab = false
                binding.swipeRefresh.isEnabled = true
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

    private fun setupInstantDebouncedSearchFlow() {
        // Live instant search is now executed directly per character via performInstantLiveSearch()
        // without any 280ms debounce delay or distinctUntilChanged suppression.
    }

    /**
     * Restored original search logic with:
     * 1. Zero artificial delay — executes immediately on the first character and updates with every keystroke.
     * 2. Strict section restriction — when inside Movies, searches ONLY Movies; when inside TV Series,
     *    searches ONLY TV Series; when inside Live TV, searches ONLY Live TV. Never returns items from other sections.
     */
    private fun performInstantLiveSearch(rawQuery: String) {
        if (!isSearchActive) return
        activeSearchJob?.cancel()
        val normQuery = IptvSearchNormalizer.normalize(rawQuery)
        val tabSnapshot = currentMainTab
        val navLevelSnapshot = iptvNavLevel
        val sectionTypeSnapshot = SectionScopedSearchHelper.resolveActiveSearchSection(
            currentMainTab = tabSnapshot,
            iptvNavLevel = navLevelSnapshot,
            activeContentType = activeIptvContentType
        )
        val categoryIdSnapshot = activeIptvCategoryId
        val subTabSnapshot = iptvSectionSubTab

        activeSearchJob = lifecycleScope.launch {
            val results: List<Any> = withContext(Dispatchers.Default) {
                if (tabSnapshot == 2) {
                    val src = activeIptvSource ?: IptvRepository.getSources(this@MainActivity).firstOrNull()
                    if (src == null) {
                        emptyList()
                    } else if (sectionTypeSnapshot != null && subTabSnapshot == 1) {
                        // Inside Favorites sub-tab of the current section (Movies / Series / Live TV)
                        val favPool = IptvRepository.getFavoriteItems(this@MainActivity, src.id, sectionTypeSnapshot)
                        SectionScopedSearchHelper.filterStreamsForSectionSearch(favPool, normQuery, sectionTypeSnapshot)
                    } else if (sectionTypeSnapshot != null && subTabSnapshot == 2) {
                        // Inside Continue Watching sub-tab of the current section (Movies / Series / Live TV)
                        val recentPool = IptvRepository.getRecentWatchedItems(this@MainActivity, src.id, sectionTypeSnapshot)
                        SectionScopedSearchHelper.filterStreamsForSectionSearch(recentPool, normQuery, sectionTypeSnapshot)
                    } else {
                        val groupSeries = (sectionTypeSnapshot == IptvContentType.SERIES && src.type != IptvSourceType.XTREAM)
                        val matched = IptvRepository.searchStreamsSql(
                            context = this@MainActivity,
                            sourceId = src.id,
                            normQuery = normQuery,
                            limit = if (normQuery.isEmpty()) 300 else 500,
                            contentType = sectionTypeSnapshot,
                            groupM3uSeries = groupSeries
                        )
                        // If inside a specific category (Level 3) and query is empty, show current category streams
                        if (normQuery.isEmpty() && navLevelSnapshot == 3 && !categoryIdSnapshot.isNullOrEmpty() && categoryIdSnapshot != "__ALL__") {
                            matched.filter { it.categoryId == categoryIdSnapshot || it.categoryName == activeIptvCategoryName }
                                .ifEmpty { matched }
                        } else {
                            matched
                        }
                    }
                } else {
                    val sourcePool = when {
                        isShowingFolderVideos && currentOpenedFolder != null -> currentOpenedFolder!!.videos
                        tabSnapshot == 1 && isVaultSessionUnlocked -> vaultVideosFlat
                        else -> publicVideosFlat
                    }
                    if (normQuery.isEmpty()) {
                        sourcePool
                    } else {
                        sourcePool.filter { v ->
                            IptvSearchNormalizer.matches(v.title, v.folderName, normQuery)
                        }
                    }
                }
            }

            if (!isSearchActive) return@launch
            if (results.isEmpty() && rawQuery.isNotBlank()) {
                binding.recyclerView.visibility = View.GONE
                binding.emptyLayout.visibility = View.VISIBLE
                binding.tvEmptyMessage.text = getString(R.string.search_no_results)
                binding.btnGrantPermission.visibility = View.GONE
            } else {
                binding.emptyLayout.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                adapter.submitIptvList(results, resetAnimation = false)
            }
        }
    }

    internal fun openSearchBar() {
        isSearchActive = true
        binding.etSearchVideos.hint = if (currentMainTab == 2) {
            val secType = SectionScopedSearchHelper.resolveActiveSearchSection(currentMainTab, iptvNavLevel, activeIptvContentType)
            if (secType != null) {
                "${getSectionDisplayName(secType)} — ${getString(R.string.iptv_search_hint)}"
            } else {
                getString(R.string.iptv_search_hint)
            }
        } else {
            getString(R.string.search_videos_hint)
        }
        binding.searchBarContainer.alpha = 0f
        binding.searchBarContainer.visibility = View.VISIBLE
        binding.searchBarContainer.animate().alpha(1f).setDuration(180L).start()
        binding.etSearchVideos.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etSearchVideos, InputMethodManager.SHOW_IMPLICIT)
        performInstantLiveSearch(binding.etSearchVideos.text.toString())
    }

    internal fun closeSearchBar() {
        isSearchActive = false
        activeSearchJob?.cancel()
        binding.etSearchVideos.setText("")
        searchQueryFlow.value = ""
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchVideos.windowToken, 0)
        binding.searchBarContainer.visibility = View.GONE
        if (currentMainTab == 2) {
            renderIptvCurrentLevel()
        } else if (isShowingFolderVideos && currentOpenedFolder != null) {
            binding.tvToolbarTitle.text = "${currentOpenedFolder!!.name} (${currentOpenedFolder!!.videos.size})"
            binding.btnBackFolder.visibility = View.VISIBLE
            adapter.submitVideos(currentOpenedFolder!!.videos)
        } else {
            showFoldersList()
        }
    }

    internal fun filterVideosByQuery(rawQuery: String) {
        if (!isSearchActive) return
        searchQueryFlow.value = rawQuery
        performInstantLiveSearch(rawQuery)
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
            binding.tvToolbarTitle.text = "${getString(R.string.app_name)} (${publicVideosFlat.size})"
            binding.btnBackFolder.visibility = View.GONE
            adapter.submitVideos(publicVideosFlat)
        } else {
            val suffix = if (isUsingLimitedAccessMode()) " • Limited" else ""
            binding.tvToolbarTitle.text = "${getString(R.string.app_name)}$suffix"
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
        binding.swipeRefresh.isEnabled = true
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

    private fun observeVideoLibraryState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                videoLibraryViewModel.uiState.collect { state ->
                    if (currentMainTab == 2) return@collect
                    when (state) {
                        is VideoLibraryUiState.Loading -> {
                            binding.swipeRefresh.isRefreshing = true
                            binding.libraryLoadingBar.visibility = View.VISIBLE
                            binding.btnGrantPermission.visibility = View.GONE
                            binding.btnRefreshEmpty.visibility = View.GONE
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyLayout.visibility = View.VISIBLE
                            binding.tvEmptyMessage.text = getString(R.string.loading_videos_msg)
                        }
                        is VideoLibraryUiState.PermissionDenied -> {
                            binding.swipeRefresh.isRefreshing = false
                            binding.libraryLoadingBar.visibility = View.GONE
                            binding.btnRefreshEmpty.visibility = View.GONE
                            binding.btnGrantPermission.text = getString(R.string.grant_permission)
                            showEmptyState(getString(R.string.permission_needed), showButton = true)
                        }
                        is VideoLibraryUiState.Empty -> {
                            binding.swipeRefresh.isRefreshing = false
                            binding.libraryLoadingBar.visibility = View.GONE
                            allDiscoveredVideos = emptyList()
                            allDiscoveredFolders = emptyList()
                            val msg = if (isUsingLimitedAccessMode()) {
                                getString(R.string.limited_access_empty_msg)
                            } else {
                                getString(R.string.no_videos_found)
                            }
                            showEmptyState(msg, showButton = isUsingLimitedAccessMode())
                            binding.btnRefreshEmpty.visibility = View.VISIBLE
                            updateResumeLastFab()
                        }
                        is VideoLibraryUiState.Success -> {
                            binding.swipeRefresh.isRefreshing = state.isRefreshing
                            binding.libraryLoadingBar.visibility = View.GONE
                            binding.btnRefreshEmpty.visibility = View.GONE
                            PlaybackStore.syncDiscoveredVideosForNewBadge(
                                this@MainActivity,
                                state.videos.map { it.id }.toSet()
                            )
                            allDiscoveredVideos = state.videos
                            allDiscoveredFolders = state.folders
                            applyFolderVisibilityAndRefreshUI()
                            updateResumeLastFab()
                        }
                    }
                }
            }
        }
    }

    internal fun checkPermissionAndLoad() {
        if (hasVideoAccessPermission()) {
            videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = true)
        } else {
            binding.swipeRefresh.isRefreshing = false
            videoLibraryViewModel.onPermissionDenied()
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
        videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = showSpinner)
    }


    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationReopenIntent(intent)
    }

    private fun handleNotificationReopenIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("OPEN_FROM_AUDIO_SERVICE", false) != true) return
        intent.removeExtra("OPEN_FROM_AUDIO_SERVICE")
        val st = VexoPlaybackService.playbackState.value
        if (st.isActive && st.isFromVideoAudioOnly && st.currentUri.isNotBlank()) {
            val uri = Uri.parse(st.currentUri)
            val pos = st.positionMs
            val title = st.title
            VexoPlaybackService.sendAction(this, VexoPlaybackService.ACTION_STOP_AND_CLOSE)
            launchPlayer(uri, title, pos)
        }
    }

    
    internal fun getCachedOrLoadIptvSources(): List<IptvSource> {
        val cached = cachedIptvSources
        if (cached != null) return cached
        val loaded = IptvRepository.getSources(this)
        cachedIptvSources = loaded
        return loaded
    }

    internal fun invalidateIptvSourcesCache() {
        cachedIptvSources = null
        lifecycleScope.launch(Dispatchers.IO) {
            cachedIptvSources = IptvRepository.getSources(this@MainActivity)
        }
    }

    private fun saveCurrentTabUiState() {
        val activeKey = if (isAudioTabHighlighted) 3 else currentMainTab
        if (activeKey != 3) {
            tabScrollStates[activeKey] = binding.recyclerView.layoutManager?.onSaveInstanceState()
            tabSearchQueries[activeKey] = if (isSearchActive) binding.etSearchVideos.text?.toString().orEmpty() else ""
        }
        if (activeKey == 0) {
            savedVideoFolder = if (isShowingFolderVideos) currentOpenedFolder else null
        } else if (activeKey == 1) {
            savedVaultFolder = if (isShowingFolderVideos) currentOpenedFolder else null
        }
    }

    private fun restoreTabScrollState(tabKey: Int) {
        val saved = tabScrollStates[tabKey] ?: return
        binding.recyclerView.post {
            binding.recyclerView.layoutManager?.onRestoreInstanceState(saved)
        }
    }

    private fun restoreTabSearchState(tabKey: Int) {
        val savedQ = tabSearchQueries[tabKey].orEmpty()
        if (savedQ.isNotEmpty()) {
            if (!isSearchActive) openSearchBar()
            if (binding.etSearchVideos.text?.toString() != savedQ) {
                binding.etSearchVideos.setText(savedQ)
            }
        } else if (isSearchActive) {
            closeSearchBar()
        }
    }

    private fun setupInPlaceAudioTabUi() {
        val accent = PlaybackStore.getAccentColor(this)
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)

        binding.etAudioTabSearch.setTextColor(primaryText)
        binding.etAudioTabSearch.setHintTextColor(secondaryText)
        binding.etAudioTabSearch.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(surface)
            setStroke(dp(1), Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        binding.etAudioTabSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isAudioTabHighlighted) renderInPlaceAudioContent()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val subLabels = listOf(
            getString(R.string.audio_sec_tracks),
            getString(R.string.audio_sec_artists),
            getString(R.string.audio_sec_albums),
            getString(R.string.audio_sec_genres),
            getString(R.string.audio_sec_playlists)
        )
        binding.audioSubTabsRow.removeAllViews()
        subLabels.forEachIndexed { idx, title ->
            val chip = TextView(this).apply {
                text = title
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    activeAudioSubTab = idx
                    updateAudioSubTabChips()
                    renderInPlaceAudioContent()
                }
            }
            binding.audioSubTabsRow.addView(chip)
        }
        updateAudioSubTabChips()

        lifecycleScope.launch {
            audioLibraryViewModel.uiState.collect {
                if (isAudioTabHighlighted) renderInPlaceAudioContent()
            }
        }
    }

    private fun updateAudioSubTabChips() {
        val accent = PlaybackStore.getAccentColor(this)
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        for (i in 0 until binding.audioSubTabsRow.childCount) {
            val tv = binding.audioSubTabsRow.getChildAt(i) as? TextView ?: continue
            val sel = (i == activeAudioSubTab)
            tv.setTextColor(if (sel) primaryText else secondaryText)
            tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(if (sel) Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent)) else surface)
                setStroke(dp(1), if (sel) accent else Color.parseColor("#24FFFFFF"))
            }
        }
    }

    private fun showInPlaceAudioLibraryTab() {
        updateAudioSubTabChips()
        if (!audioLibraryViewModel.hasAudioPermission()) {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            audioPermissionLauncher.launch(perms)
            binding.tvAudioTabStatus.text = getString(R.string.permission_needed)
            binding.tvAudioTabStatus.visibility = View.VISIBLE
            binding.audioTabListView.visibility = View.GONE
            return
        }
        if (audioLibraryViewModel.uiState.value !is AudioLibraryUiState.Success) {
            audioLibraryViewModel.refreshAudioLibrary()
        }
        renderInPlaceAudioContent()
    }

    private fun renderInPlaceAudioContent() {
        val state = audioLibraryViewModel.uiState.value
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val accent = PlaybackStore.getAccentColor(this)

        binding.tvAudioTabStatus.setTextColor(secondaryText)

        when (state) {
            is AudioLibraryUiState.Loading -> {
                binding.tvAudioTabStatus.text = getString(R.string.loading_media_label)
                binding.tvAudioTabStatus.visibility = View.VISIBLE
                binding.audioTabListView.visibility = View.GONE
            }
            is AudioLibraryUiState.Empty -> {
                binding.tvAudioTabStatus.text = getString(R.string.audio_empty_msg)
                binding.tvAudioTabStatus.visibility = View.VISIBLE
                binding.audioTabListView.visibility = View.GONE
            }
            is AudioLibraryUiState.PermissionDenied -> {
                binding.tvAudioTabStatus.text = getString(R.string.permission_needed)
                binding.tvAudioTabStatus.visibility = View.VISIBLE
                binding.audioTabListView.visibility = View.GONE
            }
            is AudioLibraryUiState.Success -> {
                val q = binding.etAudioTabSearch.text?.toString().orEmpty().trim().lowercase()
                val rows = mutableListOf<Triple<String, String, () -> Unit>>()
                when (activeAudioSubTab) {
                    0 -> {
                        val filtered = state.tracks.filter {
                            q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
                        }
                        filtered.forEachIndexed { idx, tr ->
                            rows.add(Triple("🎵  ${tr.title}", "${tr.artist} • ${tr.album}", {
                                VexoPlaybackService.playAudioQueue(this, filtered, idx)
                            }))
                        }
                    }
                    1 -> {
                        val filtered = state.artists.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                        filtered.forEach { ar ->
                            rows.add(Triple("🎤  ${ar.name}", "${ar.tracks.size} tracks", {
                                showAudioTracksListDialog(ar.name, ar.tracks)
                            }))
                        }
                    }
                    2 -> {
                        val filtered = state.albums.filter {
                            q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                        }
                        filtered.forEach { al ->
                            rows.add(Triple("💿  ${al.title}", "${al.artist} • ${al.tracks.size} tracks", {
                                showAudioTracksListDialog(al.title, al.tracks)
                            }))
                        }
                    }
                    3 -> {
                        val filtered = state.genres.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                        filtered.forEach { gr ->
                            rows.add(Triple("🎼  ${gr.name}", "${gr.tracks.size} tracks", {
                                showAudioTracksListDialog(gr.name, gr.tracks)
                            }))
                        }
                    }
                    else -> {
                        rows.add(Triple("➕  ${getString(R.string.audio_playlists_manage)}", getString(R.string.audio_sec_playlists_sub), {
                            showAudioPlaylistsManagerDialog(state.tracks)
                        }))
                        val filtered = state.playlists.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                        filtered.forEach { pl ->
                            rows.add(Triple("📑  ${pl.name}", "${pl.trackCount} tracks", {
                                showAudioPlaylistsManagerDialog(state.tracks)
                            }))
                        }
                    }
                }

                if (rows.isEmpty()) {
                    binding.tvAudioTabStatus.text = getString(R.string.audio_empty_msg)
                    binding.tvAudioTabStatus.visibility = View.VISIBLE
                    binding.audioTabListView.visibility = View.GONE
                } else {
                    binding.tvAudioTabStatus.visibility = View.GONE
                    binding.audioTabListView.visibility = View.VISIBLE
                    binding.audioTabListView.adapter = object : android.widget.BaseAdapter() {
                        override fun getCount(): Int = rows.size
                        override fun getItem(position: Int): Any = rows[position]
                        override fun getItemId(position: Int): Long = position.toLong()
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                            val rowLayout = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(14), dp(12), dp(14), dp(12))
                                addView(TextView(this@MainActivity).apply {
                                    tag = "title"
                                    textSize = 14.5f
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                    setTypeface(null, Typeface.BOLD)
                                })
                                addView(TextView(this@MainActivity).apply {
                                    tag = "sub"
                                    textSize = 12f
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                    setPadding(0, dp(2), 0, 0)
                                })
                            }
                            rowLayout.background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(12).toFloat()
                                setColor(surface)
                                setStroke(dp(1), Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent)))
                            }
                            val tvT = rowLayout.findViewWithTag<TextView>("title")
                            val tvS = rowLayout.findViewWithTag<TextView>("sub")
                            tvT.text = rows[position].first
                            tvT.setTextColor(primaryText)
                            tvS.text = rows[position].second
                            tvS.setTextColor(secondaryText)
                            return rowLayout
                        }
                    }
                    binding.audioTabListView.setOnItemClickListener { _, _, position, _ ->
                        rows.getOrNull(position)?.third?.invoke()
                    }
                }
            }
        }
    }

    private fun observeBackgroundAudioMiniPlayer() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VexoPlaybackService.playbackState.collect { st ->
                    if (!st.isActive) {
                        binding.miniAudioPlayerBar.visibility = View.GONE
                    } else {
                        binding.miniAudioPlayerBar.visibility = View.VISIBLE
                        binding.tvMiniAudioTitle.text = st.title
                        binding.tvMiniAudioSubtitle.text = st.subtitle.ifBlank { "VEXO Player" }
                        binding.btnMiniPlayPause.setImageResource(
                            if (st.isPlaying) R.drawable.ic_pause_modern else R.drawable.ic_play_modern
                        )
                        binding.btnMiniReturnVideo.visibility =
                            if (st.isFromVideoAudioOnly) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun openVlcStyleAudioLibraryDialog() {
        if (!audioLibraryViewModel.hasAudioPermission()) {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            audioPermissionLauncher.launch(perms)
            return
        }
        audioLibraryViewModel.refreshAudioLibrary()
        lifecycleScope.launch {
            audioLibraryViewModel.uiState.collect { state ->
                when (state) {
                    is AudioLibraryUiState.Success -> {
                        showAudioLibraryHubMenu(state)
                        return@collect
                    }
                    is AudioLibraryUiState.Empty -> {
                        Toast.makeText(this@MainActivity, getString(R.string.audio_empty_msg), Toast.LENGTH_SHORT).show()
                        return@collect
                    }
                    is AudioLibraryUiState.PermissionDenied -> {
                        Toast.makeText(this@MainActivity, getString(R.string.permission_needed), Toast.LENGTH_SHORT).show()
                        return@collect
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showAudioLibraryHubMenu(data: AudioLibraryUiState.Success) {
        val items = mutableListOf<PremiumMenuItem>()
        items.add(
            PremiumMenuItem("🎵", "${getString(R.string.audio_sec_tracks)} (${data.tracks.size})", getString(R.string.audio_sec_tracks_sub), isAccentHighlighted = true, iconResId = R.drawable.ic_music_note) {
                pushSettingsBackStack { showAudioLibraryHubMenu(data) }
                showAudioTracksListDialog(getString(R.string.audio_sec_tracks), data.tracks)
            }
        )
        items.add(
            PremiumMenuItem("🎤", "${getString(R.string.audio_sec_artists)} (${data.artists.size})", null, iconResId = R.drawable.ic_lux_audio) {
                pushSettingsBackStack { showAudioLibraryHubMenu(data) }
                val artistItems = data.artists.map { artist ->
                    PremiumMenuItem("🎤", artist.name, "${artist.albumCount} albums • ${artist.tracks.size} tracks", iconResId = R.drawable.ic_lux_audio) {
                        pushSettingsBackStack { showAudioLibraryHubMenu(data) }
                        showAudioTracksListDialog(artist.name, artist.tracks)
                    }
                }
                showPremiumMenuDialog("🎤", getString(R.string.audio_sec_artists), "${data.artists.size} Artists", artistItems)
            }
        )
        items.add(
            PremiumMenuItem("💿", "${getString(R.string.audio_sec_albums)} (${data.albums.size})", null, iconResId = R.drawable.ic_lux_play_engine) {
                pushSettingsBackStack { showAudioLibraryHubMenu(data) }
                val albumItems = data.albums.map { album ->
                    PremiumMenuItem("💿", album.title, "${album.artist} • ${album.tracks.size} tracks", iconResId = R.drawable.ic_lux_play_engine) {
                        pushSettingsBackStack { showAudioLibraryHubMenu(data) }
                        showAudioTracksListDialog(album.title, album.tracks)
                    }
                }
                showPremiumMenuDialog("💿", getString(R.string.audio_sec_albums), "${data.albums.size} Albums", albumItems)
            }
        )
        items.add(
            PremiumMenuItem("🎼", "${getString(R.string.audio_sec_genres)} (${data.genres.size})", null, iconResId = R.drawable.ic_music_note) {
                pushSettingsBackStack { showAudioLibraryHubMenu(data) }
                val genreItems = data.genres.map { g ->
                    PremiumMenuItem("🎼", g.name, "${g.tracks.size} tracks", iconResId = R.drawable.ic_music_note) {
                        pushSettingsBackStack { showAudioLibraryHubMenu(data) }
                        showAudioTracksListDialog(g.name, g.tracks)
                    }
                }
                showPremiumMenuDialog("🎼", getString(R.string.audio_sec_genres), "${data.genres.size} Genres", genreItems)
            }
        )
        items.add(
            PremiumMenuItem("📋", "${getString(R.string.audio_sec_playlists)} (${data.playlists.size})", getString(R.string.audio_create_playlist), isAccentHighlighted = true, iconResId = R.drawable.ic_lux_subtitles) {
                pushSettingsBackStack { showAudioLibraryHubMenu(data) }
                val plItems = mutableListOf<PremiumMenuItem>()
                plItems.add(
                    PremiumMenuItem("➕", getString(R.string.audio_create_playlist), null, isAccentHighlighted = true) {
                        val input = EditText(this).apply { hint = getString(R.string.audio_playlist_name_hint) }
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.audio_create_playlist))
                            .setView(input)
                            .setPositiveButton(getString(R.string.save)) { _, _ ->
                                audioLibraryViewModel.createPlaylist(input.text.toString())
                                Toast.makeText(this, getString(R.string.save), Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show()
                    }
                )
                for (pl in data.playlists) {
                    plItems.add(
                        PremiumMenuItem("📋", pl.name, "${pl.tracks.size} tracks") {
                            if (pl.tracks.isNotEmpty()) {
                                VexoPlaybackService.playAudioQueue(this, pl.tracks, 0)
                                closeActiveFullScreenDialogSmoothly()
                            }
                        }
                    )
                }
                showPremiumMenuDialog("📋", getString(R.string.audio_sec_playlists), "${data.playlists.size} Playlists", plItems)
            }
        )
        items.add(
            PremiumMenuItem("🎚️", getString(R.string.audio_fx_studio_title), getString(R.string.audio_fx_studio_sub), iconResId = R.drawable.ic_lux_audio) {
                pushSettingsBackStack { showAudioLibraryHubMenu(data) }
                showVexoAudioEffectsStudioDialog()
            }
        )
        showPremiumMenuDialog("🎵", getString(R.string.tab_audio), "VEXO Audio Library • Android Auto Ready", items)
    }

    private fun showAudioTracksListDialog(header: String, tracks: List<AudioTrackItem>) {
        val items = tracks.mapIndexed { idx, tr ->
            PremiumMenuItem("🎵", tr.title, "${tr.artist} • ${PlaybackStore.formatTime(tr.durationMs)}", iconResId = R.drawable.ic_music_note) {
                VexoPlaybackService.playAudioQueue(this, tracks, idx)
                closeActiveFullScreenDialogSmoothly()
            }
        }
        showPremiumMenuDialog("🎵", header, "${tracks.size} tracks", items)
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
