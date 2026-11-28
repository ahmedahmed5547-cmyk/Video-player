package com.pureplayer.video

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Android Auto Settings screen inside V Player.
 * Uses theme-aware SwitchCompat rows identical to the top dropdown's "Hidden folders" switch,
 * with zero Enable/Disable dialogs, instant save/apply, RTL & TalkBack support, and visibly
 * disabled dependent rows whenever the master Android Auto switch is OFF.
 */
internal fun MainActivity.showAndroidAutoSettingsPage(animateEntrance: Boolean = true) {
    val config = PlaybackStore.getAndroidAutoConfig(this)
    val conn = AndroidAutoManager.connectionState.value

    val statusSummary = when {
        !config.enabled -> getString(R.string.aa_master_off_sub)
        conn.isCarConnected -> getString(R.string.aa_status_connected)
        else -> getString(R.string.aa_master_on_sub)
    }

    val items = listOf(
        // 1. Master Android Auto Switch (one tap toggles immediately; no extra dialog)
        PremiumMenuItem(
            iconEmoji = "🚗",
            iconResId = R.drawable.ic_lux_android_auto,
            title = getString(R.string.aa_master_label),
            subtitle = if (config.enabled) getString(R.string.aa_master_on_sub) else getString(R.string.aa_master_off_sub),
            isAccentHighlighted = config.enabled,
            isEnabled = true,
            toggleChecked = config.enabled,
            onToggleChanged = { checked ->
                PlaybackStore.setAndroidAutoEnabled(this, checked)
                VexoPlaybackService.notifyAndroidAutoConfigChanged(this)
                showAndroidAutoSettingsPage(animateEntrance = false)
            }
        ),
        // 2. Auto-Resume on Car Connect (disabled/greyed out when master switch is OFF)
        PremiumMenuItem(
            iconEmoji = "▶️",
            iconResId = R.drawable.ic_lux_play_engine,
            title = getString(R.string.aa_auto_resume_title),
            subtitle = if (config.autoResumeOnConnect) getString(R.string.aa_auto_resume_on_sub) else getString(R.string.aa_auto_resume_off_sub),
            isAccentHighlighted = config.enabled && config.autoResumeOnConnect,
            isEnabled = config.enabled,
            toggleChecked = config.autoResumeOnConnect,
            onToggleChanged = { checked ->
                PlaybackStore.setAndroidAutoAutoResume(this, checked)
                VexoPlaybackService.notifyAndroidAutoConfigChanged(this)
                showAndroidAutoSettingsPage(animateEntrance = false)
            }
        ),
        // 3. Audio-Only Mode on Car (disabled/greyed out when master switch is OFF)
        PremiumMenuItem(
            iconEmoji = "🎧",
            iconResId = R.drawable.ic_lux_audio,
            title = getString(R.string.aa_audio_only_title),
            subtitle = if (config.audioOnlyOnCar) getString(R.string.aa_audio_only_on_sub) else getString(R.string.aa_audio_only_off_sub),
            isAccentHighlighted = config.enabled && config.audioOnlyOnCar,
            isEnabled = config.enabled,
            toggleChecked = config.audioOnlyOnCar,
            onToggleChanged = { checked ->
                PlaybackStore.setAndroidAutoAudioOnlyOnCar(this, checked)
                VexoPlaybackService.notifyAndroidAutoConfigChanged(this)
                showAndroidAutoSettingsPage(animateEntrance = false)
            }
        ),
        // 4. Minimal Car Display (disabled/greyed out when master switch is OFF)
        PremiumMenuItem(
            iconEmoji = "🛡️",
            iconResId = R.drawable.ic_lux_shield,
            title = getString(R.string.aa_minimal_display_title),
            subtitle = if (config.minimalCarDisplay) getString(R.string.aa_minimal_display_on_sub) else getString(R.string.aa_minimal_display_off_sub),
            isAccentHighlighted = config.enabled && config.minimalCarDisplay,
            isEnabled = config.enabled,
            toggleChecked = config.minimalCarDisplay,
            onToggleChanged = { checked ->
                PlaybackStore.setAndroidAutoMinimalDisplay(this, checked)
                VexoPlaybackService.notifyAndroidAutoConfigChanged(this)
                showAndroidAutoSettingsPage(animateEntrance = false)
            }
        ),
        // 5. Test / Check Android Auto Connection & Readiness Status
        PremiumMenuItem(
            iconEmoji = "🩺",
            iconResId = R.drawable.ic_lux_diagnostics,
            title = getString(R.string.aa_test_status_title),
            subtitle = getString(R.string.aa_test_status_sub),
            isAccentHighlighted = true,
            isEnabled = true,
            onClick = {
                showAndroidAutoDiagnosticsDialog()
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "🚗",
        title = getString(R.string.settings_android_auto_title),
        subtitle = statusSummary,
        items = items,
        showHeaderVersionBadge = false,
        animateEntrance = animateEntrance
    )
}

internal fun MainActivity.showAndroidAutoDiagnosticsDialog() {
    lifecycleScope.launch {
        val report = withContext(Dispatchers.IO) {
            AndroidAutoManager.runDiagnostics(applicationContext)
        }
        if (isFinishing || isDestroyed) return@launch

        val yesLabel = getString(R.string.aa_diag_yes)
        val noLabel = getString(R.string.aa_diag_no)

        val connectionLine = if (report.connection.isCarConnected) {
            getString(R.string.aa_diag_car_connected, report.connection.connectedPackage.ifBlank { "Android Auto" })
        } else {
            getString(R.string.aa_diag_car_not_connected)
        }

        val playbackLine = if (report.activeTitle.isNotBlank() && report.activeTitle != "V Player") {
            val stateWord = if (report.isPlaying) getString(R.string.aa_diag_playing) else getString(R.string.aa_diag_paused)
            getString(R.string.aa_diag_media_active, report.activeTitle, stateWord)
        } else {
            getString(R.string.aa_diag_media_idle)
        }

        val message = buildString {
            appendLine(getString(R.string.aa_diag_support_enabled, if (report.config.enabled) yesLabel else noLabel))
            appendLine(getString(R.string.aa_diag_service_ready, if (report.serviceRegisteredInManifest && report.automotiveDescDeclared) yesLabel else noLabel))
            appendLine(connectionLine)
            appendLine(playbackLine)
            appendLine()
            appendLine(getString(R.string.aa_diag_library_summary, report.favoritesCount, report.categoriesCount, report.recentCount))
            appendLine(getString(R.string.aa_diag_audio_only_state, if (report.config.audioOnlyOnCar) yesLabel else noLabel))
            append(getString(R.string.aa_diag_auto_resume_state, if (report.config.autoResumeOnConnect) yesLabel else noLabel))
        }

        AlertDialog.Builder(this@showAndroidAutoDiagnosticsDialog)
            .setTitle(getString(R.string.aa_test_status_title))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
