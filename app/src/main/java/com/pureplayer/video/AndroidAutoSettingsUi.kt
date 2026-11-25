package com.pureplayer.video

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Android Auto Settings screen inside V Player.
 * Keeps SettingsMenus.kt modular and matches the app's themed PremiumMenuDialog design.
 */
internal fun MainActivity.showAndroidAutoSettingsPage() {
    val config = PlaybackStore.getAndroidAutoConfig(this)
    val conn = AndroidAutoManager.connectionState.value

    val statusSummary = when {
        !config.enabled -> getString(R.string.aa_status_disabled)
        conn.isCarConnected -> getString(R.string.aa_status_connected)
        else -> getString(R.string.aa_status_ready)
    }

    val items = listOf(
        // 1. Enable / Disable Android Auto Support
        PremiumMenuItem(
            iconEmoji = "🚗",
            title = getString(R.string.aa_enable_title),
            subtitle = if (config.enabled) getString(R.string.aa_enable_on_sub) else getString(R.string.aa_enable_off_sub),
            isAccentHighlighted = config.enabled,
            statusChip = if (config.enabled) getString(R.string.enabled_label) else getString(R.string.disabled_label),
            onClick = {
                PlaybackStore.setAndroidAutoEnabled(this, !config.enabled)
                showAndroidAutoSettingsPage()
            }
        ),
        // 2. Auto-Resume on Car Connect (default: false)
        PremiumMenuItem(
            iconEmoji = "▶️",
            title = getString(R.string.aa_auto_resume_title),
            subtitle = if (config.autoResumeOnConnect) getString(R.string.aa_auto_resume_on_sub) else getString(R.string.aa_auto_resume_off_sub),
            isAccentHighlighted = config.enabled && config.autoResumeOnConnect,
            statusChip = if (config.autoResumeOnConnect) getString(R.string.enabled_label) else getString(R.string.disabled_label),
            onClick = {
                PlaybackStore.setAndroidAutoAutoResume(this, !config.autoResumeOnConnect)
                showAndroidAutoSettingsPage()
            }
        ),
        // 3. Audio-Only Mode on Car
        PremiumMenuItem(
            iconEmoji = "🎧",
            title = getString(R.string.aa_audio_only_title),
            subtitle = if (config.audioOnlyOnCar) getString(R.string.aa_audio_only_on_sub) else getString(R.string.aa_audio_only_off_sub),
            isAccentHighlighted = config.enabled && config.audioOnlyOnCar,
            statusChip = if (config.audioOnlyOnCar) getString(R.string.enabled_label) else getString(R.string.disabled_label),
            onClick = {
                PlaybackStore.setAndroidAutoAudioOnlyOnCar(this, !config.audioOnlyOnCar)
                showAndroidAutoSettingsPage()
            }
        ),
        // 4. Minimal Car Display (Show channel/group name only)
        PremiumMenuItem(
            iconEmoji = "🛡️",
            title = getString(R.string.aa_minimal_display_title),
            subtitle = if (config.minimalCarDisplay) getString(R.string.aa_minimal_display_on_sub) else getString(R.string.aa_minimal_display_off_sub),
            isAccentHighlighted = config.enabled && config.minimalCarDisplay,
            statusChip = if (config.minimalCarDisplay) getString(R.string.enabled_label) else getString(R.string.disabled_label),
            onClick = {
                PlaybackStore.setAndroidAutoMinimalDisplay(this, !config.minimalCarDisplay)
                showAndroidAutoSettingsPage()
            }
        ),
        // 5. Test / Check Android Auto Connection & Readiness Status
        PremiumMenuItem(
            iconEmoji = "🩺",
            title = getString(R.string.aa_test_status_title),
            subtitle = getString(R.string.aa_test_status_sub),
            isAccentHighlighted = true,
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
        showHeaderVersionBadge = false
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
