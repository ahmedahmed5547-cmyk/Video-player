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
import android.widget.PopupWindow
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
    internal var activeTopDropdownPopup: PopupWindow? = null

    internal lateinit var binding: ActivityMainBinding
    internal lateinit var adapter: MediaAdapter

    internal var allDiscoveredVideos = listOf<VideoItem>()
    internal var allDiscoveredFolders = listOf<VideoFolder>()

    private var publicFoldersOnly = listOf<VideoFolder>()
    internal var publicVideosFlat = listOf<VideoItem>()
    internal var vaultFoldersOnly = listOf<VideoFolder>()
    internal val selectionViewModel: SelectionViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[SelectionViewModel::class.java]
    }
    internal lateinit var iptvAdapter: MediaAdapter
    internal var pendingAllFilesAction: (() -> Unit)? = null
    internal var allFilesAccessRefusedThisSession: Boolean = false
    internal val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()
        if (!granted) {
            allFilesAccessRefusedThisSession = true
        }
        val action = pendingAllFilesAction
        pendingAllFilesAction = null
        action?.invoke()
    }
    internal val batchDeleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        selectionViewModel.exitSelection()
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, getString(R.string.video_deleted_toast), Toast.LENGTH_SHORT).show()
            videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = false)
        } else {
            Toast.makeText(this, getString(R.string.video_delete_failed_toast), Toast.LENGTH_SHORT).show()
        }
    }
    internal var vaultVideosFlat = listOf<VideoItem>()

    // 0 = Video Folders, 1 = Private Vault, 2 = IPTV
    internal var currentMainTab = 0
    internal var isInVaultTab = false
    internal var isShortsTabActive = false
    internal var shortsFeedController: ShortsFeedController? = null
    internal var vaultLeftTimestampMs: Long = 0L
    internal var isBiometricPromptInFlight: Boolean = false
    internal var isLaunchingVaultPlayer: Boolean = false

    internal fun isVaultMediaCurrentlyPlaying(): Boolean {
        if (PlayerActivity.isVaultVideoActivelyPlaying || isLaunchingVaultPlayer) return true
        return false
    }

    internal fun lockVaultAndClearInMemoryState(returnToVideosTabIfVisible: Boolean) {
        isVaultSessionUnlocked = false
        vaultLeftTimestampMs = 0L
        VideoThumbnailLoader.cancelAllPending()
        if (isInVaultTab) {
            selectionViewModel.exitSelection()
            updateVaultScreenSecurityFlag(false)
            if (returnToVideosTabIfVisible) {
                currentMainTab = 0
                isInVaultTab = false
                isInIptvTab = false
                isShowingFolderVideos = false
                currentOpenedFolder = null
                binding.audioTabContainer.visibility = View.GONE
                binding.shortsTabContainer.visibility = View.GONE
                isShortsTabActive = false
                shortsFeedController?.pauseAndDetachSurface()
                binding.iptvRecyclerView.visibility = View.GONE
                binding.swipeRefresh.visibility = View.VISIBLE
                updateBottomTabsVisualState()
                applyFolderVisibilityAndRefreshUI()
            }
        }
    }

    internal fun onLeavingVaultSection() {
        if (!isVaultSessionUnlocked) return
        val now = android.os.SystemClock.elapsedRealtime()
        vaultLeftTimestampMs = now
        val mode = PlaybackStore.getVaultAutoLockMode(this)
        if (HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
                mode = mode,
                leftVaultTimestampMs = vaultLeftTimestampMs,
                nowMs = now,
                delaySeconds = PlaybackStore.getVaultAutoLockDelaySeconds(this),
                isVaultMediaPlaying = isVaultMediaCurrentlyPlaying(),
                isLeavingApp = false,
                isLeavingVaultSection = true,
                isConfigChangeOrBiometric = isChangingConfigurations || isBiometricPromptInFlight
            )
        ) {
            lockVaultAndClearInMemoryState(returnToVideosTabIfVisible = false)
        }
    }
    internal var isInIptvTab = false
    private var isAudioTabHighlighted = false
    private var lastSysBarLeft = 0
    private var lastSysBarTop = 0
    private var lastSysBarRight = 0
    private var lastSysBarBottom = 0
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
    internal val audioLibraryViewModel: AudioLibraryViewModel by lazy {
        ViewModelProvider(this)[AudioLibraryViewModel::class.java]
    }
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        audioLibraryViewModel.refreshAudioLibrary()
    }
    private var activeAudioSection = AudioTabSection.TRACKS

    internal val videoLibraryViewModel: VideoLibraryViewModel by lazy {
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
        if (savedInstanceState == null) DefaultPlayerPromptManager.recordAppSession(this)
        AppIconManager.ensureSavedIconApplied(this)
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime()) && imeInsets.bottom > sysBars.bottom
            lastSysBarLeft = sysBars.left
            lastSysBarTop = sysBars.top
            lastSysBarRight = sysBars.right
            lastSysBarBottom = maxOf(sysBars.bottom, imeInsets.bottom)
            applyMainRootInsetsAndOverscan()
            val horizPad = dp(ResponsiveUiMetrics.getListHorizontalPaddingDp(resources.configuration.screenWidthDp))
            binding.recyclerView.setPadding(horizPad, dp(8), horizPad, if (imeVisible) dp(12) else dp(88))
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.mainRoot)

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

    override fun onStop() {
        super.onStop()
        shortsFeedController?.pauseAndDetachSurface()
        if (isVaultSessionUnlocked) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (vaultLeftTimestampMs == 0L) vaultLeftTimestampMs = now
            val shouldLock = HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
                mode = PlaybackStore.getVaultAutoLockMode(this),
                leftVaultTimestampMs = vaultLeftTimestampMs,
                nowMs = now,
                delaySeconds = PlaybackStore.getVaultAutoLockDelaySeconds(this),
                isVaultMediaPlaying = isVaultMediaCurrentlyPlaying(),
                isLeavingApp = true,
                isLeavingVaultSection = false,
                isConfigChangeOrBiometric = isChangingConfigurations || isBiometricPromptInFlight
            )
            if (shouldLock) {
                lockVaultAndClearInMemoryState(returnToVideosTabIfVisible = true)
            }
        }
    }

    override fun onDestroy() {
        shortsFeedController?.release()
        activeTopDropdownPopup?.dismiss()
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
        activeTopDropdownPopup?.dismiss()
        applyUserTheme(animated = false)
        updateRecyclerLayoutManager()
        updateActiveFullScreenMenuLayoutForWindowSize()
        restoreSystemBarsAndInsets()
    }

    
    private fun applyMainRootInsetsAndOverscan() {
        val isTvDevice = TvFocusHelper.isTelevisionDevice(this)
        val (overscanH, overscanV) = TvFocusHelper.getOverscanMarginsDp(isTvDevice)
        val l = lastSysBarLeft + dp(overscanH)
        val t = lastSysBarTop + dp(overscanV)
        val r = lastSysBarRight + dp(overscanH)
        val b = lastSysBarBottom + dp(overscanV)
        if (binding.mainRoot.paddingLeft != l || binding.mainRoot.paddingTop != t ||
            binding.mainRoot.paddingRight != r || binding.mainRoot.paddingBottom != b
        ) {
            binding.mainRoot.setPadding(l, t, r, b)
        }
    }

    private fun restoreSystemBarsAndInsets() {
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(binding.mainRoot)
        applyMainRootInsetsAndOverscan()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            restoreSystemBarsAndInsets()
        }
    }

    override fun onResume() {
        super.onResume()
        isLaunchingVaultPlayer = false
        if (isVaultSessionUnlocked && vaultLeftTimestampMs > 0L) {
            val now = android.os.SystemClock.elapsedRealtime()
            val shouldLock = HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
                mode = PlaybackStore.getVaultAutoLockMode(this),
                leftVaultTimestampMs = vaultLeftTimestampMs,
                nowMs = now,
                delaySeconds = PlaybackStore.getVaultAutoLockDelaySeconds(this),
                isVaultMediaPlaying = isVaultMediaCurrentlyPlaying(),
                isLeavingApp = false,
                isLeavingVaultSection = false,
                isConfigChangeOrBiometric = isChangingConfigurations || isBiometricPromptInFlight
            )
            if (shouldLock) {
                lockVaultAndClearInMemoryState(returnToVideosTabIfVisible = true)
            } else if (isInVaultTab) {
                vaultLeftTimestampMs = 0L
            }
        }
        restoreSystemBarsAndInsets()
        applyUserTheme()
        DefaultPlayerPromptManager.maybeShowPrompt(this)
        shortsFeedController?.resumeIfVisible()
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
            val showHiddenNow = PlaybackStore.isShowHiddenFolders(this)
            val isHiddenFallback = !showHiddenNow && HiddenMediaAndPlaylistHelper.isHiddenMedia(
                path = uri.path.orEmpty(),
                folderName = "",
                title = title
            )
            if (isHiddenFallback || (matchingItem != null && (
                    PlaybackStore.isFolderLocked(this, matchingItem.folderName) ||
                    (matchingItem.isHidden && !showHiddenNow)
                ))
            ) {
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

        val activeTabKey = when {
            isShortsTabActive -> 4
            isAudioTabHighlighted -> 3
            else -> currentMainTab
        }
        val isVideos = (activeTabKey == 0)
        val isShorts = (activeTabKey == 4)
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
            binding.tabShorts, binding.pillTabShorts, binding.ivTabShorts,
            isShorts, R.drawable.ic_nav_shorts_filled, R.drawable.ic_nav_shorts_outline,
            getString(R.string.tab_shorts)
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

        // Sync Left-Side Navigation Rail for Android TV & Expanded Wide Screens
        val surfaceColor = PlaybackStore.getSurfaceColor(this)
        binding.sideNavRail.setBackgroundColor(surfaceColor)
        fun styleRailTab(tab: View, icon: ImageView, active: Boolean, filledRes: Int, outlinedRes: Int, label: String) {
            tab.isSelected = active
            tab.contentDescription = label
            TooltipCompat.setTooltipText(tab, label)
            tab.background = createPillBg(active)
            icon.setImageResource(if (active) filledRes else outlinedRes)
            icon.imageTintList = ColorStateList.valueOf(if (active) accent else inactiveColor)
        }
        styleRailTab(binding.railTabVideos, binding.ivRailVideos, isVideos, R.drawable.ic_nav_video_filled, R.drawable.ic_nav_video_outlined, getString(R.string.tab_video_folders_compact))
        styleRailTab(binding.railTabAudio, binding.ivRailAudio, isAudio, R.drawable.ic_nav_audio_filled, R.drawable.ic_nav_audio_outlined, getString(R.string.tab_audio))
        styleRailTab(binding.railTabVault, binding.ivRailVault, isVault, R.drawable.ic_nav_private_filled, R.drawable.ic_nav_private_outlined, vaultLabel)
        styleRailTab(binding.railTabIptv, binding.ivRailIptv, isIptv, R.drawable.ic_nav_iptv_filled, R.drawable.ic_nav_iptv_outlined, getString(R.string.tab_iptv_compact))
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
        if (binding.iptvRecyclerView.paddingLeft != horizPad || binding.iptvRecyclerView.paddingRight != horizPad) {
            binding.iptvRecyclerView.setPadding(horizPad, binding.iptvRecyclerView.paddingTop, horizPad, binding.iptvRecyclerView.paddingBottom)
        }
        val isTvDevice = TvFocusHelper.isTelevisionDevice(this)
        val useSideRail = TvFocusHelper.shouldUseSideNavigationRail(isTvDevice, cfg.screenWidthDp)
        applyMainRootInsetsAndOverscan()
        binding.sideNavRail.visibility = if (useSideRail) View.VISIBLE else View.GONE
        binding.bottomNavBar.visibility = if (useSideRail) View.GONE else View.VISIBLE
        val contentStartMargin = if (useSideRail) dp(76) else 0
        listOf(binding.swipeRefresh, binding.iptvRecyclerView, binding.audioTabContainer, binding.iptvHeaderBar).forEach { v ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && lp.marginStart != contentStartMargin) {
                lp.marginStart = contentStartMargin
                v.layoutParams = lp
            }
        }
        val navSidePad = dp(FoldableAdaptiveMath.computeBottomNavSidePaddingDp(cfg.screenWidthDp))
        if (binding.bottomNavBar.paddingLeft != navSidePad || binding.bottomNavBar.paddingRight != navSidePad) {
            binding.bottomNavBar.setPadding(navSidePad, binding.bottomNavBar.paddingTop, navSidePad, binding.bottomNavBar.paddingBottom)
        }
        val audioSidePad = if (cfg.screenWidthDp >= 900) dp(((cfg.screenWidthDp - 760) / 2).coerceIn(16, 480)) else dp(12)
        if (binding.audioTabContainer.paddingLeft != audioSidePad || binding.audioTabContainer.paddingRight != audioSidePad) {
            binding.audioTabContainer.setPadding(audioSidePad, binding.audioTabContainer.paddingTop, audioSidePad, binding.audioTabContainer.paddingBottom)
        }
        val targetRv = if (currentMainTab == 2) binding.iptvRecyclerView else binding.recyclerView
        if (::iptvAdapter.isInitialized) iptvAdapter.isGridMode = gridEnabled
        val currentLm = targetRv.layoutManager
        if (targetSpan > 1) {
            if (currentLm is GridLayoutManager) {
                if (currentLm.spanCount != targetSpan) {
                    currentLm.spanCount = targetSpan
                }
            } else {
                targetRv.layoutManager = GridLayoutManager(this, targetSpan)
            }
        } else {
            if (currentLm is GridLayoutManager || currentLm == null) {
                targetRv.layoutManager = LinearLayoutManager(this)
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
                if (selectionViewModel.isSelectionActive()) {
                    selectionViewModel.toggleKey(SelectionViewModel.keyForFolder(folder), isVault = isInVaultTab)
                } else {
                    openFolderNow(folder)
                }
            },
            onVideoClick = { video ->
                if (selectionViewModel.isSelectionActive()) {
                    selectionViewModel.toggleKey(SelectionViewModel.keyForVideo(video), isVault = isInVaultTab)
                } else {
                    handleVideoSelection(video.uri, video.title)
                }
            },
            onVideoLongClick = { video ->
                selectionViewModel.enterSelectionWith(SelectionViewModel.keyForVideo(video), isVault = isInVaultTab)
            },
            onFolderLongClick = { folder ->
                selectionViewModel.enterSelectionWith(SelectionViewModel.keyForFolder(folder), isVault = isInVaultTab)
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
        iptvAdapter = MediaAdapter(
            onFolderClick = {},
            onVideoClick = {},
            onVideoLongClick = {},
            onFolderLongClick = {},
            onIptvItemClick = { item -> handleIptvItemClick(item) },
            onIptvItemLongClick = { item -> handleIptvItemLongClick(item) },
            onIptvFavoriteToggle = { streamItem, nowFav ->
                val msgRes = if (nowFav) R.string.iptv_fav_added_toast else R.string.iptv_fav_removed_toast
                Toast.makeText(this, getString(msgRes, streamItem.title), Toast.LENGTH_SHORT).show()
                if (currentMainTab == 2 && iptvSectionSubTab == 1) {
                    renderIptvCurrentLevel()
                } else {
                    updateIptvSubTabsVisualState()
                }
            },
            onIptvDownloadClick = { item -> handleIptvDownloadRequest(item) }
        )
        binding.iptvRecyclerView.setHasFixedSize(true)
        binding.iptvRecyclerView.setItemViewCacheSize(12)
        binding.iptvRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.iptvRecyclerView.adapter = iptvAdapter
        setupSelectionModeUi()
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


    internal fun showHiddenFoldersPermissionPromptDialog() {
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
            text = "👁️  ${getString(R.string.hidden_perm_title)}"
            setTextColor(primaryText)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        val tvMsg = TextView(this).apply {
            text = getString(R.string.hidden_perm_msg)
            setTextColor(secondaryText)
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val dlg = AlertDialog.Builder(this).setView(container).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnLater = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(secondaryText)
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                dlg.dismiss()
                videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = false, isUserPullRefresh = true)
            }
        }
        val btnGrant = TextView(this).apply {
            text = getString(R.string.hidden_perm_grant_btn)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                dlg.dismiss()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                }
            }
        }
        btnRow.addView(btnLater)
        btnRow.addView(btnGrant)
        container.addView(tvTitle)
        container.addView(tvMsg)
        container.addView(btnRow)
        dlg.show()
    }

    private fun showTopHamburgerPopupMenu(anchor: View) {
        activeTopDropdownPopup?.dismiss()

        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)
        val isLight = PlaybackStore.isLightThemeActive(this)

        val dividerColor = if (isLight) Color.parseColor("#180F172A") else Color.parseColor("#1FFFFFFF")
        val rippleColor = Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(12).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val popup = PopupWindow(
            card,
            dp(268),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(14).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { activeTopDropdownPopup = null }
        }
        activeTopDropdownPopup = popup

        fun makeThemedRowBackground(): android.graphics.drawable.Drawable {
            val contentShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(Color.TRANSPARENT)
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(Color.WHITE)
            }
            return android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                contentShape,
                mask
            )
        }

        fun addDivider() {
            card.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
                setBackgroundColor(dividerColor)
            })
        }

        fun addActionRow(icon: String, label: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                minimumHeight = dp(48)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = makeThemedRowBackground()
                contentDescription = label
            }
            val tvIcon = TextView(this).apply {
                text = icon
                textSize = 16f
                setPadding(0, 0, dp(12), 0)
            }
            val tvLabel = TextView(this).apply {
                text = label
                setTextColor(primaryText)
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvIcon)
            row.addView(tvLabel)
            row.setOnClickListener {
                popup.dismiss()
                onClick()
            }
            card.addView(row)
        }

        // 1. Settings
        addActionRow("⚙️", getString(R.string.top_menu_settings)) {
            showProSettingsDialog(clearStack = true)
        }
        addDivider()

        // 2. Sort and Layout
        addActionRow("🔃", getString(R.string.top_menu_sort)) {
            settingsBackStack.clear()
            showSortAndLayoutDialog()
        }
        addDivider()

        // 3. Open File
        addActionRow("📂", getString(R.string.top_menu_open_files)) {
            openDocumentLauncher.launch(arrayOf("video/*", "application/x-matroska"))
        }
        addDivider()

        // 4. Hidden folders (with inline theme-aware SwitchCompat)
        val showHiddenInitial = PlaybackStore.isShowHiddenFolders(this)
        val hiddenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = makeThemedRowBackground()
            contentDescription = getString(R.string.menu_hidden_folders_title)
        }
        val tvHiddenIcon = TextView(this).apply {
            text = "👁️"
            textSize = 16f
            setPadding(0, 0, dp(12), 0)
        }
        val tvHiddenLabel = TextView(this).apply {
            text = getString(R.string.menu_hidden_folders_title)
            setTextColor(primaryText)
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hiddenSwitch = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = showHiddenInitial
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        applyThemedSwitchStyle(hiddenSwitch, enabled = true)
        androidx.core.view.ViewCompat.setAccessibilityDelegate(hiddenRow, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Switch"
                info.isCheckable = true
                info.isChecked = hiddenSwitch.isChecked
            }
        })
        hiddenSwitch.setOnCheckedChangeListener { _, checked ->
            PlaybackStore.setShowHiddenFolders(this, checked)
            val lm = binding.recyclerView.layoutManager
            val savedScroll = lm?.onSaveInstanceState()
            applyFolderVisibilityAndRefreshUI()
            updateResumeLastFab()
            runCatching { audioLibraryViewModel.refreshAudioLibrary() }
            shortsFeedController?.markDirtyIfHidden(allDiscoveredVideos)
            VexoPlaybackService.notifyLibraryChanged(this)
            if (savedScroll != null) {
                binding.recyclerView.post {
                    binding.recyclerView.layoutManager?.onRestoreInstanceState(savedScroll)
                }
            }
            if (checked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                    popup.dismiss()
                    showHiddenFoldersPermissionPromptDialog()
                } else {
                    videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = false, isUserPullRefresh = true)
                }
            }
        }
        hiddenRow.setOnClickListener {
            hiddenSwitch.isChecked = !hiddenSwitch.isChecked
        }
        hiddenRow.addView(tvHiddenIcon)
        hiddenRow.addView(tvHiddenLabel)
        hiddenRow.addView(hiddenSwitch)
        card.addView(hiddenRow)

        anchor.animate().rotationBy(90f).setDuration(180L).withEndAction {
            anchor.rotation = 0f
        }.start()

        popup.showAsDropDown(anchor, -dp(8), dp(6), Gravity.END)
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
            val wasInShorts = isShortsTabActive
            if (wasInShorts) {
                isShortsTabActive = false
                shortsFeedController?.pauseAndDetachSurface()
                binding.shortsTabContainer.visibility = View.GONE
            }
            if (currentMainTab != 0 || isAudioTabHighlighted || wasInShorts) {
                saveCurrentTabUiState()
                if (currentMainTab == 2) {
                    iptvSessionSyncGuard.onLeaveMainIptvSection()
                    setSubtleIptvSyncIndicatorVisible(false)
                }
                isAudioTabHighlighted = false
                selectionViewModel.exitSelection()
                if (isInVaultTab) onLeavingVaultSection()
                updateVaultScreenSecurityFlag(false)
                binding.audioTabContainer.visibility = View.GONE
                binding.iptvRecyclerView.visibility = View.GONE
                binding.swipeRefresh.visibility = View.VISIBLE
                currentMainTab = 0
                isInVaultTab = false
                isInIptvTab = false
                binding.swipeRefresh.isEnabled = true
                restoreTabSearchState(0)
                updateBottomTabsVisualState()
                if (savedVideoFolder != null) {
                    openFolderNow(savedVideoFolder!!)
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
            val wasInShorts = isShortsTabActive
            if (wasInShorts) {
                isShortsTabActive = false
                shortsFeedController?.pauseAndDetachSurface()
                binding.shortsTabContainer.visibility = View.GONE
            }
            if (currentMainTab == 1 && isVaultSessionUnlocked && !isAudioTabHighlighted && !wasInShorts) {
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
            selectionViewModel.exitSelection()
            updateVaultScreenSecurityFlag(true)
            binding.audioTabContainer.visibility = View.GONE
            binding.iptvRecyclerView.visibility = View.GONE
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
            val wasInShorts = isShortsTabActive
            if (wasInShorts) {
                isShortsTabActive = false
                shortsFeedController?.pauseAndDetachSurface()
                binding.shortsTabContainer.visibility = View.GONE
            }
            if (currentMainTab != 2 || isAudioTabHighlighted) {
                saveCurrentTabUiState()
                VideoThumbnailLoader.cancelAllPending()
                isAudioTabHighlighted = false
                selectionViewModel.exitSelection()
                if (isInVaultTab) onLeavingVaultSection()
                updateVaultScreenSecurityFlag(false)
                binding.audioTabContainer.visibility = View.GONE
                binding.swipeRefresh.visibility = View.GONE
                binding.iptvRecyclerView.visibility = View.VISIBLE
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
                binding.iptvRecyclerView.smoothScrollToPosition(0)
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

        binding.tabShorts.setOnClickListener {
            if (isShortsTabActive) {
                shortsFeedController?.showAndPlay(allDiscoveredVideos)
                return@setOnClickListener
            }
            saveCurrentTabUiState()
            VideoThumbnailLoader.cancelAllPending()
            if (isInVaultTab) onLeavingVaultSection()
            if (currentMainTab == 2) {
                iptvSessionSyncGuard.onLeaveMainIptvSection()
                setSubtleIptvSyncIndicatorVisible(false)
            }
            isAudioTabHighlighted = false
            isInVaultTab = false
            isInIptvTab = false
            isShortsTabActive = true
            binding.iptvHeaderBar.visibility = View.GONE
            binding.emptyLayout.visibility = View.GONE
            selectionViewModel.exitSelection()
            updateVaultScreenSecurityFlag(false)
            binding.swipeRefresh.visibility = View.GONE
            binding.iptvRecyclerView.visibility = View.GONE
            binding.audioTabContainer.visibility = View.GONE
            binding.fabResumeLast.visibility = View.GONE
            binding.shortsTabContainer.alpha = 0f
            binding.shortsTabContainer.visibility = View.VISIBLE
            binding.shortsTabContainer.animate().alpha(1f).setDuration(200L).start()
            binding.tvToolbarTitle.text = getString(R.string.tab_shorts)
            binding.btnBackFolder.visibility = View.GONE
            updateBottomTabsVisualState()
            if (shortsFeedController == null) {
                shortsFeedController = ShortsFeedController(this, binding.shortsTabContainer)
            }
            shortsFeedController?.showAndPlay(allDiscoveredVideos)
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
            isShortsTabActive = false
            shortsFeedController?.pauseAndDetachSurface()
            binding.shortsTabContainer.visibility = View.GONE
            isAudioTabHighlighted = true
            binding.iptvHeaderBar.visibility = View.GONE
            binding.emptyLayout.visibility = View.GONE
            selectionViewModel.exitSelection()
            updateVaultScreenSecurityFlag(false)
            binding.swipeRefresh.visibility = View.GONE
            binding.iptvRecyclerView.visibility = View.GONE
            binding.fabResumeLast.visibility = View.GONE
            binding.audioTabContainer.visibility = View.VISIBLE
            binding.tvToolbarTitle.text = getString(R.string.tab_audio)
            binding.btnBackFolder.visibility = View.GONE
            updateBottomTabsVisualState()
            showInPlaceAudioLibraryTab()
        }

        binding.railTabVideos.setOnClickListener { binding.tabVideos.performClick() }
        binding.railTabAudio.setOnClickListener { binding.tabAudio.performClick() }
        binding.railTabVault.setOnClickListener { binding.tabVault.performClick() }
        binding.railTabIptv.setOnClickListener { binding.tabIptv.performClick() }

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
                checkPermissionAndLoad(isUserPullRefresh = true)
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
                if (isShortsTabActive) {
                    binding.tabVideos.performClick()
                    return
                }
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
            selectionViewModel.isSelectionActive() -> {
                selectionViewModel.exitSelection()
                return true
            }
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
        val viewMode = PlaybackStore.getMediaViewMode(this)
        if (filterMode == 2 || viewMode == PlaybackStore.VIEW_MODE_LIBRARY) {
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
            // Do NOT re-render IPTV or disturb iptvRecyclerView when background video MediaStore updates arrive!
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

        // 2. Public folders for Tab 1 (respects Hidden folders toggle!)
        val showHidden = PlaybackStore.isShowHiddenFolders(this)
        val hiddenFilteredFolders = HiddenMediaAndPlaylistHelper.filterFoldersByHiddenPreference(allDiscoveredFolders, showHidden)
        val hiddenFilteredVideos = HiddenMediaAndPlaylistHelper.filterVideosByHiddenPreference(allDiscoveredVideos, showHidden)
        val unlockedDiscovered = hiddenFilteredFolders.filter { it.name !in lockedSet }
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
        publicVideosFlat = sortVideosList(hiddenFilteredVideos.filter { it.folderName in visiblePublicNames }, sortMode)
        shortsFeedController?.markDirtyIfHidden(allDiscoveredVideos)

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
                            binding.swipeRefresh.isRefreshing = false
                            binding.libraryLoadingBar.visibility = if (PlaybackStore.isFirstLibraryScanDone(this@MainActivity)) View.GONE else View.VISIBLE
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

    internal fun checkPermissionAndLoad(isUserPullRefresh: Boolean = false) {
        if (hasVideoAccessPermission()) {
            val firstDone = PlaybackStore.isFirstLibraryScanDone(this)
            videoLibraryViewModel.refreshLibrary(
                force = isUserPullRefresh || !firstDone,
                showLoadingIfEmpty = !firstDone,
                isUserPullRefresh = isUserPullRefresh
            )
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
        return IptvRepository.getSources(this)
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
                            showAudioLibraryHubMenu(state)
                        }))
                        val filtered = state.playlists.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                        filtered.forEach { pl ->
                            rows.add(Triple("📑  ${pl.name}", "${pl.tracks.size} tracks", {
                                showAudioTracksListDialog(pl.name, pl.tracks)
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
                        binding.tvMiniAudioSubtitle.text = st.subtitle.ifBlank { "V Player" }
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
        showPremiumMenuDialog("🎵", getString(R.string.tab_audio), "V Player Audio Library • Android Auto Ready", items)
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
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: ""
        val isStream = scheme == "http" || scheme == "https" || scheme == "rtsp" || scheme == "rtmp" || scheme == "mms"
        val sortMode = PlaybackStore.getSortOrderMode(this)
        val playlistSource: List<VideoItem> = if (isStream) {
            emptyList()
        } else {
            val activePool = when {
                isInVaultTab && currentOpenedFolder != null -> currentOpenedFolder!!.videos
                isInVaultTab -> vaultVideosFlat
                isShowingFolderVideos && currentOpenedFolder != null -> currentOpenedFolder!!.videos
                !matchedFolder.isNullOrBlank() -> {
                    publicFoldersOnly.find { it.name == matchedFolder }?.videos ?: publicVideosFlat
                }
                else -> publicVideosFlat
            }
            sortVideosList(activePool, sortMode)
        }
        val playlistIndex = playlistSource.indexOfFirst { it.uri == uri }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = uri
            putExtra("VIDEO_TITLE", title)
            putExtra("START_POSITION_MS", startPositionMs)
            putExtra(PlayerActivity.EXTRA_IS_STREAM, isStream)
            val matchedVideoObj = allDiscoveredVideos.find { it.uri == uri }
            if (matchedVideoObj != null && matchedVideoObj.width > 0 && matchedVideoObj.height > 0) {
                putExtra(PlayerActivity.EXTRA_VIDEO_WIDTH, matchedVideoObj.width)
                putExtra(PlayerActivity.EXTRA_VIDEO_HEIGHT, matchedVideoObj.height)
            }
            putExtra(PlayerActivity.EXTRA_IS_VAULT_VIDEO, isInVaultTab)
            if (isInVaultTab) isLaunchingVaultPlayer = true
            if (!isStream && playlistSource.size > 1 && playlistIndex >= 0) {
                putStringArrayListExtra(
                    PlayerActivity.EXTRA_PLAYLIST_URIS,
                    ArrayList(playlistSource.map { it.uri.toString() })
                )
                putStringArrayListExtra(
                    PlayerActivity.EXTRA_PLAYLIST_TITLES,
                    ArrayList(playlistSource.map { it.title })
                )
                putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, playlistIndex)
            }
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
