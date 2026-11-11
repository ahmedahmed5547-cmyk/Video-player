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
import android.view.View
import android.view.inputmethod.InputMethodManager
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

            // Verify the newly generated key can initialize a Cipher right now
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
            // E.g. no fingerprint enrolled on device yet -> keep false so if someone enrolls a fingerprint later, password is still required first!
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
            // A new fingerprint was added to the device! Invalidate biometric unlock until password is entered!
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
}

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
        updateResumeLastFab()
        adapter.notifyDataSetChanged()
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
     * Requirement: Long-pressing a Folder card in the list is dedicated strictly to
     * locking/unlocking that folder with Password & Fingerprint + Changing the Password.
     */
    private fun showFolderLockMenu(folder: VideoFolder) {
        val isLocked = PlaybackStore.isFolderLocked(this, folder.name)
        val lockToggleText = if (isLocked) {
            getString(R.string.folder_menu_unlock)
        } else {
            getString(R.string.folder_menu_lock)
        }

        val options = arrayOf(
            lockToggleText,
            getString(R.string.privacy_change_pin_option),
            getString(R.string.privacy_select_locked_folders, PlaybackStore.getLockedFolders(this).size)
        )

        AlertDialog.Builder(this)
            .setTitle("🔒 ${folder.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
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
                    1 -> {
                        if (!PlaybackStore.hasPrivacyPin(this)) {
                            showFirstTimeSetupPinDialog()
                        } else {
                            showChangePasswordWithCurrentVerifyDialog()
                        }
                    }
                    2 -> openPrivacyControlSection()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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

    /**
     * Hardware-Secured Privacy Authentication:
     * - If a new fingerprint was registered on the device (or password has not yet authorized the current biometric set),
     *   Fingerprint unlock is BLOCKED and the user MUST enter the Password first!
     */
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
                    // A new fingerprint was added to the phone (or biometrics not yet bound by password)!
                    // Block fingerprint and force password entry first!
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

    private fun showPinUnlockDialog(
        subtitle: String,
        onAuthenticated: () -> Unit,
        allowBiometricRetry: Boolean
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(54, 32, 54, 16)
        }

        val tvSub = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#FACC15"))
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val etPin = EditText(this).apply {
            hint = getString(R.string.privacy_enter_pin_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(32, 28, 32, 28)
        }

        container.addView(tvSub)
        container.addView(etPin)

        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.privacy_unlock_title))
            .setView(container)
            .setPositiveButton(getString(R.string.privacy_unlock_btn)) { _, _ ->
                val entered = etPin.text.toString()
                if (PlaybackStore.verifyPrivacyPin(this, entered)) {
                    // Owner verified by Password! Re-bind hardware biometric key to current device fingerprints
                    BiometricHardwareGuard.bindKeyAfterPasswordVerified(this)
                    onAuthenticated()
                } else {
                    Toast.makeText(this, getString(R.string.privacy_wrong_pin_toast), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)

        if (allowBiometricRetry && PlaybackStore.isBiometricEnabled(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setNeutralButton(getString(R.string.privacy_use_fingerprint_btn)) { _, _ ->
                authenticatePrivacyAccess(subtitle, onAuthenticated)
            }
        }

        builder.show()
    }

    private fun showFirstTimeSetupPinDialog(onPinSaved: () -> Unit = {}) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(54, 32, 54, 16)
        }

        val tvHint = TextView(this).apply {
            text = getString(R.string.privacy_setup_pin_desc)
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val etNewPin = EditText(this).apply {
            hint = getString(R.string.privacy_new_pin_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(32, 28, 32, 28)
        }

        container.addView(tvHint)
        container.addView(etNewPin)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.privacy_setup_title))
            .setView(container)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newPin = etNewPin.text.toString().trim()
                if (newPin.length < 2) {
                    Toast.makeText(this, getString(R.string.privacy_pin_too_short), Toast.LENGTH_SHORT).show()
                } else {
                    PlaybackStore.setPrivacyPin(this, newPin)
                    BiometricHardwareGuard.bindKeyAfterPasswordVerified(this)
                    Toast.makeText(this, getString(R.string.privacy_pin_saved_toast), Toast.LENGTH_SHORT).show()
                    onPinSaved()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Requirement: Option to Change Password (strictly requires entering the Current Password first,
     * then the New Password, and re-binds the hardware biometric key).
     */
    private fun showChangePasswordWithCurrentVerifyDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(54, 28, 54, 16)
        }

        val etCurrentPin = EditText(this).apply {
            hint = getString(R.string.privacy_current_pin_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(32, 24, 32, 24)
        }

        val etNewPin = EditText(this).apply {
            hint = getString(R.string.privacy_new_pin_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(32, 24, 32, 24)
        }

        container.addView(etCurrentPin)
        container.addView(etNewPin)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.privacy_change_pin_title))
            .setView(container)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val currentInput = etCurrentPin.text.toString()
                val newPin = etNewPin.text.toString().trim()
                if (!PlaybackStore.verifyPrivacyPin(this, currentInput)) {
                    Toast.makeText(this, getString(R.string.privacy_wrong_pin_toast), Toast.LENGTH_SHORT).show()
                } else if (newPin.length < 2) {
                    Toast.makeText(this, getString(R.string.privacy_pin_too_short), Toast.LENGTH_SHORT).show()
                } else {
                    PlaybackStore.setPrivacyPin(this, newPin)
                    BiometricHardwareGuard.bindKeyAfterPasswordVerified(this)
                    Toast.makeText(this, getString(R.string.privacy_pin_saved_toast), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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

    private fun showPrivacyDashboardDialog() {
        val bioEnabled = PlaybackStore.isBiometricEnabled(this)
        val lockedCount = PlaybackStore.getLockedFolders(this).size
        val bioStatus = if (bioEnabled) getString(R.string.privacy_bio_on) else getString(R.string.privacy_bio_off)

        val options = arrayOf(
            getString(R.string.privacy_select_locked_folders, lockedCount),
            getString(R.string.privacy_change_pin_option),
            getString(R.string.privacy_toggle_biometric_option, bioStatus)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_privacy_section))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLockedFoldersMultiPickerDialog()
                    1 -> showChangePasswordWithCurrentVerifyDialog()
                    2 -> {
                        val nextState = !bioEnabled
                        PlaybackStore.setBiometricEnabled(this, nextState)
                        val stateStr = if (nextState) getString(R.string.privacy_bio_on) else getString(R.string.privacy_bio_off)
                        Toast.makeText(this, stateStr, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showLockedFoldersMultiPickerDialog() {
        if (allDiscoveredFolders.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_videos_found), Toast.LENGTH_SHORT).show()
            return
        }
        val folderNames = allDiscoveredFolders.map { it.name }.toTypedArray()
        val displayLabels = allDiscoveredFolders.map { "${it.name} (${it.videos.size})" }.toTypedArray()
        val currentLocked = PlaybackStore.getLockedFolders(this).toMutableSet()
        val checked = BooleanArray(folderNames.size) { idx ->
            currentLocked.contains(folderNames[idx])
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.privacy_locked_folders_dialog_title))
            .setMultiChoiceItems(displayLabels, checked) { _, which, isChecked ->
                val name = folderNames[which]
                if (isChecked) {
                    currentLocked.add(name)
                } else {
                    currentLocked.remove(name)
                }
            }
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                PlaybackStore.setLockedFolders(this, currentLocked)
                applyFolderVisibilityAndRefreshUI()
                updateResumeLastFab()
                Toast.makeText(
                    this,
                    getString(R.string.privacy_locked_saved_toast, currentLocked.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Requirement: Triggered by Long-Pressing "Video Folders / مجلدات الفيديو" at the top of the app!
     * Lets the user choose whether ALL folders/videos appear or ONLY specific selected folders appear.
     */
    private fun showFolderVisibilityModeDialog() {
        val currentMode = PlaybackStore.getFolderFilterMode(this)
        val allowedCount = PlaybackStore.getAllowedFolders(this).size
        val modes = arrayOf(
            getString(R.string.folder_mode_all),
            getString(R.string.folder_mode_selected_only, allowedCount),
            getString(R.string.folder_mode_all_videos_flat)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_folder_visibility))
            .setSingleChoiceItems(modes, currentMode) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> {
                        PlaybackStore.setFolderFilterMode(this, 0)
                        isShowingFolderVideos = false
                        currentOpenedFolder = null
                        applyFolderVisibilityAndRefreshUI()
                    }
                    1 -> {
                        showAllowedFoldersMultiPickerDialog()
                    }
                    2 -> {
                        PlaybackStore.setFolderFilterMode(this, 2)
                        isShowingFolderVideos = false
                        currentOpenedFolder = null
                        applyFolderVisibilityAndRefreshUI()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAllowedFoldersMultiPickerDialog() {
        if (allDiscoveredFolders.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_videos_found), Toast.LENGTH_SHORT).show()
            return
        }
        val folderNames = allDiscoveredFolders.map { it.name }.toTypedArray()
        val displayLabels = allDiscoveredFolders.map { "${it.name} (${it.videos.size})" }.toTypedArray()
        val savedAllowed = PlaybackStore.getAllowedFolders(this)
        val selectedSet = if (savedAllowed.isEmpty()) {
            folderNames.toMutableSet()
        } else {
            savedAllowed.toMutableSet()
        }
        val checked = BooleanArray(folderNames.size) { idx ->
            selectedSet.contains(folderNames[idx])
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.folder_picker_dialog_title))
            .setMultiChoiceItems(displayLabels, checked) { _, which, isChecked ->
                val name = folderNames[which]
                if (isChecked) {
                    selectedSet.add(name)
                } else {
                    selectedSet.remove(name)
                }
            }
            .setPositiveButton(getString(R.string.save)) { _, _ ->
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
            }
            .setNeutralButton(getString(R.string.folder_mode_all)) { _, _ ->
                PlaybackStore.setFolderFilterMode(this, 0)
                isShowingFolderVideos = false
                currentOpenedFolder = null
                applyFolderVisibilityAndRefreshUI()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showVideoLongPressMenu(video: VideoItem) {
        val options = arrayOf(
            getString(R.string.video_menu_play),
            getString(R.string.video_menu_details),
            getString(R.string.video_menu_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(video.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> handleVideoSelection(video.uri, video.title)
                    1 -> showVideoDetailsDialog(video)
                    2 -> confirmAndDeleteVideo(video)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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

            val detailsMessage = listOf(
                getString(R.string.video_detail_name, enrichedVideo.title),
                getString(R.string.video_detail_resolution, enrichedVideo.getResolutionLabel()),
                getString(R.string.video_detail_size, sizeFormatted),
                getString(R.string.video_detail_duration, durationFormatted),
                getString(R.string.video_detail_folder, enrichedVideo.folderName),
                getString(R.string.video_detail_path, displayPath)
            ).joinToString("\n\n")

            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.video_details_title))
                .setMessage(detailsMessage)
                .setPositiveButton(getString(R.string.video_menu_play)) { _, _ ->
                    handleVideoSelection(enrichedVideo.uri, enrichedVideo.title)
                }
                .setNeutralButton(getString(R.string.video_menu_delete)) { _, _ ->
                    confirmAndDeleteVideo(enrichedVideo)
                }
                .setNegativeButton(getString(R.string.close), null)
                .show()
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
        // Requirement: Long-Pressing on "Video Folders / مجلدات الفيديو" title in the top bar opens the Folder Visibility Selector!
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

    private fun showProSettingsDialog() {
        val options = arrayOf(
            getString(R.string.setting_privacy_section),
            getString(R.string.privacy_change_pin_option),
            getString(R.string.setting_folder_visibility),
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
                    0 -> openPrivacyControlSection()
                    1 -> {
                        if (!PlaybackStore.hasPrivacyPin(this)) {
                            showFirstTimeSetupPinDialog()
                        } else {
                            showChangePasswordWithCurrentVerifyDialog()
                        }
                    }
                    2 -> showFolderVisibilityModeDialog()
                    3 -> showAppLanguageDialog()
                    4 -> showSubtitleLanguagePriorityDialog()
                    5 -> showThemeSelectorDialog()
                    6 -> showSubtitleStyleDialog()
                    7 -> showDefaultOrientationDialog()
                    8 -> {
                        PlaybackStore.resetSubPosition(this)
                        Toast.makeText(this, getString(R.string.sub_position_reset_toast), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

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
        val filterMode = PlaybackStore.getFolderFilterMode(this)
        if (filterMode == 2) {
            val lockedSet = PlaybackStore.getLockedFolders(this)
            val unlockedVideos = allVideosFlat.filter { it.folderName !in lockedSet }
            binding.tvToolbarTitle.text = "${getString(R.string.folders_title)} (${unlockedVideos.size})"
            binding.btnBackFolder.visibility = View.GONE
            adapter.submitVideos(unlockedVideos)
        } else {
            binding.tvToolbarTitle.text = getString(R.string.folders_title)
            binding.btnBackFolder.visibility = View.GONE
            adapter.submitFolders(videoFoldersOnly)
        }
    }

    private fun applyFolderVisibilityAndRefreshUI() {
        val mode = PlaybackStore.getFolderFilterMode(this)
        val allowedSet = PlaybackStore.getAllowedFolders(this)

        videoFoldersOnly = if (mode == 1 && allowedSet.isNotEmpty()) {
            allDiscoveredFolders.filter { it.name in allowedSet }
        } else {
            allDiscoveredFolders
        }

        val visibleFolderNames = videoFoldersOnly.map { it.name }.toSet()
        allVideosFlat = allDiscoveredVideos.filter { it.folderName in visibleFolderNames }

        if (videoFoldersOnly.isEmpty()) {
            showEmptyState(getString(R.string.no_videos_found), showButton = false)
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
                        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                        val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                        val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                        val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val title = cursor.getString(nameCol) ?: continue
                            val duration = cursor.getLong(durCol)
                            val size = cursor.getLong(sizeCol)
                            val path = cursor.getString(dataCol) ?: ""
                            val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                            val height = if (heightCol != -1) cursor.getInt(heightCol) else 0

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
