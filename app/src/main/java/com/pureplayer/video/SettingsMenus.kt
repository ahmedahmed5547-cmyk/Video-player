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

internal fun MainActivity.showResumeBehaviorSettingsDialog() {
    val hostActivity = this
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


internal fun MainActivity.showSortAndLayoutDialog() {
    val hostActivity = this
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

internal fun MainActivity.sortVideosList(videos: List<VideoItem>, sortMode: Int): List<VideoItem> {
    val hostActivity = this
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

internal fun MainActivity.sortFoldersList(folders: List<VideoFolder>, sortMode: Int): List<VideoFolder> {
    val hostActivity = this
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
    val hostActivity = this
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
    val langSub = getAppLanguageDisplayName(langTag)
    val isGrid = PlaybackStore.isGridLayoutEnabled(this)
    val layoutLabel = if (isGrid) getString(R.string.layout_mode_grid) else getString(R.string.layout_mode_list)

    val decoderShort = when (PlaybackStore.getDecoderModeIndex(this)) {
        1 -> "HW"
        2 -> "SW"
        else -> "HW+"
    }
    val pipBadge = if (PlaybackStore.isAutoPipEnabled(this)) "Auto-PiP ✅" else "Auto-PiP 🚫"
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
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                showSettingsAudioEqualizerPage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "💬",
            title = getString(R.string.settings_hub_subtitles_only_title),
            subtitle = "$subLangs • ${subSize}sp",
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                showSettingsSubtitlesPlaybackPage()
            }
        ),
        // Group 2: Appearance, Language & Layout
        PremiumMenuItem(
            iconEmoji = "🎨",
            title = getString(R.string.settings_hub_appearance_title),
            subtitle = "$currentThemeName • $langSub • $layoutLabel",
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_sec_interface_theme),
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
            subtitle = "v${BuildConfig.VERSION_NAME} • GitHub Release Auto-Updater",
            sectionHeader = getString(R.string.settings_sec_system_updates),
            onClick = {
                pushSettingsBackStack { showProSettingsDialog(false) }
                openGitHubUpdaterFlow()
            }
        )
    ) else emptyList()) + listOf(
        PremiumMenuItem(
            iconEmoji = "ℹ️",
            title = getString(R.string.settings_hub_about_title),
            subtitle = getString(R.string.settings_hub_about_sub, BuildConfig.VERSION_NAME),
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
        subtitle = "Pure Video Player v${BuildConfig.VERSION_NAME}",
        items = items
    )
}

internal fun MainActivity.showSettingsPlaybackEnginePage() {
    val hostActivity = this
    val decoderSub = getDecoderModeDisplayName(PlaybackStore.getDecoderModeIndex(this))
    val orientIdx = PlaybackStore.getDefaultOrientationIndex(this)
    val orientSub = when (orientIdx) {
        0 -> getString(R.string.orientation_auto)
        2 -> getString(R.string.orientation_portrait)
        else -> getString(R.string.orientation_landscape)
    }
    val autoPipOn = PlaybackStore.isAutoPipEnabled(this)
    val autoPipSub = if (autoPipOn) "✅ " + getString(R.string.setting_auto_pip_on) else "🚫 " + getString(R.string.setting_auto_pip_off)
    val resumeSub = when (PlaybackStore.getResumeAskMode(this)) {
        1 -> getString(R.string.resume_mode_always_resume)
        2 -> getString(R.string.resume_mode_always_restart)
        else -> getString(R.string.resume_mode_ask_always)
    }

    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "⚡",
            title = getString(R.string.decoder_dialog_title),
            subtitle = decoderSub,
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_subsec_video_engine),
            onClick = {
                pushSettingsBackStack { showSettingsPlaybackEnginePage() }
                showDefaultDecoderSettingsDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🔄",
            title = getString(R.string.setting_default_orientation),
            subtitle = orientSub,
            onClick = {
                pushSettingsBackStack { showSettingsPlaybackEnginePage() }
                showDefaultOrientationDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🖼️",
            title = getString(R.string.setting_auto_pip_title),
            subtitle = autoPipSub,
            isAccentHighlighted = autoPipOn,
            sectionHeader = getString(R.string.settings_subsec_pip_and_resume),
            onClick = {
                val nextState = !autoPipOn
                PlaybackStore.setAutoPipEnabled(this, nextState)
                val msg = if (nextState) getString(R.string.setting_auto_pip_on) else getString(R.string.setting_auto_pip_off)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                showSettingsPlaybackEnginePage()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "⏯️",
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
        items = items
    )
}

internal fun MainActivity.showSettingsPrivacyFoldersPage() {
    val hostActivity = this
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
            iconEmoji = "🔒",
            title = getString(R.string.setting_privacy_section),
            subtitle = privacySub,
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_subsec_vault_security),
            onClick = {
                pushSettingsBackStack { showSettingsPrivacyFoldersPage() }
                openPrivacyControlSection()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "📂",
            title = getString(R.string.setting_folder_visibility),
            subtitle = filterSub,
            sectionHeader = getString(R.string.settings_subsec_folder_indexing),
            onClick = {
                pushSettingsBackStack { showSettingsPrivacyFoldersPage() }
                showFolderVisibilityModeDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "⚡",
            title = getString(R.string.setting_rescan_media_now),
            subtitle = getString(R.string.setting_rescan_media_sub),
            onClick = {
                checkPermissionAndLoad()
                Toast.makeText(this, getString(R.string.setting_rescan_done_toast), Toast.LENGTH_SHORT).show()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🔐",
            title = getString(R.string.setting_allow_untrusted_ssl_title),
            subtitle = if (PlaybackStore.isAllowUntrustedSsl(this)) getString(R.string.setting_allow_untrusted_ssl_on) else getString(R.string.setting_allow_untrusted_ssl_off),
            isAccentHighlighted = PlaybackStore.isAllowUntrustedSsl(this),
            onClick = {
                val nextVal = !PlaybackStore.isAllowUntrustedSsl(this)
                PlaybackStore.setAllowUntrustedSsl(this, nextVal)
                showSettingsPrivacyFoldersPage()
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "🛡️",
        title = getString(R.string.settings_hub_privacy_title),
        subtitle = privacySub,
        items = items
    )
}

internal fun MainActivity.showSettingsAppearanceDisplayPage() {
    val hostActivity = this
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
        getString(R.string.theme_teal_aqua)
    )
    val currentThemeName = themeNames[PlaybackStore.getThemeIndex(this).coerceIn(0, themeNames.size - 1)]
    val modeLabel = when (PlaybackStore.getAppearanceMode(this)) {
        PlaybackStore.APPEARANCE_LIGHT -> getString(R.string.appearance_mode_light)
        PlaybackStore.APPEARANCE_SYSTEM -> getString(R.string.appearance_mode_system)
        else -> getString(R.string.appearance_mode_dark)
    }
    val isGrid = PlaybackStore.isGridLayoutEnabled(this)
    val layoutSub = if (isGrid) {
        "${getString(R.string.layout_mode_grid)} (${calculateAdaptiveGridSpanCount()} Columns)"
    } else {
        getString(R.string.layout_mode_list)
    }

    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "🎨",
            title = getString(R.string.setting_app_theme),
            subtitle = "$modeLabel • $currentThemeName",
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_subsec_theme_colors),
            onClick = {
                pushSettingsBackStack { showSettingsAppearanceDisplayPage() }
                showThemeSelectorDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🌐",
            title = getString(R.string.setting_app_language),
            subtitle = langSub,
            sectionHeader = getString(R.string.settings_subsec_app_locale),
            onClick = {
                pushSettingsBackStack { showSettingsAppearanceDisplayPage() }
                showAppLanguageDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "📐",
            title = getString(R.string.sort_and_layout_title),
            subtitle = layoutSub,
            isAccentHighlighted = isGrid,
            sectionHeader = getString(R.string.settings_subsec_grid_and_sort),
            onClick = {
                pushSettingsBackStack { showSettingsAppearanceDisplayPage() }
                showSortAndLayoutDialog()
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
    val hostActivity = this
    return when (modeIdx) {
        1 -> "HW • " + getString(R.string.decoder_hw_title)
        2 -> "SW • " + getString(R.string.decoder_sw_title)
        else -> "HW+ • " + getString(R.string.decoder_hw_plus_title)
    }
}

internal fun MainActivity.showDefaultDecoderSettingsDialog() {
    val hostActivity = this
    val currentIdx = PlaybackStore.getDecoderModeIndex(this)
    val options = listOf(
        Triple(0, "⚡", Pair(getString(R.string.decoder_hw_plus_title), getString(R.string.decoder_hw_plus_sub))),
        Triple(1, "🔧", Pair(getString(R.string.decoder_hw_title), getString(R.string.decoder_hw_sub))),
        Triple(2, "💻", Pair(getString(R.string.decoder_sw_title), getString(R.string.decoder_sw_sub)))
    )
    val items = options.map { (idx, emoji, texts) ->
        val isSel = (idx == currentIdx)
        PremiumMenuItem(
            iconEmoji = emoji,
            title = texts.first,
            subtitle = if (isSel) "✅ ${getString(R.string.active_mode_badge)} • ${texts.second}" else texts.second,
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
    val hostActivity = this
    val subLangs = PlaybackStore.getPreferredSubtitleLanguages(this).joinToString(" → ") { it.uppercase() }
    val subSize = PlaybackStore.getSubSizeSp(this).toInt()
    val subBgOpacity = PlaybackStore.getSubBgOpacityPct(this)

    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "🌐",
            title = getString(R.string.setting_subtitle_languages),
            subtitle = subLangs,
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.settings_subsec_sub_languages),
            onClick = {
                pushSettingsBackStack { showSettingsSubtitlesPlaybackPage() }
                showSubtitleLanguagePriorityDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🔤",
            title = getString(R.string.setting_subtitle_style),
            subtitle = "${subSize}sp • ${getString(R.string.style_bg_opacity_label, subBgOpacity)}",
            sectionHeader = getString(R.string.settings_subsec_sub_appearance),
            onClick = {
                pushSettingsBackStack { showSettingsSubtitlesPlaybackPage() }
                showSubtitleStyleDialog()
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
    val hostActivity = this
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

internal fun MainActivity.showSettingsAudioEqualizerPage() {
    val hostActivity = this
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
            sectionHeader = getString(R.string.audio_preset_section_title),
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
            sectionHeader = getString(R.string.audio_eq_customizer_title),
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

internal fun MainActivity.showAudioSoundModesPage() {
    val hostActivity = this
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

    val items = modes.map { (modeIdx, emoji, texts) ->
        val isSelected = (modeIdx == activeMode)
        val subText = if (isSelected) "✅ ${getString(R.string.active_mode_badge)} • ${texts.second}" else texts.second
        PremiumMenuItem(
            iconEmoji = emoji,
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
    val hostActivity = this
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

internal fun MainActivity.showChangelogPage() {
    val hostActivity = this
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

internal fun MainActivity.getAppLanguageDisplayName(tag: String): String {
    val hostActivity = this
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
    val hostActivity = this
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

    val items = supportedAppLangs.map { (tag, flag, names) ->
        val isSel = (currentTag == tag)
        PremiumMenuItem(
            iconEmoji = flag,
            title = names.first,
            subtitle = if (isSel) "✅ ${getString(R.string.active_mode_badge)} • ${names.second}" else names.second,
            isAccentHighlighted = isSel,
            onClick = {
                PlaybackStore.setAppLanguageTag(this, tag)
                popSettingsBackStack()
            }
        )
    }
    showPremiumMenuDialog("🌐", getString(R.string.setting_app_language), getAppLanguageDisplayName(currentTag), items)
}

internal fun MainActivity.showSubtitleLanguagePriorityDialog() {
    val hostActivity = this
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
                PlaybackStore.setAppearanceMode(hostActivity, modeCode)
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
            setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
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
    val hostActivity = this
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
    val hostActivity = this
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
