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

internal fun MainActivity.showFolderLockMenu(folder: VideoFolder) {
    val hostActivity = this
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

internal fun MainActivity.toggleSingleFolderLock(folderName: String) {
    val hostActivity = this
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

internal fun MainActivity.authenticatePrivacyAccess(subtitle: String, onAuthenticated: () -> Unit) {
    val hostActivity = this
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

internal fun MainActivity.stylePremiumInputBox(editText: EditText, bg: Int, accent: Int) {
    val hostActivity = this
    editText.setTextColor(PlaybackStore.getPrimaryTextColor(this))
    editText.setHintTextColor(PlaybackStore.getSecondaryTextColor(this))
    editText.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(bg)
        setStroke(dp(1), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)))
    }
}

internal fun MainActivity.showPinUnlockDialog(
    subtitle: String,
    onAuthenticated: () -> Unit,
    allowBiometricRetry: Boolean
) {
    val hostActivity = this
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
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
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
            if (PlaybackStore.verifyPrivacyPin(hostActivity, entered)) {
                dialog.dismiss()
                BiometricHardwareGuard.bindKeyAfterPasswordVerified(hostActivity)
                onAuthenticated()
            } else {
                Toast.makeText(hostActivity, getString(R.string.privacy_wrong_pin_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    btnRow.addView(btnCancel)
    btnRow.addView(btnUnlock)
    container.addView(btnRow)

    dialog.show()
}

internal fun MainActivity.showFirstTimeSetupPinDialog(onPinSaved: () -> Unit = {}) {
    val hostActivity = this
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
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
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
                Toast.makeText(hostActivity, getString(R.string.privacy_pin_too_short), Toast.LENGTH_SHORT).show()
            } else {
                isNavigatingToSubDialog = true
                dialog.dismiss()
                isNavigatingToSubDialog = false
                PlaybackStore.setPrivacyPin(hostActivity, newPin)
                BiometricHardwareGuard.bindKeyAfterPasswordVerified(hostActivity)
                Toast.makeText(hostActivity, getString(R.string.privacy_pin_saved_toast), Toast.LENGTH_SHORT).show()
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

internal fun MainActivity.showChangePasswordWithCurrentVerifyDialog() {
    val hostActivity = this
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
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
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
            if (!PlaybackStore.verifyPrivacyPin(hostActivity, currentInput)) {
                Toast.makeText(hostActivity, getString(R.string.privacy_wrong_pin_toast), Toast.LENGTH_SHORT).show()
            } else if (newPin.length < 2) {
                Toast.makeText(hostActivity, getString(R.string.privacy_pin_too_short), Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                PlaybackStore.setPrivacyPin(hostActivity, newPin)
                BiometricHardwareGuard.bindKeyAfterPasswordVerified(hostActivity)
                Toast.makeText(hostActivity, getString(R.string.privacy_pin_saved_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    btnRow.addView(btnCancel)
    btnRow.addView(btnSave)
    container.addView(btnRow)
    dialog.show()
}

internal fun MainActivity.openPrivacyControlSection() {
    val hostActivity = this
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

internal fun MainActivity.showRemovePasswordVerifyDialog() {
    val hostActivity = this
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
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
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
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
        textSize = 14.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#E11D48"))
        }
        setOnClickListener {
            val entered = etCurrentPin.text.toString()
            if (!PlaybackStore.verifyPrivacyPin(hostActivity, entered)) {
                Toast.makeText(hostActivity, getString(R.string.privacy_wrong_pin_toast), Toast.LENGTH_SHORT).show()
            } else {
                isNavigatingToSubDialog = true
                dialog.dismiss()
                isNavigatingToSubDialog = false
                PlaybackStore.clearPrivacyPinAndLocks(hostActivity)
                BiometricHardwareGuard.clearBiometricKey(hostActivity)
                isVaultSessionUnlocked = false
                isInVaultTab = false
                applyFolderVisibilityAndRefreshUI()
                updateResumeLastFab()
                Toast.makeText(hostActivity, getString(R.string.privacy_pin_removed_toast), Toast.LENGTH_LONG).show()
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

internal fun MainActivity.showPrivacyDashboardDialog() {
    val hostActivity = this
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
internal fun MainActivity.showPremiumFolderMultiPicker(
    headerIcon: String,
    title: String,
    subtitle: String,
    initialSelected: Set<String>,
    onSaveSelection: (Set<String>) -> Unit,
    neutralActionLabel: String? = null,
    onNeutralAction: (() -> Unit)? = null
) {
    val hostActivity = this
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
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
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
            setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
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

internal fun MainActivity.showLockedFoldersMultiPickerDialog() {
    val hostActivity = this
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
internal fun MainActivity.showFolderVisibilityModeDialog() {
    val hostActivity = this
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

internal fun MainActivity.showAllowedFoldersMultiPickerDialog() {
    val hostActivity = this
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
