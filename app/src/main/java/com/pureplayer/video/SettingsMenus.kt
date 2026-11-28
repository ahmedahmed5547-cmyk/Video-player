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
import android.widget.ImageView
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

internal fun MainActivity.showResumeBehaviorSettingsDialog() {
    val currentMode = PlaybackStore.getResumeAskMode(this)
    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (currentMode == 0) R.drawable.ic_lux_check else R.drawable.ic_lux_info,
            title = getString(R.string.resume_mode_ask_always),
            subtitle = if (currentMode == 0) getString(R.string.active_mode_badge) else null,
            isAccentHighlighted = (currentMode == 0),
            onClick = {
                PlaybackStore.setResumeAskMode(this, 0)
                popSettingsBackStack()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (currentMode == 1) R.drawable.ic_lux_check else R.drawable.ic_lux_resume,
            title = getString(R.string.resume_mode_always_resume),
            subtitle = if (currentMode == 1) getString(R.string.active_mode_badge) else null,
            isAccentHighlighted = (currentMode == 1),
            onClick = {
                PlaybackStore.setResumeAskMode(this, 1)
                popSettingsBackStack()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (currentMode == 2) R.drawable.ic_lux_check else R.drawable.ic_lux_rotate,
            title = getString(R.string.resume_mode_always_restart),
            subtitle = if (currentMode == 2) getString(R.string.active_mode_badge) else null,
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


internal fun MainActivity.applySortAndLayoutChangeToActiveScreen() {
    updateRecyclerLayoutManager()
    if (currentMainTab == 2) {
        if (iptvNavLevel == 3 && currentIptvPagedList.isNotEmpty()) {
            val sortMode = PlaybackStore.getSortOrderMode(this)
            val streamsOnly = currentIptvPagedList.filterIsInstance<IptvStreamItem>()
            if (streamsOnly.size == currentIptvPagedList.size) {
                val sorted = when (sortMode) {
                    1 -> streamsOnly.reversed()
                    2 -> streamsOnly.sortedBy { it.title.lowercase(Locale.getDefault()) }
                    3 -> streamsOnly.sortedByDescending { it.title.lowercase(Locale.getDefault()) }
                    else -> streamsOnly
                }
                currentIptvPagedList.clear()
                currentIptvPagedList.addAll(sorted)
                adapter.submitIptvList(ArrayList(currentIptvPagedList), resetAnimation = false)
                return
            }
        }
        renderIptvCurrentLevel()
    } else {
        applyFolderVisibilityAndRefreshUI()
    }
}

internal fun MainActivity.showSortAndLayoutDialog() {
    showCalmSortAndViewSheet()
}

internal fun MainActivity.sortVideosList(videos: List<VideoItem>, sortMode: Int): List<VideoItem> {
    return when (sortMode) {
        0 -> videos.sortedByDescending { it.dateAddedSec }
        1 -> videos.sortedBy { it.dateAddedSec }
        2 -> videos.sortedBy { it.title.lowercase(Locale.getDefault()) }
        3 -> videos.sortedByDescending { it.title.lowercase(Locale.getDefault()) }
        4 -> videos.sortedByDescending { it.size }
        5 -> videos.sortedBy { it.size }
        6 -> videos.sortedByDescending { it.duration }
        7 -> videos.sortedBy { it.duration }
        else -> videos.sortedByDescending { it.dateAddedSec }
    }
}

internal fun MainActivity.sortFoldersList(folders: List<VideoFolder>, sortMode: Int): List<VideoFolder> {
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
internal fun MainActivity.showProSettingsDialog(clearStack: Boolean = false) {
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
        getString(R.string.theme_teal_aqua),
        getString(R.string.theme_luxury_gold)
    )
    val currentThemeName = themeNames[PlaybackStore.getThemeIndex(this).coerceIn(0, themeNames.size - 1)]
    val langTag = PlaybackStore.getAppLanguageTag(this)
    val langSub = getAppLanguageDisplayName(langTag)


    val decoderShort = when (PlaybackStore.getDecoderModeIndex(this)) {
        PlaybackStore.DECODER_MODE_AUTO -> getString(R.string.decoder_auto_short)
        1 -> "HW"
        2 -> "SW"
        else -> "HW+"
    }
    val pipBadge = if (PlaybackStore.isAutoPipEnabled(this)) "Auto-PiP On" else "Auto-PiP Off"
    val subLangs = PlaybackStore.getPreferredSubtitleLanguages(this).joinToString(" → ") { it.uppercase() }
    val subSize = PlaybackStore.getSubSizeSp(this).toInt()

    val items = listOf(
        // Group 1: Player, Audio & Subtitles
        PremiumMenuItem(
            iconEmoji = "🎬",
            title = getString(R.string.settings_hub_playback_engine_title),
            subtitle = "Decoder: $decoderShort • $pipBadge",
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_sec_media_engine),
            sectionHeaderIconResId = R.drawable.ic_lux_play_engine,
            iconResId = R.drawable.ic_lux_play_engine,
            statusChip = decoderShort,
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                showSettingsPlaybackEnginePage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🔊",
            title = getString(R.string.settings_hub_audio_title),
            subtitle = getAudioModeDisplayName(PlaybackStore.getAudioPresetMode(this)),
            isAccentHighlighted = true,
            iconResId = R.drawable.ic_lux_audio,
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                showSettingsAudioEqualizerPage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "💬",
            title = getString(R.string.settings_hub_subtitles_only_title),
            subtitle = "$subLangs • ${subSize}sp",
            iconResId = R.drawable.ic_lux_subtitles,
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                showSettingsSubtitlesPlaybackPage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🚗",
            iconResId = R.drawable.ic_lux_android_auto,
            title = getString(R.string.settings_android_auto_title),
            subtitle = if (PlaybackStore.isAndroidAutoEnabled(this)) getString(R.string.aa_master_on_sub) else getString(R.string.aa_master_off_sub),
            isAccentHighlighted = PlaybackStore.isAndroidAutoEnabled(this),
            statusChip = if (PlaybackStore.isAndroidAutoEnabled(this)) getString(R.string.enabled_label) else getString(R.string.disabled_label),
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                showAndroidAutoSettingsPage()
            }
        ),
        // Group 2: Appearance & Language (Sort & Layout is unified in the top 3-line menu)
        PremiumMenuItem(
            iconEmoji = "🎨",
            title = getString(R.string.settings_hub_appearance_title),
            subtitle = "$currentThemeName • $langSub",
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_sec_interface_theme),
            sectionHeaderIconResId = R.drawable.ic_lux_palette,
            iconResId = R.drawable.ic_lux_palette,
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                showSettingsAppearanceDisplayPage()
            }
        ),
        // Group 3: Privacy Vault & Folders
        PremiumMenuItem(
            iconEmoji = "🛡️",
            title = getString(R.string.settings_hub_privacy_title),
            subtitle = privacySub,
            isAccentHighlighted = hasPin,
            sectionHeader = getString(R.string.settings_sec_privacy_folders),
            sectionHeaderIconResId = R.drawable.ic_lux_shield,
            iconResId = R.drawable.ic_lux_shield,
            statusChip = if (hasPin) "Active" else null,
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                showSettingsPrivacyFoldersPage()
            }
        ),
        // Group 4: Updates & About
    ) + (if (BuildConfig.ENABLE_IN_APP_UPDATER) listOf(
        PremiumMenuItem(
            iconEmoji = "🚀",
            title = getString(R.string.settings_hub_updates_title),
            subtitle = "GitHub Release Auto-Updater",
            sectionHeader = getString(R.string.settings_sec_system_updates),
            sectionHeaderIconResId = R.drawable.ic_lux_update,
            iconResId = R.drawable.ic_lux_update,
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                openGitHubUpdaterFlow()
            }
        )
    ) else emptyList()) + listOf(
        PremiumMenuItem(
            iconEmoji = "🧹",
            iconResId = R.drawable.ic_lux_clear_cache,
            title = getString(R.string.clear_cache_title),
            subtitle = getString(R.string.clear_cache_row_sub),
            statusChip = if (AppCacheManager.lastKnownTotalBytes >= 0L) {
                HiddenMediaAndPlaylistHelper.formatBytesReadable(AppCacheManager.lastKnownTotalBytes)
            } else {
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { AppCacheManager.computeBreakdown(applicationContext) }
                null
            },
            onClick = {
                showClearCacheDialog {
                    showProSettingsDialog(clearStack = false)
                }
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🔋",
            iconResId = R.drawable.ic_lux_battery,
            title = getString(R.string.battery_saver_title),
            subtitle = if (BatteryOptimizationManager.isBatterySaverActive(this)) getString(R.string.battery_saver_status_on) else getString(R.string.battery_saver_status_off),
            isAccentHighlighted = BatteryOptimizationManager.isBatterySaverActive(this),
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(clearStack = false) }
                showBatterySaverSettingsDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "ℹ️",
            iconResId = R.drawable.ic_lux_info,
            title = getString(R.string.settings_hub_about_title),
            subtitle = getString(R.string.settings_hub_about_sub),
            sectionHeader = if (!BuildConfig.ENABLE_IN_APP_UPDATER) getString(R.string.settings_sec_system_updates) else null,
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                showAboutAppPage()
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "⚙️",
        title = getString(R.string.settings_header),
        subtitle = getString(R.string.app_name),
        items = items
    )
}

internal fun MainActivity.showSettingsPlaybackEnginePage(animateEntrance: Boolean = true) {
    val decoderSub = getDecoderModeDisplayName(PlaybackStore.getDecoderModeIndex(this))
    val orientIdx = PlaybackStore.getDefaultOrientationIndex(this)
    val orientSub = when (orientIdx) {
        PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO -> getString(R.string.orientation_auto_video)
        PlaybackStore.ORIENTATION_MODE_FOLLOW_DEVICE -> getString(R.string.orientation_follow_device)
        PlaybackStore.ORIENTATION_MODE_PORTRAIT -> getString(R.string.orientation_portrait)
        else -> getString(R.string.orientation_landscape)
    }
    val autoPipOn = PlaybackStore.isAutoPipEnabled(this)
    val autoPipSub = if (autoPipOn) getString(R.string.setting_auto_pip_on) else getString(R.string.setting_auto_pip_off)
    val resumeSub = when (PlaybackStore.getResumeAskMode(this)) {
        1 -> getString(R.string.resume_mode_always_resume)
        2 -> getString(R.string.resume_mode_always_restart)
        else -> getString(R.string.resume_mode_ask_always)
    }

    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_bolt,
            title = getString(R.string.decoder_dialog_title),
            subtitle = decoderSub,
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_subsec_video_engine),
            sectionHeaderIconResId = R.drawable.ic_lux_bolt,
            onClick = {
                pushSettingsBackStack { showSettingsPlaybackEnginePage() }
                showDefaultDecoderSettingsDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_rotate,
            title = getString(R.string.setting_default_orientation),
            subtitle = orientSub,
            onClick = {
                pushSettingsBackStack { showSettingsPlaybackEnginePage() }
                showDefaultOrientationDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_pip,
            title = getString(R.string.setting_auto_pip_title),
            subtitle = autoPipSub,
            isAccentHighlighted = autoPipOn,
            sectionHeader = getString(R.string.settings_subsec_pip_and_resume),
            sectionHeaderIconResId = R.drawable.ic_lux_pip,
            toggleChecked = autoPipOn,
            onToggleChanged = { checked ->
                PlaybackStore.setAutoPipEnabled(this, checked)
                showSettingsPlaybackEnginePage(animateEntrance = false)
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_resume,
            title = getString(R.string.setting_resume_behavior_title),
            subtitle = resumeSub,
            onClick = {
                pushSettingsBackStack { showSettingsPlaybackEnginePage() }
                showResumeBehaviorSettingsDialog()
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "🎬",
        title = getString(R.string.settings_hub_playback_engine_title),
        subtitle = "$decoderSub • $orientSub",
        items = items,
        animateEntrance = animateEntrance
    )
}

internal fun MainActivity.showSettingsPrivacyFoldersPage(animateEntrance: Boolean = true) {
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

    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_lock,
            title = getString(R.string.setting_privacy_section),
            subtitle = privacySub,
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_subsec_vault_security),
            sectionHeaderIconResId = R.drawable.ic_lux_lock,
            onClick = {
                pushSettingsBackStack { showSettingsPrivacyFoldersPage() }
                openPrivacyControlSection()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_folder,
            title = getString(R.string.setting_folder_visibility),
            subtitle = filterSub,
            sectionHeader = getString(R.string.settings_subsec_folder_indexing),
            sectionHeaderIconResId = R.drawable.ic_lux_folder,
            onClick = {
                pushSettingsBackStack { showSettingsPrivacyFoldersPage() }
                showFolderVisibilityModeDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_rotate,
            title = getString(R.string.setting_rescan_media_now),
            subtitle = getString(R.string.setting_rescan_media_sub),
            onClick = {
                checkPermissionAndLoad()
                Toast.makeText(this, getString(R.string.setting_rescan_done_toast), Toast.LENGTH_SHORT).show()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_shield,
            title = getString(R.string.setting_allow_untrusted_ssl_title),
            subtitle = if (PlaybackStore.isAllowUntrustedSsl(this)) getString(R.string.setting_allow_untrusted_ssl_on) else getString(R.string.setting_allow_untrusted_ssl_off),
            isAccentHighlighted = PlaybackStore.isAllowUntrustedSsl(this),
            toggleChecked = PlaybackStore.isAllowUntrustedSsl(this),
            onToggleChanged = { checked ->
                PlaybackStore.setAllowUntrustedSsl(this, checked)
                XtreamCodesClient.allowUntrustedSslFallback = checked
                showSettingsPrivacyFoldersPage(animateEntrance = false)
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "🛡️",
        title = getString(R.string.settings_hub_privacy_title),
        subtitle = privacySub,
        items = items,
        animateEntrance = animateEntrance
    )
}

internal fun MainActivity.showSettingsAppearanceDisplayPage() {
    val langTag = PlaybackStore.getAppLanguageTag(this)
    val langSub = getAppLanguageDisplayName(langTag)
    val themeNames = arrayOf(
        getString(R.string.theme_cyber_blue),
        getString(R.string.theme_amoled_gold),
        getString(R.string.theme_emerald),
        getString(R.string.theme_crimson),
        getString(R.string.theme_violet),
        getString(R.string.theme_material_you),
        getString(R.string.theme_sunset_coral),
        getString(R.string.theme_teal_aqua),
        getString(R.string.theme_luxury_gold),
        getString(R.string.theme_luxury_dark)
    )
    val currentThemeName = themeNames[PlaybackStore.getThemeIndex(this).coerceIn(0, themeNames.size - 1)]
    val modeLabel = when (PlaybackStore.getAppearanceMode(this)) {
        PlaybackStore.APPEARANCE_LIGHT -> getString(R.string.appearance_mode_light)
        PlaybackStore.APPEARANCE_SYSTEM -> getString(R.string.appearance_mode_follow_system)
        else -> getString(R.string.appearance_mode_dark)
    }
    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_palette,
            title = getString(R.string.setting_app_theme),
            subtitle = "$modeLabel • $currentThemeName",
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_subsec_theme_colors),
            sectionHeaderIconResId = R.drawable.ic_lux_palette,
            onClick = {
                pushSettingsBackStack { showSettingsAppearanceDisplayPage() }
                showThemeSelectorDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_app_icon,
            title = getString(R.string.setting_app_icon_title),
            subtitle = getString(
                AppIconManager.allOptions.firstOrNull { it.id == PlaybackStore.getAppIconStyle(this) }?.titleResId
                    ?: R.string.icon_style_default
            ),
            isAccentHighlighted = true,
            onClick = {
                pushSettingsBackStack { showSettingsAppearanceDisplayPage() }
                showAppIconSelectorDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_globe,
            title = getString(R.string.setting_app_language),
            subtitle = langSub,
            sectionHeader = getString(R.string.settings_subsec_app_locale),
            sectionHeaderIconResId = R.drawable.ic_lux_globe,
            onClick = {
                pushSettingsBackStack { showSettingsAppearanceDisplayPage() }
                showAppLanguageDialog()
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "🎨",
        title = getString(R.string.settings_hub_appearance_title),
        subtitle = "$modeLabel • $langSub",
        items = items
    )
}

internal fun MainActivity.getDecoderModeDisplayName(modeIdx: Int): String {
    return when (modeIdx) {
        PlaybackStore.DECODER_MODE_AUTO -> getString(R.string.decoder_auto_short) + " • " + getString(R.string.decoder_auto_title)
        1 -> "HW • " + getString(R.string.decoder_hw_title)
        2 -> "SW • " + getString(R.string.decoder_sw_title)
        else -> "HW+ • " + getString(R.string.decoder_hw_plus_title)
    }
}

internal fun MainActivity.showDefaultDecoderSettingsDialog() {
    val currentIdx = PlaybackStore.getDecoderModeIndex(this)
    val options = listOf(
        Triple(PlaybackStore.DECODER_MODE_AUTO, R.drawable.ic_lux_play_engine, Pair(getString(R.string.decoder_auto_title), getString(R.string.decoder_auto_sub))),
        Triple(0, R.drawable.ic_lux_bolt, Pair(getString(R.string.decoder_hw_plus_title), getString(R.string.decoder_hw_plus_sub))),
        Triple(1, R.drawable.ic_lux_settings, Pair(getString(R.string.decoder_hw_title), getString(R.string.decoder_hw_sub))),
        Triple(2, R.drawable.ic_lux_diagnostics, Pair(getString(R.string.decoder_sw_title), getString(R.string.decoder_sw_sub)))
    )
    val items = options.map { (idx, iconRes, texts) ->
        val isSel = (idx == currentIdx)
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (isSel) R.drawable.ic_lux_check else iconRes,
            title = texts.first,
            subtitle = if (isSel) "${getString(R.string.active_mode_badge)} • ${texts.second}" else texts.second,
            isAccentHighlighted = isSel,
            onClick = {
                PlaybackStore.setDecoderModeIndex(this, idx)
                Toast.makeText(this, getString(R.string.decoder_switched_toast, texts.first), Toast.LENGTH_SHORT).show()
                popSettingsBackStack()
            }
        )
    }
    showPremiumMenuDialog("⚡", getString(R.string.decoder_dialog_title), getDecoderModeDisplayName(currentIdx), items)
}

internal fun MainActivity.showSettingsSubtitlesPlaybackPage() {
    val subLangs = PlaybackStore.getPreferredSubtitleLanguages(this).joinToString(" → ") { it.uppercase() }
    val subSize = PlaybackStore.getSubSizeSp(this).toInt()
    val subBgOpacity = PlaybackStore.getSubBgOpacityPct(this)

    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_globe,
            title = getString(R.string.setting_subtitle_languages),
            subtitle = subLangs,
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_subsec_sub_languages),
            sectionHeaderIconResId = R.drawable.ic_lux_globe,
            onClick = {
                pushSettingsBackStack { showSettingsSubtitlesPlaybackPage() }
                showSubtitleLanguagePriorityDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_text_style,
            title = getString(R.string.setting_subtitle_style),
            subtitle = "${subSize}sp • ${getString(R.string.style_bg_opacity_label, subBgOpacity)}",
            sectionHeader = getString(R.string.settings_subsec_sub_appearance),
            sectionHeaderIconResId = R.drawable.ic_lux_text_style,
            onClick = {
                pushSettingsBackStack { showSettingsSubtitlesPlaybackPage() }
                showSubtitleStyleDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_translate,
            title = getString(R.string.setting_sub_auto_translate_title),
            subtitle = if (PlaybackStore.isAutoTranslateEnabled(this)) {
                "${getString(R.string.enabled_label)} • ${PlaybackStore.getAutoTranslateTargetLang(this).uppercase()} • ${getAutoTranslateProviderLabel(PlaybackStore.getAutoTranslateProvider(this))}"
            } else {
                getString(R.string.disabled_label)
            },
            isAccentHighlighted = PlaybackStore.isAutoTranslateEnabled(this),
            onClick = {
                pushSettingsBackStack { showSettingsSubtitlesPlaybackPage() }
                showSubtitleAutoTranslateSettingsPage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_lock,
            title = getString(R.string.setting_lock_sub_position),
            subtitle = if (PlaybackStore.isSubtitlePositionLocked(this)) getString(R.string.sub_pos_locked_on) else getString(R.string.sub_pos_locked_off),
            isAccentHighlighted = PlaybackStore.isSubtitlePositionLocked(this),
            onClick = {
                val nextVal = !PlaybackStore.isSubtitlePositionLocked(this)
                PlaybackStore.setSubtitlePositionLocked(this, nextVal)
                Toast.makeText(this, if (nextVal) getString(R.string.sub_pos_locked_on) else getString(R.string.sub_pos_locked_off), Toast.LENGTH_SHORT).show()
                showSettingsSubtitlesPlaybackPage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_trash,
            title = getString(R.string.setting_confirm_sub_delete_title),
            subtitle = getString(R.string.setting_confirm_sub_delete_sub),
            toggleChecked = PlaybackStore.isConfirmBeforeDeletingSubtitles(this),
            onToggleChanged = { checked ->
                PlaybackStore.setConfirmBeforeDeletingSubtitles(this, checked)
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_target,
            title = getString(R.string.setting_reset_sub_position),
            subtitle = getString(R.string.sub_position_reset_toast),
            onClick = {
                PlaybackStore.resetSubPosition(this)
                Toast.makeText(this, getString(R.string.sub_position_reset_toast), Toast.LENGTH_SHORT).show()
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "💬",
        title = getString(R.string.settings_hub_subtitles_only_title),
        subtitle = "$subLangs • ${subSize}sp",
        items = items
    )
}

internal fun MainActivity.getAudioModeDisplayName(mode: Int): String {
    return when (mode) {
        1 -> getString(R.string.audio_mode_cinematic)
        2 -> getString(R.string.audio_mode_hall)
        3 -> getString(R.string.audio_mode_voice)
        4 -> getString(R.string.audio_mode_bass)
        5 -> getString(R.string.audio_mode_stadium)
        6 -> getString(R.string.audio_mode_night)
        7 -> getString(R.string.audio_mode_custom)
        else -> getString(R.string.audio_mode_standard)
    }
}

internal fun MainActivity.showSettingsAudioEqualizerPage() {
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
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_audio,
            title = getString(R.string.audio_preset_section_title),
            subtitle = modeLabel,
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.audio_preset_section_title),
            sectionHeaderIconResId = R.drawable.ic_lux_audio,
            onClick = {
                pushSettingsBackStack { showSettingsAudioEqualizerPage() }
                showAudioSoundModesPage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_equalizer,
            title = getString(R.string.audio_eq_customizer_title),
            subtitle = eqSummary,
            isAccentHighlighted = (currentMode == 7),
            sectionHeader = getString(R.string.audio_eq_customizer_title),
            sectionHeaderIconResId = R.drawable.ic_lux_equalizer,
            onClick = {
                pushSettingsBackStack { showSettingsAudioEqualizerPage() }
                showGraphicEqualizerDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_rotate,
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

internal fun MainActivity.showAudioSoundModesPage() {
    val activeMode = PlaybackStore.getAudioPresetMode(this)
    val modes = listOf(
        Triple(0, "🎵", Pair(getString(R.string.audio_mode_standard), getString(R.string.audio_mode_standard_sub))),
        Triple(1, "🎬", Pair(getString(R.string.audio_mode_cinematic), getString(R.string.audio_mode_cinematic_sub))),
        Triple(2, "🏛️", Pair(getString(R.string.audio_mode_hall), getString(R.string.audio_mode_hall_sub))),
        Triple(3, "🗣️", Pair(getString(R.string.audio_mode_voice), getString(R.string.audio_mode_voice_sub))),
        Triple(4, "🎸", Pair(getString(R.string.audio_mode_bass), getString(R.string.audio_mode_bass_sub))),
        Triple(5, "🏟️", Pair(getString(R.string.audio_mode_stadium), getString(R.string.audio_mode_stadium_sub))),
        Triple(6, "🌙", Pair(getString(R.string.audio_mode_night), getString(R.string.audio_mode_night_sub)))
    )

    val items = modes.map { (modeIdx, _, texts) ->
        val isSelected = (modeIdx == activeMode)
        val subText = if (isSelected) "${getString(R.string.active_mode_badge)} • ${texts.second}" else texts.second
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (isSelected) R.drawable.ic_lux_check else R.drawable.ic_lux_audio,
            title = texts.first,
            subtitle = subText,
            isAccentHighlighted = isSelected,
            onClick = {
                PlaybackStore.setAudioPresetMode(this, modeIdx)
                Toast.makeText(
                    this,
                    getString(R.string.audio_mode_applied_toast, texts.first),
                    Toast.LENGTH_SHORT
                ).show()
                popSettingsBackStack()
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

internal fun MainActivity.showGraphicEqualizerDialog() {
    val hostActivity = this
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
            PlaybackStore.setAudioEqBandsDb(hostActivity, currentBands, currentPreAmp)
            Toast.makeText(
                hostActivity,
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

internal fun MainActivity.showAboutAppPage() {
    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val bg = PlaybackStore.getBackgroundColor(this)
    val isLight = PlaybackStore.isLightThemeActive(this)
    val primaryText = PlaybackStore.getPrimaryTextColor(this)
    val secondaryText = PlaybackStore.getSecondaryTextColor(this)
    val hairlineColor = Color.argb(if (isLight) 68 else 42, Color.red(secondaryText), Color.green(secondaryText), Color.blue(secondaryText))

    val outerScroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        setBackgroundColor(bg)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val (winWidthDp, _) = FoldableWindowMetricsHelper.computeCurrentWindowDp(this)
    val effectiveWidthDp = FoldableAdaptiveMath.computeEffectiveMenuWidthDp(winWidthDp)
    val innerPadDp = FoldableAdaptiveMath.computeMenuInnerPaddingDp(winWidthDp)
    val cardWidthPx = if (effectiveWidthDp > 0) dp(effectiveWidthDp) else ViewGroup.LayoutParams.MATCH_PARENT

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "vexo_responsive_menu_card"
        setPadding(dp(innerPadDp), dp(22), dp(innerPadDp), dp(36))
        setBackgroundColor(bg)
        layoutParams = FrameLayout.LayoutParams(cardWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
    }
    outerScroll.addView(container)

    val animatedRows = mutableListOf<View>()

    // Top handle pill
    container.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(Color.argb(165, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    })

    // Header Row (Unified Icon Badge + Title + Back/Close Circle)
    val headerRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), dp(16))
    }
    headerRow.addView(FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(if (isLight) 20 else 28, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(dp(1), Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        addView(ImageView(this@showAboutAppPage).apply {
            setImageResource(R.drawable.ic_lux_info)
            imageTintList = ColorStateList.valueOf(accent)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        })
    })
    headerRow.addView(TextView(this).apply {
        text = getString(R.string.settings_hub_about_title)
        setTextColor(primaryText)
        textSize = 20f
        letterSpacing = 0.03f
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    val hasParentInStackTop = settingsBackStack.isNotEmpty()
    headerRow.addView(ImageView(this).apply {
        setImageResource(if (hasParentInStackTop) R.drawable.ic_arrow_back_modern else R.drawable.ic_close_modern)
        imageTintList = ColorStateList.valueOf(primaryText)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        contentDescription = getString(if (hasParentInStackTop) R.string.back else R.string.close)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#1AFFFFFF"))
            setStroke(dp(1), hairlineColor)
        }
        setOnClickListener { navigateBackInSettingsOrClose() }
    })
    container.addView(headerRow)

    // Hero Brand Card (App Icon + V Player + Subtitle + Engine Capabilities Pill)
    val heroCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(24), dp(20), dp(22))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(18)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(surface)
            setStroke(dp(1), Color.argb(75, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    val iconFrame = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(14) }
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(24, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(dp(1), Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
            }
        }
    }
    iconFrame.addView(ImageView(this).apply {
        setImageResource(R.mipmap.ic_launcher)
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    })
    heroCard.addView(iconFrame)

    heroCard.addView(TextView(this).apply {
        text = getString(R.string.app_name)
        setTextColor(primaryText)
        textSize = 22f
        letterSpacing = 0.03f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
    })

    heroCard.addView(TextView(this).apply {
        text = getString(R.string.app_subtitle)
        setTextColor(secondaryText)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(14))
    })

    // Subtle capability pill (no version number outside Changelog)
    heroCard.addView(TextView(this).apply {
        text = "Media3  •  LibVLC  •  4K HDR  •  IPTV"
        setTextColor(accent)
        textSize = 11.5f
        letterSpacing = 0.05f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(Color.argb(if (isLight) 20 else 28, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(dp(1), Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    })
    container.addView(heroCard)
    animatedRows.add(heroCard)

    fun addAboutCard(
        iconResId: Int,
        title: String,
        subtitle: String,
        highlighted: Boolean = false,
        interactive: Boolean = false,
        onClick: (() -> Unit)? = null
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(14), dp(14), dp(14))
            isClickable = interactive
            isFocusable = interactive
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(surface)
                setStroke(
                    dp(1),
                    if (highlighted) Color.argb(85, Color.red(accent), Color.green(accent), Color.blue(accent)) else hairlineColor
                )
            }
        }
        val iconBadge = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(if (isLight) 20 else 28, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            addView(ImageView(this@showAboutAppPage).apply {
                setImageResource(iconResId)
                imageTintList = ColorStateList.valueOf(accent)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(primaryText)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(if (highlighted && !interactive) accent else secondaryText)
            textSize = 12.5f
            setTypeface(typeface, if (highlighted && !interactive) Typeface.BOLD else Typeface.NORMAL)
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(iconBadge)
        row.addView(textCol)
        if (interactive) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_lux_chevron)
                imageTintList = ColorStateList.valueOf(secondaryText)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) }
            })
            row.setOnClickListener { onClick?.invoke() }
        }
        container.addView(row)
        animatedRows.add(row)
    }

    addAboutCard(
        iconResId = R.drawable.ic_lux_changelog,
        title = getString(R.string.about_changelog_title),
        subtitle = getString(R.string.about_changelog_sub),
        highlighted = true,
        interactive = true
    ) {
        pushSettingsBackStack { showAboutAppPage() }
        showChangelogPage()
    }

    addAboutCard(
        iconResId = R.drawable.ic_lux_shield,
        title = getString(R.string.about_privacy_policy_title),
        subtitle = getString(R.string.about_privacy_policy_sub),
        highlighted = true,
        interactive = true
    ) {
        pushSettingsBackStack { showAboutAppPage() }
        showPrivacyPolicyFullPage()
    }

    addAboutCard(
        iconResId = R.drawable.ic_lux_scale,
        title = getString(R.string.about_disclaimer_title),
        subtitle = getString(R.string.about_disclaimer_sub),
        highlighted = false,
        interactive = false
    )

    addAboutCard(
        iconResId = R.drawable.ic_lux_developer,
        title = getString(R.string.about_developer_label),
        subtitle = "Ahmed M Moumen",
        highlighted = true,
        interactive = false
    )

    presentInFullScreenHost(outerScroll, container, animatedRows, bg, animateEntrance = true)
}

internal fun MainActivity.showChangelogPage() {
    val verName = BuildConfig.VERSION_NAME
    // Display ONLY the latest 4 versions (newest first), each with full notes, while keeping full source history intact
    val allConfiguredReleases = listOf(
        ChangelogReleaseEntry(
            version = verName,
            date = "2026-11-28",
            iconEmoji = "🔋",
            title = getString(R.string.changelog_v90_38_title, verName),
            description = getString(R.string.changelog_v90_38_desc),
            isLatest = true
        ),
        ChangelogReleaseEntry(
            version = "90.37",
            date = "2026-11-28",
            iconEmoji = "⚡",
            title = getString(R.string.changelog_v90_37_title, "90.37"),
            description = getString(R.string.changelog_v90_37_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.36",
            date = "2026-11-28",
            iconEmoji = "✨",
            title = getString(R.string.changelog_v90_36_title, "90.36"),
            description = getString(R.string.changelog_v90_36_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.35",
            date = "2026-11-28",
            iconEmoji = "🚀",
            title = getString(R.string.changelog_v90_35_title, "90.35"),
            description = getString(R.string.changelog_v90_35_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.34",
            date = "2026-11-28",
            iconEmoji = "✨",
            title = getString(R.string.changelog_v90_34_title, "90.34"),
            description = getString(R.string.changelog_v90_34_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.33",
            date = "2026-11-28",
            iconEmoji = "⚡",
            title = getString(R.string.changelog_v90_33_title, "90.33"),
            description = getString(R.string.changelog_v90_33_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.32",
            date = "2026-11-28",
            iconEmoji = "🎲",
            title = getString(R.string.changelog_v90_32_title, "90.32"),
            description = getString(R.string.changelog_v90_32_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.31",
            date = "2026-11-28",
            iconEmoji = "🔊",
            title = getString(R.string.changelog_v90_31_title, "90.31"),
            description = getString(R.string.changelog_v90_31_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.30",
            date = "2026-11-28",
            iconEmoji = "🚀",
            title = getString(R.string.changelog_v90_30_title, "90.30"),
            description = getString(R.string.changelog_v90_30_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.29",
            date = "2026-11-27",
            iconEmoji = "💎",
            title = getString(R.string.changelog_v90_29_title, "90.29"),
            description = getString(R.string.changelog_v90_29_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.28",
            date = "2026-11-25",
            iconEmoji = "🎛️",
            title = getString(R.string.changelog_v90_28_title, "90.28"),
            description = getString(R.string.changelog_v90_28_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.27",
            date = "2026-11-25",
            iconEmoji = "🚗",
            title = getString(R.string.changelog_v90_27_title, "90.27"),
            description = getString(R.string.changelog_v90_27_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.26",
            date = "2026-11-24",
            iconEmoji = "👁️",
            title = getString(R.string.changelog_v90_26_title, "90.26"),
            description = getString(R.string.changelog_v90_26_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.25",
            date = "2026-11-24",
            iconEmoji = "📐",
            title = getString(R.string.changelog_v90_25_title, "90.25"),
            description = getString(R.string.changelog_v90_25_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.24",
            date = "2026-11-24",
            iconEmoji = "⚡",
            title = getString(R.string.changelog_v90_24_title, "90.24"),
            description = getString(R.string.changelog_v90_24_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.23",
            date = "2026-11-24",
            iconEmoji = "📤",
            title = getString(R.string.changelog_v90_23_title, "90.23"),
            description = getString(R.string.changelog_v90_23_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.22",
            date = "2026-11-23",
            iconEmoji = "⏭️",
            title = getString(R.string.changelog_v90_22_title, "90.22"),
            description = getString(R.string.changelog_v90_22_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.21",
            date = "2026-11-23",
            iconEmoji = "🎯",
            title = getString(R.string.changelog_v90_21_title, "90.21"),
            description = getString(R.string.changelog_v90_21_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.20",
            date = "2026-11-22",
            iconEmoji = "✨",
            title = getString(R.string.changelog_v90_20_title, "90.20"),
            description = getString(R.string.changelog_v90_20_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.19",
            date = "2026-11-22",
            iconEmoji = "📺",
            title = getString(R.string.changelog_v90_18_title, "90.19"),
            description = getString(R.string.changelog_v90_18_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.17",
            date = "2026-11-22",
            iconEmoji = "📱",
            title = getString(R.string.changelog_v90_17_title, "90.17"),
            description = getString(R.string.changelog_v90_17_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.16",
            date = "2026-11-22",
            iconEmoji = "✅",
            title = getString(R.string.changelog_v90_16_title, "90.16"),
            description = getString(R.string.changelog_v90_16_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.15",
            date = "2026-11-22",
            iconEmoji = "🔋",
            title = getString(R.string.changelog_v90_15_title, "90.15"),
            description = getString(R.string.changelog_v90_15_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.14",
            date = "2026-11-21",
            iconEmoji = "⚡",
            title = getString(R.string.changelog_v90_14_title, "90.14"),
            description = getString(R.string.changelog_v90_14_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.13",
            date = "2026-11-21",
            iconEmoji = "🔧",
            title = getString(R.string.changelog_v90_13_title, "90.13"),
            description = getString(R.string.changelog_v90_13_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.12",
            date = "2026-11-21",
            iconEmoji = "✨",
            title = getString(R.string.changelog_v90_12_title, "90.12"),
            description = getString(R.string.changelog_v90_12_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.11",
            date = "2026-11-20",
            iconEmoji = "🛠️",
            title = getString(R.string.changelog_v90_11_title, "90.11"),
            description = getString(R.string.changelog_v90_11_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.10",
            date = "2026-11-18",
            iconEmoji = "📦",
            title = getString(R.string.changelog_v90_10_title),
            description = getString(R.string.changelog_v90_10_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.09",
            date = "2026-11-18",
            iconEmoji = "🛡️",
            title = getString(R.string.changelog_v90_09_title),
            description = getString(R.string.changelog_v90_09_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.08",
            date = "2026-11-18",
            iconEmoji = "🚀",
            title = getString(R.string.changelog_v90_08_title),
            description = getString(R.string.changelog_v90_08_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.07",
            date = "2026-11-18",
            iconEmoji = "✅",
            title = getString(R.string.changelog_v90_07_title),
            description = getString(R.string.changelog_v90_07_desc),
            isLatest = false
        ),
        ChangelogReleaseEntry(
            version = "90.06",
            date = "2026-11-18",
            iconEmoji = "🔍",
            title = getString(R.string.changelog_v90_06_title),
            description = getString(R.string.changelog_v90_06_desc),
            isLatest = false
        )
    )
    val latestFourReleases = ChangelogVersionManager.retainLatestVersions(allConfiguredReleases, 4)
    val items = latestFourReleases.map { release ->
        PremiumMenuItem(
            iconEmoji = release.iconEmoji,
            title = release.title,
            subtitle = "${release.date}\n${release.description}",
            isAccentHighlighted = release.isLatest,
            isInteractive = false,
            iconResId = R.drawable.ic_lux_info,
            statusChip = "v${release.version}"
        )
    }

    showPremiumMenuDialog(
        headerIcon = "📋",
        title = getString(R.string.about_changelog_title),
        subtitle = "${getString(R.string.app_name)} • Latest 4 Releases",
        items = items,
        showHeaderVersionBadge = false
    )
}

internal fun MainActivity.getAppLanguageDisplayName(tag: String): String {
    return when (tag) {
        "ar" -> getString(R.string.lang_arabic)
        "en" -> getString(R.string.lang_english)
        "fr" -> getString(R.string.lang_french)
        "es" -> getString(R.string.lang_spanish)
        "de" -> getString(R.string.lang_german)
        "tr" -> getString(R.string.lang_turkish)
        "ru" -> getString(R.string.lang_russian)
        "pt" -> getString(R.string.lang_portuguese)
        "it" -> getString(R.string.lang_italian)
        "hi" -> getString(R.string.lang_hindi)
        "ur" -> getString(R.string.lang_urdu)
        "id" -> getString(R.string.lang_indonesian)
        "zh" -> getString(R.string.lang_chinese)
        "ja" -> getString(R.string.lang_japanese)
        "ko" -> getString(R.string.lang_korean)
        else -> getString(R.string.lang_system_default)
    }
}

internal fun MainActivity.showAppLanguageDialog() {
    val currentTag = PlaybackStore.getAppLanguageTag(this)
    val devLocaleName = Locale.getDefault().displayLanguage
    val supportedAppLangs = listOf(
        Triple("", "📱", Pair(getString(R.string.lang_system_default), devLocaleName)),
        Triple("ar", "🇸🇦", Pair(getString(R.string.lang_arabic), "العربية")),
        Triple("en", "🇺🇸", Pair(getString(R.string.lang_english), "English")),
        Triple("fr", "🇫🇷", Pair(getString(R.string.lang_french), "Français")),
        Triple("es", "🇪🇸", Pair(getString(R.string.lang_spanish), "Español")),
        Triple("de", "🇩🇪", Pair(getString(R.string.lang_german), "Deutsch")),
        Triple("tr", "🇹🇷", Pair(getString(R.string.lang_turkish), "Türkçe")),
        Triple("ru", "🇷🇺", Pair(getString(R.string.lang_russian), "Русский")),
        Triple("pt", "🇧🇷", Pair(getString(R.string.lang_portuguese), "Português")),
        Triple("it", "🇮🇹", Pair(getString(R.string.lang_italian), "Italiano")),
        Triple("hi", "🇮🇳", Pair(getString(R.string.lang_hindi), "हिन्दी")),
        Triple("ur", "🇵🇰", Pair(getString(R.string.lang_urdu), "اردو")),
        Triple("id", "🇮🇩", Pair(getString(R.string.lang_indonesian), "Bahasa Indonesia")),
        Triple("zh", "🇨🇳", Pair(getString(R.string.lang_chinese), "中文")),
        Triple("ja", "🇯🇵", Pair(getString(R.string.lang_japanese), "日本語")),
        Triple("ko", "🇰🇷", Pair(getString(R.string.lang_korean), "한국어"))
    )

    val items = supportedAppLangs.map { (tag, _, names) ->
        val isSel = (currentTag == tag)
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (isSel) R.drawable.ic_lux_check else R.drawable.ic_lux_globe,
            title = names.first,
            subtitle = if (isSel) "${getString(R.string.active_mode_badge)} • ${names.second}" else names.second,
            isAccentHighlighted = isSel,
            onClick = {
                PlaybackStore.setAppLanguageTag(this, tag)
                settingsBackStack.clear()
                closeActiveFullScreenDialogSmoothly {
                    recreate()
                }
            }
        )
    }
    showPremiumMenuDialog("🌐", getString(R.string.setting_app_language), getAppLanguageDisplayName(currentTag), items)
}

internal fun MainActivity.showSubtitleLanguagePriorityDialog() {
    val langCodes = arrayOf("ar", "en", "fr", "es", "de", "tr", "ru", "pt", "it", "hi", "ur", "id", "zh", "ja", "ko")
    val langLabels = arrayOf(
        getString(R.string.sub_lang_ar),
        getString(R.string.sub_lang_en),
        getString(R.string.sub_lang_fr),
        getString(R.string.sub_lang_es),
        getString(R.string.lang_german),
        getString(R.string.sub_lang_tr),
        getString(R.string.lang_russian),
        getString(R.string.lang_portuguese),
        getString(R.string.lang_italian),
        getString(R.string.sub_lang_hi),
        getString(R.string.sub_lang_ur),
        getString(R.string.lang_indonesian),
        getString(R.string.lang_chinese),
        getString(R.string.lang_japanese),
        getString(R.string.lang_korean)
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

internal fun MainActivity.showThemeSelectorDialog() {
    val hostActivity = this
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
        ThemeOption("🩵", getString(R.string.theme_teal_aqua), "#14B8A6", "#0A0A0F"),
        ThemeOption("👑", getString(R.string.theme_luxury_gold), "#D4AF37", "#0A0A0F"),
        ThemeOption("🖤", getString(R.string.theme_luxury_dark), "#D4AF37", "#07070B")
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
    val (winWidthDp, _) = FoldableWindowMetricsHelper.computeCurrentWindowDp(this)
    val effectiveThemeW = FoldableAdaptiveMath.computeEffectiveMenuWidthDp(winWidthDp)
    val themeInnerPad = FoldableAdaptiveMath.computeMenuInnerPaddingDp(winWidthDp)
    val cardContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "vexo_responsive_menu_card"
        setPadding(dp(themeInnerPad), dp(22), dp(themeInnerPad), dp(28))
        setBackgroundColor(surface)
        layoutParams = FrameLayout.LayoutParams(
            if (effectiveThemeW > 0) dp(effectiveThemeW) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL
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
            setColor(PlaybackStore.getAccentColor(hostActivity))
        }
    }
    cardContainer.addView(topPill)

    val tvTitle = TextView(this).apply {
        text = "🎨  " + getString(R.string.theme_dialog_title)
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), 0, dp(4), dp(10))
    }
    cardContainer.addView(tvTitle)

    // Section 1: Appearance Mode Selector (Dark / Light / System Default)
    val tvModeSec = TextView(this).apply {
        text = getString(R.string.appearance_mode_section)
        setTextColor(PlaybackStore.getAccentColor(hostActivity))
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(4), dp(4), dp(8))
    }
    cardContainer.addView(tvModeSec)

    val modeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            bottomMargin = dp(14)
        }
    }
    val modeOptions = listOf(
        Triple(PlaybackStore.APPEARANCE_SYSTEM, R.drawable.ic_mode_system, getString(R.string.appearance_seg_system)),
        Triple(PlaybackStore.APPEARANCE_LIGHT, R.drawable.ic_mode_light, getString(R.string.appearance_seg_light)),
        Triple(PlaybackStore.APPEARANCE_DARK, R.drawable.ic_mode_dark, getString(R.string.appearance_seg_dark))
    )
    val modeSegments = mutableListOf<LinearLayout>()
    val modeSegmentIcons = mutableListOf<ImageView>()
    val modeSegmentLabels = mutableListOf<TextView>()
    val modeDividers = mutableListOf<View>()

    // Section 2: Accent Color Presets
    val tvAccentSec = TextView(this).apply {
        text = getString(R.string.accent_colors_section)
        setTextColor(PlaybackStore.getAccentColor(hostActivity))
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

        tvTitle.setTextColor(primaryText)
        tvModeSec.setTextColor(activeAccent)
        tvAccentSec.setTextColor(activeAccent)
        (topPill.background as? GradientDrawable)?.setColor(activeAccent)

        val segStrokeColor = if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#2AFFFFFF")
        modeRow.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(activeBg)
            setStroke(dp(1), segStrokeColor)
        }
        modeDividers.forEach { div -> div.setBackgroundColor(segStrokeColor) }
        val secondaryTint = PlaybackStore.getSecondaryTextColor(this)
        modeSegments.forEachIndexed { mIdx, seg ->
            val isModeSelected = (modeOptions[mIdx].first == activeMode)
            seg.isSelected = isModeSelected
            seg.setBackgroundColor(
                if (isModeSelected) Color.argb(48, Color.red(activeAccent), Color.green(activeAccent), Color.blue(activeAccent))
                else Color.TRANSPARENT
            )
            val fgColor = if (isModeSelected) activeAccent else secondaryTint
            modeSegmentIcons[mIdx].imageTintList = ColorStateList.valueOf(fgColor)
            modeSegmentLabels[mIdx].setTextColor(if (isModeSelected) primaryText else secondaryTint)
            modeSegmentLabels[mIdx].setTypeface(null, if (isModeSelected) Typeface.BOLD else Typeface.NORMAL)
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

    modeOptions.forEachIndexed { mIdx, (modeCode, iconRes, label) ->
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                selectedMode = modeCode
                PlaybackStore.setAppearanceMode(hostActivity, modeCode)
                refreshVisualSelection(selectedIdx, selectedMode)
                applyUserTheme(animated = true)
            }
        }
        val ivIcon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginEnd = dp(6)
            }
        }
        val tvLbl = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 10, 13, 1, TypedValue.COMPLEX_UNIT_SP
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        seg.addView(ivIcon)
        seg.addView(tvLbl)
        modeSegments.add(seg)
        modeSegmentIcons.add(ivIcon)
        modeSegmentLabels.add(tvLbl)
        modeRow.addView(seg)

        if (mIdx < modeOptions.lastIndex) {
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            modeDividers.add(div)
            modeRow.addView(div)
        }
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
            setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        val isMatYouUnsupported = (idx == PlaybackStore.THEME_MATERIAL_YOU && !PlaybackStore.isMaterialYouSupported())
        val isDarkOnly = PlaybackStore.isDarkOnlyTheme(idx)
        val hexHint = when {
            isMatYouUnsupported -> "${opt.accentHex}  •  ${getString(R.string.material_you_needs_android_12)}"
            isDarkOnly -> "${opt.accentHex}  •  ${getString(R.string.theme_dark_only_hint)}"
            else -> opt.accentHex
        }
        val tvHex = TextView(this).apply {
            text = hexHint
            setTextColor(if (isMatYouUnsupported) PlaybackStore.getSecondaryTextColor(hostActivity) else optAccent)
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        }
        if (isMatYouUnsupported) {
            row.alpha = 0.48f
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
            if (isMatYouUnsupported) {
                Toast.makeText(hostActivity, getString(R.string.material_you_needs_android_12), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedIdx = idx
            PlaybackStore.setThemeIndex(hostActivity, idx)
            if (idx == PlaybackStore.THEME_MATERIAL_YOU && PlaybackStore.isMaterialYouSupported()) {
                com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(hostActivity)
            }
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
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
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

internal fun MainActivity.showSubtitleStyleDialog() {
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

internal fun MainActivity.showDefaultOrientationDialog() {
    val current = PlaybackStore.getDefaultOrientationIndex(this)
    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (current == PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO) R.drawable.ic_lux_check else R.drawable.ic_lux_play_engine,
            title = getString(R.string.orientation_auto_video),
            subtitle = getString(R.string.orientation_auto_video_sub),
            isAccentHighlighted = (current == PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO),
            onClick = { PlaybackStore.setDefaultOrientationIndex(this, PlaybackStore.ORIENTATION_MODE_AUTO_VIDEO); popSettingsBackStack() }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (current == PlaybackStore.ORIENTATION_MODE_FOLLOW_DEVICE) R.drawable.ic_lux_check else R.drawable.ic_lux_rotate,
            title = getString(R.string.orientation_follow_device),
            subtitle = getString(R.string.orientation_follow_device_sub),
            isAccentHighlighted = (current == PlaybackStore.ORIENTATION_MODE_FOLLOW_DEVICE),
            onClick = { PlaybackStore.setDefaultOrientationIndex(this, PlaybackStore.ORIENTATION_MODE_FOLLOW_DEVICE); popSettingsBackStack() }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (current == PlaybackStore.ORIENTATION_MODE_LANDSCAPE) R.drawable.ic_lux_check else R.drawable.ic_lux_rotate,
            title = getString(R.string.orientation_landscape),
            subtitle = null,
            isAccentHighlighted = (current == PlaybackStore.ORIENTATION_MODE_LANDSCAPE),
            onClick = { PlaybackStore.setDefaultOrientationIndex(this, PlaybackStore.ORIENTATION_MODE_LANDSCAPE); popSettingsBackStack() }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (current == PlaybackStore.ORIENTATION_MODE_PORTRAIT) R.drawable.ic_lux_check else R.drawable.ic_lux_rotate,
            title = getString(R.string.orientation_portrait),
            subtitle = null,
            isAccentHighlighted = (current == PlaybackStore.ORIENTATION_MODE_PORTRAIT),
            onClick = { PlaybackStore.setDefaultOrientationIndex(this, PlaybackStore.ORIENTATION_MODE_PORTRAIT); popSettingsBackStack() }
        )
    )
    showPremiumMenuDialog("🔄", getString(R.string.setting_default_orientation), null, items, showHeaderVersionBadge = false)
}


internal fun MainActivity.showPrivacyPolicyFullPage() {
    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "🛡️",
            title = getString(R.string.privacy_policy_sec1_title),
            subtitle = getString(R.string.privacy_policy_sec1_desc),
            isAccentHighlighted = true,
            isInteractive = false
        ),
        PremiumMenuItem(
            iconEmoji = "🎬",
            title = getString(R.string.privacy_policy_sec2_title),
            subtitle = getString(R.string.privacy_policy_sec2_desc),
            isInteractive = false
        ),
        PremiumMenuItem(
            iconEmoji = "🔐",
            title = getString(R.string.privacy_policy_sec3_title),
            subtitle = getString(R.string.privacy_policy_sec3_desc),
            isInteractive = false
        ),
        PremiumMenuItem(
            iconEmoji = "⚖️",
            title = getString(R.string.about_disclaimer_title),
            subtitle = getString(R.string.about_disclaimer_sub),
            isInteractive = false
        )
    )
    showPremiumMenuDialog(
        headerIcon = "🔒",
        title = getString(R.string.about_privacy_policy_title),
        subtitle = "${getString(R.string.app_name)} — ${getString(R.string.app_subtitle)}",
        items = items
    )
}


internal fun androidx.appcompat.app.AppCompatActivity.showVexoAudioEffectsStudioDialog() {
    val ctx = this
    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    val snap = VexoAudioEffectsManager.loadSnapshot(ctx)
    var fxEnabled = snap.enabled
    var selectedPresetName = snap.presetName
    var preAmpDb = snap.preAmpDb
    var bassStrength = snap.bassBoostStrength
    var virtStrength = snap.virtualizerStrength
    var boostPct = snap.volumeBoostPercent
    val freqs = VexoAudioEffectsManager.getDeviceBandFrequenciesHz()
    val bands = VexoAudioEffectsManager.resampleBands(snap.customBandsDb.toFloatArray(), freqs.size).toMutableList()

    val accent = PlaybackStore.getAccentColor(ctx)
    val primaryText = PlaybackStore.getPrimaryTextColor(ctx)
    val secondaryText = PlaybackStore.getSecondaryTextColor(ctx)

    val scroll = ScrollView(ctx)
    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    scroll.addView(container)

    val cbEnable = CheckBox(ctx).apply {
        text = getString(R.string.audio_fx_enable_switch)
        isChecked = fxEnabled
        setTextColor(primaryText)
        buttonTintList = ColorStateList.valueOf(accent)
        setOnCheckedChangeListener { _, checked -> fxEnabled = checked }
    }
    container.addView(cbEnable)

    val tvPresetLabel = TextView(ctx).apply {
        text = "Preset: $selectedPresetName"
        setTextColor(accent)
        textSize = 13.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }
    container.addView(tvPresetLabel)

    val btnPickPreset = android.widget.Button(ctx).apply {
        text = getString(R.string.audio_fx_choose_preset)
        setOnClickListener {
            val allPresets = VexoAudioEffectsManager.getAllPresets(ctx)
            val names = allPresets.map { if (it.isCustom) "★ ${it.name}" else it.name }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.audio_fx_choose_preset))
                .setItems(names) { _, which ->
                    val chosen = allPresets[which]
                    selectedPresetName = chosen.name
                    preAmpDb = chosen.preAmpDb
                    val mapped = VexoAudioEffectsManager.resampleBands(chosen.bandsDb, freqs.size)
                    for (i in mapped.indices) bands[i] = mapped[i]
                    tvPresetLabel.text = "Preset: $selectedPresetName"
                }
                .show()
        }
    }
    container.addView(btnPickPreset)

    val tvBoostLabel = TextView(ctx).apply {
        text = "${getString(R.string.audio_fx_volume_boost)}: $boostPct%"
        setTextColor(primaryText)
        setPadding(0, dp(10), 0, dp(2))
    }
    val tvBoostWarn = TextView(ctx).apply {
        text = getString(R.string.audio_fx_boost_warning)
        setTextColor(Color.parseColor("#F59E0B"))
        textSize = 11.5f
        setPadding(0, 0, 0, dp(4))
    }
    val seekBoost = SeekBar(ctx).apply {
        max = 100
        progress = (boostPct - 100).coerceIn(0, 100)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                boostPct = 100 + p
                tvBoostLabel.text = "${getString(R.string.audio_fx_volume_boost)}: $boostPct%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    container.addView(tvBoostLabel)
    container.addView(tvBoostWarn)
    container.addView(seekBoost)

    if (VexoAudioEffectsManager.supportsBassBoost) {
        val tvBass = TextView(ctx).apply {
            text = "${getString(R.string.audio_fx_bass_boost)}: ${bassStrength / 10}%"
            setTextColor(primaryText)
            setPadding(0, dp(10), 0, dp(2))
        }
        val seekBass = SeekBar(ctx).apply {
            max = 1000
            progress = bassStrength
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    bassStrength = p
                    tvBass.text = "${getString(R.string.audio_fx_bass_boost)}: ${bassStrength / 10}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        container.addView(tvBass)
        container.addView(seekBass)
    }

    if (VexoAudioEffectsManager.supportsVirtualizer) {
        val tvVirt = TextView(ctx).apply {
            text = "${getString(R.string.audio_fx_virtualizer)}: ${virtStrength / 10}%"
            setTextColor(primaryText)
            setPadding(0, dp(10), 0, dp(2))
        }
        val seekVirt = SeekBar(ctx).apply {
            max = 1000
            progress = virtStrength
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    virtStrength = p
                    tvVirt.text = "${getString(R.string.audio_fx_virtualizer)}: ${virtStrength / 10}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        container.addView(tvVirt)
        container.addView(seekVirt)
    }

    freqs.forEachIndexed { idx, hz ->
        val label = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
        val tvBand = TextView(ctx).apply {
            text = "$label: ${String.format(Locale.US, "%+.1f dB", bands[idx])}"
            setTextColor(secondaryText)
            setPadding(0, dp(8), 0, dp(2))
        }
        val seekBand = SeekBar(ctx).apply {
            max = 300
            progress = ((bands[idx] + 15f) * 10f).toInt().coerceIn(0, 300)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    bands[idx] = (p / 10f) - 15f
                    tvBand.text = "$label: ${String.format(Locale.US, "%+.1f dB", bands[idx])}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        container.addView(tvBand)
        container.addView(seekBand)
    }

    AlertDialog.Builder(ctx)
        .setTitle(getString(R.string.audio_fx_studio_title))
        .setView(scroll)
        .setPositiveButton(getString(R.string.save)) { _, _ ->
            VexoAudioEffectsManager.saveSnapshot(
                ctx,
                AudioEffectsSnapshot(
                    enabled = fxEnabled,
                    presetName = selectedPresetName,
                    preAmpDb = preAmpDb,
                    customBandsDb = bands.toList(),
                    bassBoostStrength = bassStrength,
                    virtualizerStrength = virtStrength,
                    volumeBoostPercent = boostPct
                )
            )
            VexoAudioEffectsManager.applySavedEffects(ctx)
            ((ctx as? MainActivity)?.popSettingsBackStack() == true)
        }
        .setNeutralButton(getString(R.string.audio_fx_save_preset)) { _, _ ->
            val input = EditText(ctx).apply { hint = "My Custom Preset" }
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.audio_fx_save_preset))
                .setView(input)
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        VexoAudioEffectsManager.saveCustomPreset(ctx, name, preAmpDb, bands)
                        Toast.makeText(ctx, "Saved: $name", Toast.LENGTH_SHORT).show()
                    }
                    ((ctx as? MainActivity)?.popSettingsBackStack() == true)
                }
                .setNegativeButton(getString(R.string.cancel)) { _, _ -> ((ctx as? MainActivity)?.popSettingsBackStack() == true) }
                .show()
        }
        .setNegativeButton(getString(R.string.cancel)) { _, _ -> ((ctx as? MainActivity)?.popSettingsBackStack() == true) }
        .setOnCancelListener { ((ctx as? MainActivity)?.popSettingsBackStack() == true) }
        .show()
}


internal fun MainActivity.showBatterySaverSettingsDialog(animateEntrance: Boolean = true) {
    val manualOn = BatteryOptimizationManager.isManualBatterySaverEnabled(this)
    val autoOn = BatteryOptimizationManager.isAutoBatterySaverEnabled(this)
    val threshold = BatteryOptimizationManager.getAutoThresholdPercent(this)
    val showIndicator = BatteryOptimizationManager.isPlayerBatteryIndicatorEnabled(this)
    val lowAlerts = BatteryOptimizationManager.isLowBatteryAlertsEnabled(this)

    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "🔋",
            iconResId = R.drawable.ic_lux_battery,
            title = getString(R.string.battery_saver_manual_title),
            subtitle = if (manualOn) getString(R.string.battery_saver_manual_on_sub) else getString(R.string.battery_saver_manual_off_sub),
            isAccentHighlighted = manualOn,
            toggleChecked = manualOn,
            onToggleChanged = { checked ->
                BatteryOptimizationManager.setManualBatterySaverEnabled(this, checked)
                showBatterySaverSettingsDialog(animateEntrance = false)
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🤖",
            iconResId = R.drawable.ic_lux_play_engine,
            title = getString(R.string.battery_saver_auto_title),
            subtitle = if (autoOn) getString(R.string.battery_saver_auto_on_sub, threshold) else getString(R.string.battery_saver_auto_off_sub),
            isAccentHighlighted = autoOn,
            toggleChecked = autoOn,
            onToggleChanged = { checked ->
                BatteryOptimizationManager.setAutoBatterySaverEnabled(this, checked)
                showBatterySaverSettingsDialog(animateEntrance = false)
            }
        ),
        PremiumMenuItem(
            iconEmoji = "📉",
            iconResId = R.drawable.ic_lux_diagnostics,
            title = getString(R.string.battery_saver_threshold_title),
            subtitle = "$threshold%",
            onClick = {
                val options = arrayOf("10%", "15%", "20%", "25%")
                val values = intArrayOf(10, 15, 20, 25)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.battery_saver_threshold_title))
                    .setItems(options) { _, which ->
                        BatteryOptimizationManager.setAutoThresholdPercent(this, values[which])
                        showBatterySaverSettingsDialog()
                    }
                    .show()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "📊",
            iconResId = R.drawable.ic_lux_battery,
            title = getString(R.string.battery_indicator_title),
            subtitle = if (showIndicator) getString(R.string.enabled_label) else getString(R.string.disabled_label),
            isAccentHighlighted = showIndicator,
            toggleChecked = showIndicator,
            onToggleChanged = { checked ->
                BatteryOptimizationManager.setPlayerBatteryIndicatorEnabled(this, checked)
                showBatterySaverSettingsDialog(animateEntrance = false)
            }
        ),
        PremiumMenuItem(
            iconEmoji = "⚠️",
            iconResId = R.drawable.ic_lux_info,
            title = getString(R.string.low_battery_alerts_title),
            subtitle = if (lowAlerts) getString(R.string.low_battery_alerts_sub) else getString(R.string.disabled_label),
            isAccentHighlighted = lowAlerts,
            toggleChecked = lowAlerts,
            onToggleChanged = { checked ->
                BatteryOptimizationManager.setLowBatteryAlertsEnabled(this, checked)
                showBatterySaverSettingsDialog(animateEntrance = false)
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "🔋",
        title = getString(R.string.battery_saver_title),
        subtitle = if (BatteryOptimizationManager.isBatterySaverActive(this)) getString(R.string.battery_saver_status_on) else getString(R.string.battery_saver_status_off),
        items = items,
        showHeaderVersionBadge = false,
        animateEntrance = animateEntrance
    )
}


internal fun MainActivity.getAutoTranslateProviderLabel(provider: String): String {
    return when (provider) {
        "google_cloud" -> getString(R.string.sub_tr_provider_google_cloud)
        "deepl" -> getString(R.string.sub_tr_provider_deepl)
        "openai_llm" -> getString(R.string.sub_tr_provider_openai)
        else -> getString(R.string.sub_tr_provider_free)
    }
}

internal fun MainActivity.showSubtitleAutoTranslateSettingsPage() {
    val enabled = PlaybackStore.isAutoTranslateEnabled(this)
    val askFirst = PlaybackStore.isAskBeforeTranslating(this)
    val targetLang = PlaybackStore.getAutoTranslateTargetLang(this)
    val provider = PlaybackStore.getAutoTranslateProvider(this)
    val apiKey = PlaybackStore.getAutoTranslateApiKey(this)
    val arStyle = PlaybackStore.getArabicTranslationStyle(this)
    val isArUi = Locale.getDefault().language.equals("ar", ignoreCase = true) ||
        PlaybackStore.getAppLanguageTag(this).startsWith("ar", ignoreCase = true)
    val targetDisplay = SubtitleAutoTranslator.getLanguageDisplayName(targetLang, isArUi)
    val keyPreview = if (apiKey.isBlank()) {
        getString(R.string.sub_tr_api_key_not_set)
    } else {
        "••••••••" + apiKey.takeLast(4)
    }

    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_translate,
            title = getString(R.string.setting_sub_auto_translate_toggle),
            subtitle = if (enabled) getString(R.string.enabled_label) else getString(R.string.disabled_label),
            isAccentHighlighted = enabled,
            onClick = {
                PlaybackStore.setAutoTranslateEnabled(this, !enabled)
                showSubtitleAutoTranslateSettingsPage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_info,
            title = getString(R.string.setting_sub_auto_translate_ask),
            subtitle = if (askFirst) getString(R.string.enabled_label) else getString(R.string.disabled_label),
            isAccentHighlighted = askFirst,
            onClick = {
                PlaybackStore.setAskBeforeTranslating(this, !askFirst)
                showSubtitleAutoTranslateSettingsPage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_globe,
            title = getString(R.string.setting_sub_auto_translate_target),
            subtitle = "$targetDisplay (${targetLang.uppercase()})",
            onClick = {
                pushSettingsBackStack { showSubtitleAutoTranslateSettingsPage() }
                showAutoTranslateTargetLanguageDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_settings,
            title = getString(R.string.setting_sub_auto_translate_provider),
            subtitle = getAutoTranslateProviderLabel(provider),
            onClick = {
                pushSettingsBackStack { showSubtitleAutoTranslateSettingsPage() }
                showAutoTranslateProviderDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_key,
            title = getString(R.string.setting_sub_auto_translate_api_key),
            subtitle = keyPreview,
            onClick = { showAutoTranslateApiKeyDialog() }
        ),
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = R.drawable.ic_lux_text_style,
            title = getString(R.string.setting_sub_arabic_style),
            subtitle = if (arStyle == "simple") getString(R.string.sub_tr_ar_style_simple) else getString(R.string.sub_tr_ar_style_msa),
            onClick = {
                val nextStyle = if (arStyle == "simple") "msa" else "simple"
                PlaybackStore.setArabicTranslationStyle(this, nextStyle)
                showSubtitleAutoTranslateSettingsPage()
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "✨",
        title = getString(R.string.setting_sub_auto_translate_title),
        subtitle = "$targetDisplay • ${getAutoTranslateProviderLabel(provider)}",
        items = items
    )
}

internal fun MainActivity.showAutoTranslateTargetLanguageDialog() {
    val current = PlaybackStore.getAutoTranslateTargetLang(this)
    val isArUi = Locale.getDefault().language.equals("ar", ignoreCase = true) ||
        PlaybackStore.getAppLanguageTag(this).startsWith("ar", ignoreCase = true)
    val langs = listOf("ar", "en", "fr", "es", "de", "tr", "ru", "pt", "it", "hi", "ur", "id", "zh", "ja", "ko")
    val items = langs.map { code ->
        val name = SubtitleAutoTranslator.getLanguageDisplayName(code, isArUi)
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (code == current) R.drawable.ic_lux_check else R.drawable.ic_lux_globe,
            title = name,
            subtitle = code.uppercase(),
            isAccentHighlighted = (code == current),
            onClick = {
                PlaybackStore.setAutoTranslateTargetLang(this, code)
                popSettingsBackStack()
            }
        )
    }
    showPremiumMenuDialog(
        headerIcon = "🌐",
        title = getString(R.string.setting_sub_auto_translate_target),
        subtitle = current.uppercase(),
        items = items
    )
}

internal fun MainActivity.showAutoTranslateProviderDialog() {
    val current = PlaybackStore.getAutoTranslateProvider(this)
    val providers = listOf(
        Triple("free_google", getString(R.string.sub_tr_provider_free), getString(R.string.sub_tr_provider_free_sub)),
        Triple("google_cloud", getString(R.string.sub_tr_provider_google_cloud), getString(R.string.sub_tr_provider_key_sub)),
        Triple("deepl", getString(R.string.sub_tr_provider_deepl), getString(R.string.sub_tr_provider_key_sub)),
        Triple("openai_llm", getString(R.string.sub_tr_provider_openai), getString(R.string.sub_tr_provider_openai_sub))
    )
    val items = providers.map { (id, title, sub) ->
        PremiumMenuItem(
            iconEmoji = "",
            iconResId = if (id == current) R.drawable.ic_lux_check else R.drawable.ic_lux_settings,
            title = title,
            subtitle = sub,
            isAccentHighlighted = (id == current),
            onClick = {
                PlaybackStore.setAutoTranslateProvider(this, id)
                popSettingsBackStack()
            }
        )
    }
    showPremiumMenuDialog(
        headerIcon = "⚙️",
        title = getString(R.string.setting_sub_auto_translate_provider),
        subtitle = getAutoTranslateProviderLabel(current),
        items = items
    )
}

internal fun MainActivity.showAutoTranslateApiKeyDialog() {
    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val bg = PlaybackStore.getBackgroundColor(this)
    val primaryText = PlaybackStore.getPrimaryTextColor(this)
    val secondaryText = PlaybackStore.getSecondaryTextColor(this)
    val density = resources.displayMetrics.density
    fun dpLocal(v: Int): Int = (v * density).toInt()

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpLocal(22), dpLocal(20), dpLocal(22), dpLocal(18))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpLocal(24).toFloat()
            setColor(surface)
            setStroke(dpLocal(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    val tvTitle = TextView(this).apply {
        text = "🔑  ${getString(R.string.setting_sub_auto_translate_api_key)}"
        setTextColor(primaryText)
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dpLocal(10))
    }

    val input = android.widget.EditText(this).apply {
        setText(PlaybackStore.getAutoTranslateApiKey(this@showAutoTranslateApiKeyDialog))
        hint = getString(R.string.sub_tr_api_key_hint)
        setTextColor(primaryText)
        setHintTextColor(secondaryText)
        setSingleLine(true)
        setPadding(dpLocal(14), dpLocal(12), dpLocal(14), dpLocal(12))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpLocal(14).toFloat()
            setColor(bg)
            setStroke(dpLocal(1), Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dpLocal(14), 0, 0)
    }

    val dialog = AlertDialog.Builder(this).setView(container).create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    val btnClear = TextView(this).apply {
        text = getString(R.string.cancel)
        setTextColor(secondaryText)
        textSize = 14f
        setPadding(dpLocal(14), dpLocal(10), dpLocal(14), dpLocal(10))
        setOnClickListener { dialog.dismiss() }
    }

    val btnSave = TextView(this).apply {
        text = getString(R.string.ok)
        setTextColor(Color.parseColor("#090D16"))
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dpLocal(20), dpLocal(10), dpLocal(20), dpLocal(10))
        background = GradientDrawable().apply {
            cornerRadius = dpLocal(12).toFloat()
            setColor(accent)
        }
        setOnClickListener {
            PlaybackStore.setAutoTranslateApiKey(this@showAutoTranslateApiKeyDialog, input.text.toString().trim())
            dialog.dismiss()
            showSubtitleAutoTranslateSettingsPage()
        }
    }

    btnRow.addView(btnClear)
    btnRow.addView(btnSave)
    container.addView(tvTitle)
    container.addView(input)
    container.addView(btnRow)
    dialog.show()
}


internal fun MainActivity.showAppIconSelectorDialog() {
    val hostActivity = this
    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val bg = PlaybackStore.getBackgroundColor(this)
    val primaryText = PlaybackStore.getPrimaryTextColor(this)
    val secondaryText = PlaybackStore.getSecondaryTextColor(this)
    val density = resources.displayMetrics.density
    fun dpLocal(v: Int): Int = (v * density).toInt()

    val selectedId = PlaybackStore.getAppIconStyle(this)

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpLocal(20), dpLocal(20), dpLocal(20), dpLocal(20))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpLocal(26).toFloat()
            setColor(surface)
            setStroke(dpLocal(1), Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    val tvTitle = TextView(this).apply {
        text = "🎯  ${getString(R.string.setting_app_icon_title)}"
        setTextColor(primaryText)
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dpLocal(4))
    }

    val tvSub = TextView(this).apply {
        text = getString(R.string.setting_app_icon_sub)
        setTextColor(secondaryText)
        textSize = 13f
        setPadding(0, 0, 0, dpLocal(14))
    }

    container.addView(tvTitle)
    container.addView(tvSub)

    val dialog = AlertDialog.Builder(this).setView(container).create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    val options = AppIconManager.allOptions
    for (rowStart in options.indices step 3) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpLocal(10) }
        }
        for (col in 0 until 3) {
            val idx = rowStart + col
            if (idx >= options.size) break
            val opt = options[idx]
            val isSelected = (opt.id == selectedId)

            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                isClickable = true
                setPadding(dpLocal(8), dpLocal(12), dpLocal(8), dpLocal(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpLocal(18).toFloat()
                    setColor(if (isSelected) Color.argb(32, Color.red(accent), Color.green(accent), Color.blue(accent)) else bg)
                    setStroke(
                        dpLocal(if (isSelected) 2 else 1),
                        if (isSelected) accent else Color.argb(50, Color.red(primaryText), Color.green(primaryText), Color.blue(primaryText))
                    )
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (col > 0) marginStart = dpLocal(8)
                }
            }

            val iconFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpLocal(56), dpLocal(56)).apply {
                    bottomMargin = dpLocal(8)
                }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dpLocal(14).toFloat())
                    }
                }
            }

            val iv = ImageView(this).apply {
                setImageResource(opt.mipmapResId)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            iconFrame.addView(iv)

            val tvLabel = TextView(this).apply {
                text = (if (isSelected) "✓ " else "") + getString(opt.titleResId)
                setTextColor(if (isSelected) accent else primaryText)
                textSize = 12f
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 2
            }

            tile.addView(iconFrame)
            tile.addView(tvLabel)

            tile.setOnClickListener {
                AppIconManager.applyIconStyle(hostActivity, opt.id)
                Toast.makeText(
                    hostActivity,
                    getString(R.string.app_icon_applied_toast, getString(opt.titleResId)),
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
                popSettingsBackStack()
            }
            row.addView(tile)
        }
        container.addView(row)
    }

    val tvNote = TextView(this).apply {
        text = getString(R.string.app_icon_launcher_refresh_note)
        setTextColor(secondaryText)
        textSize = 11.5f
        gravity = Gravity.CENTER
        setPadding(dpLocal(4), dpLocal(4), dpLocal(4), dpLocal(10))
    }
    container.addView(tvNote)

    val btnClose = TextView(this).apply {
        text = getString(R.string.ok)
        setTextColor(accent)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.END
        setPadding(dpLocal(14), dpLocal(6), dpLocal(10), dpLocal(4))
        setOnClickListener {
            dialog.dismiss()
            popSettingsBackStack()
        }
    }
    container.addView(btnClose)
    dialog.setOnCancelListener { popSettingsBackStack() }
    dialog.show()
}
