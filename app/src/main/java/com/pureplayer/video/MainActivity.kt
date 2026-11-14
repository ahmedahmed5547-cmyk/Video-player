package com.pureplayer.video

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
object BiometricHardwareGuard {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "pure_player_privacy_bio_key_v1"

    sealed class CipherResult {
        data class Ready(val cipher: Cipher) : CipherResult()
        object EnrollmentChangedOrUnbound : CipherResult()
        object Unavailable : CipherResult()
    }

    fun bindKeyAfterPasswordVerified(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            PlaybackStore.setBiometricPasswordVerified(context, false)
            return
        }
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)

            keyGenerator.init(builder.build())
            keyGenerator.generateKey()

            val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (secretKey != null) {
                val testCipher = Cipher.getInstance(
                    "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}"
                )
                testCipher.init(Cipher.ENCRYPT_MODE, secretKey)
                PlaybackStore.setBiometricPasswordVerified(context, true)
            } else {
                PlaybackStore.setBiometricPasswordVerified(context, false)
            }
        } catch (_: Exception) {
            PlaybackStore.setBiometricPasswordVerified(context, false)
        }
    }

    fun prepareCipherForBiometricPrompt(context: Context): CipherResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return CipherResult.Unavailable
        if (!PlaybackStore.isBiometricPasswordVerified(context)) {
            return CipherResult.EnrollmentChangedOrUnbound
        }
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: return CipherResult.EnrollmentChangedOrUnbound

            val cipher = Cipher.getInstance(
                "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}"
            )
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            CipherResult.Ready(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            PlaybackStore.setBiometricPasswordVerified(context, false)
            try {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                ks.deleteEntry(KEY_ALIAS)
            } catch (_: Exception) {}
            CipherResult.EnrollmentChangedOrUnbound
        } catch (_: Exception) {
            CipherResult.Unavailable
        }
    }

    fun clearBiometricKey(context: Context) {
        PlaybackStore.setBiometricPasswordVerified(context, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) {
                    ks.deleteEntry(KEY_ALIAS)
                }
            } catch (_: Exception) {}
        }
    }
}

data class PremiumMenuItem(
    val iconEmoji: String,
    val title: String,
    val subtitle: String? = null,
    val isAccentHighlighted: Boolean = false,
    val isDanger: Boolean = false,
    val sectionHeader: String? = null,
    val isInteractive: Boolean = true,
    val onClick: () -> Unit = {}
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaAdapter

    private var allDiscoveredVideos = listOf<VideoItem>()
    private var allDiscoveredFolders = listOf<VideoFolder>()

    private var publicFoldersOnly = listOf<VideoFolder>()
    private var publicVideosFlat = listOf<VideoItem>()
    private var vaultFoldersOnly = listOf<VideoFolder>()
    private var vaultVideosFlat = listOf<VideoItem>()

    // 0 = Video Folders, 1 = Private Vault, 2 = IPTV
    private var currentMainTab = 0
    private var isInVaultTab = false
    private var isInIptvTab = false
    private var isVaultSessionUnlocked = false
    private var isShowingFolderVideos = false
    private var currentOpenedFolder: VideoFolder? = null
    private var currentOpenedFolderName: String? = null
    private var isSearchActive = false
    private var pendingDeleteVideo: VideoItem? = null
    private var loadMediaJob: Job? = null
    private var iptvSyncJob: Job? = null

    // Settings & Sub-Settings Back-Stack + Persistent Full-Screen Host (eliminates window close/open flicker)
    private val settingsBackStack = ArrayDeque<() -> Unit>()
    private var isNavigatingToSubDialog = false
    private var activeFullScreenDialog: AlertDialog? = null
    private var activeFullScreenHost: FrameLayout? = null
    private var activeFullScreenCardView: View? = null
    private var fullScreenMenuRenderToken: Int = 0
    private var isFullScreenDialogClosing: Boolean = false

    // Instant Debounced Search StateFlow (280ms debounce + distinctUntilChanged + flatMapLatest)
    private val searchQueryFlow = MutableStateFlow("")

    // IPTV Navigation Hierarchy State (preserved when switching tabs)
    // 0 = Sources List, 1 = Source Dashboard (Live/Movies/Series/Radio), 2 = Categories, 3 = Streams/Series List, 4 = Series Seasons, 5 = Season Episodes
    private var iptvNavLevel = 0
    private var activeIptvSource: IptvSource? = null
    private var activeIptvContentType: IptvContentType? = null
    private var activeIptvCategoryId: String? = null
    private var activeIptvCategoryName: String = ""
    private var activeIptvSeriesInfo: IptvSeriesInfoResult? = null
    private var activeIptvSeasonGroup: IptvSeasonGroup? = null
    private var currentOpenedSeriesStreamItem: IptvStreamItem? = null
    // 0 = All / Categories, 1 = Favorites ❤️, 2 = Continue Watching / History ⏱️
    private var iptvSectionSubTab = 0
    private var lastBackExitPressMs = 0L
    private var currentAnimatedBgColor = Color.parseColor("#090D16")
    private var currentAnimatedSurfaceColor = Color.parseColor("#131C2E")
    private var currentAnimatedAccentColor = Color.parseColor("#38BDF8")

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
            } catch (_: Exception) {}
            handleVideoSelection(it, it.lastPathSegment ?: getString(R.string.open_external_file))
        }
    }

    private var pendingM3uFileSourceName: String = ""
    private val m3uFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
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
            currentMainTab = savedInstanceState.getInt("STATE_MAIN_TAB", 0)
            isInVaultTab = (currentMainTab == 1)
            isInIptvTab = (currentMainTab == 2)
            isVaultSessionUnlocked = savedInstanceState.getBoolean("STATE_VAULT_UNLOCKED", false)
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
        outState.putBoolean("STATE_VAULT_UNLOCKED", isVaultSessionUnlocked)
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

    override fun onResume() {
        super.onResume()
        applyUserTheme()
        if (currentMainTab == 2) {
            // In IPTV tab: keep exact category/season/episode list & scroll position intact!
            adapter.notifyDataSetChanged()
        } else if (hasVideoAccessPermission()) {
            if (allDiscoveredVideos.isEmpty()) {
                loadVideoFoldersOnly()
            } else {
                // Keep exact opened folder and RecyclerView scroll position intact when returning from PlayerActivity,
                // while refreshing the dimmed "last played in folder" state and stopped-at timestamp!
                adapter.notifyDataSetChanged()
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
    private fun isUsingLimitedAccessMode(): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val fullGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            val partialGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            return !fullGranted && partialGranted
        }
        return false
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    /**
     * Premium UI Engine: Builds a sleek, floating, theme-harmonious card popup dialog
     * with a glowing top pill, bold header, subtitle badge, and tactile option cards!
     */
    private fun pushSettingsBackStack(reopenParent: () -> Unit) {
        settingsBackStack.addLast(reopenParent)
    }

    private fun popSettingsBackStack(): Boolean {
        val parent = settingsBackStack.removeLastOrNull() ?: return false
        parent.invoke()
        return true
    }

    private fun bindSubDialogBackNavigation(dialog: AlertDialog, btnCancelView: View? = null) {
        btnCancelView?.setOnClickListener {
            isNavigatingToSubDialog = true
            dialog.dismiss()
            isNavigatingToSubDialog = false
            popSettingsBackStack()
        }
        dialog.setOnCancelListener {
            if (!isNavigatingToSubDialog) {
                popSettingsBackStack()
            }
        }
    }

    private fun animateDialogCardEntrance(cardView: View, staggeredRows: List<View> = emptyList()) {
        cardView.alpha = 0f
        cardView.translationY = dp(26).toFloat()
        cardView.scaleX = 0.94f
        cardView.scaleY = 0.94f
        cardView.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .start()

        val rowInterp = DecelerateInterpolator(1.5f)
        staggeredRows.forEachIndexed { idx, row ->
            row.alpha = 0f
            row.translationY = dp(14).toFloat()
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((idx.coerceAtMost(10) * 22L) + 45L)
                .setDuration(210L)
                .setInterpolator(rowInterp)
                .start()
        }
    }

    private fun closeActiveFullScreenDialogSmoothly(onFinished: () -> Unit = {}) {
        val dialog = activeFullScreenDialog
        val cardView = activeFullScreenCardView
        if (dialog == null || !dialog.isShowing) {
            isFullScreenDialogClosing = false
            activeFullScreenDialog = null
            activeFullScreenHost = null
            activeFullScreenCardView = null
            onFinished()
            return
        }
        if (cardView == null) {
            isFullScreenDialogClosing = false
            isNavigatingToSubDialog = true
            runCatching { dialog.dismiss() }
            isNavigatingToSubDialog = false
            activeFullScreenDialog = null
            activeFullScreenHost = null
            activeFullScreenCardView = null
            onFinished()
            return
        }
        val closeToken = fullScreenMenuRenderToken
        isFullScreenDialogClosing = true
        cardView.animate().cancel()
        cardView.animate()
            .alpha(0f)
            .translationY(dp(18).toFloat())
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(135L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!isFullScreenDialogClosing || fullScreenMenuRenderToken != closeToken) {
                    return@withEndAction
                }
                isFullScreenDialogClosing = false
                isNavigatingToSubDialog = true
                if (dialog.isShowing) {
                    runCatching { dialog.dismiss() }
                }
                isNavigatingToSubDialog = false
                activeFullScreenDialog = null
                activeFullScreenHost = null
                activeFullScreenCardView = null
                onFinished()
            }
            .start()
    }

    private fun navigateBackInSettingsOrClose() {
        if (!popSettingsBackStack()) {
            closeActiveFullScreenDialogSmoothly()
        }
    }

    /**
     * Renders any full-screen Settings or Menu page inside a single persistent Full-Screen AlertDialog host.
     * Navigating forward or backward swaps views in-place over an opaque surface background so the underlying
     * MainActivity (Video Folders) never flashes for even a single frame!
     */
    private fun presentInFullScreenHost(
        pageScroll: View,
        cardContainer: View,
        animatedRows: List<View>,
        surfaceColor: Int
    ) {
        activeFullScreenCardView?.animate()?.cancel()
        isFullScreenDialogClosing = false
        fullScreenMenuRenderToken++
        activeFullScreenCardView = cardContainer

        val existingDialog = activeFullScreenDialog
        val existingHost = activeFullScreenHost
        if (existingDialog != null && existingDialog.isShowing && existingHost != null) {
            existingHost.setBackgroundColor(surfaceColor)
            existingDialog.window?.setBackgroundDrawable(ColorDrawable(surfaceColor))
            existingDialog.window?.statusBarColor = surfaceColor
            existingDialog.window?.navigationBarColor = surfaceColor
            existingHost.removeAllViews()
            existingHost.addView(
                pageScroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            animateDialogCardEntrance(cardContainer, animatedRows)
            return
        }

        val host = FrameLayout(this).apply {
            setBackgroundColor(surfaceColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        host.addView(
            pageScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val dialog = AlertDialog.Builder(this, R.style.Theme_PureVideoPlayer_FullScreenDialog)
            .setView(host)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(surfaceColor))
        dialog.window?.statusBarColor = surfaceColor
        dialog.window?.navigationBarColor = surfaceColor

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                navigateBackInSettingsOrClose()
                true
            } else {
                false
            }
        }

        dialog.setOnCancelListener {
            activeFullScreenDialog = null
            activeFullScreenHost = null
            activeFullScreenCardView = null
            if (!isNavigatingToSubDialog) {
                popSettingsBackStack()
            }
        }

        activeFullScreenDialog = dialog
        activeFullScreenHost = host
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        animateDialogCardEntrance(cardContainer, animatedRows)
    }

    /**
     * Premium UI Engine: Builds a sleek, full-screen, theme-harmonious page
     * with fluid spring entrance, staggered row cascade, and zero-flicker in-place navigation!
     */
    private fun showPremiumMenuDialog(
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
            isFillViewport = true
            setBackgroundColor(surface)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            setBackgroundColor(surface)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        outerScroll.addView(cardContainer)

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

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(14))
        }

        val tvHeaderIcon = TextView(this).apply {
            text = headerIcon
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(14)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)))
                setStroke(dp(1), Color.argb(80, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(primaryText)
            textSize = 17.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
        }
        headerTextCol.addView(tvTitle)

        if (!subtitle.isNullOrBlank()) {
            val tvSubtitle = TextView(this).apply {
                text = subtitle
                setTextColor(accent)
                textSize = 12.5f
                setPadding(0, dp(3), 0, 0)
            }
            headerTextCol.addView(tvSubtitle)
        }

        headerRow.addView(tvHeaderIcon)
        headerRow.addView(headerTextCol)
        cardContainer.addView(headerRow)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(12)
            }
            setBackgroundColor(if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#22FFFFFF"))
        }
        cardContainer.addView(divider)

        val animatedRows = mutableListOf<View>()

        for (item in items) {
            if (!item.sectionHeader.isNullOrBlank()) {
                val tvSec = TextView(this).apply {
                    text = item.sectionHeader
                    setTextColor(accent)
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(6), dp(10), dp(6), dp(6))
                }
                cardContainer.addView(tvSec)
                animatedRows.add(tvSec)
            }
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(9)
                }
                val strokeC = when {
                    item.isDanger -> Color.parseColor("#55F43F5E")
                    item.isAccentHighlighted -> Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent))
                    else -> if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#1FFFFFFF")
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(bg)
                    setStroke(dp(1), strokeC)
                }
            }

            val iconBadge = TextView(this).apply {
                text = item.iconEmoji
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(12)
                }
                val badgeBg = if (item.isDanger) {
                    Color.parseColor("#28F43F5E")
                } else {
                    Color.argb(32, Color.red(accent), Color.green(accent), Color.blue(accent))
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
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
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            textCol.addView(tvItemTitle)

            if (!item.subtitle.isNullOrBlank()) {
                val tvItemSub = TextView(this).apply {
                    text = item.subtitle
                    setTextColor(if (item.isAccentHighlighted) accent else secondaryText)
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                }
                textCol.addView(tvItemSub)
            }

            val tvArrow = TextView(this).apply {
                text = "›"
                setTextColor(if (item.isDanger) Color.parseColor("#F43F5E") else accent)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, dp(2), 0)
                visibility = if (item.isInteractive) View.VISIBLE else View.GONE
            }

            rowCard.addView(iconBadge)
            rowCard.addView(textCol)
            rowCard.addView(tvArrow)

            if (item.isInteractive) {
                rowCard.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(85L).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(OvershootInterpolator(1.3f)).start()
                    }
                    false
                }

                rowCard.setOnClickListener {
                    val tokenBefore = fullScreenMenuRenderToken
                    val stackBefore = settingsBackStack.size
                    item.onClick()
                    if (fullScreenMenuRenderToken == tokenBefore && settingsBackStack.size <= stackBefore && settingsBackStack.isEmpty()) {
                        closeActiveFullScreenDialogSmoothly()
                    }
                }
            }

            cardContainer.addView(rowCard)
            animatedRows.add(rowCard)
        }

        val hasParentInStack = settingsBackStack.isNotEmpty()
        val btnClose = TextView(this).apply {
            text = if (hasParentInStack) getString(R.string.back_to_previous_menu) else getString(R.string.close)
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#18FFFFFF"))
            }
            setOnClickListener {
                navigateBackInSettingsOrClose()
            }
        }
        cardContainer.addView(btnClose)

        presentInFullScreenHost(outerScroll, cardContainer, animatedRows, surface)
    }

    private fun applyUserTheme(animated: Boolean = false) {
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
        adapter.notifyDataSetChanged()
    }

    private fun updateResumeLastFab() {
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

    private fun updateBottomTabsVisualState() {
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

    private fun updateRecyclerLayoutManager() {
        val gridEnabled = PlaybackStore.isGridLayoutEnabled(this)
        adapter.isGridMode = gridEnabled
        val currentLm = binding.recyclerView.layoutManager
        if (gridEnabled) {
            if (currentLm !is GridLayoutManager) {
                binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
            }
        } else {
            if (currentLm is GridLayoutManager || currentLm == null) {
                binding.recyclerView.layoutManager = LinearLayoutManager(this)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateIptvSubTabsVisualState() {
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
        val favCount = if (src != null) IptvRepository.getFavoriteItems(this, src.id, cType).size else 0
        val recentCount = if (src != null) IptvRepository.getRecentWatchedItems(this, src.id, cType).size else 0

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
    private fun showFolderLockMenu(folder: VideoFolder) {
        settingsBackStack.clear()
        val isLocked = PlaybackStore.isFolderLocked(this, folder.name)
        val statusSubtitle = if (isLocked) {
            getString(R.string.folder_locked_badge, folder.videos.size)
        } else {
            getString(R.string.videos_count_format, folder.videos.size)
        }

        val items = mutableListOf(
            PremiumMenuItem(
                iconEmoji = if (isLocked) "🔓" else "🔒",
                title = if (isLocked) getString(R.string.folder_menu_unlock) else getString(R.string.folder_menu_lock),
                subtitle = getString(R.string.folder_lock_item_sub),
                isAccentHighlighted = true,
                onClick = {
                    if (!PlaybackStore.hasPrivacyPin(this)) {
                        showFirstTimeSetupPinDialog {
                            isVaultSessionUnlocked = true
                            toggleSingleFolderLock(folder.name)
                        }
                    } else if (isInVaultTab && isVaultSessionUnlocked) {
                        toggleSingleFolderLock(folder.name)
                    } else {
                        authenticatePrivacyAccess(folder.name) {
                            isVaultSessionUnlocked = true
                            toggleSingleFolderLock(folder.name)
                        }
                    }
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🛡️",
                title = getString(R.string.privacy_select_locked_folders, PlaybackStore.getLockedFolders(this).size),
                subtitle = getString(R.string.privacy_manage_all_sub),
                onClick = {
                    openPrivacyControlSection()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔑",
                title = getString(R.string.privacy_change_pin_option),
                subtitle = getString(R.string.privacy_change_pin_sub),
                onClick = {
                    if (!PlaybackStore.hasPrivacyPin(this)) {
                        showFirstTimeSetupPinDialog()
                    } else {
                        showChangePasswordWithCurrentVerifyDialog()
                    }
                }
            )
        )

        if (PlaybackStore.hasPrivacyPin(this)) {
            items.add(
                PremiumMenuItem(
                    iconEmoji = "🗑️",
                    title = getString(R.string.privacy_remove_pin_option),
                    subtitle = getString(R.string.privacy_remove_pin_sub),
                    isDanger = true,
                    onClick = {
                        showRemovePasswordVerifyDialog()
                    }
                )
            )
        }

        showPremiumMenuDialog(
            headerIcon = if (isLocked) "🔒" else "📁",
            title = folder.name,
            subtitle = statusSubtitle,
            items = items
        )
    }

    private fun toggleSingleFolderLock(folderName: String) {
        val currentLocked = PlaybackStore.getLockedFolders(this).toMutableSet()
        val nowLocked = if (currentLocked.contains(folderName)) {
            currentLocked.remove(folderName)
            false
        } else {
            currentLocked.add(folderName)
            true
        }
        PlaybackStore.setLockedFolders(this, currentLocked)
        applyFolderVisibilityAndRefreshUI()
        updateResumeLastFab()
        val msgRes = if (nowLocked) R.string.folder_moved_to_vault_toast else R.string.folder_restored_from_vault_toast
        Toast.makeText(this, getString(msgRes, folderName), Toast.LENGTH_SHORT).show()
    }

    private fun authenticatePrivacyAccess(subtitle: String, onAuthenticated: () -> Unit) {
        if (!PlaybackStore.hasPrivacyPin(this)) {
            showFirstTimeSetupPinDialog(onPinSaved = onAuthenticated)
            return
        }

        if (PlaybackStore.isBiometricEnabled(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            when (val cipherStatus = BiometricHardwareGuard.prepareCipherForBiometricPrompt(this)) {
                is BiometricHardwareGuard.CipherResult.Ready -> {
                    try {
                        val cancellationSignal = CancellationSignal()
                        val prompt = BiometricPrompt.Builder(this)
                            .setTitle(getString(R.string.privacy_biometric_title))
                            .setSubtitle(subtitle)
                            .setDescription(getString(R.string.privacy_biometric_desc))
                            .setNegativeButton(
                                getString(R.string.privacy_use_pin_btn),
                                mainExecutor,
                                DialogInterface.OnClickListener { _, _ ->
                                    showPinUnlockDialog(subtitle, onAuthenticated, allowBiometricRetry = true)
                                }
                            )
                            .build()

                        prompt.authenticate(
                            BiometricPrompt.CryptoObject(cipherStatus.cipher),
                            cancellationSignal,
                            mainExecutor,
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                                    super.onAuthenticationSucceeded(result)
                                    runOnUiThread { onAuthenticated() }
                                }

                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                                    super.onAuthenticationError(errorCode, errString)
                                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                                        errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                    ) {
                                        runOnUiThread {
                                            showPinUnlockDialog(subtitle, onAuthenticated, allowBiometricRetry = false)
                                        }
                                    }
                                }
                            }
                        )
                        return
                    } catch (_: Exception) {
                        showPinUnlockDialog(subtitle, onAuthenticated, allowBiometricRetry = false)
                        return
                    }
                }
                is BiometricHardwareGuard.CipherResult.EnrollmentChangedOrUnbound -> {
                    Toast.makeText(
                        this,
                        getString(R.string.privacy_new_fingerprint_blocked_toast),
                        Toast.LENGTH_LONG
                    ).show()
                    showPinUnlockDialog(
                        subtitle = getString(R.string.privacy_new_fingerprint_warning),
                        onAuthenticated = onAuthenticated,
                        allowBiometricRetry = false
                    )
                    return
                }
                is BiometricHardwareGuard.CipherResult.Unavailable -> {
                    showPinUnlockDialog(subtitle, onAuthenticated, allowBiometricRetry = false)
                    return
                }
            }
        }

        showPinUnlockDialog(subtitle, onAuthenticated, allowBiometricRetry = false)
    }

    private fun stylePremiumInputBox(editText: EditText, bg: Int, accent: Int) {
        editText.setTextColor(PlaybackStore.getPrimaryTextColor(this))
        editText.setHintTextColor(PlaybackStore.getSecondaryTextColor(this))
        editText.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(bg)
            setStroke(dp(1), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    private fun showPinUnlockDialog(
        subtitle: String,
        onAuthenticated: () -> Unit,
        allowBiometricRetry: Boolean
    ) {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val tvTitle = TextView(this).apply {
            text = getString(R.string.privacy_unlock_title)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }

        val tvSub = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#FACC15"))
            textSize = 13.5f
            setPadding(0, dp(6), 0, dp(16))
        }

        val etPin = EditText(this).apply {
            hint = getString(R.string.privacy_enter_pin_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        stylePremiumInputBox(etPin, bg, accent)

        container.addView(tvTitle)
        container.addView(tvSub)
        container.addView(etPin)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (allowBiometricRetry && PlaybackStore.isBiometricEnabled(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val btnBio = TextView(this).apply {
                text = getString(R.string.privacy_use_fingerprint_btn)
                setTextColor(accent)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    dialog.dismiss()
                    authenticatePrivacyAccess(subtitle, onAuthenticated)
                }
            }
            btnRow.addView(btnBio)
        }

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        bindSubDialogBackNavigation(dialog, btnCancel)

        val btnUnlock = TextView(this).apply {
            text = getString(R.string.privacy_unlock_btn)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                val entered = etPin.text.toString()
                if (PlaybackStore.verifyPrivacyPin(this@MainActivity, entered)) {
                    dialog.dismiss()
                    BiometricHardwareGuard.bindKeyAfterPasswordVerified(this@MainActivity)
                    onAuthenticated()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_wrong_pin_toast), Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnUnlock)
        container.addView(btnRow)

        dialog.show()
    }

    private fun showFirstTimeSetupPinDialog(onPinSaved: () -> Unit = {}) {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val tvTitle = TextView(this).apply {
            text = getString(R.string.privacy_setup_title)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }

        val tvHint = TextView(this).apply {
            text = getString(R.string.privacy_setup_pin_desc)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13.5f
            setPadding(0, dp(6), 0, dp(16))
        }

        val etNewPin = EditText(this).apply {
            hint = getString(R.string.privacy_new_pin_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        stylePremiumInputBox(etNewPin, bg, accent)

        container.addView(tvTitle)
        container.addView(tvHint)
        container.addView(etNewPin)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bindSubDialogBackNavigation(dialog, btnCancel)

        val btnSave = TextView(this).apply {
            text = getString(R.string.save)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                val newPin = etNewPin.text.toString().trim()
                if (newPin.length < 2) {
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_pin_too_short), Toast.LENGTH_SHORT).show()
                } else {
                    isNavigatingToSubDialog = true
                    dialog.dismiss()
                    isNavigatingToSubDialog = false
                    PlaybackStore.setPrivacyPin(this@MainActivity, newPin)
                    BiometricHardwareGuard.bindKeyAfterPasswordVerified(this@MainActivity)
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_pin_saved_toast), Toast.LENGTH_SHORT).show()
                    popSettingsBackStack()
                    onPinSaved()
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        container.addView(btnRow)
        dialog.show()
    }

    private fun showChangePasswordWithCurrentVerifyDialog() {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val tvTitle = TextView(this).apply {
            text = getString(R.string.privacy_change_pin_title)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        }

        val etCurrentPin = EditText(this).apply {
            hint = getString(R.string.privacy_current_pin_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        stylePremiumInputBox(etCurrentPin, bg, accent)

        val etNewPin = EditText(this).apply {
            hint = getString(R.string.privacy_new_pin_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        stylePremiumInputBox(etNewPin, bg, accent)

        container.addView(tvTitle)
        container.addView(etCurrentPin)
        container.addView(etNewPin)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bindSubDialogBackNavigation(dialog, btnCancel)

        val btnSave = TextView(this).apply {
            text = getString(R.string.save)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                val currentInput = etCurrentPin.text.toString()
                val newPin = etNewPin.text.toString().trim()
                if (!PlaybackStore.verifyPrivacyPin(this@MainActivity, currentInput)) {
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_wrong_pin_toast), Toast.LENGTH_SHORT).show()
                } else if (newPin.length < 2) {
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_pin_too_short), Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    PlaybackStore.setPrivacyPin(this@MainActivity, newPin)
                    BiometricHardwareGuard.bindKeyAfterPasswordVerified(this@MainActivity)
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_pin_saved_toast), Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        container.addView(btnRow)
        dialog.show()
    }

    private fun openPrivacyControlSection() {
        if (!PlaybackStore.hasPrivacyPin(this)) {
            showFirstTimeSetupPinDialog {
                showPrivacyDashboardDialog()
            }
        } else {
            authenticatePrivacyAccess(getString(R.string.setting_privacy_section)) {
                showPrivacyDashboardDialog()
            }
        }
    }

    private fun showRemovePasswordVerifyDialog() {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.parseColor("#88F43F5E"))
            }
        }

        val tvTitle = TextView(this).apply {
            text = getString(R.string.privacy_remove_pin_title)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }

        val tvDesc = TextView(this).apply {
            text = getString(R.string.privacy_remove_pin_confirm_desc)
            setTextColor(Color.parseColor("#FDA4AF"))
            textSize = 13.5f
            setPadding(0, dp(6), 0, dp(16))
        }

        val etCurrentPin = EditText(this).apply {
            hint = getString(R.string.privacy_current_pin_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        stylePremiumInputBox(etCurrentPin, bg, accent)

        container.addView(tvTitle)
        container.addView(tvDesc)
        container.addView(etCurrentPin)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bindSubDialogBackNavigation(dialog, btnCancel)

        val btnDelete = TextView(this).apply {
            text = getString(R.string.privacy_remove_pin_action)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E11D48"))
            }
            setOnClickListener {
                val entered = etCurrentPin.text.toString()
                if (!PlaybackStore.verifyPrivacyPin(this@MainActivity, entered)) {
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_wrong_pin_toast), Toast.LENGTH_SHORT).show()
                } else {
                    isNavigatingToSubDialog = true
                    dialog.dismiss()
                    isNavigatingToSubDialog = false
                    PlaybackStore.clearPrivacyPinAndLocks(this@MainActivity)
                    BiometricHardwareGuard.clearBiometricKey(this@MainActivity)
                    isVaultSessionUnlocked = false
                    isInVaultTab = false
                    applyFolderVisibilityAndRefreshUI()
                    updateResumeLastFab()
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_pin_removed_toast), Toast.LENGTH_LONG).show()
                    settingsBackStack.removeLastOrNull()
                    popSettingsBackStack()
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnDelete)
        container.addView(btnRow)
        dialog.show()
    }

    private fun showPrivacyDashboardDialog() {
        val bioEnabled = PlaybackStore.isBiometricEnabled(this)
        val lockedCount = PlaybackStore.getLockedFolders(this).size
        val bioStatus = if (bioEnabled) getString(R.string.privacy_bio_on) else getString(R.string.privacy_bio_off)

        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "📁",
                title = getString(R.string.privacy_select_locked_folders, lockedCount),
                subtitle = getString(R.string.privacy_locked_folders_dialog_title),
                isAccentHighlighted = true,
                onClick = { pushSettingsBackStack { showPrivacyDashboardDialog() }; showLockedFoldersMultiPickerDialog() }
            ),
            PremiumMenuItem(
                iconEmoji = "👆",
                title = getString(R.string.privacy_toggle_biometric_option, bioStatus),
                subtitle = getString(R.string.privacy_biometric_desc),
                onClick = {
                    val nextState = !bioEnabled
                    PlaybackStore.setBiometricEnabled(this, nextState)
                    val stateStr = if (nextState) getString(R.string.privacy_bio_on) else getString(R.string.privacy_bio_off)
                    Toast.makeText(this, stateStr, Toast.LENGTH_SHORT).show()
                    showPrivacyDashboardDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔑",
                title = getString(R.string.privacy_change_pin_option),
                subtitle = getString(R.string.privacy_change_pin_sub),
                onClick = { pushSettingsBackStack { showPrivacyDashboardDialog() }; showChangePasswordWithCurrentVerifyDialog() }
            ),
            PremiumMenuItem(
                iconEmoji = "🗑️",
                title = getString(R.string.privacy_remove_pin_option),
                subtitle = getString(R.string.privacy_remove_pin_sub),
                isDanger = true,
                onClick = { pushSettingsBackStack { showPrivacyDashboardDialog() }; showRemovePasswordVerifyDialog() }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "🔒",
            title = getString(R.string.setting_privacy_section),
            subtitle = getString(R.string.privacy_locked_saved_toast, lockedCount),
            items = items
        )
    }

    /**
     * Premium Multi-Choice Folder Picker (used for both Locking Folders and Selecting Visible Folders).
     */
    private fun showPremiumFolderMultiPicker(
        headerIcon: String,
        title: String,
        subtitle: String,
        initialSelected: Set<String>,
        onSaveSelection: (Set<String>) -> Unit,
        neutralActionLabel: String? = null,
        onNeutralAction: (() -> Unit)? = null
    ) {
        if (allDiscoveredFolders.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_videos_found), Toast.LENGTH_SHORT).show()
            return
        }

        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)

        val outerScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }

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
        outerScroll.addView(cardContainer)

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

        val tvTitle = TextView(this).apply {
            text = "$headerIcon  $title"
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 17.5f
            setTypeface(typeface, Typeface.BOLD)
        }
        val tvSub = TextView(this).apply {
            text = subtitle
            setTextColor(accent)
            textSize = 12.5f
            setPadding(0, dp(4), 0, dp(12))
        }
        cardContainer.addView(tvTitle)
        cardContainer.addView(tvSub)

        val selectedSet = initialSelected.toMutableSet()
        val checkBoxes = mutableListOf<Pair<String, CheckBox>>()

        for (folder in allDiscoveredFolders) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(bg)
                    setStroke(dp(1), Color.parseColor("#1FFFFFFF"))
                }
            }

            val cb = CheckBox(this).apply {
                isChecked = selectedSet.contains(folder.name)
                buttonTintList = ColorStateList.valueOf(accent)
            }
            checkBoxes.add(Pair(folder.name, cb))

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                }
            }

            val tvName = TextView(this).apply {
                text = folder.name
                setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            val tvCount = TextView(this).apply {
                text = getString(R.string.videos_count_format, folder.videos.size)
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
            }
            textCol.addView(tvName)
            textCol.addView(tvCount)

            row.addView(cb)
            row.addView(textCol)

            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedSet.add(folder.name) else selectedSet.remove(folder.name)
            }
            row.setOnClickListener {
                cb.isChecked = !cb.isChecked
            }

            cardContainer.addView(row)
        }

        val dialog = AlertDialog.Builder(this).setView(outerScroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }

        if (!neutralActionLabel.isNullOrBlank() && onNeutralAction != null) {
            val btnNeutral = TextView(this).apply {
                text = neutralActionLabel
                setTextColor(accent)
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    dialog.dismiss()
                    onNeutralAction()
                }
            }
            actionsRow.addView(btnNeutral)
        }

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        bindSubDialogBackNavigation(dialog, btnCancel)

        val btnSave = TextView(this).apply {
            text = getString(R.string.save)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                dialog.dismiss()
                onSaveSelection(selectedSet)
            }
        }

        actionsRow.addView(btnCancel)
        actionsRow.addView(btnSave)
        cardContainer.addView(actionsRow)

        dialog.show()
    }

    private fun showLockedFoldersMultiPickerDialog() {
        showPremiumFolderMultiPicker(
            headerIcon = "🔒",
            title = getString(R.string.privacy_locked_folders_dialog_title),
            subtitle = getString(R.string.privacy_biometric_desc),
            initialSelected = PlaybackStore.getLockedFolders(this),
            onSaveSelection = { newLocked ->
                PlaybackStore.setLockedFolders(this, newLocked)
                applyFolderVisibilityAndRefreshUI()
                updateResumeLastFab()
                Toast.makeText(
                    this,
                    getString(R.string.privacy_locked_saved_toast, newLocked.size),
                    Toast.LENGTH_SHORT
                ).show()
                popSettingsBackStack()
            }
        )
    }

    /**
     * Triggered by Long-Pressing "Video Folders / مجلدات الفيديو" at the top of the app!
     * Styled as a Premium Popup Card with full support for Android 14+ Allow Limited Access!
     */
    private fun showFolderVisibilityModeDialog() {
        val currentMode = PlaybackStore.getFolderFilterMode(this)
        val allowedCount = PlaybackStore.getAllowedFolders(this).size
        val isLimited = isUsingLimitedAccessMode()

        val items = mutableListOf(
            PremiumMenuItem(
                iconEmoji = "🌐",
                title = getString(R.string.folder_mode_all),
                subtitle = if (currentMode == 0) "✅ " + getString(R.string.active_mode_badge) else getString(R.string.folder_mode_all_sub),
                isAccentHighlighted = (currentMode == 0),
                onClick = {
                    PlaybackStore.setFolderFilterMode(this, 0)
                    isShowingFolderVideos = false
                    currentOpenedFolder = null
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "✅",
                title = getString(R.string.folder_mode_selected_only, allowedCount),
                subtitle = if (currentMode == 1) "✅ " + getString(R.string.active_mode_badge) else getString(R.string.folder_picker_dialog_title),
                isAccentHighlighted = (currentMode == 1),
                onClick = {
                    pushSettingsBackStack { showFolderVisibilityModeDialog() }
                    showAllowedFoldersMultiPickerDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🎬",
                title = getString(R.string.folder_mode_all_videos_flat),
                subtitle = if (currentMode == 2) "✅ " + getString(R.string.active_mode_badge) else getString(R.string.folder_mode_flat_sub),
                isAccentHighlighted = (currentMode == 2),
                onClick = {
                    PlaybackStore.setFolderFilterMode(this, 2)
                    isShowingFolderVideos = false
                    currentOpenedFolder = null
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            )
        )

        // If on Android 14+ (or Limited Access mode), add option to re-open Android's system "Allow Limited Access" video selector!
        if (Build.VERSION.SDK_INT >= 34) {
            items.add(
                PremiumMenuItem(
                    iconEmoji = "➕",
                    title = getString(R.string.limited_access_manage_title),
                    subtitle = if (isLimited) getString(R.string.limited_access_active_sub) else getString(R.string.limited_access_manage_sub),
                    isAccentHighlighted = isLimited,
                    onClick = {
                        requestStoragePermission()
                    }
                )
            )
        }

        showPremiumMenuDialog(
            headerIcon = "📂",
            title = getString(R.string.setting_folder_visibility),
            subtitle = if (isLimited) getString(R.string.limited_access_active_sub) else getString(R.string.videos_count_format, allDiscoveredVideos.size),
            items = items
        )
    }

    private fun showAllowedFoldersMultiPickerDialog() {
        val savedAllowed = PlaybackStore.getAllowedFolders(this)
        val initialSet = if (savedAllowed.isEmpty()) {
            allDiscoveredFolders.map { it.name }.toSet()
        } else {
            savedAllowed
        }

        showPremiumFolderMultiPicker(
            headerIcon = "✅",
            title = getString(R.string.folder_picker_dialog_title),
            subtitle = getString(R.string.setting_folder_visibility),
            initialSelected = initialSet,
            onSaveSelection = { selectedSet ->
                if (selectedSet.isEmpty()) {
                    PlaybackStore.setFolderFilterMode(this, 0)
                    Toast.makeText(this, getString(R.string.folder_mode_all), Toast.LENGTH_SHORT).show()
                } else {
                    PlaybackStore.setAllowedFolders(this, selectedSet)
                    PlaybackStore.setFolderFilterMode(this, 1)
                    Toast.makeText(
                        this,
                        getString(R.string.folder_picker_saved_toast, selectedSet.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isShowingFolderVideos = false
                currentOpenedFolder = null
                applyFolderVisibilityAndRefreshUI()
                popSettingsBackStack()
            },
            neutralActionLabel = if (Build.VERSION.SDK_INT >= 34) getString(R.string.limited_access_add_more_btn) else getString(R.string.folder_mode_all),
            onNeutralAction = {
                if (Build.VERSION.SDK_INT >= 34 && isUsingLimitedAccessMode()) {
                    requestStoragePermission()
                } else {
                    PlaybackStore.setFolderFilterMode(this, 0)
                    isShowingFolderVideos = false
                    currentOpenedFolder = null
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            }
        )
    }

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
                    } catch (_: Exception) {
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {}
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
            } catch (_: Exception) {}
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

    private fun handleVideoSelection(uri: Uri, title: String) {
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

    private fun showResumeBehaviorSettingsDialog() {
        val currentMode = PlaybackStore.getResumeAskMode(this)
        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "❓",
                title = getString(R.string.resume_mode_ask_always),
                subtitle = if (currentMode == 0) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (currentMode == 0),
                onClick = {
                    PlaybackStore.setResumeAskMode(this, 0)
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "▶️",
                title = getString(R.string.resume_mode_always_resume),
                subtitle = if (currentMode == 1) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (currentMode == 1),
                onClick = {
                    PlaybackStore.setResumeAskMode(this, 1)
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔄",
                title = getString(R.string.resume_mode_always_restart),
                subtitle = if (currentMode == 2) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (currentMode == 2),
                onClick = {
                    PlaybackStore.setResumeAskMode(this, 2)
                    popSettingsBackStack()
                }
            )
        )
        showPremiumMenuDialog(
            headerIcon = "⏯️",
            title = getString(R.string.setting_resume_behavior_title),
            subtitle = null,
            items = items
        )
    }

    private fun attachFluidTouchAnimation(vararg views: View) {
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
                                val allStreams = IptvRepository.loadCachedStreams(this@MainActivity, src.id)
                                val matched = if (normQuery.isEmpty()) {
                                    allStreams.take(300)
                                } else {
                                    allStreams.filter { item ->
                                        item.normalizedTitle.contains(normQuery) || item.normalizedCategory.contains(normQuery)
                                    }.sortedBy { it.contentType.ordinal }.take(400)
                                }
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

    private fun openSearchBar() {
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

    private fun closeSearchBar() {
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

    private fun filterVideosByQuery(rawQuery: String) {
        if (!isSearchActive) return
        searchQueryFlow.value = rawQuery
    }

    /**
     * Premium Media Layout (List / Grid) & Comprehensive Sort Dialog!
     */
    private fun showSortAndLayoutDialog() {
        val isGrid = PlaybackStore.isGridLayoutEnabled(this)
        val sortMode = PlaybackStore.getSortOrderMode(this)

        val items = listOf(
            // Section 1: Media Layout (List vs Grid)
            PremiumMenuItem(
                iconEmoji = "📋",
                title = getString(R.string.layout_mode_list),
                subtitle = if (!isGrid) "✅ " + getString(R.string.active_mode_badge) else getString(R.string.layout_mode_list_sub),
                isAccentHighlighted = !isGrid,
                sectionHeader = getString(R.string.section_media_layout),
                onClick = {
                    PlaybackStore.setGridLayoutEnabled(this, false)
                    updateRecyclerLayoutManager()
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔲",
                title = getString(R.string.layout_mode_grid),
                subtitle = if (isGrid) "✅ " + getString(R.string.active_mode_badge) else getString(R.string.layout_mode_grid_sub),
                isAccentHighlighted = isGrid,
                onClick = {
                    PlaybackStore.setGridLayoutEnabled(this, true)
                    updateRecyclerLayoutManager()
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            ),
            // Section 2: Sort By
            PremiumMenuItem(
                iconEmoji = "📅",
                title = getString(R.string.sort_date_newest),
                subtitle = if (sortMode == 0) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (sortMode == 0),
                sectionHeader = getString(R.string.section_sort_by),
                onClick = {
                    PlaybackStore.setSortOrderMode(this, 0)
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🕰️",
                title = getString(R.string.sort_date_oldest),
                subtitle = if (sortMode == 1) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (sortMode == 1),
                onClick = {
                    PlaybackStore.setSortOrderMode(this, 1)
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔤",
                title = getString(R.string.sort_name_az),
                subtitle = if (sortMode == 2) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (sortMode == 2),
                onClick = {
                    PlaybackStore.setSortOrderMode(this, 2)
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔤",
                title = getString(R.string.sort_name_za),
                subtitle = if (sortMode == 3) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (sortMode == 3),
                onClick = {
                    PlaybackStore.setSortOrderMode(this, 3)
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "💾",
                title = getString(R.string.sort_size_largest),
                subtitle = if (sortMode == 4) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (sortMode == 4),
                onClick = {
                    PlaybackStore.setSortOrderMode(this, 4)
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "💾",
                title = getString(R.string.sort_size_smallest),
                subtitle = if (sortMode == 5) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (sortMode == 5),
                onClick = {
                    PlaybackStore.setSortOrderMode(this, 5)
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "⏱️",
                title = getString(R.string.sort_duration_longest),
                subtitle = if (sortMode == 6) "✅ " + getString(R.string.active_mode_badge) else null,
                isAccentHighlighted = (sortMode == 6),
                onClick = {
                    PlaybackStore.setSortOrderMode(this, 6)
                    applyFolderVisibilityAndRefreshUI()
                    popSettingsBackStack()
                }
            )
        )

        val currentLayoutName = if (isGrid) getString(R.string.layout_mode_grid) else getString(R.string.layout_mode_list)
        showPremiumMenuDialog(
            headerIcon = "📐",
            title = getString(R.string.sort_and_layout_title),
            subtitle = currentLayoutName,
            items = items
        )
    }

    private fun sortVideosList(videos: List<VideoItem>, sortMode: Int): List<VideoItem> {
        return when (sortMode) {
            0 -> videos.sortedByDescending { it.dateAddedSec }
            1 -> videos.sortedBy { it.dateAddedSec }
            2 -> videos.sortedBy { it.title.lowercase(Locale.getDefault()) }
            3 -> videos.sortedByDescending { it.title.lowercase(Locale.getDefault()) }
            4 -> videos.sortedByDescending { it.size }
            5 -> videos.sortedBy { it.size }
            6 -> videos.sortedByDescending { it.duration }
            else -> videos.sortedByDescending { it.dateAddedSec }
        }
    }

    private fun sortFoldersList(folders: List<VideoFolder>, sortMode: Int): List<VideoFolder> {
        val withSortedInnerVideos = folders.map { folder ->
            VideoFolder(folder.name, sortVideosList(folder.videos, sortMode))
        }
        return when (sortMode) {
            0 -> withSortedInnerVideos.sortedByDescending { f -> f.videos.maxOfOrNull { it.dateAddedSec } ?: 0L }
            1 -> withSortedInnerVideos.sortedBy { f -> f.videos.minOfOrNull { it.dateAddedSec } ?: 0L }
            2 -> withSortedInnerVideos.sortedBy { it.name.lowercase(Locale.getDefault()) }
            3 -> withSortedInnerVideos.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
            4 -> withSortedInnerVideos.sortedByDescending { f -> f.videos.sumOf { it.size } }
            5 -> withSortedInnerVideos.sortedBy { f -> f.videos.sumOf { it.size } }
            6 -> withSortedInnerVideos.sortedByDescending { f -> f.videos.sumOf { it.duration } }
            else -> withSortedInnerVideos
        }
    }

    /**
     * Premium Settings Menu: Styled with sleek cards, emoji badges, and live status subtitles!
     */
    /**
     * Clean, Uncluttered 2-Level Settings Hub:
     * Root screen shows only 4 clean Category Cards + Developer Credit.
     * Tapping any category opens its dedicated sub-page with smooth staggered animation & full Back-Stack support!
     */
    private fun showProSettingsDialog(clearStack: Boolean = false) {
        if (clearStack) {
            settingsBackStack.clear()
        }
        val hasPin = PlaybackStore.hasPrivacyPin(this)
        val lockedCount = PlaybackStore.getLockedFolders(this).size
        val privacySub = if (hasPin) {
            getString(R.string.privacy_settings_status_active, lockedCount)
        } else {
            getString(R.string.privacy_settings_status_unset)
        }

        val themeNames = arrayOf(
            getString(R.string.theme_cyber_blue),
            getString(R.string.theme_amoled_gold),
            getString(R.string.theme_emerald),
            getString(R.string.theme_crimson),
            getString(R.string.theme_violet),
            getString(R.string.theme_material_you),
            getString(R.string.theme_sunset_coral),
            getString(R.string.theme_teal_aqua)
        )
        val currentThemeName = themeNames[PlaybackStore.getThemeIndex(this).coerceIn(0, themeNames.size - 1)]
        val langTag = PlaybackStore.getAppLanguageTag(this)
        val langSub = when (langTag) {
            "ar" -> getString(R.string.lang_arabic)
            "en" -> getString(R.string.lang_english)
            else -> getString(R.string.lang_system_default)
        }
        val subLangs = PlaybackStore.getPreferredSubtitleLanguages(this).joinToString(" → ") { it.uppercase() }

        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "🛡️",
                title = getString(R.string.settings_hub_privacy_title),
                subtitle = privacySub,
                isAccentHighlighted = true,
                onClick = {
                    pushSettingsBackStack { showProSettingsDialog(false) }
                    showSettingsPrivacyFoldersPage()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🎨",
                title = getString(R.string.settings_hub_appearance_title),
                subtitle = "$currentThemeName • $langSub",
                onClick = {
                    pushSettingsBackStack { showProSettingsDialog(false) }
                    showSettingsAppearanceDisplayPage()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "💬",
                title = getString(R.string.settings_hub_subtitles_playback_title),
                subtitle = subLangs,
                onClick = {
                    pushSettingsBackStack { showProSettingsDialog(false) }
                    showSettingsSubtitlesPlaybackPage()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔊",
                title = getString(R.string.settings_hub_audio_title),
                subtitle = getAudioModeDisplayName(PlaybackStore.getAudioPresetMode(this)),
                isAccentHighlighted = true,
                onClick = {
                    pushSettingsBackStack { showProSettingsDialog(false) }
                    showSettingsAudioEqualizerPage()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🚀",
                title = getString(R.string.settings_hub_updates_title),
                subtitle = "v${BuildConfig.VERSION_NAME} • GitHub Auto-Updater",
                onClick = {
                    pushSettingsBackStack { showProSettingsDialog(false) }
                    openGitHubUpdaterFlow()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "ℹ️",
                title = getString(R.string.settings_hub_about_title),
                subtitle = getString(R.string.settings_hub_about_sub, BuildConfig.VERSION_NAME),
                isAccentHighlighted = true,
                onClick = {
                    pushSettingsBackStack { showProSettingsDialog(false) }
                    showAboutAppPage()
                }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "⚙️",
            title = getString(R.string.settings_header),
            subtitle = "Pure Video Player Premium",
            items = items
        )
    }

    private fun showSettingsPrivacyFoldersPage() {
        val hasPin = PlaybackStore.hasPrivacyPin(this)
        val lockedCount = PlaybackStore.getLockedFolders(this).size
        val privacySub = if (hasPin) {
            getString(R.string.privacy_settings_status_active, lockedCount)
        } else {
            getString(R.string.privacy_settings_status_unset)
        }
        val filterMode = PlaybackStore.getFolderFilterMode(this)
        val filterSub = when (filterMode) {
            1 -> getString(R.string.folder_picker_saved_toast, PlaybackStore.getAllowedFolders(this).size)
            2 -> getString(R.string.folder_mode_all_videos_flat)
            else -> getString(R.string.folder_mode_all)
        }

        val items = mutableListOf(
            PremiumMenuItem(
                iconEmoji = "🔒",
                title = getString(R.string.setting_privacy_section),
                subtitle = privacySub,
                isAccentHighlighted = true,
                onClick = {
                    pushSettingsBackStack { showSettingsPrivacyFoldersPage() }
                    openPrivacyControlSection()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "📂",
                title = getString(R.string.setting_folder_visibility),
                subtitle = filterSub,
                onClick = {
                    pushSettingsBackStack { showSettingsPrivacyFoldersPage() }
                    showFolderVisibilityModeDialog()
                }
            )
        )

        if (Build.VERSION.SDK_INT >= 34 && isUsingLimitedAccessMode()) {
            items.add(
                PremiumMenuItem(
                    iconEmoji = "➕",
                    title = getString(R.string.limited_access_manage_title),
                    subtitle = getString(R.string.limited_access_active_sub),
                    isAccentHighlighted = true,
                    onClick = { requestStoragePermission() }
                )
            )
        }

        showPremiumMenuDialog(
            headerIcon = "🛡️",
            title = getString(R.string.settings_hub_privacy_title),
            subtitle = privacySub,
            items = items
        )
    }

    private fun showSettingsAppearanceDisplayPage() {
        val langTag = PlaybackStore.getAppLanguageTag(this)
        val langSub = when (langTag) {
            "ar" -> getString(R.string.lang_arabic)
            "en" -> getString(R.string.lang_english)
            else -> getString(R.string.lang_system_default)
        }
        val themeNames = arrayOf(
            getString(R.string.theme_cyber_blue),
            getString(R.string.theme_amoled_gold),
            getString(R.string.theme_emerald),
            getString(R.string.theme_crimson),
            getString(R.string.theme_violet),
            getString(R.string.theme_material_you),
            getString(R.string.theme_sunset_coral),
            getString(R.string.theme_teal_aqua)
        )
        val currentThemeName = themeNames[PlaybackStore.getThemeIndex(this).coerceIn(0, themeNames.size - 1)]
        val orientIdx = PlaybackStore.getDefaultOrientationIndex(this)
        val orientSub = when (orientIdx) {
            0 -> getString(R.string.orientation_auto)
            2 -> getString(R.string.orientation_portrait)
            else -> getString(R.string.orientation_landscape)
        }

        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "🎨",
                title = getString(R.string.setting_app_theme),
                subtitle = currentThemeName,
                isAccentHighlighted = true,
                onClick = {
                    pushSettingsBackStack { showSettingsAppearanceDisplayPage() }
                    showThemeSelectorDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🌐",
                title = getString(R.string.setting_app_language),
                subtitle = langSub,
                onClick = {
                    pushSettingsBackStack { showSettingsAppearanceDisplayPage() }
                    showAppLanguageDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔄",
                title = getString(R.string.setting_default_orientation),
                subtitle = orientSub,
                onClick = {
                    pushSettingsBackStack { showSettingsAppearanceDisplayPage() }
                    showDefaultOrientationDialog()
                }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "🎨",
            title = getString(R.string.settings_hub_appearance_title),
            subtitle = "$currentThemeName • $langSub",
            items = items
        )
    }

    private fun showSettingsSubtitlesPlaybackPage() {
        val subLangs = PlaybackStore.getPreferredSubtitleLanguages(this).joinToString(" → ") { it.uppercase() }
        val subSize = PlaybackStore.getSubSizeSp(this).toInt()
        val subBgOpacity = PlaybackStore.getSubBgOpacityPct(this)
        val resumeSub = when (PlaybackStore.getResumeAskMode(this)) {
            1 -> getString(R.string.resume_mode_always_resume)
            2 -> getString(R.string.resume_mode_always_restart)
            else -> getString(R.string.resume_mode_ask_always)
        }

        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "🔤",
                title = getString(R.string.setting_subtitle_style),
                subtitle = "${subSize}sp • ${getString(R.string.style_bg_opacity_label, subBgOpacity)}",
                isAccentHighlighted = true,
                onClick = {
                    pushSettingsBackStack { showSettingsSubtitlesPlaybackPage() }
                    showSubtitleStyleDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "💬",
                title = getString(R.string.setting_subtitle_languages),
                subtitle = subLangs,
                onClick = {
                    pushSettingsBackStack { showSettingsSubtitlesPlaybackPage() }
                    showSubtitleLanguagePriorityDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🎯",
                title = getString(R.string.setting_reset_sub_position),
                subtitle = getString(R.string.sub_position_reset_toast),
                onClick = {
                    PlaybackStore.resetSubPosition(this)
                    Toast.makeText(this, getString(R.string.sub_position_reset_toast), Toast.LENGTH_SHORT).show()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "⏯️",
                title = getString(R.string.setting_resume_behavior_title),
                subtitle = resumeSub,
                onClick = {
                    pushSettingsBackStack { showSettingsSubtitlesPlaybackPage() }
                    showResumeBehaviorSettingsDialog()
                }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "💬",
            title = getString(R.string.settings_hub_subtitles_playback_title),
            subtitle = subLangs,
            items = items
        )
    }

    private fun getAudioModeDisplayName(mode: Int): String {
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

    private fun showSettingsAudioEqualizerPage() {
        val currentMode = PlaybackStore.getAudioPresetMode(this)
        val modeLabel = getAudioModeDisplayName(currentMode)
        val preAmp = PlaybackStore.getAudioPreAmpDb(this)
        val bands = PlaybackStore.getAudioEqBandsDb(this)
        val eqSummary = String.format(
            Locale.US,
            "Pre-Amp: %+.1fdB • [60Hz: %+.0f | 230Hz: %+.0f | 910Hz: %+.0f | 3.6kHz: %+.0f | 14kHz: %+.0f]",
            preAmp, bands[0], bands[1], bands[2], bands[3], bands[4]
        )

        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "🎭",
                title = getString(R.string.audio_preset_section_title),
                subtitle = modeLabel,
                isAccentHighlighted = true,
                onClick = {
                    pushSettingsBackStack { showSettingsAudioEqualizerPage() }
                    showAudioSoundModesPage()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🎚️",
                title = getString(R.string.audio_eq_customizer_title),
                subtitle = eqSummary,
                isAccentHighlighted = (currentMode == 7),
                onClick = {
                    pushSettingsBackStack { showSettingsAudioEqualizerPage() }
                    showGraphicEqualizerDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔄",
                title = getString(R.string.audio_reset_default_title),
                subtitle = getString(R.string.audio_reset_default_sub),
                onClick = {
                    PlaybackStore.resetAudioEqualizerToStandard(this)
                    Toast.makeText(this, getString(R.string.audio_reset_toast), Toast.LENGTH_SHORT).show()
                    showSettingsAudioEqualizerPage()
                }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "🔊",
            title = getString(R.string.settings_hub_audio_title),
            subtitle = modeLabel,
            items = items
        )
    }

    private fun showAudioSoundModesPage() {
        val activeMode = PlaybackStore.getAudioPresetMode(this)
        val modes = listOf(
            Triple(0, "🎵", Pair(getString(R.string.audio_mode_standard), getString(R.string.audio_mode_standard_sub))),
            Triple(1, "🎬", Pair(getString(R.string.audio_mode_cinematic), getString(R.string.audio_mode_cinematic_sub))),
            Triple(2, "🏛️", Pair(getString(R.string.audio_mode_hall), getString(R.string.audio_mode_hall_sub))),
            Triple(3, "🗣️", Pair(getString(R.string.audio_mode_voice), getString(R.string.audio_mode_voice_sub))),
            Triple(4, "🎸", Pair(getString(R.string.audio_mode_bass), getString(R.string.audio_mode_bass_sub))),
            Triple(5, "🏟️", Pair(getString(R.string.audio_mode_stadium), getString(R.string.audio_mode_stadium_sub))),
            Triple(6, "🌙", Pair(getString(R.string.audio_mode_night), getString(R.string.audio_mode_night_sub))),
            Triple(7, "🎛️", Pair(getString(R.string.audio_mode_custom), getString(R.string.audio_mode_custom_sub)))
        )

        val items = modes.map { (modeIdx, emoji, texts) ->
            val isSelected = (modeIdx == activeMode)
            val subText = if (isSelected) "✅ ${getString(R.string.active_mode_badge)} • ${texts.second}" else texts.second
            PremiumMenuItem(
                iconEmoji = emoji,
                title = texts.first,
                subtitle = subText,
                isAccentHighlighted = isSelected,
                onClick = {
                    if (modeIdx == 7) {
                        showGraphicEqualizerDialog()
                    } else {
                        PlaybackStore.setAudioPresetMode(this, modeIdx)
                        Toast.makeText(
                            this,
                            getString(R.string.audio_mode_applied_toast, texts.first),
                            Toast.LENGTH_SHORT
                        ).show()
                        popSettingsBackStack()
                    }
                }
            )
        }

        showPremiumMenuDialog(
            headerIcon = "🎭",
            title = getString(R.string.audio_preset_section_title),
            subtitle = getAudioModeDisplayName(activeMode),
            items = items
        )
    }

    private fun showGraphicEqualizerDialog() {
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val secondaryText = PlaybackStore.getSecondaryTextColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)
        val accent = PlaybackStore.getAccentColor(this)
        val isLight = PlaybackStore.isLightThemeActive(this)

        var currentPreAmp = PlaybackStore.getAudioPreAmpDb(this)
        val currentBands = PlaybackStore.getAudioEqBandsDb(this).copyOf()

        val outerScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }
        outerScroll.addView(container)

        val tvHeader = TextView(this).apply {
            text = "🎚️  " + getString(R.string.audio_eq_customizer_title)
            setTextColor(primaryText)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        val tvSub = TextView(this).apply {
            text = getString(R.string.audio_eq_customizer_sub)
            setTextColor(secondaryText)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        }
        container.addView(tvHeader)
        container.addView(tvSub)

        fun formatDb(v: Float): String = String.format(Locale.US, "%+.1f", v)

        // Pre-Amp Slider (-10dB to +12dB -> range 220 steps of 0.1dB)
        val tvPreAmp = TextView(this).apply {
            text = getString(R.string.audio_eq_preamp_label, formatDb(currentPreAmp))
            setTextColor(accent)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(4))
        }
        val seekPreAmp = SeekBar(this).apply {
            max = 220
            progress = ((currentPreAmp + 10f) * 10f).toInt().coerceIn(0, 220)
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentPreAmp = -10f + (p / 10f)
                    tvPreAmp.text = getString(R.string.audio_eq_preamp_label, formatDb(currentPreAmp))
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        container.addView(tvPreAmp)
        container.addView(seekPreAmp)

        val bandResIds = intArrayOf(
            R.string.audio_eq_band_60,
            R.string.audio_eq_band_230,
            R.string.audio_eq_band_910,
            R.string.audio_eq_band_3600,
            R.string.audio_eq_band_14000
        )

        val bandLabels = ArrayList<TextView>(5)
        val bandSeeks = ArrayList<SeekBar>(5)

        for (idx in 0 until 5) {
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(7)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(bg)
                    setStroke(dp(1), if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#1FFFFFFF"))
                }
            }
            val lbl = TextView(this).apply {
                text = getString(bandResIds[idx], formatDb(currentBands[idx]))
                setTextColor(primaryText)
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
            }
            val sb = SeekBar(this).apply {
                max = 300
                progress = ((currentBands[idx] + 15f) * 10f).toInt().coerceIn(0, 300)
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        currentBands[idx] = -15f + (p / 10f)
                        lbl.text = getString(bandResIds[idx], formatDb(currentBands[idx]))
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            bandLabels.add(lbl)
            bandSeeks.add(sb)
            rowCard.addView(lbl)
            rowCard.addView(sb)
            container.addView(rowCard)
        }

        val dialog = AlertDialog.Builder(this).setView(outerScroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }

        val btnReset = TextView(this).apply {
            text = getString(R.string.reset)
            setTextColor(accent)
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                currentPreAmp = 0f
                seekPreAmp.progress = 100
                for (i in 0 until 5) {
                    currentBands[i] = 0f
                    bandSeeks[i].progress = 150
                }
            }
        }

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(secondaryText)
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        bindSubDialogBackNavigation(dialog, btnCancel)

        val btnSave = TextView(this).apply {
            text = getString(R.string.save)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                isNavigatingToSubDialog = true
                dialog.dismiss()
                isNavigatingToSubDialog = false
                PlaybackStore.setAudioEqBandsDb(this@MainActivity, currentBands, currentPreAmp)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.audio_mode_applied_toast, getString(R.string.audio_mode_custom)),
                    Toast.LENGTH_SHORT
                ).show()
                popSettingsBackStack()
            }
        }

        actionsRow.addView(btnReset)
        actionsRow.addView(btnCancel)
        actionsRow.addView(btnSave)
        container.addView(actionsRow)
        dialog.show()
    }

    private fun showAboutAppPage() {
        val verName = BuildConfig.VERSION_NAME
        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "📋",
                title = getString(R.string.about_changelog_title),
                subtitle = getString(R.string.about_changelog_sub, verName),
                isAccentHighlighted = true,
                isInteractive = true,
                onClick = {
                    pushSettingsBackStack { showAboutAppPage() }
                    showChangelogPage()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🏷️",
                title = getString(R.string.about_version_label),
                subtitle = "Pure Video Player v$verName (64-bit LibVLC Cinema Engine)",
                isInteractive = false
            ),
            PremiumMenuItem(
                iconEmoji = "👨‍💻",
                title = getString(R.string.about_developer_label),
                subtitle = "Ahmed M Moumen",
                isAccentHighlighted = true,
                isInteractive = false
            )
        )

        showPremiumMenuDialog(
            headerIcon = "ℹ️",
            title = getString(R.string.settings_hub_about_title),
            subtitle = "Pure Video Player • v$verName",
            items = items
        )
    }

    private fun showChangelogPage() {
        val verName = BuildConfig.VERSION_NAME
        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "✨",
                title = "v$verName — Cast Screen & Performance Improvements",
                subtitle = getString(R.string.changelog_v36_2_desc),
                isAccentHighlighted = true,
                isInteractive = false
            ),
            PremiumMenuItem(
                iconEmoji = "🎨",
                title = "v36.1 — Categorized Settings & Fluid Theme Studio",
                subtitle = getString(R.string.changelog_v36_1_desc),
                isInteractive = false
            ),
            PremiumMenuItem(
                iconEmoji = "❤️",
                title = "v34.0 — IPTV Favorites, Watch History & Back-Swipe",
                subtitle = getString(R.string.changelog_v34_0_desc),
                isInteractive = false
            ),
            PremiumMenuItem(
                iconEmoji = "📺",
                title = "v30.0 — Integrated IPTV Hub (Xtream Codes & M3U)",
                subtitle = getString(R.string.changelog_v30_0_desc),
                isInteractive = false
            ),
            PremiumMenuItem(
                iconEmoji = "🛡️",
                title = "v28.0 — Private Vault, Grid/List Layout & Smart Sort",
                subtitle = getString(R.string.changelog_v28_0_desc),
                isInteractive = false
            )
        )

        showPremiumMenuDialog(
            headerIcon = "📋",
            title = getString(R.string.about_changelog_title),
            subtitle = "Pure Video Player • Ahmed M Moumen",
            items = items
        )
    }

    private fun showAppLanguageDialog() {
        val currentTag = PlaybackStore.getAppLanguageTag(this)
        val items = listOf(
            PremiumMenuItem("📱", getString(R.string.lang_system_default), null, currentTag == "") {
                PlaybackStore.setAppLanguageTag(this, ""); popSettingsBackStack()
            },
            PremiumMenuItem("🇸🇦", getString(R.string.lang_arabic), "العربية", currentTag == "ar") {
                PlaybackStore.setAppLanguageTag(this, "ar"); popSettingsBackStack()
            },
            PremiumMenuItem("🇺🇸", getString(R.string.lang_english), "English", currentTag == "en") {
                PlaybackStore.setAppLanguageTag(this, "en"); popSettingsBackStack()
            }
        )
        showPremiumMenuDialog("🌐", getString(R.string.setting_app_language), null, items)
    }

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
                popSettingsBackStack()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> popSettingsBackStack() }
            .setOnCancelListener { popSettingsBackStack() }
            .show()
    }

    private fun showThemeSelectorDialog() {
        var selectedIdx = PlaybackStore.getThemeIndex(this)
        var selectedMode = PlaybackStore.getAppearanceMode(this)
        val surface = PlaybackStore.getSurfaceColor(this)

        data class ThemeOption(val emoji: String, val name: String, val accentHex: String, val bgHex: String)
        val dynamicSysHex = PlaybackStore.getDynamicSystemAccentHex(this)
        val themes = listOf(
            ThemeOption("💎", getString(R.string.theme_cyber_blue), "#38BDF8", "#090D16"),
            ThemeOption("✨", getString(R.string.theme_amoled_gold), "#F59E0B", "#000000"),
            ThemeOption("🌿", getString(R.string.theme_emerald), "#10B981", "#090D16"),
            ThemeOption("🔥", getString(R.string.theme_crimson), "#F43F5E", "#090D16"),
            ThemeOption("🔮", getString(R.string.theme_violet), "#A855F7", "#090D16"),
            ThemeOption("🌈", getString(R.string.theme_material_you), dynamicSysHex, "#090D16"),
            ThemeOption("🍊", getString(R.string.theme_sunset_coral), "#FB923C", "#090D16"),
            ThemeOption("🩵", getString(R.string.theme_teal_aqua), "#14B8A6", "#090D16")
        )

        val outerScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(surface)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            setBackgroundColor(surface)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        outerScroll.addView(cardContainer)

        val topPill = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(PlaybackStore.getAccentColor(this@MainActivity))
            }
        }
        cardContainer.addView(topPill)

        val tvTitle = TextView(this).apply {
            text = "🎨  " + getString(R.string.theme_dialog_title)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        cardContainer.addView(tvTitle)

        // Section 1: Appearance Mode Selector (Dark / Light / System Default)
        val tvModeSec = TextView(this).apply {
            text = getString(R.string.appearance_mode_section)
            setTextColor(PlaybackStore.getAccentColor(this@MainActivity))
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        cardContainer.addView(tvModeSec)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val modeOptions = listOf(
            Triple(0, "🌙", getString(R.string.appearance_mode_dark)),
            Triple(1, "☀️", getString(R.string.appearance_mode_light)),
            Triple(2, "📱", getString(R.string.appearance_mode_system))
        )
        val modeButtons = mutableListOf<TextView>()

        // Section 2: Accent Color Presets
        val tvAccentSec = TextView(this).apply {
            text = getString(R.string.accent_colors_section)
            setTextColor(PlaybackStore.getAccentColor(this@MainActivity))
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }

        val rowViews = mutableListOf<LinearLayout>()
        val nameViews = mutableListOf<TextView>()
        val checkViews = mutableListOf<TextView>()
        var btnBackRef: TextView? = null

        fun refreshVisualSelection(activeIdx: Int, activeMode: Int) {
            val activeAccent = PlaybackStore.getAccentColor(this)
            val activeSurface = PlaybackStore.getSurfaceColor(this)
            val activeBg = PlaybackStore.getBackgroundColor(this)
            val primaryText = PlaybackStore.getPrimaryTextColor(this)
            val isLight = PlaybackStore.isLightThemeActive(this)

            outerScroll.setBackgroundColor(activeSurface)
            cardContainer.setBackgroundColor(activeSurface)
            activeFullScreenHost?.setBackgroundColor(activeSurface)
            activeFullScreenDialog?.window?.setBackgroundDrawable(ColorDrawable(activeSurface))
            activeFullScreenDialog?.window?.statusBarColor = activeSurface
            activeFullScreenDialog?.window?.navigationBarColor = activeSurface

            tvTitle.setTextColor(primaryText)
            tvModeSec.setTextColor(activeAccent)
            tvAccentSec.setTextColor(activeAccent)
            (topPill.background as? GradientDrawable)?.setColor(activeAccent)

            modeButtons.forEachIndexed { mIdx, btn ->
                val isModeSelected = (mIdx == activeMode)
                btn.setTextColor(if (isModeSelected) primaryText else PlaybackStore.getSecondaryTextColor(this))
                btn.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(13).toFloat()
                    setColor(
                        if (isModeSelected) Color.argb(42, Color.red(activeAccent), Color.green(activeAccent), Color.blue(activeAccent))
                        else activeBg
                    )
                    setStroke(
                        dp(if (isModeSelected) 2 else 1),
                        if (isModeSelected) activeAccent else if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#22FFFFFF")
                    )
                }
            }

            themes.forEachIndexed { idx, opt ->
                val isSelected = (idx == activeIdx)
                val row = rowViews[idx]
                val tvN = nameViews[idx]
                val check = checkViews[idx]
                val optColor = Color.parseColor(opt.accentHex)

                tvN.setTextColor(primaryText)
                row.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(if (isSelected) Color.argb(38, Color.red(optColor), Color.green(optColor), Color.blue(optColor)) else activeBg)
                    setStroke(
                        dp(if (isSelected) 2 else 1),
                        if (isSelected) optColor else if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#22FFFFFF")
                    )
                }
                check.text = if (isSelected) "✓" else ""
                check.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isSelected) optColor else if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#18FFFFFF"))
                }
                if (isSelected) {
                    row.animate().scaleX(1.02f).scaleY(1.02f).setDuration(180L).setInterpolator(OvershootInterpolator(1.6f)).start()
                    check.scaleX = 0.5f
                    check.scaleY = 0.5f
                    check.animate().scaleX(1f).scaleY(1f).setDuration(220L).setInterpolator(OvershootInterpolator(2.0f)).start()
                } else {
                    row.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                }
            }

            btnBackRef?.setTextColor(primaryText)
            btnBackRef?.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#18FFFFFF"))
            }
        }

        modeOptions.forEach { (modeCode, emoji, label) ->
            val btn = TextView(this).apply {
                text = "$emoji  $label"
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (modeCode < 2) dp(8) else 0
                }
                setOnClickListener {
                    selectedMode = modeCode
                    PlaybackStore.setAppearanceMode(this@MainActivity, modeCode)
                    refreshVisualSelection(selectedIdx, selectedMode)
                    applyUserTheme(animated = true)
                }
            }
            modeButtons.add(btn)
            modeRow.addView(btn)
        }
        cardContainer.addView(modeRow)
        cardContainer.addView(tvAccentSec)

        val animatedItems = mutableListOf<View>(modeRow, tvAccentSec)
        themes.forEachIndexed { idx, opt ->
            val optAccent = Color.parseColor(opt.accentHex)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }

            val swatchCircle = TextView(this).apply {
                text = opt.emoji
                textSize = 17f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(opt.bgHex))
                    setStroke(dp(3), optAccent)
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(this).apply {
                text = opt.name
                setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            val tvHex = TextView(this).apply {
                text = opt.accentHex
                setTextColor(optAccent)
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            }
            textCol.addView(tvName)
            textCol.addView(tvHex)

            val tvCheck = TextView(this).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#090D16"))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            }

            row.addView(swatchCircle)
            row.addView(textCol)
            row.addView(tvCheck)

            row.setOnClickListener {
                selectedIdx = idx
                PlaybackStore.setThemeIndex(this, idx)
                refreshVisualSelection(selectedIdx, selectedMode)
                applyUserTheme(animated = true)
            }

            rowViews.add(row)
            nameViews.add(tvName)
            checkViews.add(tvCheck)
            cardContainer.addView(row)
            animatedItems.add(row)
        }

        val btnBack = TextView(this).apply {
            text = if (settingsBackStack.isNotEmpty()) getString(R.string.back_to_previous_menu) else getString(R.string.close)
            gravity = Gravity.CENTER
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            setOnClickListener {
                navigateBackInSettingsOrClose()
            }
        }
        btnBackRef = btnBack
        cardContainer.addView(btnBack)

        refreshVisualSelection(selectedIdx, selectedMode)
        presentInFullScreenHost(outerScroll, cardContainer, animatedItems, PlaybackStore.getSurfaceColor(this))
    }

    private fun showSubtitleStyleDialog() {
        val primaryText = PlaybackStore.getPrimaryTextColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val accent = PlaybackStore.getAccentColor(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(surface)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 24)
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
            setTextColor(primaryText)
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
            setTextColor(primaryText)
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
            setTextColor(primaryText)
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
            setTextColor(primaryText)
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
            setTextColor(primaryText)
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
                setTextColor(primaryText)
                buttonTintList = ColorStateList.valueOf(accent)
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
                popSettingsBackStack()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> popSettingsBackStack() }
            .setOnCancelListener { popSettingsBackStack() }
            .show()
    }

    private fun showDefaultOrientationDialog() {
        val current = PlaybackStore.getDefaultOrientationIndex(this)
        val items = listOf(
            PremiumMenuItem("🔄", getString(R.string.orientation_auto), null, current == 0) {
                PlaybackStore.setDefaultOrientationIndex(this, 0); popSettingsBackStack()
            },
            PremiumMenuItem("↔️", getString(R.string.orientation_landscape), null, current == 1) {
                PlaybackStore.setDefaultOrientationIndex(this, 1); popSettingsBackStack()
            },
            PremiumMenuItem("↕️", getString(R.string.orientation_portrait), null, current == 2) {
                PlaybackStore.setDefaultOrientationIndex(this, 2); popSettingsBackStack()
            }
        )
        showPremiumMenuDialog("🔄", getString(R.string.setting_default_orientation), null, items)
    }

    private fun showFoldersList() {
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

    private fun applyFolderVisibilityAndRefreshUI() {
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

    private fun checkPermissionAndLoad() {
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

    private fun requestStoragePermission() {
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

    private fun loadVideoFoldersOnly(showSpinner: Boolean = false) {
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
                } catch (_: Exception) {}

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
                } catch (_: Exception) {}

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
                } catch (_: Exception) {}

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

    private fun showEmptyState(message: String, showButton: Boolean) {
        binding.recyclerView.visibility = View.GONE
        binding.emptyLayout.visibility = View.VISIBLE
        binding.tvEmptyMessage.text = message
        binding.btnGrantPermission.visibility = if (showButton) View.VISIBLE else View.GONE
    }

    private fun normalizeGitHubRepoInput(rawInput: String): String {
        var clean = rawInput.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .trim('/')
        if (clean.endsWith(".git")) {
            clean = clean.removeSuffix(".git")
        }
        val parts = clean.split("/").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0]}/${parts[1]}"
            parts.size == 1 -> "${parts[0]}/Video-player"
            else -> ""
        }
    }

    private fun openGitHubUpdaterFlow() {
        val savedRepo = PlaybackStore.getGitHubRepo(this)
        if (savedRepo.isBlank() || !savedRepo.contains("/")) {
            showConfigureGitHubRepoDialog { configuredRepo ->
                checkForGitHubUpdatesNow(configuredRepo)
            }
        } else {
            checkForGitHubUpdatesNow(savedRepo)
        }
    }

    private fun showConfigureGitHubRepoDialog(onConfigured: (String) -> Unit = {}) {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)

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
            text = getString(R.string.update_repo_setup_title)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 17.5f
            setTypeface(typeface, Typeface.BOLD)
        }

        val tvDesc = TextView(this).apply {
            text = getString(R.string.update_repo_setup_desc)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
        }

        val currentRepo = PlaybackStore.getGitHubRepo(this)
        val etRepo = EditText(this).apply {
            setText(currentRepo)
            hint = "username/Video-player"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        stylePremiumInputBox(etRepo, bg, accent)

        container.addView(tvTitle)
        container.addView(tvDesc)
        container.addView(etRepo)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bindSubDialogBackNavigation(dialog, btnCancel)

        val btnSave = TextView(this).apply {
            text = getString(R.string.save)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                val normalized = normalizeGitHubRepoInput(etRepo.text.toString())
                if (!normalized.contains("/")) {
                    Toast.makeText(this@MainActivity, getString(R.string.update_repo_invalid_toast), Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    PlaybackStore.setGitHubRepo(this@MainActivity, normalized)
                    onConfigured(normalized)
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        container.addView(btnRow)
        dialog.show()
    }

    private fun checkForGitHubUpdatesNow(repoSlug: String) {
        Toast.makeText(this, getString(R.string.update_checking_toast, repoSlug), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var remoteVersionCode = -1
                var remoteVersionName = ""
                var directApkUrl: String? = null
                var latestActionsUrl = "https://github.com/$repoSlug/actions"

                // 1. Check remote app/build.gradle.kts on main or master branch for latest versionCode
                for (branch in listOf("main", "master")) {
                    try {
                        val rawUrl = "https://raw.githubusercontent.com/$repoSlug/$branch/app/build.gradle.kts"
                        val conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 8000
                            setRequestProperty("User-Agent", "PureVideoPlayer-Updater")
                        }
                        if (conn.responseCode in 200..299) {
                            val gradleText = conn.inputStream.bufferedReader().use { it.readText() }
                            val codeMatch = Regex("versionCode\\s*=\\s*(\\d+)").find(gradleText)
                            val nameMatch = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(gradleText)
                            if (codeMatch != null) {
                                remoteVersionCode = codeMatch.groupValues[1].toIntOrNull() ?: -1
                                remoteVersionName = nameMatch?.groupValues?.getOrNull(1) ?: "$remoteVersionCode.0"
                                break
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 2. Check GitHub Releases (latest) for a direct .apk download asset
                try {
                    val relUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
                    val conn = (URL(relUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("User-Agent", "PureVideoPlayer-Updater")
                    }
                    if (conn.responseCode in 200..299) {
                        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JSONObject(jsonStr)
                        val assets = root.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.optJSONObject(i) ?: continue
                                val dlUrl = asset.optString("browser_download_url")
                                if (dlUrl.endsWith(".apk", ignoreCase = true)) {
                                    directApkUrl = dlUrl
                                    break
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                // 3. Check latest successful GitHub Actions workflow run URL
                try {
                    val runsUrl = "https://api.github.com/repos/$repoSlug/actions/runs?status=success&per_page=1"
                    val conn = (URL(runsUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("User-Agent", "PureVideoPlayer-Updater")
                    }
                    if (conn.responseCode in 200..299) {
                        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val runs = JSONObject(jsonStr).optJSONArray("workflow_runs")
                        if (runs != null && runs.length() > 0) {
                            val htmlUrl = runs.optJSONObject(0)?.optString("html_url")
                            if (!htmlUrl.isNullOrBlank()) {
                                latestActionsUrl = htmlUrl
                            }
                        }
                    }
                } catch (_: Exception) {}

                Pair(
                    Pair(remoteVersionCode, remoteVersionName.ifBlank { "$remoteVersionCode.0" }),
                    Pair(directApkUrl ?: "https://github.com/$repoSlug/releases/latest/download/PureVideoPlayer.apk", latestActionsUrl)
                )
            }

            if (isFinishing || isDestroyed) return@launch
            val (versionPair, urlPair) = result
            val (remoteCode, remoteName) = versionPair
            val (apkDownloadUrl, actionsRunUrl) = urlPair
            val currentCode = BuildConfig.VERSION_CODE
            val currentName = BuildConfig.VERSION_NAME

            val statusSubtitle = when {
                remoteCode > currentCode -> getString(R.string.update_status_new_available, remoteName, currentName)
                remoteCode == currentCode -> getString(R.string.update_status_up_to_date, currentName)
                else -> "GitHub: $repoSlug (v$currentName)"
            }

            val items = listOf(
                PremiumMenuItem(
                    iconEmoji = "⬇️",
                    title = getString(R.string.update_download_direct_btn),
                    subtitle = apkDownloadUrl.substringAfter("github.com/"),
                    isAccentHighlighted = true,
                    onClick = {
                        downloadAndInstallApkInApp(apkDownloadUrl, actionsRunUrl)
                    }
                ),
                PremiumMenuItem(
                    iconEmoji = "🌐",
                    title = getString(R.string.update_open_actions_btn),
                    subtitle = actionsRunUrl.substringAfter("github.com/"),
                    onClick = {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actionsRunUrl)))
                        } catch (_: Exception) {}
                    }
                ),
                PremiumMenuItem(
                    iconEmoji = "⚙️",
                    title = getString(R.string.update_change_repo_btn, repoSlug),
                    subtitle = getString(R.string.update_repo_setup_desc),
                    onClick = {
                        showConfigureGitHubRepoDialog { newRepo ->
                            checkForGitHubUpdatesNow(newRepo)
                        }
                    }
                )
            )

            showPremiumMenuDialog(
                headerIcon = "🚀",
                title = getString(R.string.update_check_option_title),
                subtitle = statusSubtitle,
                items = items
            )
        }
    }

    private fun downloadAndInstallApkInApp(apkUrl: String, fallbackActionsUrl: String) {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val tvTitle = TextView(this).apply {
            text = getString(R.string.update_downloading_title)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }

        val tvPercent = TextView(this).apply {
            text = "0%"
            setTextColor(accent)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(10))
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(accent)
        }

        container.addView(tvTitle)
        container.addView(tvPercent)
        container.addView(progressBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        lifecycleScope.launch {
            val downloadedFile = withContext(Dispatchers.IO) {
                try {
                    val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 12_000
                        readTimeout = 20_000
                        setRequestProperty("User-Agent", "PureVideoPlayer-Updater")
                    }
                    if (conn.responseCode !in 200..299) return@withContext null
                    val totalBytes = conn.contentLength.coerceAtLeast(1)
                    val outFile = File(externalCacheDir ?: cacheDir, "PureVideoPlayer_update.apk")
                    if (outFile.exists()) outFile.delete()

                    conn.inputStream.use { input ->
                        FileOutputStream(outFile).use { output ->
                            val buf = ByteArray(16 * 1024)
                            var downloaded = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                withContext(Dispatchers.Main) {
                                    progressBar.progress = pct
                                    val mb = String.format(Locale.US, "%.1f MB", downloaded / (1024.0 * 1024.0))
                                    tvPercent.text = "$pct% ($mb)"
                                }
                            }
                        }
                    }
                    if (outFile.exists() && outFile.length() > 100_000L) outFile else null
                } catch (_: Exception) {
                    null
                }
            }

            if (isFinishing || isDestroyed) return@launch
            dialog.dismiss()

            if (downloadedFile != null) {
                installDownloadedApkFile(downloadedFile)
            } else {
                // If GitHub Release APK wasn't published yet, open the latest successful GitHub Actions run page directly!
                Toast.makeText(this@MainActivity, getString(R.string.update_fallback_actions_toast), Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackActionsUrl)))
                } catch (_: Exception) {}
            }
        }
    }

    private fun installDownloadedApkFile(apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.update_install_error_toast), Toast.LENGTH_LONG).show()
        }
    }

    private fun launchPlayer(
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

    private fun renderIptvCurrentLevel() {
        if (currentMainTab != 2) return
        binding.iptvHeaderBar.visibility = View.VISIBLE
        binding.fabResumeLast.visibility = View.GONE
        binding.btnBackFolder.visibility = View.GONE
        adapter.isVaultMode = false

        val sources = IptvRepository.getSources(this)
        if (activeIptvSource != null) {
            activeIptvSource = sources.find { it.id == activeIptvSource?.id }
        }
        if (activeIptvSource == null && sources.isNotEmpty()) {
            val savedActiveId = IptvRepository.getActiveSourceId(this)
            activeIptvSource = sources.find { it.id == savedActiveId } ?: sources.first()
        }

        if (sources.isEmpty()) {
            iptvNavLevel = 0
            binding.iptvSubTabsBar.visibility = View.GONE
            binding.tvToolbarTitle.text = getString(R.string.iptv_toolbar_title)
            binding.tvIptvStatusSubtitle.text = getString(R.string.iptv_sources_subtitle)
            binding.emptyLayout.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            val initialCards = listOf<Any>(
                IptvAddCardItem(
                    title = "➕  " + getString(R.string.iptv_add_source_title),
                    subtitle = getString(R.string.iptv_add_source_subtitle)
                )
            )
            adapter.submitIptvList(initialCards)
            return
        }

        binding.emptyLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE

        when (iptvNavLevel) {
            0 -> {
                // Level 0: Xtream & M3U Sources List + prominent (+) Add New Source card right below
                binding.iptvSubTabsBar.visibility = View.GONE
                binding.tvToolbarTitle.text = "${getString(R.string.iptv_toolbar_title)} (${sources.size})"
                binding.tvIptvStatusSubtitle.text = getString(R.string.iptv_sources_manage_hint)
                val list = mutableListOf<Any>()
                list.addAll(sources)
                list.add(
                    IptvAddCardItem(
                        title = "➕  " + getString(R.string.iptv_add_source_title),
                        subtitle = getString(R.string.iptv_add_source_subtitle)
                    )
                )
                adapter.submitIptvList(list)
            }
            1 -> {
                // Level 1: IPTV Smarters Pro Dashboard for Active Source (Shows ONLY non-empty sections + Favorites & Continue Watching!)
                binding.iptvSubTabsBar.visibility = View.GONE
                val src = activeIptvSource ?: run {
                    iptvNavLevel = 0
                    renderIptvCurrentLevel()
                    return
                }
                binding.tvToolbarTitle.text = src.name
                binding.tvIptvStatusSubtitle.text = "${src.type.name} • ${src.totalItemsCount()} ${getString(R.string.iptv_items_unit)}"

                val allItems = IptvRepository.loadCachedStreams(this, src.id)
                val liveCnt = allItems.count { it.contentType == IptvContentType.LIVE_TV }
                val movieCnt = allItems.count { it.contentType == IptvContentType.MOVIES }
                val seriesCnt = allItems.count { it.contentType == IptvContentType.SERIES }
                val radioCnt = allItems.count { it.contentType == IptvContentType.RADIO }

                val sectionCards = mutableListOf<Any>()
                if (liveCnt > 0) {
                    sectionCards.add(
                        IptvSectionCardItem(
                            contentType = IptvContentType.LIVE_TV,
                            title = "📺  " + getString(R.string.iptv_sec_live_tv),
                            subtitle = getString(R.string.iptv_sec_live_sub, liveCnt),
                            count = liveCnt
                        )
                    )
                }
                if (movieCnt > 0) {
                    sectionCards.add(
                        IptvSectionCardItem(
                            contentType = IptvContentType.MOVIES,
                            title = "🎬  " + getString(R.string.iptv_sec_movies),
                            subtitle = getString(R.string.iptv_sec_movies_sub, movieCnt),
                            count = movieCnt
                        )
                    )
                }
                if (seriesCnt > 0) {
                    sectionCards.add(
                        IptvSectionCardItem(
                            contentType = IptvContentType.SERIES,
                            title = "🎞️  " + getString(R.string.iptv_sec_series),
                            subtitle = getString(R.string.iptv_sec_series_sub, seriesCnt),
                            count = seriesCnt
                        )
                    )
                }
                if (radioCnt > 0) {
                    sectionCards.add(
                        IptvSectionCardItem(
                            contentType = IptvContentType.RADIO,
                            title = "📻  " + getString(R.string.iptv_sec_radio),
                            subtitle = getString(R.string.iptv_sec_radio_sub, radioCnt),
                            count = radioCnt
                        )
                    )
                }
                sectionCards.add(
                    IptvAddCardItem(
                        title = "➕  " + getString(R.string.iptv_add_source_title),
                        subtitle = getString(R.string.iptv_add_source_subtitle)
                    )
                )
                adapter.submitIptvList(sectionCards)
            }
            2, 3 -> {
                val src = activeIptvSource ?: return
                val cType = activeIptvContentType ?: return
                val secName = getSectionDisplayName(cType)

                // Show the Sub-Tabs Bar (📂 التصنيفات | ❤️ المفضلة | ⏱️ أُكمل المشاهدة) inside Series, Movies, Live TV, Radio!
                binding.iptvSubTabsBar.visibility = View.VISIBLE
                updateIptvSubTabsVisualState()

                if (iptvSectionSubTab == 1) {
                    // Favorites Tab ❤️ for this section
                    binding.tvToolbarTitle.text = "$secName • ${getString(R.string.iptv_subtab_favorites)}"
                    val favStreams = IptvRepository.getFavoriteItems(this, src.id, cType)
                    binding.tvIptvStatusSubtitle.text = "${favStreams.size} ${getString(R.string.iptv_items_unit)}"
                    if (favStreams.isEmpty()) {
                        showEmptyState(getString(R.string.iptv_favorites_empty_msg), showButton = false)
                    } else {
                        binding.emptyLayout.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                        adapter.submitIptvList(favStreams)
                    }
                    return
                }

                if (iptvSectionSubTab == 2) {
                    // Continue Watching / History Tab ⏱️ for this section
                    binding.tvToolbarTitle.text = "$secName • ${getString(R.string.iptv_subtab_recent)}"
                    val recentStreams = IptvRepository.getRecentWatchedItems(this, src.id, cType)
                    binding.tvIptvStatusSubtitle.text = "${recentStreams.size} ${getString(R.string.iptv_items_unit)}"
                    if (recentStreams.isEmpty()) {
                        showEmptyState(getString(R.string.iptv_recent_empty_msg), showButton = false)
                    } else {
                        binding.emptyLayout.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                        adapter.submitIptvList(recentStreams)
                    }
                    return
                }

                // SubTab 0: Standard Categories (Level 2) or Category Streams (Level 3)
                if (iptvNavLevel == 2) {
                    binding.tvToolbarTitle.text = "${src.name} • $secName"
                    binding.tvIptvStatusSubtitle.text = getString(R.string.iptv_long_press_reclassify_hint)

                    val sectionStreams = IptvRepository.loadCachedStreams(this, src.id)
                        .filter { it.contentType == cType }

                    val categories = mutableListOf<Any>()
                    if (sectionStreams.isNotEmpty()) {
                        categories.add(
                            IptvCategory(
                                id = "__ALL__",
                                name = "🌐  " + getString(R.string.iptv_all_channels_category),
                                contentType = cType,
                                itemCount = sectionStreams.size
                            )
                        )
                    }
                    val grouped = sectionStreams.groupBy { it.categoryName }
                        .filter { (_, list) -> list.isNotEmpty() }
                        .map { (catName, list) ->
                            IptvCategory(
                                id = list.first().categoryId,
                                name = catName,
                                contentType = cType,
                                itemCount = list.size
                            )
                        }
                    categories.addAll(grouped)
                    adapter.submitIptvList(categories)
                } else {
                    binding.tvToolbarTitle.text = activeIptvCategoryName
                    val rawStreams = IptvRepository.loadCachedStreams(this, src.id)
                        .filter { it.contentType == cType }
                        .let { list ->
                            if (activeIptvCategoryId == "__ALL__" || activeIptvCategoryId.isNullOrEmpty()) {
                                list
                            } else {
                                list.filter { it.categoryName == activeIptvCategoryName || it.categoryId == activeIptvCategoryId }
                            }
                        }

                    val displayStreams = if (cType == IptvContentType.SERIES && src.type != IptvSourceType.XTREAM) {
                        rawStreams.groupBy { it.seriesName.ifBlank { it.title } }
                            .map { (sName, eps) ->
                                val first = eps.first()
                                first.copy(
                                    title = sName,
                                    categoryName = "${first.categoryName} • ${eps.size} ${getString(R.string.iptv_episodes_unit)}"
                                )
                            }
                    } else {
                        rawStreams
                    }

                    binding.tvIptvStatusSubtitle.text = "${displayStreams.size} ${getString(R.string.iptv_items_unit)}"
                    adapter.submitIptvList(displayStreams)
                }
            }
            4 -> {
                // Level 4: Series Seasons
                binding.iptvSubTabsBar.visibility = View.GONE
                val sInfo = activeIptvSeriesInfo ?: return
                binding.tvToolbarTitle.text = sInfo.seriesTitle
                val src = activeIptvSource
                val sItem = currentOpenedSeriesStreamItem
                val lastEpLabel = if (src != null && sItem != null) {
                    val sKey = sItem.seriesId.ifBlank { sItem.seriesName.ifBlank { sItem.title } }
                    IptvRepository.getLastWatchedSeriesEpisodeLabel(this, src.id, sKey)
                } else null
                val prefix = if (!lastEpLabel.isNullOrBlank()) "▶ ${getString(R.string.iptv_last_watched_ep_badge, lastEpLabel)}  •  " else ""
                binding.tvIptvStatusSubtitle.text = prefix + sInfo.plot.ifBlank { "${sInfo.seasons.size} Seasons" }
                adapter.submitIptvList(sInfo.seasons)
            }
            5 -> {
                // Level 5: Season Episodes
                binding.iptvSubTabsBar.visibility = View.GONE
                val sInfo = activeIptvSeriesInfo ?: return
                val season = activeIptvSeasonGroup ?: return
                binding.tvToolbarTitle.text = "${sInfo.seriesTitle} • ${season.name}"
                binding.tvIptvStatusSubtitle.text = getString(R.string.iptv_episodes_count_format, season.episodes.size)
                adapter.submitIptvList(season.episodes)
            }
        }
    }

    private fun navigateBackInIptvHierarchy() {
        when {
            iptvNavLevel == 5 -> iptvNavLevel = 4
            iptvNavLevel == 4 -> {
                // Return to either Favorites/Recent subtab or Category streams list
                iptvNavLevel = if (iptvSectionSubTab != 0) 2 else 3
            }
            iptvNavLevel == 3 && iptvSectionSubTab == 0 -> iptvNavLevel = 2
            iptvNavLevel in 2..3 && iptvSectionSubTab != 0 -> {
                // If inside ❤️ Favorites or ⏱️ Continue Watching sub-tab, back-swipe returns to All Categories sub-tab first!
                iptvSectionSubTab = 0
                iptvNavLevel = 2
            }
            iptvNavLevel == 2 -> iptvNavLevel = 1
            else -> iptvNavLevel = 0
        }
        renderIptvCurrentLevel()
    }

    private fun getSectionDisplayName(type: IptvContentType): String {
        return when (type) {
            IptvContentType.LIVE_TV -> getString(R.string.iptv_sec_live_tv)
            IptvContentType.MOVIES -> getString(R.string.iptv_sec_movies)
            IptvContentType.SERIES -> getString(R.string.iptv_sec_series)
            IptvContentType.RADIO -> getString(R.string.iptv_sec_radio)
        }
    }

    private fun handleIptvItemClick(item: Any) {
        when (item) {
            is IptvAddCardItem -> showAddIptvSourceDialog()
            is IptvSource -> {
                activeIptvSource = item
                IptvRepository.setActiveSourceId(this, item.id)
                iptvSectionSubTab = 0
                iptvNavLevel = 1
                renderIptvCurrentLevel()
            }
            is IptvSectionCardItem -> {
                activeIptvContentType = item.contentType
                iptvSectionSubTab = 0
                iptvNavLevel = 2
                renderIptvCurrentLevel()
            }
            is IptvCategory -> {
                activeIptvCategoryId = item.id
                activeIptvCategoryName = item.name.removePrefix("🌐  ")
                iptvSectionSubTab = 0
                iptvNavLevel = 3
                renderIptvCurrentLevel()
            }
            is IptvStreamItem -> {
                when (item.contentType) {
                    IptvContentType.LIVE_TV, IptvContentType.RADIO -> {
                        IptvRepository.recordRecentWatchedItem(this, item.sourceId, item.id)
                        launchPlayer(
                            uri = Uri.parse(item.streamUrl),
                            title = item.title,
                            startPositionMs = 0L,
                            httpUserAgent = item.httpUserAgent,
                            httpReferrer = item.httpReferrer
                        )
                    }
                    IptvContentType.MOVIES -> {
                        showIptvMovieDetailsDialog(item)
                    }
                    IptvContentType.SERIES -> {
                        currentOpenedSeriesStreamItem = item
                        openIptvSeriesSeasons(item)
                    }
                }
            }
            is IptvSeasonGroup -> {
                activeIptvSeasonGroup = item
                iptvNavLevel = 5
                renderIptvCurrentLevel()
            }
            is IptvEpisodeItem -> {
                val src = activeIptvSource
                val sItem = currentOpenedSeriesStreamItem
                if (src != null && sItem != null) {
                    IptvRepository.recordRecentWatchedItem(this, src.id, sItem.id)
                    val sKey = sItem.seriesId.ifBlank { sItem.seriesName.ifBlank { sItem.title } }
                    IptvRepository.saveLastWatchedSeriesEpisode(
                        context = this,
                        sourceId = src.id,
                        seriesKey = sKey,
                        seasonNumber = item.seasonNumber,
                        episodeNumber = item.episodeNumber,
                        episodeTitle = item.title,
                        streamUrl = item.streamUrl
                    )
                }
                val savedPos = PlaybackStore.getSavedPosition(this, Uri.parse(item.streamUrl))
                launchPlayer(
                    uri = Uri.parse(item.streamUrl),
                    title = item.title,
                    startPositionMs = if (savedPos > 3000L) savedPos else 0L,
                    httpUserAgent = item.httpUserAgent,
                    httpReferrer = item.httpReferrer
                )
            }
        }
    }

    private fun handleIptvItemLongClick(item: Any) {
        when (item) {
            is IptvSource -> showIptvSourceManagementMenu(item)
            is IptvCategory -> {
                if (item.id != "__ALL__") {
                    showReclassifyCategoryDialog(item.name)
                }
            }
            is IptvStreamItem -> {
                settingsBackStack.clear()
                val isFav = IptvRepository.isFavoriteStream(this, item.sourceId, item.id)
                val favTitle = if (isFav) getString(R.string.iptv_fav_remove_option) else getString(R.string.iptv_fav_add_option)
                val menuItems = mutableListOf<PremiumMenuItem>()
                if (item.contentType == IptvContentType.MOVIES && item.streamUrl.isNotBlank()) {
                    menuItems.add(
                        PremiumMenuItem(
                            iconEmoji = "⬇️",
                            title = getString(R.string.iptv_download_btn),
                            subtitle = getString(R.string.iptv_download_sub),
                            isAccentHighlighted = true,
                            onClick = {
                                downloadIptvMediaLocally(
                                    title = item.title,
                                    streamUrl = item.streamUrl,
                                    userAgent = item.httpUserAgent,
                                    referrer = item.httpReferrer
                                )
                            }
                        )
                    )
                }
                menuItems.add(
                    PremiumMenuItem(
                        iconEmoji = if (isFav) "💔" else "❤️",
                        title = favTitle,
                        subtitle = getString(R.string.iptv_subtab_favorites),
                        isAccentHighlighted = (item.contentType != IptvContentType.MOVIES),
                        onClick = {
                            val nowFav = IptvRepository.toggleFavoriteStream(this, item.sourceId, item.id)
                            val msgRes = if (nowFav) R.string.iptv_fav_added_toast else R.string.iptv_fav_removed_toast
                            Toast.makeText(this, getString(msgRes, item.title), Toast.LENGTH_SHORT).show()
                            renderIptvCurrentLevel()
                        }
                    )
                )
                menuItems.add(
                    PremiumMenuItem(
                        iconEmoji = "🗂️",
                        title = getString(R.string.iptv_reclassify_category_title),
                        subtitle = item.categoryName,
                        onClick = {
                            showReclassifyCategoryDialog(item.categoryName)
                        }
                    )
                )
                showPremiumMenuDialog(
                    headerIcon = if (isFav) "❤️" else "🎬",
                    title = item.title,
                    subtitle = item.categoryName,
                    items = menuItems
                )
            }
            is IptvEpisodeItem -> {
                settingsBackStack.clear()
                val epLabel = "S${item.seasonNumber}E${item.episodeNumber} • ${item.title}"
                showPremiumMenuDialog(
                    headerIcon = "🎞️",
                    title = item.title,
                    subtitle = "Season ${item.seasonNumber} • Episode ${item.episodeNumber}",
                    items = listOf(
                        PremiumMenuItem(
                            iconEmoji = "▶️",
                            title = getString(R.string.iptv_episode_play_option),
                            subtitle = epLabel,
                            isAccentHighlighted = true,
                            onClick = {
                                handleIptvItemClick(item)
                            }
                        ),
                        PremiumMenuItem(
                            iconEmoji = "⬇️",
                            title = getString(R.string.iptv_download_btn),
                            subtitle = getString(R.string.iptv_download_sub),
                            isAccentHighlighted = true,
                            onClick = {
                                val sTitle = activeIptvSeriesInfo?.seriesTitle ?: ""
                                val dlTitle = if (sTitle.isNotBlank()) "${sTitle}_S${item.seasonNumber}E${item.episodeNumber}_${item.title}" else item.title
                                downloadIptvMediaLocally(
                                    title = dlTitle,
                                    streamUrl = item.streamUrl,
                                    userAgent = item.httpUserAgent,
                                    referrer = item.httpReferrer
                                )
                            }
                        )
                    )
                )
            }
        }
    }

    private fun showAddIptvSourceDialog() {
        settingsBackStack.clear()
        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "🔗",
                title = getString(R.string.iptv_add_m3u_url_option),
                subtitle = getString(R.string.iptv_add_m3u_url_sub),
                isAccentHighlighted = true,
                sectionHeader = getString(R.string.iptv_add_opt1_header),
                onClick = {
                    pushSettingsBackStack { showAddIptvSourceDialog() }
                    showAddOrEditM3uUrlDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "📂",
                title = getString(R.string.iptv_add_m3u_file_option),
                subtitle = getString(R.string.iptv_add_m3u_file_sub),
                onClick = {
                    pendingM3uFileSourceName = ""
                    m3uFilePickerLauncher.launch(arrayOf("*/*"))
                }
            ),
            PremiumMenuItem(
                iconEmoji = "📡",
                title = getString(R.string.iptv_add_xtream_option),
                subtitle = getString(R.string.iptv_add_xtream_sub),
                isAccentHighlighted = true,
                sectionHeader = getString(R.string.iptv_add_opt2_header),
                onClick = {
                    pushSettingsBackStack { showAddIptvSourceDialog() }
                    showAddOrEditXtreamDialog()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🧪",
                title = getString(R.string.iptv_add_demo_source_option),
                subtitle = getString(R.string.iptv_add_demo_source_sub),
                onClick = {
                    addBuiltInDemoIptvSource()
                }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "➕",
            title = getString(R.string.iptv_add_source_title),
            subtitle = getString(R.string.iptv_add_source_subtitle),
            items = items
        )
    }

    private fun showAddOrEditM3uUrlDialog(existingSource: IptvSource? = null) {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)

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
            text = "🔗  " + getString(R.string.iptv_add_m3u_url_option)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }

        val etName = EditText(this).apply {
            setText(existingSource?.name ?: "")
            hint = getString(R.string.iptv_hint_source_name)
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        stylePremiumInputBox(etName, bg, accent)

        val etUrl = EditText(this).apply {
            setText(existingSource?.serverOrM3uUrl ?: "")
            hint = "https://example.com/playlist.m3u8"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }
        stylePremiumInputBox(etUrl, bg, accent)

        container.addView(tvTitle)
        container.addView(etName)
        container.addView(etUrl)

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
        }
        bindSubDialogBackNavigation(dialog, btnCancel)

        val btnSave = TextView(this).apply {
            text = getString(R.string.save)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                val urlInput = XtreamCodesClient.sanitizeCredentialOrUrl(etUrl.text.toString())
                val nameInput = etName.text.toString().trim().ifEmpty { "M3U Playlist" }
                if (urlInput.isEmpty() || (!urlInput.contains(".") && !urlInput.startsWith("http", ignoreCase = true))) {
                    Toast.makeText(this@MainActivity, getString(R.string.iptv_err_invalid_url), Toast.LENGTH_SHORT).show()
                } else {
                    isNavigatingToSubDialog = true
                    dialog.dismiss()
                    isNavigatingToSubDialog = false
                    settingsBackStack.clear()
                    syncIptvSourceWithProgress(
                        existingSourceId = existingSource?.id,
                        name = nameInput,
                        type = IptvSourceType.M3U_URL,
                        serverOrM3uUrl = urlInput
                    )
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        container.addView(btnRow)
        dialog.show()
    }

    private fun showAddOrEditXtreamDialog(
        existingSource: IptvSource? = null,
        prefillName: String = "",
        prefillServer: String = "",
        prefillUser: String = "",
        prefillPass: String = ""
    ) {
        val accent = PlaybackStore.getAccentColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)

        val outerScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }

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
        outerScroll.addView(container)

        val tvTitle = TextView(this).apply {
            text = "📡  " + getString(R.string.iptv_add_xtream_option)
            setTextColor(PlaybackStore.getPrimaryTextColor(this@MainActivity))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(tvTitle)

        fun makeFieldLabel(textStr: String): TextView {
            return TextView(this).apply {
                text = textStr
                setTextColor(accent)
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(8), dp(4), dp(4))
            }
        }

        // 1. Source Name (Optional)
        container.addView(makeFieldLabel("📝 " + getString(R.string.iptv_hint_source_name)))
        val etName = EditText(this).apply {
            setSingleLine(true)
            setText(existingSource?.name ?: prefillName)
            hint = getString(R.string.iptv_hint_source_name)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        stylePremiumInputBox(etName, bg, accent)
        container.addView(etName)

        // 2. Username (setSingleLine BEFORE inputType so Android never resets inputType or auto-capitalizes!)
        container.addView(makeFieldLabel("👤 " + getString(R.string.iptv_hint_username)))
        val etUsername = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(existingSource?.username ?: prefillUser)
            hint = getString(R.string.iptv_hint_username)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        stylePremiumInputBox(etUsername, bg, accent)
        container.addView(etUsername)

        // 3. Password (setSingleLine BEFORE inputType!)
        container.addView(makeFieldLabel("🔑 " + getString(R.string.iptv_hint_password)))
        val existingPass = if (existingSource != null) IptvCredentialVault.decryptSecret(existingSource.encryptedPassword) else prefillPass
        val etPassword = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(existingPass)
            hint = getString(R.string.iptv_hint_password)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        stylePremiumInputBox(etPassword, bg, accent)
        container.addView(etPassword)

        val cbShowPass = CheckBox(this).apply {
            text = getString(R.string.iptv_show_password_toggle)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            buttonTintList = ColorStateList.valueOf(accent)
            isChecked = true
            setPadding(dp(6), dp(6), dp(6), dp(2))
            setOnCheckedChangeListener { _, isChecked ->
                val sel = etPassword.selectionEnd.coerceAtLeast(0)
                etPassword.inputType = if (isChecked) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                etPassword.setSelection(sel.coerceAtMost(etPassword.text.length))
            }
        }
        // Default to visible password so user can immediately verify there are no typos!
        etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        container.addView(cbShowPass)

        // 4. Server URL / Domain (e.g. mar10.sbs or http://mar10.sbs:8080)
        container.addView(makeFieldLabel("🌐 " + getString(R.string.iptv_hint_server_url)))
        val etServer = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(existingSource?.serverOrM3uUrl ?: prefillServer)
            hint = "mar10.sbs  أو  http://mar10.sbs:8080"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        stylePremiumInputBox(etServer, bg, accent)
        container.addView(etServer)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }

        val dialog = AlertDialog.Builder(this).setView(outerScroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnCancel = TextView(this).apply {
            text = getString(R.string.cancel)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bindSubDialogBackNavigation(dialog, btnCancel)

        val btnSave = TextView(this).apply {
            text = getString(R.string.save)
            setTextColor(Color.parseColor("#090D16"))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                var srv = XtreamCodesClient.sanitizeCredentialOrUrl(etServer.text.toString())
                var usr = XtreamCodesClient.sanitizeCredentialOrUrl(etUsername.text.toString())
                var pwd = XtreamCodesClient.sanitizeCredentialOrUrl(etPassword.text.toString())

                // Smart Auto-Swap: If user accidentally typed the domain (e.g. mar10.sbs) in Username box and username in Server box!
                if (!srv.contains(".") && usr.contains(".") && !usr.contains("@")) {
                    val tmp = srv
                    srv = usr
                    usr = tmp
                }

                // If user pasted a full get.php URL with ?username=...&password=... into Server URL box, auto-extract them!
                if (srv.contains("username=", ignoreCase = true) && (usr.isEmpty() || pwd.isEmpty())) {
                    val uMatch = Regex("(?i)[?&]username=([^&]+)").find(srv)?.groupValues?.getOrNull(1)
                    val pMatch = Regex("(?i)[?&]password=([^&]+)").find(srv)?.groupValues?.getOrNull(1)
                    if (usr.isEmpty() && !uMatch.isNullOrBlank()) usr = Uri.decode(uMatch)
                    if (pwd.isEmpty() && !pMatch.isNullOrBlank()) pwd = Uri.decode(pMatch)
                }

                val nameStr = etName.text.toString().trim().ifEmpty {
                    srv.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
                        .ifEmpty { "Xtream IPTV" }
                }
                if (srv.isEmpty() || usr.isEmpty() || pwd.isEmpty()) {
                    Toast.makeText(this@MainActivity, getString(R.string.iptv_err_missing_fields), Toast.LENGTH_SHORT).show()
                } else {
                    isNavigatingToSubDialog = true
                    dialog.dismiss()
                    isNavigatingToSubDialog = false
                    settingsBackStack.clear()
                    syncIptvSourceWithProgress(
                        existingSourceId = existingSource?.id,
                        name = nameStr,
                        type = IptvSourceType.XTREAM,
                        serverOrM3uUrl = srv,
                        username = usr,
                        passwordPlain = pwd
                    )
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        container.addView(btnRow)
        dialog.show()
    }

    private fun syncIptvSourceWithProgress(
        existingSourceId: String?,
        name: String,
        type: IptvSourceType,
        serverOrM3uUrl: String,
        username: String = "",
        passwordPlain: String = ""
    ) {
        settingsBackStack.clear()
        closeActiveFullScreenDialogSmoothly()
        iptvSyncJob?.cancel()
        binding.iptvLoadingOverlay.visibility = View.VISIBLE
        binding.tvIptvLoadingStatus.text = getString(R.string.iptv_loading_data)

        iptvSyncJob = lifecycleScope.launch {
            val res = IptvRepository.syncAndSaveSource(
                context = this@MainActivity,
                existingSourceId = existingSourceId,
                name = name,
                type = type,
                serverOrM3uUrl = serverOrM3uUrl,
                username = username,
                passwordPlain = passwordPlain
            )
            binding.iptvLoadingOverlay.visibility = View.GONE
            when (res) {
                is IptvSyncResult.Success -> {
                    activeIptvSource = res.source
                    iptvNavLevel = 1
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.iptv_sync_success_toast, res.source.name, res.totalLoaded),
                        Toast.LENGTH_LONG
                    ).show()
                    renderIptvCurrentLevel()
                }
                is IptvSyncResult.Error -> {
                    Toast.makeText(this@MainActivity, res.userMessage, Toast.LENGTH_LONG).show()
                    if (type == IptvSourceType.XTREAM) {
                        val diagInfo = "Server: $serverOrM3uUrl\nUser: $username\nStatus: ${res.technicalDiagnostic}"
                        showPremiumMenuDialog(
                            headerIcon = "⚠️",
                            title = res.userMessage,
                            subtitle = diagInfo,
                            items = listOf(
                                PremiumMenuItem(
                                    iconEmoji = "✏️",
                                    title = getString(R.string.iptv_source_edit_action),
                                    subtitle = "مراجعة وتعديل الرابط أو اليوزر أو الباسورد",
                                    isAccentHighlighted = true,
                                    onClick = {
                                        showAddOrEditXtreamDialog(
                                            existingSource = null,
                                            prefillName = name,
                                            prefillServer = serverOrM3uUrl,
                                            prefillUser = username,
                                            prefillPass = passwordPlain
                                        )
                                    }
                                )
                            )
                        )
                    }
                    renderIptvCurrentLevel()
                }
            }
        }
    }

    private fun refreshExistingIptvSource(source: IptvSource) {
        val decryptedPass = IptvCredentialVault.decryptSecret(source.encryptedPassword)
        syncIptvSourceWithProgress(
            existingSourceId = source.id,
            name = source.name,
            type = source.type,
            serverOrM3uUrl = source.serverOrM3uUrl,
            username = source.username,
            passwordPlain = decryptedPass
        )
    }

    private fun showIptvSourceManagementMenu(source: IptvSource) {
        settingsBackStack.clear()
        val items = mutableListOf(
            PremiumMenuItem(
                iconEmoji = "📺",
                title = getString(R.string.iptv_source_open_action),
                subtitle = "${source.totalItemsCount()} ${getString(R.string.iptv_items_unit)}",
                isAccentHighlighted = true,
                onClick = {
                    activeIptvSource = source
                    IptvRepository.setActiveSourceId(this, source.id)
                    iptvNavLevel = 1
                    renderIptvCurrentLevel()
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔄",
                title = getString(R.string.iptv_source_refresh_action),
                subtitle = getString(R.string.iptv_source_refresh_sub),
                onClick = {
                    refreshExistingIptvSource(source)
                }
            )
        )

        if (source.type == IptvSourceType.XTREAM || source.type == IptvSourceType.M3U_URL) {
            items.add(
                PremiumMenuItem(
                    iconEmoji = "✏️",
                    title = getString(R.string.iptv_source_edit_action),
                    subtitle = source.serverOrM3uUrl,
                    onClick = {
                        pushSettingsBackStack { showIptvSourceManagementMenu(source) }
                        if (source.type == IptvSourceType.XTREAM) {
                            showAddOrEditXtreamDialog(source)
                        } else {
                            showAddOrEditM3uUrlDialog(source)
                        }
                    }
                )
            )
        }

        items.add(
            PremiumMenuItem(
                iconEmoji = "🗑️",
                title = getString(R.string.iptv_source_delete_action),
                subtitle = getString(R.string.delete_action),
                isDanger = true,
                onClick = {
                    IptvRepository.deleteSource(this, source.id)
                    if (activeIptvSource?.id == source.id) {
                        activeIptvSource = IptvRepository.getSources(this).firstOrNull()
                        iptvNavLevel = 0
                    }
                    Toast.makeText(this, getString(R.string.iptv_source_deleted_toast, source.name), Toast.LENGTH_SHORT).show()
                    renderIptvCurrentLevel()
                }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "📡",
            title = source.name,
            subtitle = "${source.type.name} • ${source.accountStatus}",
            items = items
        )
    }

    private fun handleIptvDownloadRequest(item: Any) {
        when (item) {
            is IptvStreamItem -> {
                if (item.streamUrl.isNotBlank()) {
                    downloadIptvMediaLocally(
                        title = item.title,
                        streamUrl = item.streamUrl,
                        userAgent = item.httpUserAgent,
                        referrer = item.httpReferrer
                    )
                }
            }
            is IptvEpisodeItem -> {
                if (item.streamUrl.isNotBlank()) {
                    val sTitle = activeIptvSeriesInfo?.seriesTitle ?: ""
                    val dlTitle = if (sTitle.isNotBlank()) {
                        "${sTitle}_S${item.seasonNumber}E${item.episodeNumber}_${item.title}"
                    } else {
                        "S${item.seasonNumber}E${item.episodeNumber}_${item.title}"
                    }
                    downloadIptvMediaLocally(
                        title = dlTitle,
                        streamUrl = item.streamUrl,
                        userAgent = item.httpUserAgent,
                        referrer = item.httpReferrer
                    )
                }
            }
        }
    }

    private fun downloadIptvMediaLocally(
        title: String,
        streamUrl: String,
        userAgent: String = "",
        referrer: String = ""
    ) {
        val cleanUrl = streamUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(this, getString(R.string.iptv_download_failed_toast), Toast.LENGTH_SHORT).show()
            return
        }

        val urlPath = cleanUrl.substringBefore("?").substringAfterLast("/")
        val rawExt = urlPath.substringAfterLast(".", "").lowercase(Locale.ROOT)
        val ext = when {
            rawExt in setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "flv", "m4v") -> rawExt
            else -> "mp4"
        }

        val sanitizedBase = title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "PureVideo_${System.currentTimeMillis()}" }

        val fileName = if (sanitizedBase.lowercase(Locale.ROOT).endsWith(".$ext")) {
            sanitizedBase
        } else {
            "$sanitizedBase.$ext"
        }

        val ua = userAgent.ifBlank {
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }

        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(cleanUrl)).apply {
                setTitle(title)
                setDescription("Pure Video Player • Offline Download")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                addRequestHeader("User-Agent", ua)
                if (referrer.isNotBlank()) {
                    addRequestHeader("Referer", referrer)
                }
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()
                setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "PureVideoPlayer/$fileName")
            }
            dm.enqueue(req)
            Toast.makeText(this, getString(R.string.iptv_download_started_toast, title), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            // Fallback direct background stream download to Movies/PureVideoPlayer
            Toast.makeText(this, getString(R.string.iptv_download_started_toast, title), Toast.LENGTH_LONG).show()
            lifecycleScope.launch {
                val savedFile = withContext(Dispatchers.IO) {
                    try {
                        val moviesRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                            ?: getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                            ?: filesDir
                        val targetDir = File(moviesRoot, "PureVideoPlayer").apply { mkdirs() }
                        val outFile = File(targetDir, fileName)
                        val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            connectTimeout = 15_000
                            readTimeout = 30_000
                            setRequestProperty("User-Agent", ua)
                            if (referrer.isNotBlank()) setRequestProperty("Referer", referrer)
                        }
                        if (conn.responseCode !in 200..299) return@withContext null
                        conn.inputStream.use { input ->
                            FileOutputStream(outFile).use { output ->
                                val buf = ByteArray(32 * 1024)
                                while (true) {
                                    val r = input.read(buf)
                                    if (r <= 0) break
                                    output.write(buf, 0, r)
                                }
                            }
                        }
                        if (outFile.exists() && outFile.length() > 0L) {
                            runCatching {
                                MediaScannerConnection.scanFile(
                                    applicationContext,
                                    arrayOf(outFile.absolutePath),
                                    null,
                                    null
                                )
                            }
                            outFile
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
                if (!isFinishing && !isDestroyed) {
                    if (savedFile != null) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.iptv_download_completed_toast, title),
                            Toast.LENGTH_LONG
                        ).show()
                        loadVideoFoldersOnly()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.iptv_download_failed_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun showIptvMovieDetailsDialog(movie: IptvStreamItem) {
        settingsBackStack.clear()
        val metaParts = mutableListOf<String>()
        if (movie.categoryName.isNotBlank()) metaParts.add(movie.categoryName)
        if (movie.rating.isNotBlank() && movie.rating != "0") metaParts.add("⭐ ${movie.rating}")
        if (movie.releaseDate.isNotBlank()) metaParts.add(movie.releaseDate)
        val subLine = metaParts.joinToString(" • ").ifEmpty { "VOD Movie" }
        val isFav = IptvRepository.isFavoriteStream(this, movie.sourceId, movie.id)

        val items = mutableListOf(
            PremiumMenuItem(
                iconEmoji = "▶️",
                title = getString(R.string.iptv_play_movie_now),
                subtitle = subLine,
                isAccentHighlighted = true,
                onClick = {
                    IptvRepository.recordRecentWatchedItem(this, movie.sourceId, movie.id)
                    val savedPos = PlaybackStore.getSavedPosition(this, Uri.parse(movie.streamUrl))
                    launchPlayer(
                        uri = Uri.parse(movie.streamUrl),
                        title = movie.title,
                        startPositionMs = if (savedPos > 3000L) savedPos else 0L,
                        httpUserAgent = movie.httpUserAgent,
                        httpReferrer = movie.httpReferrer
                    )
                }
            ),
            PremiumMenuItem(
                iconEmoji = "⬇️",
                title = getString(R.string.iptv_download_btn),
                subtitle = getString(R.string.iptv_download_sub),
                isAccentHighlighted = true,
                onClick = {
                    settingsBackStack.clear()
                    downloadIptvMediaLocally(
                        title = movie.title,
                        streamUrl = movie.streamUrl,
                        userAgent = movie.httpUserAgent,
                        referrer = movie.httpReferrer
                    )
                }
            ),
            PremiumMenuItem(
                iconEmoji = if (isFav) "💔" else "❤️",
                title = if (isFav) getString(R.string.iptv_fav_remove_option) else getString(R.string.iptv_fav_add_option),
                subtitle = getString(R.string.iptv_subtab_favorites),
                onClick = {
                    val nowFav = IptvRepository.toggleFavoriteStream(this, movie.sourceId, movie.id)
                    val msgRes = if (nowFav) R.string.iptv_fav_added_toast else R.string.iptv_fav_removed_toast
                    Toast.makeText(this, getString(msgRes, movie.title), Toast.LENGTH_SHORT).show()
                    renderIptvCurrentLevel()
                    showIptvMovieDetailsDialog(movie)
                }
            )
        )
        if (movie.plot.isNotBlank()) {
            items.add(
                PremiumMenuItem(
                    iconEmoji = "📝",
                    title = getString(R.string.iptv_movie_story_label),
                    subtitle = movie.plot,
                    isInteractive = false
                )
            )
        }
        items.add(
            PremiumMenuItem(
                iconEmoji = "🗂️",
                title = getString(R.string.iptv_reclassify_category_title),
                subtitle = movie.categoryName,
                onClick = {
                    pushSettingsBackStack { showIptvMovieDetailsDialog(movie) }
                    showReclassifyCategoryDialog(movie.categoryName)
                }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "🎬",
            title = movie.title,
            subtitle = subLine,
            items = items
        )
    }

    private fun openIptvSeriesSeasons(seriesItem: IptvStreamItem) {
        val src = activeIptvSource ?: return
        if (src.type == IptvSourceType.XTREAM && seriesItem.seriesId.isNotBlank()) {
            binding.iptvLoadingOverlay.visibility = View.VISIBLE
            binding.tvIptvLoadingStatus.text = getString(R.string.iptv_loading_series_episodes)
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching { XtreamCodesClient.fetchSeriesSeasonsAndEpisodes(src, seriesItem) }.getOrNull()
                }
                binding.iptvLoadingOverlay.visibility = View.GONE
                if (res != null && res.seasons.isNotEmpty()) {
                    activeIptvSeriesInfo = res
                    iptvNavLevel = 4
                    renderIptvCurrentLevel()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.iptv_err_no_episodes), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // M3U Series: group cached episodes matching seriesName by seasonNumber
            val sName = seriesItem.seriesName.ifBlank { seriesItem.title }
            val allEps = IptvRepository.loadCachedStreams(this, src.id)
                .filter { it.contentType == IptvContentType.SERIES && (it.seriesName.equals(sName, ignoreCase = true) || it.title.startsWith(sName, ignoreCase = true)) }
            val seasons = allEps.groupBy { it.seasonNumber.coerceAtLeast(1) }
                .entries
                .sortedBy { it.key }
                .map { (sNum, epList) ->
                    IptvSeasonGroup(
                        seasonNumber = sNum,
                        name = "Season $sNum / الموسم $sNum",
                        episodes = epList.sortedBy { it.episodeNumber }.mapIndexed { idx, ep ->
                            IptvEpisodeItem(
                                id = ep.id,
                                title = ep.title,
                                seasonNumber = sNum,
                                episodeNumber = if (ep.episodeNumber > 0) ep.episodeNumber else idx + 1,
                                streamUrl = ep.streamUrl,
                                logoUrl = ep.logoUrl,
                                httpUserAgent = ep.httpUserAgent,
                                httpReferrer = ep.httpReferrer
                            )
                        }
                    )
                }
            if (seasons.isNotEmpty()) {
                activeIptvSeriesInfo = IptvSeriesInfoResult(
                    seriesTitle = sName,
                    coverUrl = seriesItem.logoUrl,
                    plot = seriesItem.plot,
                    rating = seriesItem.rating,
                    releaseDate = seriesItem.releaseDate,
                    seasons = seasons
                )
                iptvNavLevel = 4
                renderIptvCurrentLevel()
            } else {
                Toast.makeText(this, getString(R.string.iptv_err_no_episodes), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showReclassifyCategoryDialog(categoryName: String) {
        val src = activeIptvSource ?: return
        val options = listOf(
            Triple("📺", getString(R.string.iptv_sec_live_tv), IptvContentType.LIVE_TV),
            Triple("🎬", getString(R.string.iptv_sec_movies), IptvContentType.MOVIES),
            Triple("🎞️", getString(R.string.iptv_sec_series), IptvContentType.SERIES),
            Triple("📻", getString(R.string.iptv_sec_radio), IptvContentType.RADIO)
        )
        val items = options.map { (emoji, label, targetType) ->
            PremiumMenuItem(
                iconEmoji = emoji,
                title = label,
                subtitle = categoryName,
                isAccentHighlighted = (activeIptvContentType == targetType),
                onClick = {
                    IptvRepository.setCategoryOverride(this, src.id, categoryName, targetType)
                    activeIptvSource = IptvRepository.getSources(this).find { it.id == src.id }
                    Toast.makeText(this, "✅ $categoryName → $label", Toast.LENGTH_SHORT).show()
                    settingsBackStack.clear()
                    renderIptvCurrentLevel()
                }
            )
        }
        showPremiumMenuDialog(
            headerIcon = "🗂️",
            title = getString(R.string.iptv_reclassify_category_title),
            subtitle = categoryName,
            items = items
        )
    }

    private fun addBuiltInDemoIptvSource() {
        val sampleM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Big Buck Bunny Live" group-title="Live Channels / قنوات مباشرة",Big Buck Bunny HD (Live Stream)
            https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
            #EXTINF:-1 tvg-name="Apple BipBop Live" group-title="Live Channels / قنوات مباشرة",Apple Advanced HLS Live
            https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8
            #EXTINF:-1 tvg-name="Tears of Steel HLS Live" group-title="Live Documentary / وثائقي مباشر",Tears of Steel Live HLS
            https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8
            #EXTINF:-1 tvg-name="Sintel Cinema Movie" group-title="Movies / أفلام سينمائية",Sintel Open Movie (1080p VOD)
            https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4
            #EXTINF:-1 tvg-name="Tears of Steel Movie" group-title="Movies / أفلام سينمائية",Tears of Steel Sci-Fi (VOD)
            https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4
            #EXTINF:-1 tvg-name="Elephants Dream Movie" group-title="Movies / أفلام أنيميشن",Elephants Dream HD (VOD)
            https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4
            #EXTINF:-1 tvg-name="Blender Adventures S01E01" group-title="Series / مسلسلات",Blender Adventures S01E01
            https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4
            #EXTINF:-1 tvg-name="Blender Adventures S01E02" group-title="Series / مسلسلات",Blender Adventures S01E02
            https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4
            #EXTINF:-1 tvg-name="Blender Adventures S02E01" group-title="Series / مسلسلات",Blender Adventures S02E01
            https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4
            #EXTINF:-1 tvg-name="Blender Adventures S02E02" group-title="Series / مسلسلات",Blender Adventures S02E02
            https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4
        """.trimIndent()

        lifecycleScope.launch {
            val demoFile = withContext(Dispatchers.IO) {
                val f = File(filesDir, "demo_iptv_showcase.m3u8")
                f.writeText(sampleM3u, Charsets.UTF_8)
                f
            }
            syncIptvSourceWithProgress(
                existingSourceId = "demo_builtin_source",
                name = "Pure IPTV Demo (قنوات وأفلام ومسلسلات تجريبية)",
                type = IptvSourceType.M3U_FILE,
                serverOrM3uUrl = Uri.fromFile(demoFile).toString()
            )
        }
    }
}
