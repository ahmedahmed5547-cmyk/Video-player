package com.pureplayer.video

import android.Manifest
import android.app.Activity
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
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
import android.widget.ProgressBar
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pureplayer.video.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val onClick: () -> Unit
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaAdapter

    private var allDiscoveredVideos = listOf<VideoItem>()
    private var allDiscoveredFolders = listOf<VideoFolder>()

    private var allVideosFlat = listOf<VideoItem>()
    private var videoFoldersOnly = listOf<VideoFolder>()

    private var isShowingFolderVideos = false
    private var currentOpenedFolder: VideoFolder? = null
    private var isSearchActive = false
    private var pendingDeleteVideo: VideoItem? = null
    private var loadMediaJob: Job? = null

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
        if (hasVideoAccessPermission()) {
            loadVideoFoldersOnly()
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
    private fun showPremiumMenuDialog(
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
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(surface)
                setStroke(dp(1), Color.argb(95, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }
        outerScroll.addView(cardContainer)

        // Glowing top drag/accent pill
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

        // Header Row (Icon Badge + Title & Subtitle)
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
            setTextColor(Color.WHITE)
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

        // Subtle Divider
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(12)
            }
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
        }
        cardContainer.addView(divider)

        val dialog = AlertDialog.Builder(this)
            .setView(outerScroll)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Add each option as a sleek interactive mini-card (with optional organized category header)
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
                    else -> Color.parseColor("#1FFFFFFF")
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
                setTextColor(if (item.isDanger) Color.parseColor("#FDA4AF") else Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            textCol.addView(tvItemTitle)

            if (!item.subtitle.isNullOrBlank()) {
                val tvItemSub = TextView(this).apply {
                    text = item.subtitle
                    setTextColor(if (item.isAccentHighlighted) accent else Color.parseColor("#94A3B8"))
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
            }

            rowCard.addView(iconBadge)
            rowCard.addView(textCol)
            rowCard.addView(tvArrow)

            rowCard.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                }
                false
            }

            rowCard.setOnClickListener {
                dialog.dismiss()
                item.onClick()
            }

            cardContainer.addView(rowCard)
        }

        // Sleek Close Button at bottom
        val btnClose = TextView(this).apply {
            text = getString(R.string.close)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CBD5E1"))
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
                setColor(Color.parseColor("#18FFFFFF"))
            }
            setOnClickListener { dialog.dismiss() }
        }
        cardContainer.addView(btnClose)

        dialog.show()
    }

    private fun applyUserTheme() {
        val accent = PlaybackStore.getAccentColor(this)
        val bg = PlaybackStore.getBackgroundColor(this)
        val surface = PlaybackStore.getSurfaceColor(this)

        binding.mainRoot.setBackgroundColor(bg)
        binding.topBar.setBackgroundColor(surface)
        binding.searchBarContainer.setBackgroundColor(surface)
        window.statusBarColor = surface
        window.navigationBarColor = bg
        binding.btnSettings.imageTintList = ColorStateList.valueOf(accent)
        binding.btnToggleSearch.imageTintList = ColorStateList.valueOf(accent)
        binding.fabResumeLast.backgroundTintList = ColorStateList.valueOf(accent)

        val searchBoxBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(bg)
            setStroke(2, accent)
        }
        binding.etSearchVideos.background = searchBoxBg
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
                if (PlaybackStore.isFolderLocked(this, folder.name)) {
                    authenticatePrivacyAccess(folder.name) {
                        openFolderNow(folder)
                    }
                } else {
                    openFolderNow(folder)
                }
            },
            onVideoClick = { video ->
                handleVideoSelection(video.uri, video.title)
            },
            onVideoLongClick = { video ->
                showVideoLongPressMenu(video)
            },
            onFolderLongClick = { folder ->
                showFolderLockMenu(folder)
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun openFolderNow(folder: VideoFolder) {
        isShowingFolderVideos = true
        currentOpenedFolder = folder
        binding.tvToolbarTitle.text = "${folder.name} (${folder.videos.size})"
        binding.btnBackFolder.visibility = View.VISIBLE
        adapter.submitVideos(folder.videos)
    }

    /**
     * Premium Folder Long-Press Popup Menu:
     * Dedicated strictly to locking/unlocking the folder with Password & Fingerprint + Changing Password.
     */
    private fun showFolderLockMenu(folder: VideoFolder) {
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
                            toggleSingleFolderLock(folder.name)
                        }
                    } else {
                        authenticatePrivacyAccess(folder.name) {
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
        val msgRes = if (nowLocked) R.string.folder_locked_toast else R.string.folder_unlocked_toast
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
        editText.setTextColor(Color.WHITE)
        editText.setHintTextColor(Color.parseColor("#64748B"))
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
            setTextColor(Color.WHITE)
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
            setOnClickListener { dialog.dismiss() }
        }

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
            setTextColor(Color.WHITE)
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
            setOnClickListener { dialog.dismiss() }
        }

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
                    dialog.dismiss()
                    PlaybackStore.setPrivacyPin(this@MainActivity, newPin)
                    BiometricHardwareGuard.bindKeyAfterPasswordVerified(this@MainActivity)
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_pin_saved_toast), Toast.LENGTH_SHORT).show()
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
            setTextColor(Color.WHITE)
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
            setOnClickListener { dialog.dismiss() }
        }

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
            setTextColor(Color.WHITE)
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
            setOnClickListener { dialog.dismiss() }
        }

        val btnDelete = TextView(this).apply {
            text = getString(R.string.privacy_remove_pin_action)
            setTextColor(Color.WHITE)
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
                    dialog.dismiss()
                    PlaybackStore.clearPrivacyPinAndLocks(this@MainActivity)
                    BiometricHardwareGuard.clearBiometricKey(this@MainActivity)
                    applyFolderVisibilityAndRefreshUI()
                    updateResumeLastFab()
                    Toast.makeText(this@MainActivity, getString(R.string.privacy_pin_removed_toast), Toast.LENGTH_LONG).show()
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
                onClick = { showLockedFoldersMultiPickerDialog() }
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
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🔑",
                title = getString(R.string.privacy_change_pin_option),
                subtitle = getString(R.string.privacy_change_pin_sub),
                onClick = { showChangePasswordWithCurrentVerifyDialog() }
            ),
            PremiumMenuItem(
                iconEmoji = "🗑️",
                title = getString(R.string.privacy_remove_pin_option),
                subtitle = getString(R.string.privacy_remove_pin_sub),
                isDanger = true,
                onClick = { showRemovePasswordVerifyDialog() }
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
            setTextColor(Color.WHITE)
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
                setTextColor(Color.WHITE)
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
            setOnClickListener { dialog.dismiss() }
        }

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
                }
            ),
            PremiumMenuItem(
                iconEmoji = "✅",
                title = getString(R.string.folder_mode_selected_only, allowedCount),
                subtitle = if (currentMode == 1) "✅ " + getString(R.string.active_mode_badge) else getString(R.string.folder_picker_dialog_title),
                isAccentHighlighted = (currentMode == 1),
                onClick = {
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
                }
            }
        )
    }

    private fun showVideoLongPressMenu(video: VideoItem) {
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
                onClick = { showVideoDetailsDialog(video) }
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
                    handleVideoSelection(enrichedVideo.uri, enrichedVideo.title)
                },
                PremiumMenuItem("📐", getString(R.string.video_detail_resolution, enrichedVideo.getResolutionLabel()), "$sizeFormatted • $durationFormatted") {
                    handleVideoSelection(enrichedVideo.uri, enrichedVideo.title)
                },
                PremiumMenuItem("📍", getString(R.string.video_detail_path, displayPath), null) {},
                PremiumMenuItem("🗑️", getString(R.string.video_menu_delete), getString(R.string.delete_action), isDanger = true) {
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
            val formattedTime = PlaybackStore.formatTime(savedPositionMs)
            val items = listOf(
                PremiumMenuItem(
                    iconEmoji = "▶️",
                    title = getString(R.string.resume_from_format, formattedTime),
                    subtitle = title,
                    isAccentHighlighted = true,
                    onClick = { launchPlayer(uri, title, startPositionMs = savedPositionMs) }
                ),
                PremiumMenuItem(
                    iconEmoji = "🔄",
                    title = getString(R.string.start_from_beginning),
                    subtitle = "00:00",
                    onClick = { launchPlayer(uri, title, startPositionMs = 0L) }
                )
            )
            showPremiumMenuDialog(
                headerIcon = "⏱️",
                title = getString(R.string.resume_dialog_title),
                subtitle = formattedTime,
                items = items
            )
        } else {
            launchPlayer(uri, title, startPositionMs = 0L)
        }
    }

    private fun setupListeners() {
        // Long-Pressing on "Video Folders / مجلدات الفيديو" title in the top bar opens the Premium Folder Visibility Selector!
        binding.tvToolbarTitle.isLongClickable = true
        binding.tvToolbarTitle.setOnLongClickListener {
            showFolderVisibilityModeDialog()
            true
        }

        binding.btnBackFolder.setOnClickListener {
            if (isSearchActive) {
                closeSearchBar()
            } else {
                showFoldersList()
            }
        }

        binding.btnToggleSearch.setOnClickListener {
            if (isSearchActive) {
                closeSearchBar()
            } else {
                openSearchBar()
            }
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
                filterVideosByQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

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
                when {
                    isSearchActive -> closeSearchBar()
                    isShowingFolderVideos -> showFoldersList()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun openSearchBar() {
        isSearchActive = true
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
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchVideos.windowToken, 0)
        binding.searchBarContainer.visibility = View.GONE
        if (isShowingFolderVideos && currentOpenedFolder != null) {
            binding.tvToolbarTitle.text = "${currentOpenedFolder!!.name} (${currentOpenedFolder!!.videos.size})"
            binding.btnBackFolder.visibility = View.VISIBLE
            adapter.submitVideos(currentOpenedFolder!!.videos)
        } else {
            showFoldersList()
        }
    }

    private fun filterVideosByQuery(rawQuery: String) {
        val query = rawQuery.trim()
        if (!isSearchActive) return

        val sourcePool = if (isShowingFolderVideos && currentOpenedFolder != null) {
            currentOpenedFolder!!.videos
        } else {
            val lockedSet = PlaybackStore.getLockedFolders(this)
            allVideosFlat.filter { it.folderName !in lockedSet }
        }

        if (query.isEmpty()) {
            adapter.submitVideos(sourcePool)
            return
        }

        val filtered = sourcePool.filter {
            it.title.contains(query, ignoreCase = true) || it.folderName.contains(query, ignoreCase = true)
        }
        adapter.submitVideos(filtered)
    }

    /**
     * Premium Settings Menu: Styled with sleek cards, emoji badges, and live status subtitles!
     */
    private fun showProSettingsDialog() {
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
        val langTag = PlaybackStore.getAppLanguageTag(this)
        val langSub = when (langTag) {
            "ar" -> getString(R.string.lang_arabic)
            "en" -> getString(R.string.lang_english)
            else -> getString(R.string.lang_system_default)
        }
        val subLangs = PlaybackStore.getPreferredSubtitleLanguages(this).joinToString(" → ") { it.uppercase() }
        val themeNames = arrayOf(
            getString(R.string.theme_cyber_blue),
            getString(R.string.theme_amoled_gold),
            getString(R.string.theme_emerald),
            getString(R.string.theme_crimson),
            getString(R.string.theme_violet)
        )
        val currentThemeName = themeNames[PlaybackStore.getThemeIndex(this).coerceIn(0, themeNames.size - 1)]
        val subSize = PlaybackStore.getSubSizeSp(this).toInt()
        val subBgOpacity = PlaybackStore.getSubBgOpacityPct(this)
        val orientIdx = PlaybackStore.getDefaultOrientationIndex(this)
        val orientSub = when (orientIdx) {
            0 -> getString(R.string.orientation_auto)
            2 -> getString(R.string.orientation_portrait)
            else -> getString(R.string.orientation_landscape)
        }

        val items = mutableListOf(
            // Category 1: Privacy & Folders
            PremiumMenuItem(
                iconEmoji = "🔒",
                title = getString(R.string.setting_privacy_section),
                subtitle = privacySub,
                isAccentHighlighted = true,
                sectionHeader = getString(R.string.settings_cat_privacy_folders),
                onClick = { openPrivacyControlSection() }
            ),
            PremiumMenuItem(
                iconEmoji = "📂",
                title = getString(R.string.setting_folder_visibility),
                subtitle = filterSub,
                onClick = { showFolderVisibilityModeDialog() }
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

        // Category 2: Appearance, Language & Display
        items.addAll(
            listOf(
                PremiumMenuItem(
                    iconEmoji = "🎨",
                    title = getString(R.string.setting_app_theme),
                    subtitle = currentThemeName,
                    sectionHeader = getString(R.string.settings_cat_appearance_display),
                    onClick = { showThemeSelectorDialog() }
                ),
                PremiumMenuItem(
                    iconEmoji = "🌐",
                    title = getString(R.string.setting_app_language),
                    subtitle = langSub,
                    onClick = { showAppLanguageDialog() }
                ),
                PremiumMenuItem(
                    iconEmoji = "🔄",
                    title = getString(R.string.setting_default_orientation),
                    subtitle = orientSub,
                    onClick = { showDefaultOrientationDialog() }
                )
            )
        )

        // Category 3: Subtitles & Sync
        items.addAll(
            listOf(
                PremiumMenuItem(
                    iconEmoji = "🔤",
                    title = getString(R.string.setting_subtitle_style),
                    subtitle = "${subSize}sp • ${getString(R.string.style_bg_opacity_label, subBgOpacity)}",
                    sectionHeader = getString(R.string.settings_cat_subtitles),
                    onClick = { showSubtitleStyleDialog() }
                ),
                PremiumMenuItem(
                    iconEmoji = "💬",
                    title = getString(R.string.setting_subtitle_languages),
                    subtitle = subLangs,
                    onClick = { showSubtitleLanguagePriorityDialog() }
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
                // Category 4: Updates & GitHub Auto-Download
                PremiumMenuItem(
                    iconEmoji = "🔄",
                    title = getString(R.string.update_check_option_title),
                    subtitle = getString(R.string.update_check_option_sub, BuildConfig.VERSION_CODE),
                    isAccentHighlighted = true,
                    sectionHeader = getString(R.string.settings_cat_updates),
                    onClick = {
                        openGitHubUpdaterFlow()
                    }
                )
            )
        )

        showPremiumMenuDialog(
            headerIcon = "⚙️",
            title = getString(R.string.settings_header),
            subtitle = "Pure Video Player Premium",
            items = items
        )
    }

    private fun showAppLanguageDialog() {
        val currentTag = PlaybackStore.getAppLanguageTag(this)
        val items = listOf(
            PremiumMenuItem("📱", getString(R.string.lang_system_default), null, currentTag == "") {
                PlaybackStore.setAppLanguageTag(this, "")
            },
            PremiumMenuItem("🇸🇦", getString(R.string.lang_arabic), "العربية", currentTag == "ar") {
                PlaybackStore.setAppLanguageTag(this, "ar")
            },
            PremiumMenuItem("🇺🇸", getString(R.string.lang_english), "English", currentTag == "en") {
                PlaybackStore.setAppLanguageTag(this, "en")
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
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showThemeSelectorDialog() {
        val current = PlaybackStore.getThemeIndex(this)
        val themes = listOf(
            Triple("💎", getString(R.string.theme_cyber_blue), "#38BDF8"),
            Triple("✨", getString(R.string.theme_amoled_gold), "#F59E0B"),
            Triple("🌿", getString(R.string.theme_emerald), "#10B981"),
            Triple("🔥", getString(R.string.theme_crimson), "#F43F5E"),
            Triple("🔮", getString(R.string.theme_violet), "#A855F7")
        )
        val items = themes.mapIndexed { idx, (emoji, name, hex) ->
            PremiumMenuItem(
                iconEmoji = emoji,
                title = name,
                subtitle = hex,
                isAccentHighlighted = (idx == current),
                onClick = {
                    PlaybackStore.setThemeIndex(this, idx)
                    applyUserTheme()
                }
            )
        }
        showPremiumMenuDialog("🎨", getString(R.string.theme_dialog_title), null, items)
    }

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
        val current = PlaybackStore.getDefaultOrientationIndex(this)
        val items = listOf(
            PremiumMenuItem("🔄", getString(R.string.orientation_auto), null, current == 0) {
                PlaybackStore.setDefaultOrientationIndex(this, 0)
            },
            PremiumMenuItem("↔️", getString(R.string.orientation_landscape), null, current == 1) {
                PlaybackStore.setDefaultOrientationIndex(this, 1)
            },
            PremiumMenuItem("↕️", getString(R.string.orientation_portrait), null, current == 2) {
                PlaybackStore.setDefaultOrientationIndex(this, 2)
            }
        )
        showPremiumMenuDialog("🔄", getString(R.string.setting_default_orientation), null, items)
    }

    private fun showFoldersList() {
        isShowingFolderVideos = false
        currentOpenedFolder = null
        val filterMode = PlaybackStore.getFolderFilterMode(this)
        if (filterMode == 2) {
            val lockedSet = PlaybackStore.getLockedFolders(this)
            val unlockedVideos = allVideosFlat.filter { it.folderName !in lockedSet }
            binding.tvToolbarTitle.text = "${getString(R.string.folders_title)} (${unlockedVideos.size})"
            binding.btnBackFolder.visibility = View.GONE
            adapter.submitVideos(unlockedVideos)
        } else {
            val suffix = if (isUsingLimitedAccessMode()) " • Limited" else ""
            binding.tvToolbarTitle.text = "${getString(R.string.folders_title)}$suffix"
            binding.btnBackFolder.visibility = View.GONE
            adapter.submitFolders(videoFoldersOnly)
        }
    }

    private fun applyFolderVisibilityAndRefreshUI() {
        val mode = PlaybackStore.getFolderFilterMode(this)
        val allowedSet = PlaybackStore.getAllowedFolders(this)

        val filteredBySelection = if (mode == 1 && allowedSet.isNotEmpty()) {
            allDiscoveredFolders.filter { it.name in allowedSet }
        } else {
            allDiscoveredFolders
        }

        // If user is in "Allow Limited Access" mode or deleted/changed folders such that allowedSet didn't match,
        // automatically fall back to showing all discovered folders so the screen is never blank!
        videoFoldersOnly = if (filteredBySelection.isEmpty() && allDiscoveredFolders.isNotEmpty()) {
            allDiscoveredFolders
        } else {
            filteredBySelection
        }

        val visibleFolderNames = videoFoldersOnly.map { it.name }.toSet()
        allVideosFlat = allDiscoveredVideos.filter { it.folderName in visibleFolderNames }

        if (videoFoldersOnly.isEmpty()) {
            val msg = if (isUsingLimitedAccessMode()) {
                getString(R.string.limited_access_empty_msg)
            } else {
                getString(R.string.no_videos_found)
            }
            showEmptyState(msg, showButton = isUsingLimitedAccessMode())
        } else {
            binding.emptyLayout.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            if (isSearchActive) {
                filterVideosByQuery(binding.etSearchVideos.text.toString())
            } else if (isShowingFolderVideos && currentOpenedFolder != null) {
                val updated = videoFoldersOnly.find { it.name == currentOpenedFolder?.name }
                if (updated != null) {
                    currentOpenedFolder = updated
                    binding.tvToolbarTitle.text = "${updated.name} (${updated.videos.size})"
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
            loadVideoFoldersOnly()
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

    private fun loadVideoFoldersOnly() {
        loadMediaJob?.cancel()
        binding.swipeRefresh.isRefreshing = true

        loadMediaJob = lifecycleScope.launch {
            val (videoList, groupedFolders) = withContext(Dispatchers.IO) {
                val list = mutableListOf<VideoItem>()
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT
                )
                val selection = "${MediaStore.Video.Media.SIZE} > 0"
                val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

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

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val title = cursor.getString(nameCol) ?: continue
                            val duration = cursor.getLong(durCol)
                            val size = cursor.getLong(sizeCol)
                            val path = if (dataCol != -1) (cursor.getString(dataCol) ?: "") else ""
                            val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                            val height = if (heightCol != -1) cursor.getInt(heightCol) else 0

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
                                    path = path
                                )
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
            allDiscoveredVideos = videoList
            allDiscoveredFolders = groupedFolders

            applyFolderVisibilityAndRefreshUI()
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
            setTextColor(Color.WHITE)
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
            setOnClickListener { dialog.dismiss() }
        }

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

                Triple(remoteVersionCode, directApkUrl ?: "https://github.com/$repoSlug/releases/latest/download/PureVideoPlayer.apk", latestActionsUrl)
            }

            if (isFinishing || isDestroyed) return@launch
            val (remoteCode, apkDownloadUrl, actionsRunUrl) = result
            val currentCode = BuildConfig.VERSION_CODE

            val statusSubtitle = when {
                remoteCode > currentCode -> getString(R.string.update_status_new_available, remoteCode, currentCode)
                remoteCode == currentCode -> getString(R.string.update_status_up_to_date, currentCode)
                else -> "GitHub: $repoSlug (v$currentCode)"
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
            setTextColor(Color.WHITE)
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
